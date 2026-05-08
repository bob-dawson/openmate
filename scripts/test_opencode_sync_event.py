"""Listen to opencode SSE and trigger a prompt simultaneously."""
import urllib.request
import json
import time
import threading

url = "http://127.0.0.1:4096/global/event"
sessions_url = "http://127.0.0.1:4096/session"

print("Fetching sessions...")
req = urllib.request.Request(sessions_url)
resp = urllib.request.urlopen(req, timeout=10)
sessions = json.loads(resp.read())
if not sessions:
    print("No sessions found")
    exit(1)
session_id = sessions[0]["id"]
print(f"Using session: {session_id}")

def listen():
    req = urllib.request.Request(url)
    resp = urllib.request.urlopen(req, timeout=35)
    start = time.time()
    while time.time() - start < 20:
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
                if ptype == "sync":
                    sync = payload.get("syncEvent", {})
                    print(f"  SYNC: agg={sync.get('aggregateID','?')} seq={sync.get('seq','?')} evt_type={sync.get('type','?')}")
                else:
                    print(f"  [{time.time()-start:.1f}s] {ptype}")
            except:
                print(f"  raw: {data[:80]}")

t = threading.Thread(target=listen, daemon=True)
t.start()
time.sleep(2)

print(f"Sending prompt to {session_id}...")
prompt_url = f"http://127.0.0.1:4096/session/{session_id}/prompt_async"
prompt_data = json.dumps({"content": "say hi in 3 words"}).encode()
prompt_req = urllib.request.Request(prompt_url, data=prompt_data, method="POST")
prompt_req.add_header("Content-Type", "application/json")
try:
    prompt_resp = urllib.request.urlopen(prompt_req, timeout=5)
    print(f"Prompt sent: {prompt_resp.status}")
except Exception as e:
    print(f"Prompt error: {e}")

t.join(timeout=20)
print("Done")
