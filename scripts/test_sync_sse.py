"""Quick test: listen to Bridge SSE /api/bridge/sync/events for 30s and print notifications."""
import sys
import os
import urllib.request
import urllib.error

BRIDGE_HOST = os.environ.get("BRIDGE_HOST", "127.0.0.1")
BRIDGE_PORT = os.environ.get("BRIDGE_PORT", "4097")
BASE_URL = f"http://{BRIDGE_HOST}:{BRIDGE_PORT}"
TOKEN_FILE = os.path.join(os.path.dirname(__file__), ".bridge_token")

def get_token():
    if os.path.exists(TOKEN_FILE):
        with open(TOKEN_FILE, "r") as f:
            return f.read().strip()
    return None

def listen_sse():
    token = get_token()
    url = f"{BASE_URL}/api/bridge/sync/events"
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    print(f"Connecting to {url} ...")
    try:
        resp = urllib.request.urlopen(req, timeout=35)
        print(f"Connected: {resp.status}")
        import time
        start = time.time()
        while time.time() - start < 30:
            line = resp.readline()
            if not line:
                print("[connection closed]")
                break
            decoded = line.decode("utf-8", errors="replace").strip()
            if decoded:
                print(f"[{time.time()-start:.1f}s] {decoded}")
        print("Done listening (30s timeout)")
    except urllib.error.HTTPError as e:
        print(f"HTTP error: {e.code} {e.reason}")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    listen_sse()
