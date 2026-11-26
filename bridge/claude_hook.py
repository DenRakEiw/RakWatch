#!/usr/bin/env python3
"""
Claude Code PreToolUse Hook -> Vibrates your LG Watch W7 and waits for APPROVE / STOP!
Add to ~/.claude/settings.json under hooks.PreToolUse.
"""
import json
import sys
import urllib.request

BRIDGE = "http://127.0.0.1:8765/api/hook/wait"

try:
    payload = json.load(sys.stdin)
except Exception:
    payload = {}

tool_name = payload.get("tool_name", "Tool")
tool_input = payload.get("tool_input", {})
summary = f"Claude: {tool_name}"
if isinstance(tool_input, dict) and "command" in tool_input:
    summary = f"Run: {str(tool_input['command'])[:42]}"

req = urllib.request.Request(
    BRIDGE,
    data=json.dumps({"message": summary, "timeout": 90}).encode("utf-8"),
    headers={"Content-Type": "application/json"},
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=95) as resp:
        res = json.loads(resp.read().decode("utf-8"))
        if res.get("decision") == "block":
            print(json.dumps({"decision": "block", "reason": "Rejected from LG Watch W7"}))
        else:
            print(json.dumps({"decision": "approve"}))
except Exception:
    # If bridge isn't running, don't block Claude Code
    print(json.dumps({}))
