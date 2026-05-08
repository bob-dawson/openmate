"""
Bridge API debug tool. Handles auth (PIN pairing) and provides authenticated requests.

Usage:
  python bridge_tool.py get /api/bridge/status
  python bridge_tool.py get /api/bridge/sync/sessions
  python bridge_tool.py get /api/bridge/sync/session/SESSION_ID/init?limit=30
  python bridge_tool.py get /api/bridge/sync/session/SESSION_ID/events?afterSeq=0
  python bridge_tool.py pair                    # Full PIN pairing flow
  python bridge_tool.py token                   # Show stored token
  python bridge_tool.py raw 'SQL_QUERY'         # Query opencode.db directly
"""

import sys
import os
import json
import sqlite3
import urllib.request
import urllib.error

BRIDGE_HOST = os.environ.get("BRIDGE_HOST", "127.0.0.1")
BRIDGE_PORT = os.environ.get("BRIDGE_PORT", "4097")
BASE_URL = f"http://{BRIDGE_HOST}:{BRIDGE_PORT}"
TOKEN_FILE = os.path.join(os.path.dirname(__file__), ".bridge_token")
OPENCODE_DB = os.path.join(os.environ.get("USERPROFILE", ""), ".local", "share", "opencode", "opencode.db")


def get_token():
    if os.path.exists(TOKEN_FILE):
        with open(TOKEN_FILE, "r") as f:
            return f.read().strip()
    return None


def save_token(token):
    with open(TOKEN_FILE, "w") as f:
        f.write(token)


def api_request(method, path, data=None, token=None):
    url = f"{BASE_URL}{path}"
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if data is not None:
        data = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"HTTP {e.code}: {body}")
        return None
    except urllib.error.URLError as e:
        print(f"Connection failed: {e.reason}")
        return None


def cmd_pair():
    token = get_token()
    if token:
        r = api_request("GET", "/api/bridge/status", token=token)
        if r:
            print(f"Existing token works. Bridge status: {json.dumps(r, indent=2)}")
            return

    print("Requesting PIN...")
    r = api_request("POST", "/api/bridge/pair/request", data={})
    if not r:
        return
    pin = r.get("pin")
    print(f"PIN: {pin}")
    print("Approving PIN (localhost)...")

    r = api_request("POST", "/api/bridge/pair/approve", data={"pin": pin})
    if not r or not r.get("approved"):
        print("Approve failed")
        return
    print("PIN approved. Confirming...")

    r = api_request("POST", "/api/bridge/pair/confirm", data={"pin": pin})
    if not r:
        return
    token = r.get("token")
    save_token(token)
    print(f"Token saved to {TOKEN_FILE}")


def cmd_token():
    token = get_token()
    if token:
        print(token)
    else:
        print("No token. Run: python bridge_tool.py pair")


def cmd_get(path):
    token = get_token()
    if not token:
        print("No token. Run: python bridge_tool.py pair")
        return
    if not path.startswith("/"):
        path = "/" + path
    r = api_request("GET", path, token=token)
    if r is not None:
        print(json.dumps(r, indent=2, ensure_ascii=True))


def cmd_raw(sql):
    if not os.path.exists(OPENCODE_DB):
        print(f"DB not found: {OPENCODE_DB}")
        return
    conn = sqlite3.connect(OPENCODE_DB)
    conn.row_factory = sqlite3.Row
    try:
        cur = conn.execute(sql)
        cols = [d[0] for d in cur.description] if cur.description else []
        rows = cur.fetchall()
        if cols:
            print("\t".join(cols))
            for row in rows:
                vals = [str(row[c])[:100] for c in cols]
                print("\t".join(vals))
        print(f"({len(rows)} rows)")
    except Exception as e:
        print(f"SQL error: {e}")
    finally:
        conn.close()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return

    cmd = sys.argv[1].lower()

    if cmd == "pair":
        cmd_pair()
    elif cmd == "token":
        cmd_token()
    elif cmd == "get":
        if len(sys.argv) < 3:
            print("Usage: python bridge_tool.py get /path")
            return
        cmd_get(sys.argv[2])
    elif cmd == "raw":
        if len(sys.argv) < 3:
            print("Usage: python bridge_tool.py raw 'SQL_QUERY'")
            return
        cmd_raw(sys.argv[2])
    else:
        print(f"Unknown command: {cmd}")
        print(__doc__)


if __name__ == "__main__":
    main()
