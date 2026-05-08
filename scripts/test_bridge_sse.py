"""Listen to Bridge SSE and trigger prompt to see if notifications arrive."""
import urllib.request, json, time, threading

BRIDGE = "http://127.0.0.1:4097"
OPENCODE = "http://127.0.0.1:4096"
TOKEN_FILE = "D:\\openmate\\scripts\\.bridge_token"

def get_token():
    try:
        with open(TOKEN_FILE) as f:
            return f.read().strip()
    except:
        return None

token = get_token()
print(f"Token: {'yes' if token else 'no'}")

resp = urllib.request.urlopen(f"{OPENCODE}/session", timeout=5)
sessions = json.loads(resp.read())
sid = sessions[0]["id"]
print(f"Session: {sid}")

notifications = []

def listen_bridge():
    url = f"{BRIDGE}/api/bridge/sync/events"
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    resp = urllib.request.urlopen(req, timeout=35)
    start = time.time()
    while time.time() - start < 25:
        line = resp.readline()
        if not line:
            break
        decoded = line.decode("utf-8", errors="replace").strip()
        if not decoded:
            continue
        elapsed = time.time() - start
        if decoded.startswith("data:"):
            data = decoded[5:].strip()
            try:
                obj = json.loads(data)
                s = obj.get("sessionID", "?")
                q = obj.get("seq", "?")
                notifications.append((s, q))
                print(f"  [{elapsed:.1f}s] NOTIFICATION: session={s} seq={q}")
            except:
                print(f"  [{elapsed:.1f}s] raw: {data[:80]}")
        elif decoded.startswith("event:") or decoded.startswith("id:"):
            pass
        else:
            print(f"  [{elapsed:.1f}s] line: {decoded[:80]}")

t = threading.Thread(target=listen_bridge, daemon=True)
t.start()
time.sleep(2)

prompt_url = f"{OPENCODE}/session/{sid}/prompt_async"
data = json.dumps({"parts": [{"type": "text", "text": "say hello in 3 words"}]}).encode()
req = urllib.request.Request(prompt_url, data=data, method="POST")
req.add_header("Content-Type", "application/json")
try:
    r = urllib.request.urlopen(req, timeout=5)
    print(f"Prompt sent: {r.status}")
except Exception as e:
    print(f"Prompt error: {e}")

t.join(timeout=25)
print(f"\nTotal Bridge notifications: {len(notifications)}")
