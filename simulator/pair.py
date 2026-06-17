"""
OpenMate Android Emulator Pairing Script
Uses uiautomator2 to automate the pairing flow in the Android emulator.

Prerequisites:
    - Docker container running: docker compose up -d (in D:\\openmate\\docker)
- Android emulator running with OpenMate APK installed
- uiautomator2 installed: pip install uiautomator2

Usage:
    python pair.py                          # Full auto-pair with defaults
    python pair.py --name MyBridge          # Custom instance name
    python pair.py --host 10.0.2.2          # Custom Bridge host
    python pair.py --port 4097              # Custom Bridge port
    python pair.py --container openmate-bridge  # Custom container name
"""

import argparse
import re
import subprocess
import sys
import time

import uiautomator2 as u2

BRIDGE_CONTAINER = "openmate-bridge"
DEFAULT_NAME = "Test-Bridge"
DEFAULT_HOST = "10.0.2.2"
DEFAULT_PORT = "4097"
EMULATOR_SERIAL = "emulator-5554"


def run(cmd, **kwargs):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kwargs)


def look(d, label):
    xml = d.dump_hierarchy()
    d.screenshot(f"D:\\openmate\\simulator\\screens\\{label}.png")
    texts = re.findall(r'text="([^"]+)"', xml)
    descs = re.findall(r'content-desc="([^"]+)"', xml)
    texts = [t for t in texts if t and not re.match(r"^\d+:\d+", t)]
    descs = [t for t in descs if t]
    print(f"[{label}] Texts: {texts}")
    print(f"[{label}] Descs: {descs}")
    return xml, texts, descs


def dismiss_keyboard(d):
    if d(description="Done").exists:
        d(description="Done").click()
        time.sleep(0.5)


def wait_for_app(d, timeout=10):
    start = time.time()
    while time.time() - start < timeout:
        current = d.app_current()
        if current["package"] == "com.openmate":
            return True
        time.sleep(1)
    return False


def approve_pin(container, pin):
    result = run(["docker", "exec", container, "/usr/local/bin/openmate", "approve", pin])
    output = result.stdout.strip()
    print(f"  Approve result: {output}")
    return "approved" in output.lower()


def extract_pin(texts):
    for t in texts:
        if re.match(r"^\d{6}$", t):
            return t
    return None


def pair(d, name, host, port, container):
    print("=== Step 1: Navigate to Add Instance ===")
    if not d(description="Add Instance").exists and not d(text="Add Instance").exists:
        if d(description="Back").exists:
            d(description="Back").click()
            time.sleep(1)

    d(description="Add Instance").click()
    time.sleep(2)

    if not d(className="android.widget.EditText").exists:
        print("ERROR: Could not reach Add Instance form")
        return False

    look(d, "form")

    print("=== Step 2: Fill form ===")
    fields = d(className="android.widget.EditText")
    if fields.count < 3:
        print(f"ERROR: Expected 3 EditText fields, found {fields.count}")
        return False

    fields[0].set_text(name)
    time.sleep(0.3)
    fields[1].set_text(host)
    time.sleep(0.3)
    fields[2].set_text(port)
    time.sleep(0.3)

    dismiss_keyboard(d)
    time.sleep(0.5)
    look(d, "filled")

    print("=== Step 3: Test Connection ===")
    d(text="Test Connection").click()
    time.sleep(3)
    _, texts, _ = look(d, "tested")

    if not any("connected" in t.lower() for t in texts):
        print("ERROR: Test Connection failed - Bridge not connected")
        return False
    print("  Bridge connected!")

    print("=== Step 4: Save ===")
    d.click(360, 1036)
    time.sleep(3)
    _, texts, descs = look(d, "pair_prompt")

    print("=== Step 5: Approve PIN ===")
    pin = extract_pin(texts)
    if not pin:
        print("ERROR: Could not find 6-digit PIN on screen")
        print(f"  Screen texts: {texts}")
        return False
    print(f"  PIN: {pin}")

    if not approve_pin(container, pin):
        print("ERROR: Approve failed")
        return False

    print("=== Step 6: Confirm ===")
    d(text="Confirm").click()
    time.sleep(3)
    _, texts, _ = look(d, "confirmed")

    if any("instances" in t.lower() for t in texts):
        instance_name = next((t for t in texts if t == name), None)
        if instance_name:
            print(f"\nPairing complete! Instance '{name}' added successfully.")
        else:
            print(f"\nPairing complete! Instance added (name may differ).")
        return True
    else:
        print("ERROR: Unexpected state after confirm")
        return False


def main():
    parser = argparse.ArgumentParser(description="OpenMate Android Emulator Pairing")
    parser.add_argument("--name", default=DEFAULT_NAME, help="Instance name")
    parser.add_argument("--host", default=DEFAULT_HOST, help="Bridge host address")
    parser.add_argument("--port", default=DEFAULT_PORT, help="Bridge port")
    parser.add_argument("--container", default=BRIDGE_CONTAINER, help="Docker container name")
    parser.add_argument("--serial", default=EMULATOR_SERIAL, help="Emulator serial")
    args = parser.parse_args()

    print(f"Connecting to emulator ({args.serial})...")
    d = u2.connect(args.serial)

    current = d.app_current()
    print(f"Current app: {current['package']}")
    if current["package"] != "com.openmate":
        print("Launching OpenMate app...")
        d.app_start("com.openmate", ".app.MainActivity")
        time.sleep(3)
        if not wait_for_app(d):
            print("ERROR: Could not launch OpenMate app")
            sys.exit(1)

    success = pair(d, args.name, args.host, args.port, args.container)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
