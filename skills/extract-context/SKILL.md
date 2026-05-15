---
name: extract-context
description: Parse a Claude Code session .jsonl file and extract the human prompts + assistant text replies as clean Markdown. Use this when the user asks to "bring context from session X" or "reload the conversation from <sid>". Strips system wrappers (<local-command-caveat>, <system-reminder>, <command-name>, <task-notification>), thinking blocks, and tool_use noise — keeps only the actual dialogue.
---

# Usage

Invoke the Python script `extract.py` with one of these forms:

```
python extract.py --sid <session-uuid>
python extract.py --jsonl /path/to/<sid>.jsonl
python extract.py --sid <session-uuid> --last 5
python extract.py --sid <session-uuid> --since 2026-05-14
```

## Args

- `--sid <uuid>` — Resolve via `~/.claude/projects/<encoded-cwd>/<sid>.jsonl`. Scans all project dirs to locate.
- `--jsonl <path>` — Direct path to a `.jsonl` (bypasses lookup).
- `--last N` — Only the last N user/assistant turn pairs.
- `--since YYYY-MM-DD` — Only messages with timestamp >= this date.
- `--format markdown|plain` — Output format. Default: markdown.
- `--no-assistant` — Skip assistant replies, keep only user prompts.

## Output

A clean Markdown document with:
- A header with session id, cwd, message count, date range
- Each turn as `## Turn N (timestamp)` with `**user:**` and `**assistant:**` sections
- Wrappers (`<local-command-caveat>`, `<system-reminder>`, ...) are stripped
- Thinking blocks are dropped
- Tool calls are summarized as `→ Tool: Read(path/to/file.kt)` lines (no full args/output)
- Long messages are truncated at 5000 chars with `…(truncated)`

## When to invoke

- The user mentions a session ID and wants context from it imported.
- The user says "remember what we discussed in chat X" or "use the conclusions from session X".
- The user wants a clean summary of an old conversation without going through 1000 lines of NDJSON.

After invoking, paste the returned Markdown into your reasoning as context. Do NOT relay it verbatim to the user unless they ask — use it to inform your next response.
