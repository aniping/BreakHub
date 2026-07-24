import argparse
import ctypes
import getpass
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence
from urllib.parse import urlparse, urlunparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


SKILL_NAME = "breakpoint-debugging"
MCP_SERVER_KEY = "microbreakpoint"
MANAGER_NAME = "breakpoint-debugging-manager.exe"
MANAGER_PERMISSION_PATTERN = "*breakpoint-debugging-manager.exe*"
MCP_EXE_RELATIVE = Path("scripts/mcp/breakhub-mcp.exe")
TARGET_EXAMPLE_RELATIVE = Path("scripts/mcp/breakhub_targets.example.json")
FORBIDDEN_SKILL_SCRIPTS = (
    Path("scripts/install.ps1"),
    Path("scripts/uninstall.ps1"),
    Path("scripts/manage-targets.ps1"),
)
TOOL_PERMISSIONS = {
    "microbreakpoint_*": "ask",
    "microbreakpoint_list_equipment": "allow",
    "microbreakpoint_find_interfaces": "allow",
    "microbreakpoint_get_interface": "allow",
    "microbreakpoint_find_breakpoints": "allow",
    "microbreakpoint_get_breakpoint": "allow",
    "microbreakpoint_find_interactions": "allow",
    "microbreakpoint_get_interaction": "allow",
    "microbreakpoint_delete_breakpoints": "deny",
    "microbreakpoint_continue_interactions": "deny",
}
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*m")


class ManagerError(RuntimeError):
    pass


RESOURCE_BUSY_ERRORS = {5, 32, 33}
RETRY_DELAYS = (0.0, 0.1, 0.3, 0.7)


def _resource_busy_message(paths: Iterable[Path]) -> str:
    listed = ", ".join(str(path) for path in paths)
    return (
        "RESOURCE_BUSY: Close OpenCode or the process using Breakpoint Debugging "
        f"and retry. Files could not be replaced: {listed}"
    )


def _windows_file_is_replaceable(path: Path) -> bool:
    if os.name != "nt" or not path.is_file():
        return True

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = (
        ctypes.c_wchar_p,
        ctypes.c_uint32,
        ctypes.c_uint32,
        ctypes.c_void_p,
        ctypes.c_uint32,
        ctypes.c_uint32,
        ctypes.c_void_p,
    )
    create_file.restype = ctypes.c_void_p
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = (ctypes.c_void_p,)
    close_handle.restype = ctypes.c_int

    delete_access = 0x00010000
    share_read_write_delete = 0x00000007
    open_existing = 3
    handle = create_file(
        str(path),
        delete_access,
        share_read_write_delete,
        None,
        open_existing,
        0,
        None,
    )
    invalid_handle = ctypes.c_void_p(-1).value
    if handle != invalid_handle:
        close_handle(handle)
        return True
    return ctypes.get_last_error() not in RESOURCE_BUSY_ERRORS


def _ensure_files_replaceable(paths: Iterable[Path]) -> None:
    candidates = sorted({path.resolve() for path in paths if path.is_file()})
    for delay in RETRY_DELAYS:
        if delay:
            time.sleep(delay)
        busy = [path for path in candidates if not _windows_file_is_replaceable(path)]
        if not busy:
            return
    raise ManagerError(_resource_busy_message(busy))


def _ensure_tree_replaceable(root: Path) -> None:
    if root.is_dir():
        _ensure_files_replaceable(path for path in root.rglob("*.exe"))


def _retry_path_operation(paths: Iterable[Path], operation: Callable[[], None]) -> None:
    last_error: OSError | None = None
    for delay in RETRY_DELAYS:
        if delay:
            time.sleep(delay)
        try:
            operation()
            return
        except OSError as error:
            if os.name != "nt" or error.winerror not in RESOURCE_BUSY_ERRORS:
                raise
            last_error = error
    raise ManagerError(_resource_busy_message(paths)) from last_error


def _rename_path(source: Path, destination: Path) -> None:
    _retry_path_operation([source, destination], lambda: source.rename(destination))


def _remove_file(path: Path, *, strict: bool) -> None:
    if not path.exists():
        return
    try:
        _retry_path_operation([path], path.unlink)
    except ManagerError:
        if strict:
            raise
        print(f"Warning: deferred cleanup remains at {path}", file=sys.stderr)


def _remove_tree(path: Path, strict: bool) -> None:
    if not path.exists():
        return
    last_error: OSError | None = None
    for delay in RETRY_DELAYS:
        if delay:
            time.sleep(delay)
        try:
            shutil.rmtree(path)
            return
        except OSError as error:
            last_error = error
    if strict:
        assert last_error is not None
        if os.name == "nt" and last_error.winerror in RESOURCE_BUSY_ERRORS:
            raise ManagerError(_resource_busy_message([path])) from last_error
        raise ManagerError(f"Cannot remove directory after retries: {path}") from last_error
    print(f"Warning: deferred cleanup remains at {path}", file=sys.stderr)


def _strip_jsonc_comments(content: str) -> str:
    result: list[str] = []
    index = 0
    in_string = False
    escaped = False

    while index < len(content):
        char = content[index]
        if in_string:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue

        if char == '"':
            in_string = True
            result.append(char)
            index += 1
            continue

        next_char = content[index + 1] if index + 1 < len(content) else ""
        if char == "/" and next_char == "/":
            index += 2
            while index < len(content) and content[index] not in "\r\n":
                index += 1
            continue
        if char == "/" and next_char == "*":
            index += 2
            while index + 1 < len(content):
                if content[index] == "*" and content[index + 1] == "/":
                    index += 2
                    break
                if content[index] in "\r\n":
                    result.append(content[index])
                index += 1
            else:
                raise ValueError("JSONC contains an unterminated block comment")
            continue

        result.append(char)
        index += 1

    return "".join(result)


def _strip_trailing_commas(content: str) -> str:
    result: list[str] = []
    index = 0
    in_string = False
    escaped = False

    while index < len(content):
        char = content[index]
        if in_string:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue

        if char == '"':
            in_string = True
            result.append(char)
            index += 1
            continue

        if char == ",":
            lookahead = index + 1
            while lookahead < len(content) and content[lookahead].isspace():
                lookahead += 1
            if lookahead < len(content) and content[lookahead] in "}]":
                index += 1
                continue

        result.append(char)
        index += 1

    return "".join(result)


def parse_jsonc(content: str) -> dict[str, Any]:
    normalized = _strip_trailing_commas(_strip_jsonc_comments(content))
    value = json.loads(normalized)
    if not isinstance(value, dict):
        raise ValueError("OpenCode config root must be an object")
    return value


def _read_config(path: Path) -> dict[str, Any]:
    if not path.exists() or not path.read_text(encoding="utf-8").strip():
        return {"$schema": "https://opencode.ai/config.json"}
    try:
        return parse_jsonc(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise ManagerError(f"OpenCode config is not valid JSON/JSONC: {path}") from error


def _write_config(path: Path, config: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(
            json.dumps(config, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _find_project_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if any(
            (candidate / marker).exists()
            for marker in (".git", "opencode.json", "opencode.jsonc")
        ):
            return candidate
    return current


def _normalize_scope(scope: str) -> str:
    normalized = scope.strip().lower()
    if normalized not in {"project", "global"}:
        raise ManagerError("Scope must be project or global")
    return normalized


def _resolve_paths(scope: str, project_root: Path) -> dict[str, Path]:
    normalized_scope = _normalize_scope(scope)
    resolved_project = project_root.resolve()
    if normalized_scope == "global":
        user_profile = os.environ.get("USERPROFILE")
        if not user_profile:
            raise ManagerError("USERPROFILE is required for a global installation")
        open_code_root = Path(user_profile).resolve() / ".config" / "opencode"
        config_path = open_code_root / "opencode.json"
    else:
        open_code_root = resolved_project / ".opencode"
        jsonc_path = resolved_project / "opencode.jsonc"
        json_path = resolved_project / "opencode.json"
        config_path = jsonc_path if jsonc_path.exists() or not json_path.exists() else json_path

    skills_root = open_code_root / "skills"
    destination = skills_root / SKILL_NAME
    if destination.name != SKILL_NAME or destination.parent != skills_root:
        raise ManagerError(
            f"Refusing to manage a path outside the expected OpenCode skills directory: {destination}"
        )
    data_root = open_code_root / "breakhub"
    return {
        "project_root": resolved_project,
        "open_code_root": open_code_root,
        "skills_root": skills_root,
        "destination": destination,
        "data_root": data_root,
        "target_config": data_root / "breakhub_targets.json",
        "binding_store": data_root / "breakhub_bindings.json",
        "manager": data_root / MANAGER_NAME,
        "config": config_path,
    }


def _manager_source() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve()
    return Path(__file__).resolve()


def _locate_package(explicit: str | None) -> Path:
    if explicit:
        candidate = Path(explicit).resolve()
    else:
        candidate = _manager_source().parent / f"{SKILL_NAME}.zip"
    if not candidate.is_file():
        raise ManagerError(
            f"Cannot find {SKILL_NAME}.zip. Keep it beside {MANAGER_NAME} or pass --package."
        )
    return candidate


def _validate_package(archive: zipfile.ZipFile) -> None:
    top_levels: set[str] = set()
    normalized_names: set[str] = set()
    for entry in archive.infolist():
        normalized = entry.filename.replace("\\", "/").strip("/")
        if not normalized:
            continue
        parts = normalized.split("/")
        if entry.filename.startswith(("/", "\\")) or ".." in parts:
            raise ManagerError(f"Package contains an unsafe path: {entry.filename}")
        mode = entry.external_attr >> 16
        if stat.S_ISLNK(mode):
            raise ManagerError(f"Package must not contain symbolic links: {entry.filename}")
        top_levels.add(parts[0])
        normalized_names.add(normalized)

    if top_levels != {SKILL_NAME}:
        raise ManagerError(
            "Package must contain exactly one top-level breakpoint-debugging directory"
        )
    required = {
        f"{SKILL_NAME}/SKILL.md",
        f"{SKILL_NAME}/{MCP_EXE_RELATIVE.as_posix()}",
        f"{SKILL_NAME}/{TARGET_EXAMPLE_RELATIVE.as_posix()}",
    }
    missing = sorted(required - normalized_names)
    if missing:
        raise ManagerError(f"Package is missing required files: {', '.join(missing)}")
    forbidden = [
        f"{SKILL_NAME}/{relative.as_posix()}"
        for relative in FORBIDDEN_SKILL_SCRIPTS
        if f"{SKILL_NAME}/{relative.as_posix()}" in normalized_names
    ]
    if forbidden:
        raise ManagerError(
            "Lifecycle and configuration scripts must not be embedded in the Skill: "
            + ", ".join(forbidden)
        )


def _extract_package(package: Path, destination: Path) -> Path:
    with zipfile.ZipFile(package) as archive:
        _validate_package(archive)
        archive.extractall(destination)
    return destination / SKILL_NAME


def _get_mapping(target: dict[str, Any], name: str) -> dict[str, Any]:
    value = target.get(name)
    if not isinstance(value, dict):
        value = {}
        target[name] = value
    return value


def _existing_string(target: Any, name: str, default: str) -> str:
    if isinstance(target, dict):
        value = target.get(name)
        if isinstance(value, str) and value.strip():
            return value
    return default


def _merge_install_config(
    config: dict[str, Any], paths: dict[str, Path]
) -> dict[str, Any]:
    mcp = _get_mapping(config, "mcp")
    existing_server = mcp.get(MCP_SERVER_KEY)
    existing_environment = (
        existing_server.get("environment") if isinstance(existing_server, dict) else None
    )
    default_thread_id = "opencode-" + hashlib.sha256(
        str(paths["project_root"]).lower().encode("utf-8")
    ).hexdigest()[:12]
    user_id = _existing_string(
        existing_environment, "MCP_GATEWAY_USER_ID", "opencode-local-user"
    )
    thread_id = _existing_string(
        existing_environment, "MCP_GATEWAY_THREAD_ID", default_thread_id
    )

    mcp[MCP_SERVER_KEY] = {
        "type": "local",
        "command": [(paths["destination"] / MCP_EXE_RELATIVE).as_posix()],
        "cwd": paths["project_root"].as_posix(),
        "enabled": True,
        "timeout": 15000,
        "environment": {
            "MCP_GATEWAY_TARGETS_PATH": paths["target_config"].as_posix(),
            "MCP_GATEWAY_BINDINGS_PATH": paths["binding_store"].as_posix(),
            "MCP_GATEWAY_USER_ID": user_id,
            "MCP_GATEWAY_THREAD_ID": thread_id,
        },
    }

    permission = _get_mapping(config, "permission")
    _get_mapping(permission, "skill")[SKILL_NAME] = "allow"
    _get_mapping(permission, "bash")[MANAGER_PERMISSION_PATTERN] = "ask"
    permission.update(TOOL_PERMISSIONS)
    return config


def _remove_install_config(config: dict[str, Any]) -> dict[str, Any]:
    mcp = config.get("mcp")
    if isinstance(mcp, dict):
        mcp.pop(MCP_SERVER_KEY, None)
    permission = config.get("permission")
    if isinstance(permission, dict):
        skill_permission = permission.get("skill")
        if isinstance(skill_permission, dict):
            skill_permission.pop(SKILL_NAME, None)
        bash_permission = permission.get("bash")
        if isinstance(bash_permission, dict):
            bash_permission.pop(MANAGER_PERMISSION_PATTERN, None)
        for name in TOOL_PERMISSIONS:
            permission.pop(name, None)
    return config


def _run_opencode(project_root: Path) -> subprocess.CompletedProcess[str] | None:
    opencode = shutil.which("opencode.cmd") or shutil.which("opencode.exe")
    if not opencode:
        opencode = shutil.which("opencode")
    if not opencode:
        return None
    if Path(opencode).suffix.lower() in {".cmd", ".bat"}:
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", opencode, "mcp", "list"]
    else:
        command = [opencode, "mcp", "list"]
    return subprocess.run(
        command,
        cwd=project_root,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )


def _verify_mcp_connection(project_root: Path) -> None:
    result = _run_opencode(project_root)
    if result is None:
        print("Warning: OpenCode CLI was not found; skipping MCP connection verification.")
        return
    output = ANSI_ESCAPE.sub("", result.stdout + "\n" + result.stderr)
    if result.returncode != 0 or not re.search(
        r"microbreakpoint.*connected", output, re.IGNORECASE | re.DOTALL
    ):
        raise ManagerError(
            f"OpenCode MCP connection verification failed: {output.strip()}"
        )
    print("MCP verification: microbreakpoint connected")


def install(scope: str, project_root: Path, package: Path) -> None:
    paths = _resolve_paths(scope, project_root)
    manager_source = _manager_source()
    replace_manager = manager_source != paths["manager"].resolve()
    config_path = paths["config"]
    config_existed = config_path.exists()
    original_config = config_path.read_bytes() if config_existed else None
    config = _merge_install_config(_read_config(config_path), paths)
    data_root_existed = paths["data_root"].exists()
    target_existed = paths["target_config"].exists()
    original_target_config = (
        paths["target_config"].read_bytes() if target_existed else None
    )
    manager_existed = paths["manager"].exists()
    backup: Path | None = None
    manager_backup: Path | None = None
    staging: Path | None = None
    destination_switched = False
    manager_change_started = False

    with tempfile.TemporaryDirectory(prefix=f"{SKILL_NAME}-") as temporary:
        extracted = _extract_package(package, Path(temporary))
        paths["skills_root"].mkdir(parents=True, exist_ok=True)
        staging = paths["skills_root"] / f".{SKILL_NAME}.install-{uuid.uuid4().hex}"
        shutil.copytree(extracted, staging)
        _ensure_tree_replaceable(paths["destination"])
        if replace_manager:
            _ensure_files_replaceable([paths["manager"]])
        try:
            if paths["destination"].exists():
                backup = paths["skills_root"] / (
                    f".{SKILL_NAME}.backup-{uuid.uuid4().hex}"
                )
                _rename_path(paths["destination"], backup)
            _rename_path(staging, paths["destination"])
            destination_switched = True
            staging = None

            paths["data_root"].mkdir(parents=True, exist_ok=True)
            if not target_existed:
                shutil.copy2(
                    paths["destination"] / TARGET_EXAMPLE_RELATIVE,
                    paths["target_config"],
                )
            else:
                _write_config(
                    paths["target_config"],
                    _read_connections(paths["target_config"]),
                )
            if replace_manager:
                if manager_existed:
                    manager_backup = paths["data_root"] / (
                        f".{MANAGER_NAME}.backup-{uuid.uuid4().hex}"
                    )
                    _rename_path(paths["manager"], manager_backup)
                manager_change_started = True
                shutil.copy2(manager_source, paths["manager"])
            _write_config(config_path, config)
            _verify_mcp_connection(paths["project_root"])
        except Exception:
            if destination_switched and paths["destination"].exists():
                _remove_tree(paths["destination"], strict=True)
            if backup is not None and backup.exists():
                _rename_path(backup, paths["destination"])
                backup = None
            if original_config is None:
                config_path.unlink(missing_ok=True)
            else:
                config_path.write_bytes(original_config)
            if original_target_config is None:
                paths["target_config"].unlink(missing_ok=True)
            else:
                paths["target_config"].write_bytes(original_target_config)
            if manager_change_started and paths["manager"].exists():
                _remove_file(paths["manager"], strict=True)
            if manager_backup is not None and manager_backup.exists():
                _rename_path(manager_backup, paths["manager"])
                manager_backup = None
            if not data_root_existed and paths["data_root"].exists():
                try:
                    paths["data_root"].rmdir()
                except OSError:
                    pass
            raise
        finally:
            if staging is not None and staging.exists():
                _remove_tree(staging, strict=False)

    if backup is not None and backup.exists():
        _remove_tree(backup, strict=False)
    if manager_backup is not None and manager_backup.exists():
        _remove_file(manager_backup, strict=False)
    print(f"Installed skill: {paths['destination']}")
    print(f"Management executable: {paths['manager']}")
    print(f"OpenCode MCP config: {config_path}")


def uninstall(
    scope: str, project_root: Path, remove_data: bool = False
) -> None:
    paths = _resolve_paths(scope, project_root)
    if remove_data:
        try:
            _manager_source().relative_to(paths["data_root"])
        except ValueError:
            pass
        else:
            raise ManagerError(
                "Run the release copy of the manager outside the BreakHub data directory when using --remove-data."
            )

    _ensure_tree_replaceable(paths["destination"])
    if remove_data:
        _ensure_tree_replaceable(paths["data_root"])

    config_existed = paths["config"].exists()
    original_config = paths["config"].read_bytes() if config_existed else None
    skill_removal: Path | None = None
    data_removal: Path | None = None
    try:
        if paths["destination"].exists():
            skill_removal = paths["skills_root"] / (
                f".{SKILL_NAME}.remove-{uuid.uuid4().hex}"
            )
            _rename_path(paths["destination"], skill_removal)
        if remove_data and paths["data_root"].exists():
            data_removal = paths["open_code_root"] / (
                f".breakhub.remove-{uuid.uuid4().hex}"
            )
            _rename_path(paths["data_root"], data_removal)
        if config_existed:
            config = _remove_install_config(_read_config(paths["config"]))
            _write_config(paths["config"], config)
    except Exception:
        if original_config is not None:
            paths["config"].write_bytes(original_config)
        if data_removal is not None and data_removal.exists():
            _rename_path(data_removal, paths["data_root"])
        if skill_removal is not None and skill_removal.exists():
            _rename_path(skill_removal, paths["destination"])
        raise

    if config_existed:
        print(f"Removed OpenCode MCP registration: {paths['config']}")
    if skill_removal is not None:
        _remove_tree(skill_removal, strict=False)
        print(f"Removed skill: {paths['destination']}")
    else:
        print(f"Skill is not installed: {paths['destination']}")
    if data_removal is not None:
        _remove_tree(data_removal, strict=False)
        print(f"Removed MCP data: {paths['data_root']}")


def _target_config_path(
    scope: str, project_root: Path, explicit: str | None
) -> Path:
    if explicit:
        return Path(explicit).resolve()
    return _resolve_paths(scope, project_root)["target_config"]


def _normalize_breakhub_url(value: str) -> str:
    normalized = value.strip()
    if "://" not in normalized:
        normalized = "http://" + normalized
    parsed = urlparse(normalized)
    hostname = parsed.hostname
    try:
        port = parsed.port
    except ValueError as error:
        raise ManagerError("BreakHub URL contains an invalid port") from error
    if (
        parsed.scheme not in {"http", "https"}
        or not hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or parsed.params
        or (port is not None and not 1 <= port <= 65535)
    ):
        raise ManagerError("BreakHub URL must be an HTTP or HTTPS host with an optional port")
    scheme = parsed.scheme.lower()
    host = hostname.lower()
    authority = f"[{host}]" if ":" in host else host
    if port is not None and not (
        (scheme == "http" and port == 80) or (scheme == "https" and port == 443)
    ):
        authority += f":{port}"
    return urlunparse((scheme, authority, parsed.path.rstrip("/"), "", "", ""))


def _connection_id(url: str) -> str:
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:12]
    return f"connection-{digest}"


def _read_connections(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ManagerError(f"Target registry does not exist: {path}")
    try:
        registry = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ManagerError(f"Target registry is not valid JSON: {path}") from error
    if not isinstance(registry, dict):
        raise ManagerError("Target registry root must be an object")
    raw_connections = registry.get("connections")
    if not isinstance(raw_connections, list):
        legacy_targets = registry.get("targets")
        if not isinstance(legacy_targets, list):
            raise ManagerError("Target registry must contain a connections list")
        raw_connections = [
            {
                "url": target.get("breakpoint_url"),
                "access_token": target.get("gateway_token"),
            }
            for target in legacy_targets
            if isinstance(target, dict) and target.get("enabled", True) is not False
        ]

    connections: list[dict[str, str]] = []
    seen_urls: set[str] = set()
    for item in raw_connections:
        if not isinstance(item, dict):
            raise ManagerError("Every target connection must be an object")
        url = _normalize_breakhub_url(str(item.get("url") or ""))
        access_token = str(item.get("access_token") or "").strip()
        if not access_token:
            raise ManagerError("Every target connection must contain an access token")
        if url in seen_urls:
            raise ManagerError("Target registry contains a duplicate BreakHub URL")
        seen_urls.add(url)
        connections.append({"url": url, "access_token": access_token})
    return {"version": 2, "connections": connections}


def _fetch_equipment(url: str, access_token: str) -> dict[str, str]:
    request = Request(
        url + "/api/v1/equipment",
        headers={
            "Authorization": f"Bearer {access_token}",
            "Accept": "application/json",
        },
        method="GET",
    )
    try:
        with urlopen(request, timeout=5) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (HTTPError, URLError, TimeoutError, UnicodeError, json.JSONDecodeError) as error:
        raise ManagerError("Cannot refresh equipment identity from BreakHub") from error
    if not isinstance(payload, dict):
        raise ManagerError("BreakHub returned an invalid equipment identity")
    equipment_id = str(payload.get("equipment_id") or "").strip()
    display_name = str(payload.get("display_name") or equipment_id).strip()
    if not equipment_id:
        raise ManagerError("BreakHub returned an empty equipment ID")
    return {"equipment_id": equipment_id, "display_name": display_name or equipment_id}


def list_targets(config_path: Path) -> None:
    registry = _read_connections(config_path)
    listed = []
    for connection in registry["connections"]:
        connection_id = _connection_id(connection["url"])
        try:
            equipment = _fetch_equipment(
                connection["url"], connection["access_token"]
            )
            listed.append(
                {
                    "connection_id": connection_id,
                    **equipment,
                    "status": "available",
                }
            )
        except ManagerError:
            listed.append({"connection_id": connection_id, "status": "unreachable"})
    print(json.dumps(listed, ensure_ascii=False, indent=2))


def upsert_target(
    config_path: Path,
    url: str,
    access_token: str | None,
) -> None:
    normalized_url = _normalize_breakhub_url(url)
    token = (access_token or "").strip()
    if not token:
        token = getpass.getpass("BreakHub access token: ").strip()
    if not token:
        raise ManagerError("Access token is required")
    equipment = _fetch_equipment(normalized_url, token)
    registry = _read_connections(config_path)
    connections = registry["connections"]
    replacement = {"url": normalized_url, "access_token": token}
    for index, connection in enumerate(connections):
        if connection["url"] == normalized_url:
            connections[index] = replacement
            break
    else:
        connections.append(replacement)
    _write_config(config_path, registry)
    print(
        json.dumps(
            {
                "connection_id": _connection_id(normalized_url),
                **equipment,
                "status": "available",
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def remove_target(config_path: Path, connection_id: str, confirmed: bool) -> None:
    normalized_id = connection_id.strip()
    if not normalized_id:
        raise ManagerError("Connection ID is required")
    registry = _read_connections(config_path)
    connections = registry["connections"]
    matching_indexes = [
        index
        for index, connection in enumerate(connections)
        if _connection_id(connection["url"]) == normalized_id
    ]
    if not matching_indexes:
        print(f"Connection is already absent: {normalized_id}")
        return
    if len(matching_indexes) > 1:
        raise ManagerError("Target registry contains duplicate connection IDs")
    if not confirmed:
        raise ManagerError("Connection removal requires --yes after explicit user confirmation")
    del connections[matching_indexes[0]]
    _write_config(config_path, registry)
    print(f"Updated target registry: {config_path}")


def _interactive_arguments() -> list[str]:
    print("Breakpoint Debugging Manager")
    print("  [1] Install or repair (default)")
    print("  [2] Uninstall")
    action = input("Choose 1 or 2 [1]: ").strip().lower()
    command = "uninstall" if action in {"2", "uninstall", "u"} else "install"
    print("  [1] Current project (default)")
    print("  [2] Global OpenCode directory")
    selected_scope = input("Choose 1 or 2 [1]: ").strip().lower()
    scope = "global" if selected_scope in {"2", "global", "g"} else "project"
    return [command, "--scope", scope]


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="breakpoint-debugging-manager")
    subparsers = parser.add_subparsers(dest="command", required=True)
    install_parser = subparsers.add_parser("install", help="Install or repair the integration")
    install_parser.add_argument("--scope", choices=("project", "global"), default="project")
    install_parser.add_argument("--project-root", default="")
    install_parser.add_argument("--package", default="")
    uninstall_parser = subparsers.add_parser("uninstall", help="Uninstall the integration")
    uninstall_parser.add_argument("--scope", choices=("project", "global"), default="project")
    uninstall_parser.add_argument("--project-root", default="")
    uninstall_parser.add_argument("--remove-data", action="store_true")
    targets_parser = subparsers.add_parser("targets", help="Manage equipment targets")
    target_commands = targets_parser.add_subparsers(dest="target_command", required=True)

    def add_target_location(command_parser: argparse.ArgumentParser) -> None:
        command_parser.add_argument(
            "--scope", choices=("project", "global"), default="project"
        )
        command_parser.add_argument("--project-root", default="")
        command_parser.add_argument("--config", default="")

    list_parser = target_commands.add_parser("list", help="List targets without secrets")
    add_target_location(list_parser)
    upsert_parser = target_commands.add_parser("upsert", help="Add or update a target")
    add_target_location(upsert_parser)
    upsert_parser.add_argument("--url", required=True)
    upsert_parser.add_argument("--access-token")
    remove_parser = target_commands.add_parser("remove", help="Remove a target")
    add_target_location(remove_parser)
    remove_parser.add_argument("--connection-id", required=True)
    remove_parser.add_argument("--yes", action="store_true")
    return parser


def main(arguments: Sequence[str] | None = None) -> int:
    raw_arguments = list(arguments if arguments is not None else sys.argv[1:])
    if not raw_arguments:
        raw_arguments = _interactive_arguments()
    options = _build_parser().parse_args(raw_arguments)
    project_root = (
        Path(options.project_root).resolve()
        if options.project_root
        else _find_project_root(Path.cwd())
    )
    try:
        if options.command == "install":
            install(options.scope, project_root, _locate_package(options.package or None))
        elif options.command == "uninstall":
            uninstall(options.scope, project_root, options.remove_data)
        elif options.command == "targets":
            target_config = _target_config_path(
                options.scope, project_root, options.config or None
            )
            if options.target_command == "list":
                list_targets(target_config)
            elif options.target_command == "upsert":
                upsert_target(
                    target_config,
                    options.url,
                    options.access_token,
                )
            else:
                remove_target(target_config, options.connection_id, options.yes)
        return 0
    except (ManagerError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
