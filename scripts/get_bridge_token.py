import sqlite3
import hmac
import hashlib
import os
import argparse
import json

DB_PATH = os.path.expanduser("~/.openmate/bridge.db")
TOKEN_FILE = os.path.join(os.path.dirname(__file__), ".bridge_token")


def get_secret_key(db_path):
    conn = sqlite3.connect(db_path)
    row = conn.execute("SELECT value FROM config WHERE key='auth.secret_key'").fetchone()
    conn.close()
    if not row:
        raise ValueError("auth.secret_key not found in DB")
    return bytes.fromhex(row[0])


def generate_token(secret_key, device_id):
    device_prefix = device_id[:16]
    random_part = os.urandom(24)
    random_hex = random_part.hex()
    payload = f"{device_prefix}{random_hex}"
    signature = hmac.new(secret_key, payload.encode(), hashlib.sha256).digest()
    signature_hex = signature.hex()
    token = f"{payload}{signature_hex}"
    assert len(token) == 128
    return token


def ensure_device(db_path, device_id, ip="127.0.0.1", name="TestDevice"):
    conn = sqlite3.connect(db_path)
    existing = conn.execute(
        "SELECT device_id FROM paired_devices WHERE device_id=?", (device_id,)
    ).fetchone()
    if existing:
        print(f"  Device {device_id} already exists")
    else:
        import time
        now = int(time.time() * 1000)
        conn.execute(
            "INSERT INTO paired_devices (device_id, client_device_id, ip, name, user_agent, paired_at, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (device_id, "", ip, name, "OpenMate/Test", now, now),
        )
        conn.commit()
        print(f"  Device {device_id} inserted")
    conn.close()


def main():
    parser = argparse.ArgumentParser(description="Get/refresh a Bridge test token")
    parser.add_argument("--device", default="testdevice000001", help="device_id (16 hex chars)")
    parser.add_argument("--db", default=DB_PATH, help="path to bridge.db")
    parser.add_argument("--name", default="CLI-Test", help="device display name")
    parser.add_argument("--show", action="store_true", help="show token in console")
    parser.add_argument("--save", action="store_true", default=True, help="save token to .bridge_token")
    args = parser.parse_args()

    device_id = args.device[:16]

    if not os.path.exists(args.db):
        print(f"ERROR: DB not found at {args.db}")
        return

    print(f"DB: {args.db}")
    secret_key = get_secret_key(args.db)
    print(f"  secret_key loaded ({len(secret_key)} bytes)")

    ensure_device(args.db, device_id, name=args.name)
    token = generate_token(secret_key, device_id)

    if args.save:
        with open(TOKEN_FILE, "w") as f:
            f.write(token + "\n")
        print(f"  Token saved to {TOKEN_FILE}")

    if args.show:
        print(f"  Authorization: Bearer {token}")


if __name__ == "__main__":
    main()
