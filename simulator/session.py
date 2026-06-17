"""
OpenMate Android Emulator Session Script
Creates a new session (with working directory) and sends a message.

Prerequisites:
    - Instance already paired (run pair.py first)
    - Docker container running
    - Android emulator running with OpenMate APK installed
    - uiautomator2 installed: pip install uiautomator2

Usage:
    python session.py                                                # Default settings
    python session.py --title "My Session" --message "Explain recursion"
    python session.py --directory /root/workspace                    # Custom working directory
    python session.py --wait 30                                      # Wait longer for AI response

State guard: Script verifies it starts from the instance detail page or instance list.
If on the instance list, it navigates to the instance detail page first.
"""

import argparse
import os
import re
import sys
import time

import uiautomator2 as u2

sys.path.insert(0, os.path.dirname(__file__))
from look import look

EMULATOR_SERIAL = "emulator-5554"
DEFAULT_INSTANCE = "Test-Bridge"
DEFAULT_TITLE = "Test Session"
DEFAULT_DIRECTORY = "/root/workspace"
DEFAULT_MESSAGE = "Hello, what is 2+2?"
DEFAULT_WAIT = 15


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


def is_instance_detail(d):
    return d(description="New Session").exists


def is_instance_list(d):
    return d(description="Add Instance").exists or d(description="Scan QR Code").exists


def navigate_to_instance_detail(d, instance_name):
    if is_instance_detail(d):
        return True

    if is_instance_list(d):
        if d(text=instance_name).exists:
            d(text=instance_name).click()
            time.sleep(2)
            return is_instance_detail(d)
        print(f"ERROR: Instance '{instance_name}' not found in list")
        return False

    print("ERROR: Not on instance list or instance detail page")
    print("  Navigate to the instance list first, then run this script")
    return False


def select_directory(d, directory):
    d(text="Select directory").click()
    time.sleep(2)

    if not d(text="Select Directory").exists:
        print("ERROR: Directory picker did not open")
        return False

    fields = d(className="android.widget.EditText")
    if fields.count < 1:
        print("ERROR: No path input field in directory picker")
        return False

    fields[0].set_text(directory)
    time.sleep(0.3)
    dismiss_keyboard(d)
    time.sleep(0.5)

    d(text="Confirm").click()
    time.sleep(2)

    if d(text="Select Directory").exists:
        print(f"ERROR: Directory '{directory}' not found or invalid")
        return False

    print(f"  Directory set to: {directory}")
    return True


def create_session(d, title, directory):
    print("=== Step 1: Open New Session dialog ===")
    d(description="New Session").click()
    time.sleep(2)

    _, texts, _ = look(d, "session_dialog")
    if not d(text="Create").exists:
        print("ERROR: New Session dialog not shown")
        return False

    print("=== Step 2: Fill title ===")
    fields = d(className="android.widget.EditText")
    if fields.count < 1:
        print("ERROR: No title input field found")
        return False

    fields[0].set_text(title)
    time.sleep(0.3)
    dismiss_keyboard(d)
    time.sleep(0.5)

    print("=== Step 3: Select directory ===")
    if not select_directory(d, directory):
        return False

    print("=== Step 4: Create session ===")
    d(text="Create").click()
    time.sleep(3)

    _, texts, _ = look(d, "session_created")
    if not any("message" in t.lower() for t in texts):
        print("ERROR: Session chat view not shown")
        return False

    print(f"  Session '{title}' created in {directory}!")
    return True


def send_message(d, message, wait_seconds):
    print("=== Step 5: Send message ===")
    fields = d(className="android.widget.EditText")
    if fields.count < 1:
        print("ERROR: No message input field found")
        return False

    fields[0].set_text(message)
    time.sleep(0.3)
    dismiss_keyboard(d)
    time.sleep(0.5)

    d(description="Send").click()
    print(f"  Message sent: {message!r}")
    print(f"  Waiting {wait_seconds}s for AI response...")
    time.sleep(wait_seconds)

    _, texts, _ = look(d, "session_response")
    return True


def session(d, instance_name, title, directory, message, wait_seconds):
    if not navigate_to_instance_detail(d, instance_name):
        return False

    if not create_session(d, title, directory):
        return False

    if not send_message(d, message, wait_seconds):
        return False

    print(f"\nSession complete! Title: {title!r}, Dir: {directory}, Message: {message!r}")
    return True


def main():
    parser = argparse.ArgumentParser(description="OpenMate Android Emulator Session")
    parser.add_argument("--instance", default=DEFAULT_INSTANCE, help="Instance name to use")
    parser.add_argument("--title", default=DEFAULT_TITLE, help="Session title")
    parser.add_argument("--directory", default=DEFAULT_DIRECTORY, help="Working directory on Bridge")
    parser.add_argument("--message", default=DEFAULT_MESSAGE, help="Message to send")
    parser.add_argument("--wait", type=int, default=DEFAULT_WAIT, help="Seconds to wait for AI response")
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

    success = session(d, args.instance, args.title, args.directory, args.message, args.wait)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
