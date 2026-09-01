from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import zipfile
from pathlib import Path
from typing import Dict

from task_support import (
    BUILD_ROOT,
    DIST_ROOT,
    REPO_ROOT,
    SCRIPTS_ROOT,
    TaskError,
    build_manager_executable,
    build_mcp_executable,
    remove_matching,
    reset_directory,
    run,
    safe_child,
    single_file,
)


SKILL_NAME = "breakpoint-debugging"
SKILL_SOURCE = REPO_ROOT / "skills" / SKILL_NAME
MCP_PROJECT = REPO_ROOT / "bp-mcp"
MCP_EXECUTABLE = MCP_PROJECT / "dist" / "breakhub-mcp.exe"
MANAGER_EXECUTABLE = (
    BUILD_ROOT / "breakpoint-debugging-manager" / "dist" / "breakpoint-debugging-manager.exe"
)


def _write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def _zip_tree(source: Path, destination: Path, *, include_root: bool) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        destination.unlink()
    relative_root = source.parent if include_root else source
    with zipfile.ZipFile(
        destination,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(relative_root).as_posix())


def _ensure_mcp(python: str, skip_build: bool) -> None:
    if skip_build:
        if not MCP_EXECUTABLE.is_file():
            raise TaskError(f"MCP executable does not exist: {MCP_EXECUTABLE}")
        return
    build_mcp_executable(python)


def package_breakpoint_debugging(
    *,
    python: str = "python",
    output_path: str = "",
    skip_mcp_build: bool = False,
    skip_manager_build: bool = False,
) -> Path:
    output = (
        Path(output_path).resolve()
        if output_path
        else DIST_ROOT / SKILL_NAME / f"{SKILL_NAME}.zip"
    )
    _ensure_mcp(python, skip_mcp_build)
    if skip_manager_build:
        if not MANAGER_EXECUTABLE.is_file():
            raise TaskError(f"Manager executable does not exist: {MANAGER_EXECUTABLE}")
    else:
        build_manager_executable(python)

    stage_root = reset_directory(
        BUILD_ROOT / "breakpoint-debugging-package",
        BUILD_ROOT,
        "Skill staging directory",
    )
    staged_skill = stage_root / SKILL_NAME
    shutil.copytree(SKILL_SOURCE, staged_skill)
    staged_mcp = staged_skill / "scripts" / "mcp"
    staged_mcp.mkdir(parents=True)
    shutil.copy2(MCP_EXECUTABLE, staged_mcp)
    shutil.copy2(MCP_PROJECT / "breakhub_targets.example.json", staged_mcp)

    candidates = []
    codex_home = os.environ.get("CODEX_HOME")
    if codex_home:
        candidates.append(
            Path(codex_home) / "skills" / ".system" / "skill-creator" / "scripts" / "quick_validate.py"
        )
    user_profile = os.environ.get("USERPROFILE")
    if user_profile:
        candidates.append(
            Path(user_profile)
            / ".codex"
            / "skills"
            / ".system"
            / "skill-creator"
            / "scripts"
            / "quick_validate.py"
        )
    validator = next((path for path in candidates if path.is_file()), None)
    if validator:
        run([python, "-X", "utf8", validator, staged_skill])
    else:
        print("WARNING: skill-creator quick_validate.py was not found; skipping validation.")

    output.parent.mkdir(parents=True, exist_ok=True)
    legacy_installer = output.parent / "install-breakpoint-debugging.ps1"
    if legacy_installer.is_file():
        legacy_installer.unlink()
    _zip_tree(staged_skill, output, include_root=True)
    shutil.copy2(MANAGER_EXECUTABLE, output.parent / MANAGER_EXECUTABLE.name)
    shutil.copy2(
        SCRIPTS_ROOT / "release" / "breakpoint-debugging" / "README.md",
        output.parent / "README.md",
    )
    print(f"Packaged {output}")
    print(f"Manager: {output.parent / MANAGER_EXECUTABLE.name}")
    return output


def package_ateagent(
    *,
    python: str = "python",
    version: str = "0.1.0",
    output_path: str = "",
    skip_mcp_build: bool = False,
) -> Path:
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", version):
        raise TaskError(f"Invalid integration version: {version}")
    output = (
        Path(output_path).resolve()
        if output_path
        else DIST_ROOT
        / SKILL_NAME
        / f"breakpoint-debugging-ateagent-{version}.zip"
    )
    _ensure_mcp(python, skip_mcp_build)
    stage_root = reset_directory(
        BUILD_ROOT / "breakpoint-debugging-ateagent-package",
        BUILD_ROOT,
        "AteAgent staging directory",
    )
    staged_skill = stage_root / "skill" / SKILL_NAME
    shutil.copytree(SKILL_SOURCE, staged_skill)
    staged_runtime = stage_root / "runtime"
    staged_win_runtime = staged_runtime / "win-x64"
    staged_win_runtime.mkdir(parents=True)
    shutil.copy2(MCP_EXECUTABLE, staged_win_runtime)
    shutil.copy2(MCP_PROJECT / "breakhub_targets.example.json", staged_runtime)

    _write_json(
        stage_root / "ateagent-integration.json",
        {
            "schemaVersion": 1,
            "id": "breakhub",
            "version": version,
            "displayName": "BreakHub 断点调试",
            "platform": "win32",
            "arch": "x64",
            "skill": {"name": SKILL_NAME, "path": f"skill/{SKILL_NAME}"},
            "mcp": {
                "serverName": "microbreakpoint",
                "executable": "runtime/win-x64/breakhub-mcp.exe",
            },
        },
    )
    files: Dict[str, str] = {}
    for path in sorted(stage_root.rglob("*")):
        if path.is_file() and path.name != "SHA256SUMS.json":
            relative = path.relative_to(stage_root).as_posix()
            files[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    _write_json(stage_root / "SHA256SUMS.json", {"schemaVersion": 1, "files": files})
    _zip_tree(stage_root, output, include_root=False)
    print(f"Packaged AteAgent integration: {output}")
    return output


def test_ateagent_package(package_path: str = "", version: str = "0.1.0") -> None:
    package = (
        Path(package_path).resolve()
        if package_path
        else DIST_ROOT / SKILL_NAME / f"breakpoint-debugging-ateagent-{version}.zip"
    )
    if not package.is_file():
        raise TaskError(f"AteAgent integration package is missing: {package}")
    required = {
        "ateagent-integration.json",
        "SHA256SUMS.json",
        f"skill/{SKILL_NAME}/SKILL.md",
        f"skill/{SKILL_NAME}/references/tool-reference.md",
        "runtime/win-x64/breakhub-mcp.exe",
        "runtime/breakhub_targets.example.json",
    }
    with zipfile.ZipFile(package) as archive:
        names = {name.replace("\\", "/") for name in archive.namelist() if not name.endswith("/")}
        missing = required - names
        if missing:
            raise TaskError(f"AteAgent package entries are missing: {sorted(missing)}")
        powershell_entries = sorted(name for name in names if name.lower().endswith(".ps1"))
        if powershell_entries:
            raise TaskError(f"AteAgent package contains PowerShell scripts: {powershell_entries}")
        for name in names:
            allowed = (
                name in {
                    "ateagent-integration.json",
                    "SHA256SUMS.json",
                    "runtime/breakhub_targets.example.json",
                    "runtime/win-x64/breakhub-mcp.exe",
                }
                or name.startswith(f"skill/{SKILL_NAME}/")
            )
            if not allowed:
                raise TaskError(f"Unexpected AteAgent package entry: {name}")
        manifest = json.loads(archive.read("ateagent-integration.json"))
        expected = {
            "schemaVersion": 1,
            "id": "breakhub",
            "version": version,
            "platform": "win32",
            "arch": "x64",
        }
        if any(manifest.get(key) != value for key, value in expected.items()):
            raise TaskError("AteAgent integration manifest does not match the public contract.")
        if manifest.get("skill", {}).get("name") != SKILL_NAME:
            raise TaskError("AteAgent manifest has the wrong Skill name.")
        if manifest.get("mcp", {}).get("serverName") != "microbreakpoint":
            raise TaskError("AteAgent manifest has the wrong MCP server name.")
        if "requiredTools" in manifest.get("mcp", {}):
            raise TaskError("AteAgent manifest must rely on MCP tool discovery.")
        checksums = json.loads(archive.read("SHA256SUMS.json"))
        for name, expected_hash in checksums["files"].items():
            if name not in names:
                raise TaskError(f"Checksum references a missing entry: {name}")
            actual = hashlib.sha256(archive.read(name)).hexdigest()
            if actual != expected_hash:
                raise TaskError(f"Checksum mismatch for {name}")
    print("AteAgent integration package contract: passed")


def _version_from_mcp_project() -> str:
    project = (MCP_PROJECT / "pyproject.toml").read_text(encoding="utf-8")
    match = re.search(r'^version\s*=\s*"([^"]+)"\s*$', project, re.MULTILINE)
    if not match:
        raise TaskError("Could not read the AteAgent version from bp-mcp/pyproject.toml.")
    return match.group(1)


def _copy_maven_artifact(module: Path, destination: Path) -> Path:
    candidates = [
        path
        for path in (module / "target").glob("*.jar")
        if not re.search(r"-(sources|javadoc|tests)\.jar$", path.name)
    ]
    if len(candidates) != 1:
        raise TaskError(
            f"Expected exactly one release JAR under {module / 'target'}, found {len(candidates)}."
        )
    artifact = candidates[0]
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(artifact, destination / artifact.name)
    return artifact


def clear_release_artifacts() -> None:
    safe_child(DIST_ROOT, REPO_ROOT, "release directory")
    DIST_ROOT.mkdir(parents=True, exist_ok=True)
    remove_matching(
        DIST_ROOT,
        (
            "breakhub*.jar",
            "bp-probe*.jar",
            "bp-skill.zip",
            "install-bp-skill.ps1",
            "breakpoint-debugging.zip",
            "install-breakpoint-debugging.ps1",
            "breakpoint-debugging-manager.exe",
            "instrument-demo*.jar",
        ),
    )
    categories = {
        DIST_ROOT / "hub": (
            "breakhub*.jar",
            "BreakHub-Setup-*.exe",
            "application.yml",
            "start.ps1",
            "start.cmd",
        ),
        DIST_ROOT / "java-probe": ("bp-probe*.jar", "README.md"),
        DIST_ROOT / SKILL_NAME: ("*.zip", "*.exe", "*.ps1", "README.md"),
        DIST_ROOT / "java-demo": ("start.ps1",),
    }
    for directory, patterns in categories.items():
        safe_child(directory, DIST_ROOT, "release category")
        directory.mkdir(parents=True, exist_ok=True)
        remove_matching(directory, patterns)


def package_repository(python: str, build_repository) -> Path:
    build_repository(python)
    clear_release_artifacts()
    hub_output = DIST_ROOT / "hub"
    probe_output = DIST_ROOT / "java-probe"
    skill_output = DIST_ROOT / SKILL_NAME
    _copy_maven_artifact(REPO_ROOT / "bp-hub", hub_output)
    _copy_maven_artifact(REPO_ROOT / "bp-probe" / "java", probe_output)
    shutil.copy2(SCRIPTS_ROOT / "release" / "hub" / "application.yml", hub_output)
    shutil.copy2(SCRIPTS_ROOT / "release" / "hub" / "start.cmd", hub_output)
    shutil.copy2(SCRIPTS_ROOT / "release" / "java-probe" / "README.md", probe_output)
    version = _version_from_mcp_project()
    package_breakpoint_debugging(
        python=python,
        output_path=str(skill_output / "breakpoint-debugging.zip"),
        skip_mcp_build=True,
    )
    ateagent = package_ateagent(
        python=python,
        version=version,
        output_path=str(skill_output / f"breakpoint-debugging-ateagent-{version}.zip"),
        skip_mcp_build=True,
    )
    test_release_layout(str(DIST_ROOT))
    test_ateagent_package(str(ateagent), version)
    print(f"Release artifacts: {DIST_ROOT}")
    return DIST_ROOT


def test_release_layout(dist_path: str = "") -> None:
    dist = Path(dist_path).resolve() if dist_path else DIST_ROOT
    hub = dist / "hub"
    probe = dist / "java-probe"
    skill = dist / SKILL_NAME
    hub_jar = single_file(hub, "breakhub*.jar")
    probe_jar = single_file(probe, "bp-probe*.jar")
    skill_zip = single_file(skill, "breakpoint-debugging.zip")
    manager = single_file(skill, "breakpoint-debugging-manager.exe")
    required = [
        hub / "application.yml",
        hub / "start.cmd",
        probe / "README.md",
        skill / "README.md",
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise TaskError(f"Required release files are missing: {missing}")
    powershell_files = list(dist.rglob("*.ps1"))
    if powershell_files:
        raise TaskError(f"Release must not contain PowerShell scripts: {powershell_files}")
    probe_readme = (probe / "README.md").read_text(encoding="utf-8")
    required_probe_terms = ("mvn install:install-file", "<artifactId>bp-probe</artifactId>", "BreakHubProbe", "handleLease")
    if any(term not in probe_readme for term in required_probe_terms):
        raise TaskError("Java Probe manual is missing Maven installation instructions.")
    if any(term in probe_readme for term in ("ReportingLeaseManager", "DebugInvoker", "DebuggerSettings")):
        raise TaskError("Java Probe manual references a retired API.")
    config = (hub / "application.yml").read_text(encoding="utf-8")
    demo_config = (REPO_ROOT / "example" / "java" / "src" / "main" / "resources" / "application.yml").read_text(encoding="utf-8")
    token_pattern = re.compile(r"^\s*business-client-token:\s*(\S+)\s*$", re.MULTILINE)
    hub_token = token_pattern.search(config)
    demo_token = token_pattern.search(demo_config)
    if not hub_token or not demo_token or hub_token.group(1) != demo_token.group(1):
        raise TaskError("Hub and Java Demo business-client-token values do not match.")
    if not re.search(r"^\s*address:\s*127\.0\.0\.1\s*$", config, re.MULTILINE) or "请替换" in config:
        raise TaskError("Packaged Hub configuration is not ready for local integration.")
    if manager.stat().st_size == 0:
        raise TaskError("Breakpoint Debugging manager executable is empty.")
    with zipfile.ZipFile(skill_zip) as archive:
        names = [name.replace("\\", "/") for name in archive.namelist()]
    top_levels = sorted({name.split("/", 1)[0] for name in names if name})
    if top_levels != [SKILL_NAME]:
        raise TaskError(f"Unexpected Skill ZIP top-level entries: {top_levels}")
    powershell_entries = sorted(name for name in names if name.lower().endswith(".ps1"))
    if powershell_entries:
        raise TaskError(f"Skill ZIP contains PowerShell scripts: {powershell_entries}")
    forbidden = {f"{SKILL_NAME}/scripts/{name}" for name in ("install.ps1", "uninstall.ps1", "manage-targets.ps1")}
    if forbidden.intersection(names):
        raise TaskError("Skill ZIP contains a forbidden lifecycle script.")
    root_files = [path.name for path in dist.iterdir() if path.is_file()]
    if root_files:
        raise TaskError(f"Release files must be categorized: {root_files}")
    print(f"Release layout validation: passed ({hub_jar.name}, {probe_jar.name})")
