#!/usr/bin/env python3
import argparse
import json
import math
from pathlib import Path

FEATURE_ORDER = ["requests_per_second", "mean_inter_request_ms", "error_rate", "mean_request_bytes", "mean_processing_ms", "unique_endpoints", "endpoint_repeatability", "get_share", "post_share", "other_methods_share"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--scope", default="gateway")
    parser.add_argument("--backend", default="127.0.0.1:3000")
    parser.add_argument("--minimum-windows", type=int, default=30)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.input.read_text().splitlines() if line.strip()]
    vectors = [row["feature_vector"] for row in rows if row.get("baseline_scope") == args.scope and row.get("backend") == args.backend]
    if len(vectors) < args.minimum_windows:
        parser.error(f"need at least {args.minimum_windows} matching windows; found {len(vectors)}")
    n, d = len(vectors), len(FEATURE_ORDER)
    mean = [sum(vector[i] for vector in vectors) / n for i in range(d)]
    covariance = [[sum((vector[i] - mean[i]) * (vector[j] - mean[j]) for vector in vectors) / (n - 1) for j in range(d)] for i in range(d)]
    result = {
        "baseline_version": "initial-v1",
        "baseline_scope": args.scope,
        "backend": args.backend,
        "window_seconds": 60,
        "samples": n,
        "feature_order": FEATURE_ORDER,
        "mean": mean,
        "stddev": [math.sqrt(covariance[i][i]) for i in range(d)],
        "covariance_matrix": covariance,
        "source_windows": {"first": rows[0]["window_start"], "last": rows[-1]["window_end"]},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"wrote {args.output}: {n} windows")


if __name__ == "__main__":
    main()
