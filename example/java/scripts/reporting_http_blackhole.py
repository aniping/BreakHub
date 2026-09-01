from __future__ import annotations

import argparse
import ipaddress
import json
import socket
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Tuple, cast


def event(**values: object) -> None:
    values.setdefault("at", datetime.now(timezone.utc).isoformat())
    print(json.dumps(values, separators=(",", ":")), flush=True)


def read_request(client: socket.socket) -> Tuple[str, str, Dict[str, object], Any]:
    client.settimeout(5)
    stream = client.makefile("rwb", buffering=0)
    raw = bytearray()
    while b"\r\n\r\n" not in raw:
        chunk = stream.read(4096)
        if not chunk:
            raise ValueError("connection closed before request headers were complete")
        raw.extend(chunk)
        if len(raw) > 65536:
            raise ValueError("request headers exceed 64 KiB")
    head, body = bytes(raw).split(b"\r\n\r\n", 1)
    lines = head.decode("ascii").split("\r\n")
    request_line = lines[0].split(" ")
    if len(request_line) != 3:
        raise ValueError("invalid HTTP request line")
    lengths = []
    for line in lines[1:]:
        name, separator, value = line.partition(":")
        if not separator or not name:
            raise ValueError("invalid HTTP header")
        if name.strip().lower() == "content-length":
            lengths.append(int(value.strip()))
    if len(lengths) != 1 or lengths[0] < 0:
        raise ValueError("one valid Content-Length header is required")
    while len(body) < lengths[0]:
        chunk = stream.read(min(4096, lengths[0] - len(body)))
        if not chunk:
            raise ValueError("connection closed before request body was complete")
        body += chunk
        if len(head) + len(body) > 1048576:
            raise ValueError("request exceeds 1 MiB")
    payload_value = json.loads(body[: lengths[0]].decode("utf-8"))
    if not isinstance(payload_value, dict) or not all(
        isinstance(key, str) for key in payload_value
    ):
        raise ValueError("request body must be a JSON object")
    payload = cast(Dict[str, object], payload_value)
    return request_line[0], request_line[1], payload, stream


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-BindAddress", "--bind-address", default="127.0.0.1")
    parser.add_argument("-Port", "--port", type=int, default=18622)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("Port must be between 1 and 65535")
    address = ipaddress.ip_address(args.bind_address)
    if not address.is_loopback:
        parser.error("BindAddress must be a loopback address")

    listener = socket.socket(socket.AF_INET6 if address.version == 6 else socket.AF_INET)
    held = []
    try:
        listener.bind((str(address), args.port))
        listener.listen()
        event(event="listening", address=str(address), port=args.port)
        create_client, _ = listener.accept()
        try:
            method, path, payload, stream = read_request(create_client)
            valid = method == "POST" and path == "/api/demo/debugger/enabled" and set(payload) == {"enabled"} and payload["enabled"] is True
            if not valid:
                raise ValueError("first request is not a valid Reporting Lease create")
            lease_id = uuid.uuid4().hex
            body = json.dumps(
                {
                    "success": True,
                    "result": "created",
                    "changed": True,
                    "enabled": True,
                    "lease_timeout_seconds": 30,
                    "reporting_status": "healthy",
                    "lease_id": lease_id,
                },
                separators=(",", ":"),
            ).encode("utf-8")
            head = f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode("ascii")
            stream.write(head + body)
            event(event="create_ack")
        finally:
            create_client.close()

        sequence = 0
        while True:
            client, _ = listener.accept()
            sequence += 1
            try:
                method, path, payload, stream = read_request(client)
                base_valid = (
                    method == "POST"
                    and path == "/api/demo/debugger/enabled"
                    and set(payload) == {"enabled", "lease_id"}
                    and isinstance(payload["enabled"], bool)
                    and payload["lease_id"] == lease_id
                )
                kind = "renew" if base_valid and payload["enabled"] else "stop" if base_valid else "unknown"
                valid = base_valid
                client.settimeout(None)
                held.append((client, stream))
            except (OSError, ValueError, json.JSONDecodeError):
                kind = "unknown"
                valid = False
                client.close()
            event(event="blackhole_request", sequence=sequence, request_kind=kind, contract_valid=valid)
    except KeyboardInterrupt:
        return 0
    finally:
        for client, stream in held:
            stream.close()
            client.close()
        listener.close()


if __name__ == "__main__":
    raise SystemExit(main())
