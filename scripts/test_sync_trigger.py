"""Trigger prompt and listen for sync events from opencode."""
import urllib.request, json, time, threading

sessions_url = "http://127.0.0.1:4096/session"
sse_url = "http://127.0.0.1:4096/global/event"

resp = urllib.request.urlopen(sessions_url, timeout=5)
sessions = json.loads(resp.read())
sid = sessions[0]["id"]
print(f"Session: {sid}")

sync_events = []

def listen():
    req = urllib.request.Request(sse_url)
    resp = urllib.request.urlopen(req, timeout=35)
    start = time.time()
    while time.time() - start < 25:
        line = resp.readline()
        if not line:
            break
        decoded = line.decode("utf-8", errors="replace").strip()
        if decoded.startswith("data:"):
            data = decoded[5:].strip()
            try:
                obj = json.loads(data)
                payload = obj.get("payload", {})
                ptype = payload.get("type", "?")
                elapsed = time.time() - start
                if ptype == "sync":
                    sync = payload.get("syncEvent", {})
                    agg = sync.get("aggregateID", "?")
                    seq = sync.get("seq", "?")
                    evt = sync.get("type", "?")
                    sync_events.append((agg, seq, evt))
                    print(f"  [{elapsed:.1f}s] SYNC: agg={agg} seq={seq} type={evt}")
                else:
                    print(f"  [{elapsed:.1f}s] {ptype}")
            except Exception as e:
                pass

t = threading.Thread(target=listen, daemon=True)
t.start()
time.sleep(2)

prompt_url = f"http://127.0.0.1:4096/session/{sid}/prompt_async"
data = json.dumps({"parts": [{"type": "text", "text": "say hi in 3 words"}]}).encode()
req = urllib.request.Request(prompt_url, data=data, method="POST")
req.add_header("Content-Type", "application/json")
try:
    r = urllib.request.urlopen(req, timeout=5)
    print(f"Prompt sent: {r.status}")
except Exception as e:
    print(f"Prompt error: {e}")

t.join(timeout=25)
print(f"\nTotal sync events: {len(sync_events)}")
for e in sync_events:
    print(f"  {e}")
