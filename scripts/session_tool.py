#!/usr/bin/env python3
"""OpenMate session analysis tool.

Usage:
  python session_tool.py list [--all]
  python session_tool.py find <keyword>
  python session_tool.py parts <sessionID> [--limit N]
  python session_tool.py export <sessionID> [--limit N] [--after TIMESTAMP] [-o FILE]
"""

import json
import urllib.request
import argparse
import sys
import os
from datetime import datetime

if sys.stdout.encoding != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://127.0.0.1:6096"


def get(path, params=None):
    url = BASE + path
    if params:
        qs = "&".join(f"{k}={v}" for k, v in params.items() if v is not None)
        if qs:
            url += "?" + qs
    return json.loads(urllib.request.urlopen(url).read())


def get_sessions(limit=500):
    return get("/experimental/session", {"limit": str(limit)})


def cmd_list(args):
    for s in get_sessions():
        if not args.all and s.get("parentID"):
            continue
        parent = " [sub]" if s.get("parentID") else ""
        title = s.get("title", "") or "Untitled"
        print(f"{s['id']}  {title[:60]}{parent}")


def cmd_find(args):
    kw = args.keyword.lower()
    found = False
    for s in get_sessions():
        if s.get("parentID"):
            continue
        title = s.get("title", "") or ""
        if kw in title.lower():
            print(f"{s['id']}  {title[:60]}")
            found = True
    if not found:
        print(f"No sessions matching '{args.keyword}'")


def cmd_parts(args):
    params = {"limit": str(args.limit)} if args.limit else None
    msgs = get(f"/session/{args.session_id}/message", params)

    for m in msgs:
        info = m.get("info", {})
        role = info.get("role", "?")
        ts = info.get("time", {}).get("created", 0)
        ts_str = datetime.fromtimestamp(ts / 1000).strftime("%m-%d %H:%M") if ts else "?"
        completed = info.get("time", {}).get("completed")
        status = "" if completed else " [RUNNING]"

        parts = m.get("parts", [])
        print(f"\n--- {role} @ {ts_str}{status} msg={info.get('id', '')[:12]} ---")
        for p in parts:
            ptype = p.get("type", "?")
            if ptype == "text":
                text = p.get("text", "")[:80].replace("\n", "\\n")
                print(f"  [text] {text}")
            elif ptype == "file":
                print(f"  [file] {p.get('filename', '')} {p.get('url', '')}")
            elif ptype == "tool":
                tool = p.get("tool", "")
                call_id = p.get("callID", "")[:15]
                st = p.get("state", {})
                st_str = st.get("status", "") if isinstance(st, dict) else ""
                print(f"  [tool] {tool} call={call_id} status={st_str}")
            elif ptype == "reasoning":
                text = p.get("text", "")[:60].replace("\n", "\\n")
                print(f"  [reasoning] {text}")
            elif ptype in ("step-start", "step-finish"):
                print(f"  [{ptype}]")
            else:
                print(f"  [{ptype}]")


def cmd_export(args):
    params = {"limit": str(args.limit)} if args.limit else None
    msgs = get(f"/session/{args.session_id}/message", params)

    if args.after or args.before:
        out = []
        for m in msgs:
            ts = m.get("info", {}).get("time", {}).get("created", 0)
            if args.after and ts <= args.after:
                continue
            if args.before and ts >= args.before:
                continue
            out.append(m)
        msgs = out

    text = json.dumps(msgs, indent=2, ensure_ascii=False)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"Exported {len(msgs)} messages to {args.output}")
    else:
        print(text)


def main():
    parser = argparse.ArgumentParser(description="OpenMate session analysis")
    sub = parser.add_subparsers(dest="cmd")

    p_list = sub.add_parser("list")
    p_list.add_argument("--all", action="store_true")

    p_find = sub.add_parser("find")
    p_find.add_argument("keyword")

    p_parts = sub.add_parser("parts")
    p_parts.add_argument("session_id")
    p_parts.add_argument("--limit", type=int, default=20)

    p_export = sub.add_parser("export")
    p_export.add_argument("session_id")
    p_export.add_argument("--limit", type=int, default=20)
    p_export.add_argument("--after", type=int)
    p_export.add_argument("--before", type=int)
    p_export.add_argument("--output", "-o")

    args = parser.parse_args()
    cmds = {
        "list": cmd_list,
        "find": cmd_find,
        "parts": cmd_parts,
        "export": cmd_export,
    }
    fn = cmds.get(args.cmd)
    if fn:
        fn(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
