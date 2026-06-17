"""
Quick screen inspector for Android emulator.
Dumps current UI texts, content-descs, and EditText fields.
Saves screenshot to simulator/screens/current.png

Usage:
    python look.py
    python look.py --label my_screen
"""

import argparse
import os
import re
import sys
import time

import uiautomator2 as u2

EMULATOR_SERIAL = "emulator-5554"
SCREENS_DIR = os.path.join(os.path.dirname(__file__), "screens")


def look(d, label="current"):
    os.makedirs(SCREENS_DIR, exist_ok=True)
    xml = d.dump_hierarchy()
    d.screenshot(os.path.join(SCREENS_DIR, f"{label}.png"))
    texts = re.findall(r'text="([^"]+)"', xml)
    descs = re.findall(r'content-desc="([^"]+)"', xml)
    texts = [t for t in texts if t and not re.match(r"^\d+:\d+", t)]
    descs = [t for t in descs if t]
    try:
        print(f"Texts: {texts[:30]}")
        print(f"Descs: {descs[:10]}")
    except UnicodeEncodeError:
        print(f"Texts: {[t.encode('ascii', 'replace').decode() for t in texts[:30]]}")
        print(f"Descs: {[t.encode('ascii', 'replace').decode() for t in descs[:10]]}")

    fields = d(className="android.widget.EditText")
    if fields.count > 0:
        print(f"EditText fields: {fields.count}")
        for i in range(fields.count):
            info = fields[i].info
            print(f"  [{i}] text={info['text']!r} bounds={info['bounds']}")

    return xml, texts, descs


def main():
    parser = argparse.ArgumentParser(description="Quick screen inspector")
    parser.add_argument("--label", default="current", help="Screenshot filename label")
    parser.add_argument("--serial", default=EMULATOR_SERIAL, help="Emulator serial")
    args = parser.parse_args()

    d = u2.connect(args.serial)
    look(d, args.label)


if __name__ == "__main__":
    main()
