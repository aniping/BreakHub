import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import manager
from manager import ManagerError, parse_jsonc


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


if __name__ == "__main__":
    unittest.main()
