#!/bin/bash
set -e

mkdir -p /root/.local/share/opencode
mkdir -p /root/.local/state/opencode
mkdir -p /root/.openmate
mkdir -p /root/workspace

echo "=== OpenMate V2 Test Container ==="

opencode2 serve --port 4098 --hostname 127.0.0.1 > /tmp/oc2.log 2>&1 &
OC_PID=$!

sleep 5

PASSWORD=$(grep -oP 'server password \K\S+' /tmp/oc2.log | tail -1)
if [ -z "$PASSWORD" ]; then
    echo "ERROR: Could not extract password from opencode2 output"
    cat /tmp/oc2.log
    exit 1
fi
echo "opencode2 password: $PASSWORD"

# Escape single quotes for SQL
ESC_PASSWORD=$(echo "$PASSWORD" | sed "s/'/''/g")

sqlite3 /root/.openmate/bridge.db "
CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS paired_devices (
    device_id TEXT PRIMARY KEY,
    client_device_id TEXT NOT NULL DEFAULT '',
    ip TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    user_agent TEXT NOT NULL DEFAULT '',
    paired_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_paired_devices_client_device_id
    ON paired_devices(client_device_id) WHERE client_device_id != '';
CREATE TABLE IF NOT EXISTS bridge_config (key TEXT PRIMARY KEY, value TEXT NOT NULL);

INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('bridge.port', '4097', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('bridge.hostname', '0.0.0.0', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.binary', 'opencode2', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.hostname', '127.0.0.1', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.port', '4098', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.directory', '/root/workspace', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.auto_start', 'false', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.auto_restart', 'false', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.db_path', '/root/.local/share/opencode/opencode-next.db', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.run_as_user', '', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('opencode.password', '${ESC_PASSWORD}', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('fs.allowed_paths', '', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('gateway.url', '', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('gateway.auto_connect', 'false', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('auth.secret_key', 'd7d7a3d4e6f7a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6', strftime('%s','now'));
INSERT OR REPLACE INTO config (key, value, updated_at) VALUES ('auth.instance_id', 'aabbccddeeff00112233445566778899', strftime('%s','now'));
"
echo "bridge.db configured"

exec openmate
