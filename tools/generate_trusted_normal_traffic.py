#!/usr/bin/env python3
import argparse
import http.client
import json
import random
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlencode

PRODUCTS = ("p-101", "p-102", "p-103", "p-104")
QUERIES = ("shoes", "backpack", "keyboard", "coffee")
USER_AGENTS = ("VG-Web/1.0", "VG-Mobile/1.0", "VG-Web/1.1")


class GatewayClient:
    def __init__(self, gateway_host, gateway_port, host_header, stats):
        self.gateway_host, self.gateway_port, self.host_header, self.stats = gateway_host, gateway_port, host_header, stats
        self.token = None

    def request(self, method, path, payload=None):
        body = json.dumps(payload, separators=(",", ":")) if payload is not None else None
        headers = {"Host": self.host_header, "User-Agent": random.choice(USER_AGENTS), "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        try:
            connection = http.client.HTTPConnection(self.gateway_host, self.gateway_port, timeout=5)
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read()
            connection.close()
            with self.stats["lock"]:
                self.stats["status"][response.status] += 1
            return response.status, json.loads(raw or b"{}")
        except (OSError, http.client.HTTPException, json.JSONDecodeError):
            with self.stats["lock"]:
                self.stats["failures"] += 1
            return 0, {}

    def pause(self, low=0.25, high=1.8):
        time.sleep(random.uniform(low, high))

    def browse(self):
        self.request("GET", "/catalog")
        self.pause()
        self.request("GET", "/products/" + random.choice(PRODUCTS))
        self.pause(0.4, 2.4)

    def search(self):
        self.request("GET", "/catalog")
        self.pause()
        self.request("GET", "/search?" + urlencode({"q": random.choice(QUERIES)}))
        self.pause(0.4, 2.1)

    def order(self):
        status, data = self.request("POST", "/auth/login", {"username": "normal-user-" + str(random.randint(1, 80)), "password": "trusted-demo"})
        if status != 200:
            return
        self.token = data.get("access_token")
        self.pause(0.4, 1.7)
        self.request("GET", "/catalog")
        self.pause()
        status, data = self.request("POST", "/orders", {"product_id": random.choice(PRODUCTS), "quantity": random.choice((1, 1, 1, 2))})
        if status == 201:
            self.pause(0.5, 2.5)
            self.request("GET", "/orders/" + data["id"])
        self.pause()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway-host", default="127.0.0.1")
    parser.add_argument("--gateway-port", type=int, default=8080)
    parser.add_argument("--host-header", default="normal.local")
    parser.add_argument("--users", type=int, default=5)
    parser.add_argument("--duration-seconds", type=int, default=300)
    args = parser.parse_args()
    if args.users < 1 or args.duration_seconds < 1:
        parser.error("--users and --duration-seconds must be positive")
    stats = {"lock": threading.Lock(), "status": Counter(), "failures": 0}
    deadline = time.monotonic() + args.duration_seconds

    def user_loop():
        client = GatewayClient(args.gateway_host, args.gateway_port, args.host_header, stats)
        while time.monotonic() < deadline:
            random.choices((client.browse, client.search, client.order), weights=(55, 25, 20))[0]()

    with ThreadPoolExecutor(max_workers=args.users) as pool:
        list(pool.map(lambda _: user_loop(), range(args.users)))
    print(json.dumps({"status_counts": dict(stats["status"]), "transport_failures": stats["failures"]}, sort_keys=True))


if __name__ == "__main__":
    main()
