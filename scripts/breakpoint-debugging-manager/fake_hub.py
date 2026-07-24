"""Test-only BreakHub identity endpoint for manager executable integration tests."""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer integration-test-token":
            self.send_json(401, {"code": "INVALID_TOKEN"})
            return
        if self.path == "/api/v1/equipment":
            self.send_json(
                200,
                {
                    "equipment_id": "equipment-test",
                    "display_name": "Integration Test",
                },
            )
            return
        self.send_json(404, {"code": "NOT_FOUND"})

    def send_json(self, status, body):
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_args):
        return


def main() -> None:
    ready_path = Path(sys.argv[1])
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    ready_path.write_text(
        f"http://127.0.0.1:{server.server_port}",
        encoding="utf-8",
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
