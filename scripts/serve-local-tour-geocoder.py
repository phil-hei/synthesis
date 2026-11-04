#!/usr/bin/env python3
"""Serve a precomputed address->candidate JSON index on loopback, without outbound requests."""
import argparse
import json
import math
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def load_index(path):
    if path.stat().st_size > 20_000_000:
        raise ValueError("Address index exceeds 20 MB")
    index = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(index, dict) or len(index) > 10_000:
        raise ValueError("Expected an object with at most 10000 address queries")
    for query, candidates in index.items():
        if not isinstance(query, str) or not query.strip() or len(query) > 1000 or not isinstance(candidates, list) or len(candidates) > 100:
            raise ValueError("Invalid query or candidate count")
        for candidate in candidates:
            lon, lat = float(candidate["lon"]), float(candidate["lat"])
            if not math.isfinite(lon) or not math.isfinite(lat) or abs(lon) > 180 or abs(lat) > 90 or not candidate.get("display_name"):
                raise ValueError("Invalid candidate coordinate or label")
    return index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8585)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    index = load_index(args.index)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            url = urlsplit(self.path)
            parameters = parse_qs(url.query)
            status, result = 200, []
            if url.path != "/search":
                status, result = 404, {"error": "Unknown endpoint"}
            else:
                query = parameters.get("q", [""])[0]
                if not query.strip() or len(query) > 1000:
                    status, result = 400, {"error": "A query of at most 1000 characters is required"}
                else:
                    try:
                        limit = max(1, min(5, int(parameters.get("limit", ["5"])[0])))
                        result = index.get(query.strip(), [])[:limit]
                    except ValueError:
                        status, result = 400, {"error": "Invalid limit"}
            data = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_):
            pass  # Request URLs contain customer addresses.

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Local tour geocoder on 127.0.0.1:{args.port}; {len(index)} indexed queries; no outbound requests", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
