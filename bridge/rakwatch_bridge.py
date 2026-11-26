#!/usr/bin/env python3
"""
RakWatch Local PC Bridge (Port 8765)
Connects your LG Watch W7 to Claude Code (via hooks) & Antigravity (via keyboard/clipboard automation).
Zero third-party dependencies — pure Python 3 standard library.
"""

import json
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "0.0.0.0"
PORT = 8765

state_lock = threading.Lock()
current_state = {
    "agent": "antigravity",
    "state": "idle",       # "idle", "working", "pending"
    "message": "Ready on PC",
    "request_id": "",
}

# Pending hook waiters: request_id -> {"event": Event, "decision": None}
pending_hooks = {}


def ps_send_keys(keys: str):
    """Send keys to the currently focused Windows IDE/terminal window."""
    cmd = [
        "powershell", "-NoProfile", "-Command",
        f"Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait('{keys}')"
    ]
    subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def ps_paste_and_submit(text: str):
    """Copy spoken voice prompt to Windows clipboard, paste into focused Antigravity/Claude input, and press Enter."""
    safe = text.replace("'", "''")
    script = (
        f"Set-Clipboard -Value '{safe}'; "
        "Add-Type -AssemblyName System.Windows.Forms; "
        "Start-Sleep -Milliseconds 120; "
        "[System.Windows.Forms.SendKeys]::SendWait('^v'); "
        "Start-Sleep -Milliseconds 150; "
        "[System.Windows.Forms.SendKeys]::SendWait('{ENTER}')"
    )
    subprocess.Popen(["powershell", "-NoProfile", "-Command", script], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


class RakWatchHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[RakWatch] {self.address_string()} - {fmt % args}", flush=True)

    def _send_json(self, code: int, payload: dict):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    def do_GET(self):
        if self.path == "/api/status":
            with state_lock:
                self._send_json(200, dict(current_state))
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        body = self._read_json()

        if self.path == "/api/approve":
            req_id = body.get("request_id", "")
            with state_lock:
                waiter = pending_hooks.get(req_id) or (next(iter(pending_hooks.values())) if pending_hooks else None)
                if waiter:
                    waiter["decision"] = "approve"
                    waiter["event"].set()
                    current_state["state"] = "working"
                    current_state["message"] = "Approved ✓"
                else:
                    # Direct desktop approval (Enter in active Antigravity/Claude window)
                    current_state["state"] = "working"
                    current_state["message"] = "Approved ✓ (Enter)"
                    ps_send_keys("{ENTER}")
            self._send_json(200, {"ok": True, "action": "approve"})

        elif self.path == "/api/reject":
            req_id = body.get("request_id", "")
            with state_lock:
                waiter = pending_hooks.get(req_id) or (next(iter(pending_hooks.values())) if pending_hooks else None)
                if waiter:
                    waiter["decision"] = "block"
                    waiter["event"].set()
                    current_state["state"] = "idle"
                    current_state["message"] = "Rejected ✕"
                else:
                    current_state["state"] = "idle"
                    current_state["message"] = "Stopped ✕ (Esc)"
                    ps_send_keys("{ESC}")
            self._send_json(200, {"ok": True, "action": "reject"})

        elif self.path == "/api/prompt":
            prompt = (body.get("prompt") or "").strip()
            target = body.get("target", "antigravity")
            if prompt:
                with state_lock:
                    current_state["agent"] = target
                    current_state["state"] = "working"
                    current_state["message"] = f"» {prompt[:48]}"
                print(f"[VOICE -> {target.upper()}] {prompt}", flush=True)
                ps_paste_and_submit(prompt)
            self._send_json(200, {"ok": True, "prompt": prompt})

        elif self.path == "/api/notify":
            # Push a status update or pending approval to the watch
            req_id = body.get("request_id") or str(uuid.uuid4())[:8]
            with state_lock:
                current_state["agent"] = body.get("agent", current_state["agent"])
                current_state["state"] = body.get("state", "pending")
                current_state["message"] = body.get("message", "Approval needed")
                current_state["request_id"] = req_id
            self._send_json(200, {"ok": True, "request_id": req_id})

        elif self.path == "/api/hook/wait":
            # Blocking endpoint for Claude Code PreToolUse hook!
            req_id = str(uuid.uuid4())[:8]
            msg = body.get("message", "Approve tool?")
            timeout = float(body.get("timeout", 90))
            ev = threading.Event()
            with state_lock:
                pending_hooks[req_id] = {"event": ev, "decision": None}
                current_state["agent"] = "claude"
                current_state["state"] = "pending"
                current_state["message"] = msg
                current_state["request_id"] = req_id

            ev.wait(timeout=timeout)
            with state_lock:
                info = pending_hooks.pop(req_id, {})
                decision = info.get("decision") or "approve"
                if current_state["request_id"] == req_id:
                    current_state["state"] = "idle"
                    current_state["message"] = "Ready"
            self._send_json(200, {"decision": decision, "request_id": req_id})

        else:
            self._send_json(404, {"error": "not found"})


if __name__ == "__main__":
    print(f"RakWatch PC Bridge listening on http://{HOST}:{PORT}", flush=True)
    server = ThreadingHTTPServer((HOST, PORT), RakWatchHandler)
    server.serve_forever()
