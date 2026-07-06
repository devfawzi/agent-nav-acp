#!/usr/bin/env python3
"""Harness de capture des fixtures stream-json de Claude Code.

Spawn le vrai binaire `claude` en stream-json bidirectionnel, joue des scénarios
scriptés et enregistre le NDJSON brut dans src/test/resources/fixtures/claude/<version>/.

Usage :
    python3 tools/capture_fixtures.py [scenario ...]   # défaut : tous

À relancer à chaque montée de version de claude : le diff des fixtures entre versions
rend le drift de protocole visible avant qu'il n'explose chez les users.
"""
import json
import os
import re
import selectors
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

MODEL = "claude-haiku-4-5-20251001"  # le moins cher — prompts minimaux
TIMEOUT_S = 90

BASE_ARGS = [
    "--output-format", "stream-json",
    "--input-format", "stream-json",
    "--verbose",
    "--permission-prompt-tool", "stdio",
    "--model", MODEL,
]

# Un scénario = permission policy + args additionnels + liste d'étapes :
#   ("send", "<texte user>")          — envoie un user message
#   ("wait_result", n)                — attend n events result
#   ("control", {...})                — envoie un control_request (request est complété d'un request_id)
#   ("wait_control_response", 1)      — attend une control_response
#   ("sleep", seconds)
SCENARIOS = {
    "simple-text": {
        "steps": [("send", "Reply with exactly: pong"), ("wait_result", 1)],
    },
    "thinking": {
        "steps": [("send", "Think step by step then answer: what is 17*23? Reply with the number only."),
                   ("wait_result", 1)],
    },
    "write-file": {
        "extra_args": ["--permission-mode", "acceptEdits"],
        "steps": [("send", "Create a file named hello.txt containing exactly 'agentnav'. Do not explain."),
                   ("wait_result", 1)],
    },
    "edit-file": {
        "extra_args": ["--permission-mode", "acceptEdits"],
        "pre": lambda cwd: (open(os.path.join(cwd, "note.txt"), "w").write("old content\n")),
        "steps": [("send", "Edit note.txt: replace 'old' with 'new'. Do not explain."),
                   ("wait_result", 1)],
    },
    "bash-allow": {
        # NB : les commandes read-only (echo, ls…) sont AUTO-approuvées par claude sans
        # can_use_tool — il faut une commande à effet pour déclencher la permission.
        "permission": "allow",
        "steps": [("send", "Run exactly this bash command: touch created-by-fixture.txt"),
                   ("wait_result", 1)],
    },
    "bash-deny": {
        "permission": "deny",
        "steps": [("send", "Run exactly this bash command: touch should-not-exist.txt"),
                   ("wait_result", 1)],
    },
    "plan-mode": {
        "extra_args": ["--permission-mode", "plan"],
        "steps": [("send", "Create a file plan-test.txt with content 'x'. Keep it minimal."),
                   ("wait_result", 1)],
    },
    "interrupt": {
        "steps": [("send", "Count slowly from 1 to 500, one number per line."),
                   ("sleep", 4),
                   ("control", {"subtype": "interrupt"}),
                   ("wait_result", 1)],
    },
    "set-model": {
        "steps": [("send", "Reply with exactly: ok"), ("wait_result", 1),
                   ("control", {"subtype": "set_model", "model": "claude-sonnet-4-6"}),
                   ("wait_control_response", 1)],
    },
    "set-permission-mode": {
        "steps": [("send", "Reply with exactly: ok"), ("wait_result", 1),
                   ("control", {"subtype": "set_permission_mode", "mode": "plan"}),
                   ("wait_control_response", 1)],
    },
    "rename-session": {
        "steps": [("send", "Reply with exactly: ok"), ("wait_result", 1),
                   ("control", {"subtype": "rename_session", "title": "AgentNav fixture title"}),
                   ("wait_control_response", 1)],
    },
    "tool-error": {
        "steps": [("send", "Read the file /nonexistent/definitely-missing.txt and tell me its first line."),
                   ("wait_result", 1)],
    },
    "slash-context": {
        "steps": [("send", "/context"), ("wait_result", 1)],
    },
    "slash-usage": {
        "steps": [("send", "/usage"), ("wait_result", 1)],
    },
    "slash-unknown": {
        "steps": [("send", "/nexistepas"), ("wait_result", 1)],
    },
    # resume : la fixture capture le flux du 2e spawn (--resume <sid>)
    "resume": {"special": "resume"},
}


def claude_version():
    out = subprocess.run(["claude", "--version"], capture_output=True, text=True).stdout
    m = re.search(r"(\d+\.\d+\.\d+)", out)
    return m.group(1) if m else "unknown"


def sanitize(line, home):
    return line.replace(home, "~")


def user_msg(text):
    return json.dumps({"type": "user",
                       "message": {"role": "user",
                                   "content": [{"type": "text", "text": text}]}})


def run_scenario(name, spec, out_dir):
    if spec.get("special") == "resume":
        return run_resume(name, out_dir)

    cwd = tempfile.mkdtemp(prefix=f"agentnav-fix-{name}-")
    if "pre" in spec:
        spec["pre"](cwd)
    args = ["stdbuf", "-oL", "-eL", "claude"] + BASE_ARGS + spec.get("extra_args", [])
    if "--permission-mode" not in args:
        args += ["--permission-mode", "default"]

    lines = drive(args, cwd, spec.get("steps", []), spec.get("permission", "allow"))
    write_fixture(out_dir, name, lines)
    shutil.rmtree(cwd, ignore_errors=True)


def run_resume(name, out_dir):
    cwd = tempfile.mkdtemp(prefix="agentnav-fix-resume-")
    sid = str(uuid.uuid4())
    base = ["stdbuf", "-oL", "-eL", "claude"] + BASE_ARGS + ["--permission-mode", "default"]
    # 1er spawn : crée la session (fixture non conservée)
    drive(base + ["--session-id", sid], cwd,
          [("send", "Remember the codeword: zebra42. Reply ok."), ("wait_result", 1)], "allow")
    # 2e spawn : --resume — c'est LUI qu'on capture
    lines = drive(base + ["--resume", sid], cwd,
                  [("send", "What is the codeword? Reply with just the codeword."), ("wait_result", 1)],
                  "allow")
    write_fixture(out_dir, name, lines)
    shutil.rmtree(cwd, ignore_errors=True)


def drive(args, cwd, steps, permission_policy):
    """Exécute les étapes contre un process claude, répond aux can_use_tool, capture stdout."""
    env = dict(os.environ, NO_COLOR="1", TERM="dumb")
    proc = subprocess.Popen(args, cwd=cwd, env=env, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                            bufsize=1)
    sel = selectors.DefaultSelector()
    sel.register(proc.stdout, selectors.EVENT_READ)
    captured = []
    deadline = time.time() + TIMEOUT_S

    def send(payload):
        proc.stdin.write(payload + "\n")
        proc.stdin.flush()

    def pump(until):
        """Lit stdout jusqu'à satisfaire `until(event) -> bool` ou timeout."""
        while time.time() < deadline:
            if not sel.select(timeout=1):
                continue
            line = proc.stdout.readline()
            if not line:
                return False
            line = line.rstrip("\n")
            if not line:
                continue
            captured.append(line)
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            # Réponse automatique aux permissions
            req = ev.get("request") or {}
            if ev.get("type") == "control_request" and req.get("subtype") == "can_use_tool":
                rid = ev.get("request_id")
                if permission_policy == "allow":
                    inner = {"behavior": "allow", "updatedInput": req.get("input") or {}}
                else:
                    inner = {"behavior": "deny", "message": "denied by fixture harness"}
                send(json.dumps({"type": "control_response",
                                 "response": {"subtype": "success", "request_id": rid,
                                              "response": inner}}))
            if until(ev):
                return True
        return False

    for step in steps:
        kind, arg = step
        if kind == "send":
            send(user_msg(arg))
        elif kind == "wait_result":
            for _ in range(arg):
                pump(lambda ev: ev.get("type") == "result")
        elif kind == "control":
            rid = f"fix-{arg['subtype']}-{int(time.time()*1000)}"
            send(json.dumps({"type": "control_request", "request_id": rid, "request": arg}))
        elif kind == "wait_control_response":
            for _ in range(arg):
                pump(lambda ev: ev.get("type") == "control_response")
        elif kind == "sleep":
            end = time.time() + arg
            pump(lambda ev: time.time() >= end)

    try:
        proc.stdin.close()
        proc.terminate()
        proc.wait(timeout=5)
    except Exception:
        proc.kill()
    return captured


def write_fixture(out_dir, name, lines):
    home = os.path.expanduser("~")
    path = os.path.join(out_dir, f"{name}.ndjson")
    with open(path, "w") as f:
        for line in lines:
            f.write(sanitize(line, home) + "\n")
    print(f"  ✓ {name}: {len(lines)} events → {path}")


def main():
    version = claude_version()
    out_dir = os.path.join(os.path.dirname(__file__), "..",
                           "src", "test", "resources", "fixtures", "claude", version)
    os.makedirs(out_dir, exist_ok=True)
    wanted = sys.argv[1:] or list(SCENARIOS.keys())
    print(f"Claude {version} → {len(wanted)} scénario(s)")
    for name in wanted:
        if name not in SCENARIOS:
            print(f"  ✗ scénario inconnu: {name}")
            continue
        print(f"— {name}")
        try:
            run_scenario(name, SCENARIOS[name], out_dir)
        except Exception as e:
            print(f"  ✗ {name} FAILED: {e}")


if __name__ == "__main__":
    main()
