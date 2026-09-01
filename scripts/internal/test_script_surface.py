from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_ROOT = REPO_ROOT / "scripts"
ENTRYPOINTS = {
    "build.cmd": "build",
    "package.cmd": "package",
    "package-java-demo.cmd": "package-java-demo",
    "test.cmd": "test",
}


class ScriptSurfaceTest(unittest.TestCase):
    def test_repository_tracks_no_powershell_scripts(self) -> None:
        completed = subprocess.run(
            ["git", "ls-files", "--", "*.ps1"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        tracked = [line for line in completed.stdout.splitlines() if line]
        self.assertEqual([], tracked)

    def test_scripts_root_exposes_only_four_command_files(self) -> None:
        actual = sorted(path.name for path in SCRIPTS_ROOT.iterdir() if path.is_file())
        self.assertEqual(
            ["build.cmd", "package-java-demo.cmd", "package.cmd", "test.cmd"],
            actual,
        )

    def test_command_files_target_python_tasks_and_preserve_exit_codes(self) -> None:
        for name, task in ENTRYPOINTS.items():
            content = (SCRIPTS_ROOT / name).read_text(encoding="utf-8")
            self.assertIn(r"internal\repo_tasks.py", content)
            self.assertIn(f'" {task} %*', content)
            self.assertIn("setlocal DisableDelayedExpansion", content)
            self.assertIn("endlocal & exit /b %_BREAKHUB_EXIT_CODE%", content)

    def test_command_file_handles_spaces_arguments_and_exit_code(self) -> None:
        (REPO_ROOT / "build").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="command surface ", dir=REPO_ROOT / "build") as root:
            smoke_root = Path(root)
            scripts = smoke_root / "scripts with spaces"
            internal = scripts / "internal"
            internal.mkdir(parents=True)
            shutil.copy2(SCRIPTS_ROOT / "build.cmd", scripts / "build.cmd")
            (internal / "repo_tasks.py").write_text(
                """import sys
expected = ["build", "-Python", "value with spaces", "alpha&beta"]
raise SystemExit(37 if sys.argv[1:] == expected else 41)
""",
                encoding="utf-8",
            )
            driver = smoke_root / "invoke.cmd"
            driver.write_text(
                f'@echo off\ncd /d "%SystemRoot%"\ncall "{scripts / "build.cmd"}" -Python "value with spaces" "alpha&beta"\nexit /b %errorlevel%\n',
                encoding="ascii",
            )
            completed = subprocess.run(
                [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(driver)],
                check=False,
            )
            self.assertEqual(37, completed.returncode)

    def test_release_launchers_are_command_files(self) -> None:
        self.assertTrue((SCRIPTS_ROOT / "release" / "hub" / "start.cmd").is_file())
        self.assertTrue((SCRIPTS_ROOT / "release" / "java-demo" / "start.cmd").is_file())


if __name__ == "__main__":
    unittest.main()
