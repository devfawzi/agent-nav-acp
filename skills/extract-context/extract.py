#!/usr/bin/env python3
"""
extract-context — parse a Claude Code session .jsonl into clean Markdown.

Strips system wrappers, thinking blocks, tool noise — keeps the actual dialogue
so you can re-inject context from a past chat into the current one.

Usage:
    python extract.py --sid <uuid>
    python extract.py --jsonl /path/to/<sid>.jsonl
    python extract.py --sid <uuid> --last 5
    python extract.py --sid <uuid> --since 2026-05-14 --format markdown
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from glob import glob
from pathlib import Path
from typing import Any, Iterable

SYSTEM_WRAPPER_PREFIXES: tuple[str, ...] = (
    "<local-command-caveat>",
    "<local-command-stdout>",
    "<local-command-stderr>",
    "<system-reminder>",
    "<command-name>",
    "<command-message>",
    "<command-args>",
    "<task-notification>",
    "<bash-stdout>",
    "<bash-stderr>",
    "<user-prompt-submit-hook>",
)
MAX_CHARS = 5000
TOOL_INPUT_PREVIEW_LEN = 80


def is_system_wrapper(text: str) -> bool:
    """True if the message content is purely a Claude Code wrapper, not human text."""
    t = text.lstrip()
    if not t:
        return True
    return any(t.startswith(p) for p in SYSTEM_WRAPPER_PREFIXES)


def extract_text(content: Any) -> str:
    """Pull the textual portion from a message.content (string or array of blocks)."""
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if not isinstance(block, dict):
                continue
            btype = block.get("type")
            if btype == "text":
                parts.append(block.get("text", ""))
            # thinking blocks are skipped on purpose
            # tool_use / tool_result handled separately
        return "\n".join(p for p in parts if p)
    return str(content)


def summarize_tool_use(block: dict[str, Any]) -> str:
    """One-line summary of a tool_use block (no full args/output)."""
    name = block.get("name", "tool")
    inp = block.get("input", {})
    if not isinstance(inp, dict):
        return f"→ Tool: {name}"
    # Pick the most informative single field per tool
    detail = ""
    if name in ("Read", "Edit", "Write", "MultiEdit"):
        detail = inp.get("file_path") or inp.get("path") or ""
    elif name == "Bash":
        detail = inp.get("command", "")
    elif name in ("Grep", "Glob"):
        detail = inp.get("pattern", "")
    elif name in ("WebFetch", "WebSearch"):
        detail = inp.get("url") or inp.get("query") or ""
    elif name == "Task":
        detail = inp.get("description") or inp.get("subagent_type") or ""
    elif name == "TodoWrite":
        todos = inp.get("todos", [])
        detail = f"{len(todos)} item(s)" if isinstance(todos, list) else ""
    elif name == "Skill":
        detail = inp.get("skill", "")
    else:
        # MCP tools, etc.: show first 1-2 fields compactly
        items = list(inp.items())[:2]
        detail = ", ".join(f"{k}={str(v)[:30]}" for k, v in items)
    detail = (detail or "")[:TOOL_INPUT_PREVIEW_LEN]
    return f"→ Tool: {name}({detail})" if detail else f"→ Tool: {name}"


def find_jsonl_by_sid(sid: str) -> Path | None:
    """Search all ~/.claude/projects/*/  for <sid>.jsonl."""
    home = os.path.expanduser("~")
    candidates = glob(f"{home}/.claude/projects/*/{sid}.jsonl")
    return Path(candidates[0]) if candidates else None


def parse_jsonl(path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """
    Return (turns, meta). Each turn is {"role": "user"|"assistant", "text": str,
    "tool_calls": list[str], "timestamp": str|None}.
    """
    turns: list[dict[str, Any]] = []
    meta = {"cwd": None, "summary": None, "session_id": path.stem, "first_ts": None, "last_ts": None}

    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or not line.startswith("{"):
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue

            t = obj.get("type")
            if meta["cwd"] is None and obj.get("cwd"):
                meta["cwd"] = obj.get("cwd")
            if t == "summary" and not meta["summary"]:
                meta["summary"] = obj.get("summary") or obj.get("text")

            if t not in ("user", "assistant"):
                continue

            msg = obj.get("message") or {}
            content = msg.get("content")
            text = extract_text(content)

            if t == "user" and is_system_wrapper(text):
                continue  # skip wrappers

            tool_calls: list[str] = []
            if isinstance(content, list):
                for block in content:
                    if isinstance(block, dict) and block.get("type") == "tool_use":
                        tool_calls.append(summarize_tool_use(block))

            # Skip turns that have neither text nor tool_calls
            if not text and not tool_calls:
                continue

            ts = obj.get("timestamp")
            if ts and meta["first_ts"] is None:
                meta["first_ts"] = ts
            if ts:
                meta["last_ts"] = ts

            turns.append({
                "role": t,
                "text": text[:MAX_CHARS] + ("…(truncated)" if len(text) > MAX_CHARS else ""),
                "tool_calls": tool_calls,
                "timestamp": ts,
            })

    return turns, meta


def filter_turns(
    turns: list[dict[str, Any]],
    *,
    last: int | None,
    since: str | None,
    no_assistant: bool,
) -> list[dict[str, Any]]:
    if since:
        since_dt = datetime.fromisoformat(since).replace(tzinfo=timezone.utc)
        def _ts_ok(turn):
            ts = turn.get("timestamp")
            if not ts:
                return True
            try:
                return datetime.fromisoformat(ts.replace("Z", "+00:00")) >= since_dt
            except ValueError:
                return True
        turns = [t for t in turns if _ts_ok(t)]
    if no_assistant:
        turns = [t for t in turns if t["role"] == "user"]
    if last is not None and last > 0:
        # Last N user/assistant turn PAIRS = last 2N entries
        turns = turns[-last * 2:]
    return turns


def render_markdown(turns: list[dict[str, Any]], meta: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append(f"# Conversation context (session `{meta['session_id'][:8]}…`)")
    lines.append("")
    if meta.get("summary"):
        lines.append(f"**Summary**: {meta['summary']}")
        lines.append("")
    lines.append(f"- **cwd**: `{meta.get('cwd') or '(unknown)'}`")
    if meta.get("first_ts"):
        lines.append(f"- **first**: {meta['first_ts']}")
    if meta.get("last_ts"):
        lines.append(f"- **last**: {meta['last_ts']}")
    lines.append(f"- **turns**: {len(turns)}")
    lines.append("")
    lines.append("---")
    lines.append("")

    turn_idx = 0
    for t in turns:
        if t["role"] == "user":
            turn_idx += 1
            ts_short = (t.get("timestamp") or "")[:19].replace("T", " ")
            lines.append(f"## Turn {turn_idx} — {ts_short}")
            lines.append("")
            lines.append(f"**user:** {t['text']}")
            lines.append("")
        else:  # assistant
            if t["text"]:
                lines.append(f"**assistant:** {t['text']}")
                lines.append("")
            for tc in t["tool_calls"]:
                lines.append(f"`{tc}`")
            if t["tool_calls"]:
                lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def render_plain(turns: list[dict[str, Any]], meta: dict[str, Any]) -> str:
    out: list[str] = []
    out.append(f"Session: {meta['session_id']}")
    out.append(f"Cwd: {meta.get('cwd')}")
    if meta.get("summary"):
        out.append(f"Summary: {meta['summary']}")
    out.append("")
    for t in turns:
        prefix = "USER:" if t["role"] == "user" else "ASSISTANT:"
        out.append(f"{prefix} {t['text']}")
        for tc in t["tool_calls"]:
            out.append(f"  {tc}")
        out.append("")
    return "\n".join(out)


def main() -> int:
    p = argparse.ArgumentParser(description="Extract a Claude Code session as clean Markdown.")
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--sid", help="Session UUID (looked up in ~/.claude/projects/*/)")
    g.add_argument("--jsonl", help="Direct path to a .jsonl file")
    p.add_argument("--last", type=int, default=None, help="Only the last N turn pairs")
    p.add_argument("--since", default=None, help="Only messages on/after YYYY-MM-DD")
    p.add_argument("--format", choices=("markdown", "plain"), default="markdown")
    p.add_argument("--no-assistant", action="store_true", help="Drop assistant replies")
    args = p.parse_args()

    if args.sid:
        path = find_jsonl_by_sid(args.sid)
        if path is None:
            print(f"Error: no .jsonl found for sid {args.sid} in ~/.claude/projects/", file=sys.stderr)
            return 2
    else:
        path = Path(args.jsonl).expanduser()
        if not path.is_file():
            print(f"Error: file not found: {path}", file=sys.stderr)
            return 2

    turns, meta = parse_jsonl(path)
    turns = filter_turns(turns, last=args.last, since=args.since, no_assistant=args.no_assistant)

    out = render_markdown(turns, meta) if args.format == "markdown" else render_plain(turns, meta)
    sys.stdout.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
