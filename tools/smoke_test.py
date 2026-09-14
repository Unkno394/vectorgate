#!/usr/bin/env python3
import argparse
import json
import sys
import time
from urllib.error import URLError
from urllib.request import Request, urlopen


def get(url, expected=200):
    try:
        with urlopen(Request(url), timeout=3) as response:
            body = response.read().decode()
            if response.status != expected:
                raise RuntimeError(f"{url}: expected HTTP {expected}, got {response.status}")
            return body
    except URLError as error:
        raise RuntimeError(f"{url}: {error.reason}") from error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--retries", type=int, default=15, help="attempts while services start")
    args = parser.parse_args()
    checks = [
        ("dashboard", "http://127.0.0.1:5173/"),
        ("gateway route API", "http://127.0.0.1:8080/__gateway/routes"),
        ("analytics live API", "http://127.0.0.1:9091/api/state"),
        ("proxied backend", "http://127.0.0.1:8080/catalog"),
    ]
    last_error = None
    for _ in range(args.retries):
        try:
            results = {name: get(url) for name, url in checks}
            state = json.loads(results["analytics live API"])
            if "windows" not in state or "incidents" not in state:
                raise RuntimeError("analytics live API returned an unexpected document")
            routes = json.loads(results["gateway route API"])
            if "routes" not in routes:
                raise RuntimeError("gateway route API returned an unexpected document")
            print("VectorGate smoke test passed: dashboard, gateway, analytics and backend are reachable.")
            return
        except (RuntimeError, json.JSONDecodeError) as error:
            last_error = error
            time.sleep(2)
    print(f"VectorGate smoke test failed: {last_error}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
