"""Test: listen to opencode /global/event SSE for 30s, show all events."""
import urllib.request
import time

url = "http://127.0.0.1:4098/global/event"
print(f"Connecting to {url} ...")
req = urllib.request.Request(url)
try:
    resp = urllib.request.urlopen(req, timeout=35)
    print(f"Connected: {resp.status}")
    start = time.time()
    count = 0
    while time.time() - start < 30:
        line = resp.readline()
        if not line:
            print("[connection closed]")
            break
        decoded = line.decode("utf-8", errors="replace").strip()
        if decoded.startswith("data:"):
            count += 1
            data = decoded[5:].strip()
            import json
            try:
                obj = json.loads(data)
                payload = obj.get("payload", {})
                ptype = payload.get("type", "?")
                if ptype == "sync":
                    sync = payload.get("syncEvent", {})
                    print(f"[{time.time()-start:.1f}s] SYNC: aggregate={sync.get('aggregateID','?')} seq={sync.get('seq','?')} type={sync.get('type','?')}")
                else:
                    print(f"[{time.time()-start:.1f}s] event type={ptype}")
            except:
                print(f"[{time.time()-start:.1f}s] raw: {data[:100]}")
    print(f"Total events: {count}")
except Exception as e:
    print(f"Error: {e}")
