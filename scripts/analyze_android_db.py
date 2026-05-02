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

    print("\n=== PartEntity types count ===")
    try:
        for row in db.execute("SELECT type, count(*) FROM PartEntity GROUP BY type"):
            print(f"  {row[0]}: {row[1]}")
    except Exception as e:
        print(f"  Error: {e}")

    print("\n=== File parts ===")
    try:
        for row in db.execute("SELECT id, mime, filename, length(url) FROM PartEntity WHERE type='file' LIMIT 10"):
            print(f"  id={row[0]}, mime={row[1]}, filename={row[2]}, url_len={row[3]}")
    except Exception as e:
        print(f"  Error: {e}")

    print("\n=== Text parts with 'Called the' (synthetic check) ===")
    try:
        for row in db.execute("SELECT id, substr(text,1,80) FROM PartEntity WHERE type='text' AND text LIKE '%Called the%' LIMIT 5"):
            print(f"  id={row[0]}, text={row[1]}")
    except Exception as e:
        print(f"  Error: {e}")

db.close()
