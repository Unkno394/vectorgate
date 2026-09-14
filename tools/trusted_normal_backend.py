#!/usr/bin/env python3
import argparse
import json
import random
import secrets
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PRODUCTS = [
    {"id": "p-101", "name": "Running shoes", "price": 79},
    {"id": "p-102", "name": "Travel backpack", "price": 64},
    {"id": "p-103", "name": "Wireless keyboard", "price": 51},
    {"id": "p-104", "name": "Coffee grinder", "price": 88},
]
TOKENS, ORDERS = set(), {}


class Handler(BaseHTTPRequestHandler):
    server_version = "VectorGateTrustedNormal/1.0"

    def log_message(self, *_):
        pass

    def send_json(self, status, value, delay=(0.008, 0.045)):
        time.sleep(random.uniform(*delay))
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            return json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            return None

    def authorized(self):
        return self.headers.get("Authorization", "").removeprefix("Bearer ") in TOKENS

    def do_GET(self):
        request = urlparse(self.path)
        if request.path == "/catalog":
            return self.send_json(HTTPStatus.OK, {"items": PRODUCTS})
        if request.path.startswith("/products/"):
            product = next((item for item in PRODUCTS if item["id"] == request.path.rsplit("/", 1)[-1]), None)
            return self.send_json(HTTPStatus.OK, product) if product else self.send_json(HTTPStatus.NOT_FOUND, {"error": "product_not_found"})
        if request.path == "/search":
            query = parse_qs(request.query).get("q", [""])[0].lower()
            items = [item for item in PRODUCTS if query in item["name"].lower()]
            return self.send_json(HTTPStatus.OK, {"query": query, "items": items}, (0.015, 0.070))
        if request.path.startswith("/orders/"):
            if not self.authorized():
                return self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "auth_required"})
            order = ORDERS.get(request.path.rsplit("/", 1)[-1])
            return self.send_json(HTTPStatus.OK, order, (0.012, 0.060)) if order else self.send_json(HTTPStatus.NOT_FOUND, {"error": "order_not_found"})
        self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self):
        payload = self.read_json()
        if payload is None:
            return self.send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_json"})
        if self.path == "/auth/login":
            if not payload.get("username") or not payload.get("password"):
                return self.send_json(HTTPStatus.BAD_REQUEST, {"error": "credentials_required"})
            token = secrets.token_urlsafe(18)
            TOKENS.add(token)
            return self.send_json(HTTPStatus.OK, {"access_token": token}, (0.020, 0.090))
        if self.path == "/orders":
            if not self.authorized():
                return self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "auth_required"})
            product_id = payload.get("product_id")
            if not any(item["id"] == product_id for item in PRODUCTS):
                return self.send_json(HTTPStatus.BAD_REQUEST, {"error": "unknown_product"})
            order_id = "o-" + secrets.token_hex(5)
            ORDERS[order_id] = {"id": order_id, "product_id": product_id, "quantity": payload.get("quantity", 1), "state": "created"}
            return self.send_json(HTTPStatus.CREATED, ORDERS[order_id], (0.030, 0.120))
        self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=3000)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Trusted-normal backend on http://127.0.0.1:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
