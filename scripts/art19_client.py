#!/usr/bin/env python3
"""Minimal ART19 MCP client — stdlib only, no dependencies.

Copy this file or the class into your bot. It handles the session handshake
and the SSE framing so you can just call tools.

    c = Art19MCP().connect()
    print(c.call("get_series", series_slug="lu"))
"""

import json
import urllib.error
import urllib.request

DEFAULT_URL = "http://127.0.0.1:3007/mcp"


class SessionExpired(RuntimeError):
    """Raised when the server rejects our Mcp-Session-Id (e.g. after a restart)."""


class Art19MCP:
    def __init__(self, url=DEFAULT_URL, timeout=120):
        self.url = url
        self.timeout = timeout
        self.sid = None
        self._rid = 0

    # ── transport ────────────────────────────────────────────────────────
    def _post(self, payload):
        """POST JSON-RPC. Returns (raw_text, session_id_or_None)."""
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        if self.sid:
            headers["Mcp-Session-Id"] = self.sid
        req = urllib.request.Request(
            self.url, data=json.dumps(payload).encode(), headers=headers
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                sid = r.headers.get("Mcp-Session-Id") or r.headers.get("mcp-session-id")
                return r.read().decode(), sid
        except urllib.error.HTTPError as e:
            body = e.read().decode()
            # The one HTTP error worth retrying: stale session after a restart.
            if e.code in (400, 401, 404) and "session" in body.lower():
                raise SessionExpired(body) from None
            raise RuntimeError(f"HTTP {e.code} from MCP: {body[:300]}") from None

    @staticmethod
    def _parse(raw):
        """Strip SSE framing ('data: ' lines) then parse JSON."""
        if not raw.strip():
            return None
        lines = [ln[6:] if ln.startswith("data: ") else ln for ln in raw.splitlines()]
        text = "\n".join(ln for ln in lines if ln.strip() and not ln.startswith("event:"))
        return json.loads(text) if text.strip() else None

    # ── lifecycle ────────────────────────────────────────────────────────
    def connect(self):
        """initialize + notifications/initialized. Returns self."""
        self._rid += 1
        raw, sid = self._post({
            "jsonrpc": "2.0",
            "id": self._rid,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "art19-bot", "version": "1"},
            },
        })
        if sid:
            self.sid = sid
        if not self.sid:
            raise RuntimeError("server did not return an Mcp-Session-Id header")
        self._parse(raw)
        # Required handshake; server answers 202 with an empty body.
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        return self

    # ── calls ────────────────────────────────────────────────────────────
    def call(self, name, **arguments):
        """Call a tool. Returns the parsed payload. Raises on tool error.

        Transparently re-initializes once if the session went stale.
        """
        if not self.sid:
            self.connect()
        try:
            return self._call_once(name, arguments)
        except SessionExpired:
            self.sid = None
            self.connect()
            return self._call_once(name, arguments)

    def _call_once(self, name, arguments):
        self._rid += 1
        raw, _ = self._post({
            "jsonrpc": "2.0",
            "id": self._rid,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments},
        })
        d = self._parse(raw)
        if d is None:
            raise RuntimeError(f"empty response from tool {name}")
        if d.get("error"):
            raise RuntimeError(d["error"])
        result = d["result"]
        if result.get("isError"):
            raise RuntimeError(result["content"][0]["text"])
        return json.loads(result["content"][0]["text"])

    def tools(self):
        """List available tools."""
        self._rid += 1
        raw, _ = self._post({
            "jsonrpc": "2.0", "id": self._rid, "method": "tools/list", "params": {},
        })
        return [t["name"] for t in self._parse(raw)["result"]["tools"]]


# ── demo ─────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    c = Art19MCP().connect()
    print("session:", c.sid[:8], "| tools:", len(c.tools()))

    s = c.call("get_series", series_slug="lu")
    print("series:", s["data"]["attributes"]["title"])

    eps = c.call("list_episodes", series_slug="lu", sort="-released_at", page_size=3)
    for e in eps["episodes"]:
        print(f"  {e['title']}  {e['released_at']}")

    # Read an episode's markers (the shape you'll care about most)
    latest = eps["episodes"][0]
    mp = c.call("list_marker_points", episode_id=latest["id"])
    print(f"\nmarkers on {latest['title']}:")
    for m in mp["marker_points"]:
        print(f"  {m['type']:<17} pos={str(m['position_type']):<5} "
              f"start={m['start_position']} dur={m['maximum_content_duration']} "
              f"count={m['maximum_content_count']}")
