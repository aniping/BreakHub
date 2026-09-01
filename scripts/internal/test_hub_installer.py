from __future__ import annotations

import stat
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
INTERNAL_ROOT = REPO_ROOT / "scripts" / "internal"
if str(INTERNAL_ROOT) not in sys.path:
    sys.path.insert(0, str(INTERNAL_ROOT))

from hub_installer_tasks import RUNTIME_MODULES, _find_jdk  # noqa: E402
from task_support import TaskError, reset_directory  # noqa: E402


class HubInstallerContractTest(unittest.TestCase):
    def test_module_command_file_exposes_the_installer_task(self) -> None:
        entrypoint = REPO_ROOT / "bp-hub" / "scripts" / "build-installer.cmd"
        content = entrypoint.read_text(encoding="utf-8")
        self.assertIn(r"scripts\internal\repo_tasks.py", content)
        self.assertIn("_BREAKHUB_TASK=package-hub-installer", content)
        self.assertIn(r"run-python-task.cmd", content)

    def test_nsis_installs_both_launchers_and_preserves_product_data(self) -> None:
        script = (REPO_ROOT / "bp-hub" / "installer" / "BreakHub.nsi").read_text(
            encoding="utf-8"
        )
        for required in (
            "RequestExecutionLevel user",
            r'$LOCALAPPDATA\Programs\BreakHub',
            "BreakHub-Start.exe",
            "BreakHub-Stop.exe",
            "CreateShortCut",
            "WriteUninstaller",
        ):
            self.assertIn(required, script)
        self.assertNotIn(r'RMDir /r "$LOCALAPPDATA\BreakHub"', script)
        self.assertNotIn('!insertmacro MUI_PAGE_DIRECTORY', script)
        self.assertGreaterEqual(
            script.count(r'StrCpy $INSTDIR "$LOCALAPPDATA\Programs\BreakHub"'), 3
        )
        self.assertIn(r'IfFileExists "$INSTDIR\*.*" upgrade_cleanup_failed', script)
        self.assertNotIn("breakhub-mcp", script.lower())

    def test_builder_rejects_a_non_java_17_jdk(self) -> None:
        with tempfile.TemporaryDirectory() as directory_value:
            directory = Path(directory_value)
            (directory / "bin").mkdir()
            (directory / "bin" / "jpackage.exe").touch()
            (directory / "bin" / "jlink.exe").touch()
            (directory / "release").write_text(
                'JAVA_VERSION="23.0.2"\n', encoding="utf-8"
            )

            with self.assertRaisesRegex(TaskError, "Java 17"):
                _find_jdk(str(directory))

    def test_installer_configuration_uses_a_runtime_home_placeholder(self) -> None:
        template = (
            REPO_ROOT / "bp-hub" / "installer" / "application.yml.template"
        ).read_text(encoding="utf-8")
        self.assertIn("@BREAKHUB_HOME@/data", template)
        self.assertIn("@BREAKHUB_HOME@/logs/breakhub.log", template)

    def test_build_directory_cleanup_handles_read_only_jpackage_files(self) -> None:
        (REPO_ROOT / "build").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=REPO_ROOT / "build") as parent_value:
            parent = Path(parent_value)
            target = parent / "app-image"
            target.mkdir()
            executable = target / "BreakHub-Start.exe"
            executable.write_bytes(b"launcher")
            executable.chmod(stat.S_IREAD)

            reset_directory(target, parent, "test app image")

            self.assertTrue(target.is_dir())
            self.assertEqual([], list(target.iterdir()))

    def test_runtime_covers_modules_reported_by_jdeps(self) -> None:
        required = {
            "java.base",
            "java.compiler",
            "java.desktop",
            "java.instrument",
            "java.management",
            "java.net.http",
            "java.prefs",
            "java.rmi",
            "java.scripting",
            "java.security.jgss",
            "java.sql.rowset",
            "jdk.jfr",
            "jdk.unsupported",
        }

        self.assertEqual(set(), required.difference(RUNTIME_MODULES))


if __name__ == "__main__":
    unittest.main()
