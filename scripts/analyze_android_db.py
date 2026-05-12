import sqlite3, sys, os
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

if len(sys.argv) < 2:
    print("Usage: python analyze_android_db.py <db_path> [--sql <query>]")
    sys.exit(1)

db_path = sys.argv[1]

if not os.path.exists(db_path):
    print(f"File not found: {db_path}")
    sys.exit(1)

db = sqlite3.connect(db_path)

if len(sys.argv) >= 3 and sys.argv[2] == "--sql":
    query = " ".join(sys.argv[3:])
    for row in db.execute(query):
        print(row)
else:
    print("=== Tables ===")
    for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
        print(f"  {row[0]}")

    print("\n=== session_message types count ===")
    try:
        for row in db.execute("SELECT type, count(*) FROM session_message GROUP BY type"):
            print(f"  {row[0]}: {row[1]}")
    except Exception as e:
        print(f"  Error: {e}")

    print("\n=== session_message per session ===")
    try:
        for row in db.execute("SELECT sessionId, count(*) FROM session_message GROUP BY sessionId ORDER BY sessionId"):
            print(f"  {row[0]}: {row[1]}")
    except Exception as e:
        print(f"  Error: {e}")

    print("\n=== sync_state entries ===")
    try:
        for row in db.execute("SELECT sessionId, lastSyncSeq FROM sync_state"):
            print(f"  {row[0]}: seq={row[1]}")
    except Exception as e:
        print(f"  Error: {e}")

db.close()
