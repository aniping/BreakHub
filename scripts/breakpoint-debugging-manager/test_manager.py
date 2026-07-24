import contextlib
import io
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import manager
from manager import ManagerError, main, parse_jsonc


class ParseJsoncTest(unittest.TestCase):
    def test_accepts_comments_and_trailing_commas_without_changing_strings(self) -> None:
        content = r'''
        {
          // Existing OpenCode configuration.
          "$schema": "https://opencode.ai/config.json",
          "url": "https://example.test/a//b",
          "marker": "/* keep this text */",
          "nested": {
            "enabled": true,
          },
        }
        '''

        self.assertEqual(
            parse_jsonc(content),
            {
                "$schema": "https://opencode.ai/config.json",
                "url": "https://example.test/a//b",
                "marker": "/* keep this text */",
                "nested": {"enabled": True},
            },
        )


class ResourceBusyTest(unittest.TestCase):
    def test_replaceability_probe_retries_then_succeeds(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / "breakhub-mcp.exe"
            executable.write_bytes(b"test")
            with (
                patch.object(
                    manager,
                    "_windows_file_is_replaceable",
                    side_effect=[False, False, True],
                ) as probe,
                patch.object(manager.time, "sleep") as sleep,
            ):
                manager._ensure_files_replaceable([executable])

            self.assertEqual(probe.call_count, 3)
            self.assertEqual(sleep.call_count, 2)

    def test_replaceability_probe_returns_resource_busy_without_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / "breakhub-mcp.exe"
            executable.write_bytes(b"test")
            with (
                patch.object(
                    manager,
                    "_windows_file_is_replaceable",
                    return_value=False,
                ),
                patch.object(manager.time, "sleep"),
            ):
                with self.assertRaisesRegex(ManagerError, "RESOURCE_BUSY"):
                    manager._ensure_files_replaceable([executable])

            self.assertEqual(executable.read_bytes(), b"test")


class TargetsCommandTest(unittest.TestCase):
    def setUp(self) -> None:
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                if self.path != "/api/v1/equipment":
                    self.send_error(404)
                    return
                if self.headers.get("Authorization") != "Bearer access-secret":
                    self.send_error(401)
                    return
                payload = json.dumps(
                    {
                        "equipment_id": "equipment-01",
                        "display_name": "Authoritative Lab",
                    }
                ).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_args: object) -> None:
                return

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://localhost:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_list_refreshes_authoritative_identity_without_exposing_connection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config_path = Path(temporary) / "breakhub_targets.json"
            config_path.write_text(
                json.dumps(
                    {
                        "version": 2,
                        "connections": [
                            {
                                "url": self.url,
                                "access_token": "access-secret",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            output = io.StringIO()

            with contextlib.redirect_stdout(output):
                exit_code = main(["targets", "list", "--config", str(config_path)])

            self.assertEqual(exit_code, 0)
            listed = json.loads(output.getvalue())
            self.assertEqual(len(listed), 1)
            self.assertTrue(listed[0]["connection_id"].startswith("connection-"))
            self.assertEqual(listed[0]["equipment_id"], "equipment-01")
            self.assertEqual(listed[0]["display_name"], "Authoritative Lab")
            self.assertEqual(listed[0]["status"], "available")
            serialized = output.getvalue()
            self.assertNotIn(self.url, serialized)
            self.assertNotIn("access-secret", serialized)

    def test_upsert_accepts_host_port_only_and_remove_requires_confirmation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config_path = Path(temporary) / "breakhub_targets.json"
            config_path.write_text(
                '{"version": 2, "connections": []}\n', encoding="utf-8"
            )
            output = io.StringIO()

            with contextlib.redirect_stdout(output):
                exit_code = main(
                    [
                        "targets",
                        "upsert",
                        "--config",
                        str(config_path),
                        "--url",
                        self.url.removeprefix("http://"),
                        "--access-token",
                        "access-secret",
                    ]
                )

            self.assertEqual(exit_code, 0)
            result = json.loads(output.getvalue())
            self.assertEqual(result["equipment_id"], "equipment-01")
            self.assertEqual(result["display_name"], "Authoritative Lab")
            self.assertNotIn(self.url, output.getvalue())
            self.assertNotIn("access-secret", output.getvalue())
            registry = json.loads(config_path.read_text(encoding="utf-8"))
            self.assertEqual(
                registry,
                {
                    "version": 2,
                    "connections": [
                        {"url": self.url, "access_token": "access-secret"}
                    ],
                },
            )
            connection_id = result["connection_id"]

            duplicate_output = io.StringIO()
            equivalent_url = self.url.replace(
                "http://localhost", "HTTP://LOCALHOST"
            ) + "/"
            with contextlib.redirect_stdout(duplicate_output):
                self.assertEqual(
                    main(
                        [
                            "targets",
                            "upsert",
                            "--config",
                            str(config_path),
                            "--url",
                            equivalent_url,
                            "--access-token",
                            "access-secret",
                        ]
                    ),
                    0,
                )
            duplicate_result = json.loads(duplicate_output.getvalue())
            self.assertEqual(duplicate_result["connection_id"], connection_id)
            self.assertEqual(
                len(
                    json.loads(config_path.read_text(encoding="utf-8"))[
                        "connections"
                    ]
                ),
                1,
            )

            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(
                    main(
                        [
                            "targets",
                            "remove",
                            "--config",
                            str(config_path),
                            "--connection-id",
                            connection_id,
                        ]
                    ),
                    1,
                )
            self.assertEqual(
                len(json.loads(config_path.read_text(encoding="utf-8"))["connections"]),
                1,
            )
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(
                    main(
                        [
                            "targets",
                            "remove",
                            "--config",
                            str(config_path),
                            "--connection-id",
                            connection_id,
                            "--yes",
                        ]
                    ),
                    0,
                )
            self.assertEqual(
                json.loads(config_path.read_text(encoding="utf-8"))["connections"], []
            )


if __name__ == "__main__":
    unittest.main()
