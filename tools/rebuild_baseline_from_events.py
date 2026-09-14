#!/usr/bin/env python3
import argparse
import json
import math
import re
from collections import Counter
from datetime import datetime
from pathlib import Path

FEATURE_ORDER = ["requests_per_second", "mean_inter_request_ms", "error_rate", "mean_request_bytes", "mean_processing_ms", "unique_endpoints", "endpoint_repeatability", "get_share", "post_share", "other_methods_share"]
UUID = re.compile(r"^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")
ORDER_ID = re.compile(r"^o-[0-9a-fA-F]+$")
PRODUCT_ID = re.compile(r"^p-[0-9]+$")


def logical_endpoint(path):
    path = path.split("?", 1)[0]
    out = []
    for segment in path.split("/"):
        if segment.isdecimal() or UUID.fullmatch(segment) or ORDER_ID.fullmatch(segment) or PRODUCT_ID.fullmatch(segment):
            out.append(":id")
        else:
            out.append(segment)
    return "/".join(out) or "/"


def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def vector(events):
    events.sort(key=lambda item: item["_time"])
    count = len(events)
    methods = Counter(item["method"] for item in events)
    endpoints = {item["logical_endpoint"] for item in events}
    intervals = [(events[i]["_time"] - events[i - 1]["_time"]).total_seconds() * 1000 for i in range(1, count)]
    get_share = methods["GET"] / count if count else 0
    post_share = methods["POST"] / count if count else 0
    return [
        count / 60.0,
        sum(intervals) / len(intervals) if intervals else 0,
        sum(item["response_status"] >= 400 for item in events) / count if count else 0,
        sum(item["request_bytes"] for item in events) / count if count else 0,
        sum(item["duration_ms"] for item in events) / count if count else 0,
        len(endpoints),
        1 - len(endpoints) / count if count else 0,
        get_share,
        post_share,
        1 - get_share - post_share,
    ]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("events", type=Path)
    parser.add_argument("source_windows", type=Path)
    parser.add_argument("normalized_events", type=Path)
    parser.add_argument("normalized_windows", type=Path)
    parser.add_argument("baseline", type=Path)
    args = parser.parse_args()
    raw = [json.loads(line) for line in args.events.read_text().splitlines() if line.strip()]
    for event in raw:
        event["_time"] = timestamp(event["timestamp"])
        event["logical_endpoint"] = logical_endpoint(event["endpoint"])
    approved = [json.loads(line) for line in args.source_windows.read_text().splitlines() if line.strip()]
    rebuilt = []
    used = []
    for source in approved:
        start, end = timestamp(source["window_start"]), timestamp(source["window_end"])
        events = [event for event in raw if start <= event["_time"] < end]
        if not events:
            raise SystemExit("an approved window has no raw events")
        values = vector(events)
        rebuilt.append({"window_start": source["window_start"], "window_end": source["window_end"], "baseline_scope": "gateway", "backend": "127.0.0.1:3000", "requests": len(events), "method_counts": dict(Counter(event["method"] for event in events)), "feature_vector": values})
        used.extend(events)
    dimensions, samples = len(FEATURE_ORDER), len(rebuilt)
    mean = [sum(row["feature_vector"][i] for row in rebuilt) / samples for i in range(dimensions)]
    covariance = [[sum((row["feature_vector"][i] - mean[i]) * (row["feature_vector"][j] - mean[j]) for row in rebuilt) / (samples - 1) for j in range(dimensions)] for i in range(dimensions)]
    for path in (args.normalized_events, args.normalized_windows, args.baseline):
        path.parent.mkdir(parents=True, exist_ok=True)
    args.normalized_events.write_text("".join(json.dumps({key: value for key, value in event.items() if key != "_time"}, separators=(",", ":")) + "\n" for event in used))
    args.normalized_windows.write_text("".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rebuilt))
    args.baseline.write_text(json.dumps({"baseline_version": "initial-v1", "baseline_scope": "gateway", "backend": "127.0.0.1:3000", "window_seconds": 60, "samples": samples, "feature_order": FEATURE_ORDER, "mean": mean, "stddev": [math.sqrt(covariance[i][i]) for i in range(dimensions)], "covariance_matrix": covariance, "source": "trusted-normal-events-logical.ndjson", "path_normalization": "strip query; replace numeric IDs, UUIDs, o-hex order IDs and p-numeric product IDs with :id"}, indent=2) + "\n")
    print(f"rebuilt {samples} windows from {len(used)} normalized events")


if __name__ == "__main__":
    main()
