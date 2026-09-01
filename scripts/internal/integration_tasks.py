from __future__ import annotations

import json
import re
import shutil
import subprocess
import time
import uuid
from pathlib import Path
from typing import Optional

from task_support import BUILD_ROOT, REPO_ROOT, SCRIPTS_ROOT, TaskError, run, safe_child


def _read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def _captured(command, *, cwd: Optional[Path] = None, check: bool = True):
    return run(command, cwd=cwd, capture=True, check=check)


def test_breakpoint_debugging_install(
    *,
    package_path: str = "",
    manager_path: str = "",
    python: str = "python",
) -> None:
    package = (
        Path(package_path).resolve()
        if package_path
        else REPO_ROOT / "dist" / "breakpoint-debugging" / "breakpoint-debugging.zip"
    )
    manager = (
        Path(manager_path).resolve()
        if manager_path
        else REPO_ROOT
        / "dist"
        / "breakpoint-debugging"
        / "breakpoint-debugging-manager.exe"
    )
    if not package.is_file() or not manager.is_file():
        raise TaskError("Breakpoint Debugging package or manager is missing.")
    test_root = safe_child(
        BUILD_ROOT / f"breakpoint-debugging-install-test-{uuid.uuid4().hex}",
        BUILD_ROOT,
        "install integration test directory",
    )
    fake_hub: Optional[subprocess.Popen[bytes]] = None
    try:
        release_root = test_root / "release"
        (test_root / ".git").mkdir(parents=True)
        release_root.mkdir(parents=True)
        test_package = release_root / package.name
        test_manager = release_root / manager.name
        shutil.copy2(package, test_package)
        shutil.copy2(manager, test_manager)
        config_path = test_root / "opencode.jsonc"
        _write_text(
            config_path,
            """{
  // Existing user configuration must survive install and uninstall.
  "$schema": "https://opencode.ai/config.json",
  "theme": "legacy-test",
  "permission": {
    "bash": {
      "*https://example.test/a//b*": "ask",
    },
  },
}
""",
        )
        target_config = test_root / ".opencode" / "breakhub" / "breakhub_targets.json"
        _write_text(
            target_config,
            """{
  "version": 1,
  "targets": [
    {
      "equipment_id": "legacy-enabled",
      "display_name": "Must Not Be Persisted",
      "breakpoint_url": "http://127.0.0.1",
      "gateway_token": "legacy-token",
      "enabled": true
    },
    {
      "equipment_id": "legacy-disabled",
      "breakpoint_url": "http://127.0.0.1:2",
      "gateway_token": "disabled-token",
      "enabled": false
    }
  ]
}
""",
        )

        installed = _captured(
            [
                test_manager,
                "install",
                "--scope",
                "project",
                "--project-root",
                test_root,
                "--package",
                test_package,
            ],
            check=False,
        )
        install_output = installed.stdout or ""
        if installed.returncode or "MCP verification: microbreakpoint connected" not in install_output:
            raise TaskError(f"Manager install or MCP verification failed:\n{install_output}")

        migrated = _read_json(target_config)
        assert isinstance(migrated, dict)
        migrated_text = json.dumps(migrated, ensure_ascii=False, separators=(",", ":"))
        connections = migrated.get("connections", [])
        if (
            migrated.get("version") != 2
            or len(connections) != 1
            or connections[0].get("url") != "http://127.0.0.1:18621"
            or re.search(r"equipment_id|display_name|legacy-disabled|disabled-token", migrated_text)
        ):
            raise TaskError("Manager did not migrate the target registry to URL/token-only v2.")

        config = _read_json(config_path)
        assert isinstance(config, dict)
        if config.get("theme") != "legacy-test":
            raise TaskError("Manager did not preserve the existing theme.")
        bash_permission = config.get("permission", {}).get("bash", {})
        if bash_permission.get("*https://example.test/a//b*") != "ask":
            raise TaskError("Manager did not preserve unrelated OpenCode permissions.")
        if config.get("permission", {}).get("skill", {}).get("breakpoint-debugging") != "allow":
            raise TaskError("Manager did not allow the breakpoint-debugging Skill.")

        installed_skill = test_root / ".opencode" / "skills" / "breakpoint-debugging"
        metadata = (installed_skill / "SKILL.md").read_text(encoding="utf-8")
        if not re.search(r"^name: breakpoint-debugging\s*$", metadata, re.MULTILINE):
            raise TaskError("Installed Skill metadata has the wrong name.")
        for forbidden in ("install.ps1", "uninstall.ps1", "manage-targets.ps1"):
            if (installed_skill / "scripts" / forbidden).exists():
                raise TaskError(f"Installed Skill must not contain {forbidden}.")

        command_path = Path(config["mcp"]["microbreakpoint"]["command"][0])
        if not command_path.is_file():
            raise TaskError("OpenCode configuration does not point to the MCP executable.")
        persisted_manager = (
            test_root / ".opencode" / "breakhub" / "breakpoint-debugging-manager.exe"
        )
        if not persisted_manager.is_file():
            raise TaskError("Manager was not persisted outside the installed Skill.")

        status = _captured(["opencode", "mcp", "list"], cwd=test_root, check=False)
        plain_status = re.sub(r"\x1b\[[0-9;]*m", "", status.stdout or "")
        if not re.search(r"microbreakpoint.*connected", plain_status, re.DOTALL):
            raise TaskError(f"OpenCode did not connect to the installed MCP server:\n{plain_status}")

        ready_path = test_root / "fake-hub-url.txt"
        fake_hub_script = SCRIPTS_ROOT / "breakpoint-debugging-manager" / "fake_hub.py"
        creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        fake_hub = subprocess.Popen(
            [python, str(fake_hub_script), str(ready_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=creation_flags,
        )
        for _ in range(50):
            if ready_path.is_file():
                break
            time.sleep(0.1)
        if not ready_path.is_file():
            raise TaskError("Fake BreakHub did not become ready.")
        fake_hub_url = ready_path.read_text(encoding="utf-8").strip()

        connection_test = (
            SCRIPTS_ROOT / "breakpoint-debugging-manager" / "test_mcp_connection_exe.py"
        )
        bindings = test_root / ".opencode" / "breakhub" / "breakhub_bindings.json"
        connection = _captured(
            [
                python,
                connection_test,
                "--mcp",
                command_path,
                "--config",
                target_config,
                "--bindings",
                bindings,
                "--cwd",
                test_root,
                "--url",
                fake_hub_url,
            ],
            check=False,
        )
        connection_output = connection.stdout or ""
        if connection.returncode or "conversational connection management: passed" not in connection_output:
            raise TaskError(f"Packaged MCP connection management failed:\n{connection_output}")
        target = _read_json(target_config)
        assert isinstance(target, dict)
        if target.get("version") != 2:
            raise TaskError("MCP did not persist target registry version 2.")
        for item in target.get("connections", []):
            if set(item) != {"url", "access_token"}:
                raise TaskError("MCP persisted equipment identity in connection data.")

        with command_path.open("rb"):
            busy = _captured(
                [persisted_manager, "uninstall", "--scope", "project", "--project-root", test_root],
                check=False,
            )
        busy_output = busy.stdout or ""
        if busy.returncode == 0 or "RESOURCE_BUSY" not in busy_output:
            raise TaskError(f"Occupied MCP did not produce RESOURCE_BUSY:\n{busy_output}")
        busy_config = _read_json(config_path)
        assert isinstance(busy_config, dict)
        if not installed_skill.is_dir() or "microbreakpoint" not in busy_config.get("mcp", {}):
            raise TaskError("Busy uninstall changed registration before failing.")

        uninstalled = _captured(
            [persisted_manager, "uninstall", "--scope", "project", "--project-root", test_root],
            check=False,
        )
        if uninstalled.returncode:
            raise TaskError(f"Manager uninstall failed:\n{uninstalled.stdout or ''}")
        if installed_skill.exists():
            raise TaskError("Manager uninstall did not remove the installed Skill.")
        updated = _read_json(config_path)
        assert isinstance(updated, dict)
        if "microbreakpoint" in updated.get("mcp", {}):
            raise TaskError("Manager uninstall did not remove MCP registration.")
        if "breakpoint-debugging" in updated.get("permission", {}).get("skill", {}):
            raise TaskError("Manager uninstall did not remove the Skill permission.")
        if updated.get("theme") != "legacy-test" or updated.get("permission", {}).get("bash", {}).get("*https://example.test/a//b*") != "ask":
            raise TaskError("Manager uninstall did not preserve unrelated configuration.")
        if not persisted_manager.is_file():
            raise TaskError("Manager uninstall removed the retained BreakHub data directory.")
        print("Breakpoint Debugging manager install/uninstall integration test: passed")
    finally:
        if fake_hub and fake_hub.poll() is None:
            fake_hub.terminate()
            try:
                fake_hub.wait(timeout=5)
            except subprocess.TimeoutExpired:
                fake_hub.kill()
                fake_hub.wait(timeout=5)
        if test_root.exists():
            for attempt in range(5):
                try:
                    shutil.rmtree(test_root)
                    break
                except OSError:
                    if attempt == 4:
                        raise
                    time.sleep(0.2)
