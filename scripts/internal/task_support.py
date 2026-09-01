from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Iterable, Mapping, Optional, Sequence


INTERNAL_ROOT = Path(__file__).resolve().parent
SCRIPTS_ROOT = INTERNAL_ROOT.parent
REPO_ROOT = SCRIPTS_ROOT.parent
BUILD_ROOT = REPO_ROOT / "build"
DIST_ROOT = REPO_ROOT / "dist"


class TaskError(RuntimeError):
    pass


def _command_for_windows(command: Sequence[object]) -> list[str]:
    rendered = [str(part) for part in command]
    executable = shutil.which(rendered[0])
    if executable:
        rendered[0] = executable
    return rendered


def run(
    command: Sequence[object],
    *,
    cwd: Optional[Path] = None,
    env: Optional[Mapping[str, str]] = None,
    capture: bool = False,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    process_env = os.environ.copy()
    if env:
        process_env.update(env)
    completed = subprocess.run(
        _command_for_windows(command),
        cwd=cwd,
        env=process_env,
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )
    if check and completed.returncode:
        output = (completed.stdout or "").strip()
        detail = f"\n{output}" if output else ""
        raise TaskError(f"Command failed with exit code {completed.returncode}: {command[0]}{detail}")
    return completed


def safe_child(path: Path, parent: Path, description: str) -> Path:
    resolved = path.resolve()
    resolved_parent = parent.resolve()
    try:
        common = Path(os.path.commonpath((str(resolved), str(resolved_parent))))
    except ValueError as exc:
        raise TaskError(f"{description} is outside {resolved_parent}: {resolved}") from exc
    if os.path.normcase(str(common)) != os.path.normcase(str(resolved_parent)):
        raise TaskError(f"{description} is outside {resolved_parent}: {resolved}")
    return resolved


def reset_directory(path: Path, parent: Path, description: str) -> Path:
    resolved = safe_child(path, parent, description)
    if resolved == parent.resolve():
        raise TaskError(f"Refusing to replace the {description} root: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True)
    return resolved


def single_file(directory: Path, pattern: str) -> Path:
    files = sorted(path for path in directory.glob(pattern) if path.is_file())
    if len(files) != 1:
        raise TaskError(
            f"Expected exactly one {pattern} under {directory}, found {len(files)}."
        )
    return files[0]


def remove_matching(directory: Path, patterns: Iterable[str]) -> None:
    if not directory.exists():
        return
    for pattern in patterns:
        for path in directory.glob(pattern):
            if path.is_file():
                path.unlink()


def python_environment(python: str) -> dict[str, str]:
    completed = run(
        [python, "-c", "import sys; print(sys.prefix)"],
        capture=True,
    )
    prefix = Path((completed.stdout or "").strip())
    env: dict[str, str] = {}
    library_bin = prefix / "Library" / "bin"
    if library_bin.is_dir():
        env["PATH"] = f"{library_bin}{os.pathsep}{os.environ.get('PATH', '')}"
    return env


def build_mcp_executable(python: str, output_directory: str = "") -> Path:
    project_root = REPO_ROOT / "bp-mcp"
    build_path = project_root / "build" / "pyinstaller"
    output = Path(output_directory).resolve() if output_directory else project_root / "dist"
    output.mkdir(parents=True, exist_ok=True)
    run(
        [
            python,
            "-m",
            "PyInstaller",
            "-F",
            "--clean",
            "--noconfirm",
            "--name",
            "breakhub-mcp",
            "--copy-metadata",
            "fastmcp",
            "--exclude-module",
            "websockets",
            "--paths",
            project_root / "src",
            "--distpath",
            output,
            "--workpath",
            build_path / "work",
            "--specpath",
            build_path,
            project_root / "src" / "bp_mcp" / "frozen_stdio_server.py",
        ],
        env=python_environment(python),
    )
    executable = output / "breakhub-mcp.exe"
    if not executable.is_file():
        raise TaskError(f"MCP executable was not created: {executable}")
    print(f"Built {executable}")
    return executable


def build_manager_executable(python: str, output_directory: str = "") -> Path:
    source_root = SCRIPTS_ROOT / "breakpoint-debugging-manager"
    build_path = BUILD_ROOT / "breakpoint-debugging-manager"
    output = Path(output_directory).resolve() if output_directory else build_path / "dist"
    output.mkdir(parents=True, exist_ok=True)
    run(
        [
            python,
            "-m",
            "PyInstaller",
            "-F",
            "--clean",
            "--noconfirm",
            "--name",
            "breakpoint-debugging-manager",
            "--distpath",
            output,
            "--workpath",
            build_path / "work",
            "--specpath",
            build_path,
            source_root / "manager.py",
        ],
        env=python_environment(python),
    )
    executable = output / "breakpoint-debugging-manager.exe"
    if not executable.is_file():
        raise TaskError(f"Manager executable was not created: {executable}")
    print(f"Built {executable}")
    return executable
