#!/usr/bin/env python3
"""List AntennaPod ad-timestamp events stored on the Nostr relay(s).

Connects to the relay(s) used by NostrClient, sends a REQ subscription and
prints every event returned until EOSE (or the timeout is reached).

By default only the app's own ad-timestamp entries are listed: kind 31337
events tagged with t:antennapod-adskip (the same marker NostrClient adds on
publish). Use --all to list every event on the relay, or override the filter
with --kind/--tag.

Relay selection (first match wins):
  1. --relay arguments (repeatable)
  2. NOSTR_RELAYS environment variable (comma-separated)
  3. default: wss://adskip.1681248.com

Examples:
  python3 scripts/list_nostr_events.py
  python3 scripts/list_nostr_events.py --all --limit 10
  python3 scripts/list_nostr_events.py --json
  NOSTR_RELAYS=wss://relay.damus.io python3 scripts/list_nostr_events.py
"""

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone

DEFAULT_RELAYS = ["wss://adskip.1681248.com"]
DEFAULT_TIMEOUT = 15

try:
    from websockets.sync.client import connect
except ImportError:
    sys.exit(
        "The 'websockets' module is required. Install it with:\n"
        "  pip install websockets"
    )


def iso_from_ts(unix_ts):
    try:
        return datetime.fromtimestamp(unix_ts, tz=timezone.utc).isoformat()
    except (OverflowError, OSError, ValueError):
        return str(unix_ts)


def short_tags(tags):
    parts = []
    for tag in tags[:5]:
        if tag:
            parts.append(tag[0] + ":" + (tag[1] if len(tag) > 1 else ""))
    return " ".join(parts)


def format_event(event):
    return "{ts} kind={kind} pubkey={pubkey} id={id} tags=[{tags}] content={content}".format(
        ts=iso_from_ts(event.get("created_at", 0)),
        kind=event.get("kind", "?"),
        pubkey=event.get("pubkey", "")[:12],
        id=event.get("id", "")[:16],
        tags=short_tags(event.get("tags", [])),
        content=(event.get("content", "") or "")[:160].replace("\n", " "),
    )


def query_relay(relay_url, filter_obj, timeout):
    events = []
    sub_id = "list-nostr-%d" % int(time.time())
    request = json.dumps(["REQ", sub_id, filter_obj])
    try:
        with connect(relay_url, open_timeout=timeout) as ws:
            ws.send(request)
            while True:
                raw = ws.recv(timeout=timeout)
                try:
                    msg = json.loads(raw)
                except ValueError:
                    continue
                if not isinstance(msg, list) or len(msg) < 2:
                    continue
                msg_type = msg[0]
                if msg_type == "EVENT" and len(msg) > 2:
                    events.append(msg[2])
                elif msg_type == "EOSE":
                    break
                elif msg_type == "CLOSED":
                    reason = msg[2] if len(msg) > 2 else "unknown"
                    print(
                        "relay {} closed subscription: {}".format(relay_url, reason),
                        file=sys.stderr,
                    )
                    break
                elif msg_type == "NOTICE" and len(msg) > 1:
                    print(
                        "relay {} notice: {}".format(relay_url, msg[1]), file=sys.stderr
                    )
    except Exception as exc:
        print("relay {} failed: {}".format(relay_url, exc), file=sys.stderr)
    return events


def main():
    parser = argparse.ArgumentParser(
        description="List AntennaPod ad-timestamp events on the Nostr relay(s)."
    )
    parser.add_argument(
        "--relay",
        action="append",
        dest="relays",
        help="relay URL to query (repeatable)",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="list every event on the relay (no kind/tag filter)",
    )
    parser.add_argument(
        "--kind",
        type=int,
        action="append",
        default=[],
        help="only list events of this kind (repeatable; default: 31337)",
    )
    parser.add_argument(
        "--tag",
        help="only list events carrying this t: tag (default: antennapod-adskip)",
    )
    parser.add_argument("--author", help="only list events from this pubkey (hex)")
    parser.add_argument(
        "--limit", type=int, help="relay hint for the maximum number of events"
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT,
        help="seconds to wait for events/EOSE per relay (default: %d)"
        % DEFAULT_TIMEOUT,
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print raw JSON events instead of a human-readable summary",
    )
    args = parser.parse_args()

    if args.relays:
        relays = args.relays
    elif os.environ.get("NOSTR_RELAYS"):
        relays = [r.strip() for r in os.environ["NOSTR_RELAYS"].split(",") if r.strip()]
    else:
        relays = DEFAULT_RELAYS

    filter_obj = {}
    if not args.all:
        filter_obj["kinds"] = args.kind or [31337]
        if args.tag is not None:
            filter_obj["#t"] = [args.tag]
        elif not args.kind:
            filter_obj["#t"] = ["antennapod-adskip"]
    if args.author:
        filter_obj["authors"] = [args.author]
    if args.limit is not None:
        filter_obj["limit"] = args.limit

    seen = {}
    for relay_url in relays:
        events = query_relay(relay_url, filter_obj, args.timeout)
        for event in events:
            event_id = event.get("id")
            if event_id is not None:
                if event_id in seen:
                    continue
                seen[event_id] = True
            if args.json:
                print(json.dumps(event, ensure_ascii=False))
            else:
                print(format_event(event))

    print(
        "queried {} relay(s), found {} unique event(s)".format(len(relays), len(seen)),
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
