#!/usr/bin/env python3
import argparse
import json
import socket
import time
from datetime import datetime, timezone
from pathlib import Path


def parse_timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=9090, type=int)
    parser.add_argument("--speed", default=1.0, type=float, help="Timestamp speed multiplier")
    parser.add_argument("--loop", action="store_true")
    parser.add_argument("--validate", action="store_true", help="Validate the template without opening a UDP socket")
    args = parser.parse_args()
    if args.speed <= 0:
        parser.error("--speed must be positive")
    events = [json.loads(line) for line in args.input.read_text().splitlines() if line.strip()]
    if not events:
        parser.error("input has no events")
    for event in events:
        for required in ("timestamp", "method", "endpoint", "request_bytes", "baseline_scope"):
            if required not in event:
                parser.error(f"event misses required field: {required}")
    events.sort(key=lambda event: parse_timestamp(event["timestamp"]))
    if args.validate:
        print(f"valid: {len(events)} events; scope={events[0]['baseline_scope']}")
        return
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        while True:
            start_source, start_now = parse_timestamp(events[0]["timestamp"]), datetime.now(timezone.utc)
            previous = None
            for event in events:
                source_time = parse_timestamp(event["timestamp"])
                target = start_now + (source_time - start_source) / args.speed
                if previous is not None:
                    time.sleep(max(0, (target - datetime.now(timezone.utc)).total_seconds()))
                replayed = dict(event)
                replayed["timestamp"] = target.isoformat(timespec="milliseconds").replace("+00:00", "Z")
                sock.sendto(json.dumps(replayed, separators=(",", ":")).encode(), (args.host, args.port))
                previous = event
            if not args.loop:
                break
    finally:
        sock.close()


if __name__ == "__main__":
    main()
