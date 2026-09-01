from __future__ import annotations

import hashlib
import os
import re
import shutil
import stat
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Iterable

from task_support import BUILD_ROOT, DIST_ROOT, REPO_ROOT, TaskError, reset_directory, run


HUB_ROOT = REPO_ROOT / "bp-hub"
INSTALLER_SOURCE = HUB_ROOT / "installer"
INSTALLER_BUILD_ROOT = BUILD_ROOT / "hub-installer"
JDK_ARCHIVE_ROOT = REPO_ROOT / "vendor" / "jdk"
JDK_EXTRACTION_ROOT = BUILD_ROOT / "jdk"
ICON_SOURCE = INSTALLER_SOURCE / "breakhub.ico"
LAUNCHER_NAME = "BreakHub"
STOP_LAUNCHER_NAME = "BreakHub-Stop"
PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher"
WINDOWS_LAUNCHER = "com.ateagents.breakhub.BreakHubWindowsLauncher"
WINDOWS_STOPPER = "com.ateagents.breakhub.BreakHubStop"
RUNTIME_MODULES = (
    "java.base",
    "java.compiler",
    "java.desktop",
    "java.instrument",
    "java.logging",
    "java.management",
    "java.naming",
    "java.net.http",
    "java.prefs",
    "java.rmi",
    "java.scripting",
    "java.security.jgss",
    "java.sql",
    "java.sql.rowset",
    "java.transaction.xa",
    "java.xml",
    "jdk.charsets",
    "jdk.crypto.ec",
    "jdk.jfr",
    "jdk.localedata",
    "jdk.management",
    "jdk.unsupported",
)


def _release_version() -> str:
    root = ET.parse(HUB_ROOT / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = root.findtext("m:version", namespaces=namespace)
    if not version:
        raise TaskError("bp-hub/pom.xml does not define a version.")
    return re.sub(r"-SNAPSHOT$", "", version)


def _file_version(version: str) -> str:
    numeric = [part for part in version.split(".") if part.isdigit()]
    if len(numeric) < 3:
        raise TaskError(f"Installer version must contain three numeric parts: {version}")
    return ".".join((numeric + ["0", "0", "0", "0"])[:4])


def _jdk_candidates(requested: str) -> Iterable[Path]:
    if requested:
        yield Path(requested)
        return
    bundled = _bundled_jdk()
    if bundled:
        yield bundled
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        yield Path(java_home)
    yield Path(r"C:\Program Files\Java\jdk-17")
    discovered = shutil.which("jpackage.exe") or shutil.which("jpackage")
    if discovered:
        yield Path(discovered).resolve().parent.parent


def _bundled_jdk() -> Path | None:
    archives = sorted(JDK_ARCHIVE_ROOT.glob("*.zip"))
    if not archives:
        return None
    if len(archives) != 1:
        raise TaskError(
            f"Expected one portable JDK ZIP under {JDK_ARCHIVE_ROOT}, found {len(archives)}."
        )
    archive = archives[0]
    destination = JDK_EXTRACTION_ROOT / archive.stem
    digest = _verify_archive_checksum(archive)
    existing = _jdk_root_in(destination)
    digest_marker = destination / ".archive.sha256"
    if existing and digest_marker.is_file() and (
        digest_marker.read_text(encoding="ascii").strip() == digest
    ):
        return existing
    return _extract_jdk_archive(archive, destination, digest)


def _extract_jdk_archive(
    archive: Path, destination: Path, digest: str = ""
) -> Path:
    archive_digest = digest or _verify_archive_checksum(archive)
    extracted = reset_directory(destination, destination.parent, "portable JDK directory")
    with zipfile.ZipFile(archive) as bundled:
        for member in bundled.infolist():
            member_path = Path(member.filename)
            if member_path.is_absolute() or member_path.drive or ".." in member_path.parts:
                raise TaskError(f"Portable JDK archive contains an unsafe path: {member.filename}")
        bundled.extractall(extracted)
    jdk = _jdk_root_in(extracted)
    if not jdk:
        raise TaskError(f"Portable JDK archive does not contain a Java 17 JDK: {archive}")
    (extracted / ".archive.sha256").write_text(archive_digest, encoding="ascii")
    return jdk


def _verify_archive_checksum(archive: Path) -> str:
    digest = hashlib.sha256()
    with archive.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    checksum_file = archive.with_suffix(archive.suffix + ".sha256")
    if checksum_file.is_file():
        expected = checksum_file.read_text(encoding="ascii").split()[0].lower()
        if expected != actual:
            raise TaskError(
                f"Portable JDK checksum mismatch: expected {expected}, got {actual}."
            )
    return actual


def _jdk_root_in(directory: Path) -> Path | None:
    if not directory.is_dir():
        return None
    candidates = [
        release.parent
        for release in directory.rglob("release")
        if _jdk_major_version(release.parent) == 17
        and (release.parent / "bin" / "jpackage.exe").is_file()
        and (release.parent / "bin" / "jlink.exe").is_file()
    ]
    if len(candidates) > 1:
        raise TaskError(f"Portable JDK archive contains multiple Java 17 JDKs: {directory}")
    return candidates[0] if candidates else None


def _jdk_major_version(jdk: Path) -> int | None:
    release = jdk / "release"
    if not release.is_file():
        return None
    match = re.search(
        r'^JAVA_VERSION="(\d+)(?:\.[^"]*)?"$',
        release.read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    return int(match.group(1)) if match else None


def _find_jdk(requested: str) -> Path:
    seen: set[str] = set()
    for candidate in _jdk_candidates(requested):
        resolved = candidate.resolve()
        key = os.path.normcase(str(resolved))
        if key in seen:
            continue
        seen.add(key)
        if _jdk_major_version(resolved) == 17 and (
            resolved / "bin" / "jpackage.exe"
        ).is_file() and (
            resolved / "bin" / "jlink.exe"
        ).is_file():
            return resolved
    detail = requested or "JAVA_HOME, Program Files Java installations, and PATH"
    raise TaskError(f"A Java 17 JDK with jpackage and jlink was not found via {detail}.")


def _find_nsis(requested: str) -> Path:
    candidates = []
    if requested:
        candidates.append(Path(requested))
    else:
        discovered = shutil.which("makensis.exe") or shutil.which("makensis")
        if discovered:
            candidates.append(Path(discovered))
        candidates.extend(
            (
                Path(r"C:\Program Files (x86)\NSIS\makensis.exe"),
                Path(r"C:\Program Files\NSIS\makensis.exe"),
            )
        )
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved.is_file():
            return resolved
    detail = requested or "PATH and the standard NSIS installation directories"
    raise TaskError(f"makensis.exe was not found via {detail}.")


def _jdk_environment(jdk: Path) -> dict[str, str]:
    return {
        "JAVA_HOME": str(jdk),
        "PATH": f"{jdk / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}",
    }


def _build_hub(jdk: Path) -> None:
    web_root = HUB_ROOT / "web"
    run(["npm", "ci"], cwd=web_root)
    run(["npm", "run", "build"], cwd=web_root)
    run(
        ["mvn", "-f", HUB_ROOT / "pom.xml", "clean", "package", "-DskipTests"],
        env=_jdk_environment(jdk),
    )


def _hub_jar() -> Path:
    jars = [
        path
        for path in (HUB_ROOT / "target").glob("breakhub*.jar")
        if not re.search(r"-(sources|javadoc|tests)\.jar$", path.name)
    ]
    if len(jars) != 1:
        raise TaskError(f"Expected one executable Hub JAR, found {len(jars)}.")
    return jars[0]


def validate_hub_app_image(image: Path) -> None:
    required = (
        image / f"{LAUNCHER_NAME}.exe",
        image / f"{STOP_LAUNCHER_NAME}.exe",
        image / "runtime" / "bin" / "server" / "jvm.dll",
        image / "application.yml.template",
        image / "README.txt",
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise TaskError(f"Hub application image is incomplete: {missing}")
    powershell = [str(path) for path in image.rglob("*.ps1")]
    if powershell:
        raise TaskError(f"Hub application image contains PowerShell scripts: {powershell}")
    mcp_files = [path.name for path in image.rglob("*") if "breakhub-mcp" in path.name.lower()]
    if mcp_files:
        raise TaskError(f"Hub application image must not bundle Agent MCP files: {mcp_files}")


def _make_image_replaceable(image: Path) -> None:
    for path in image.rglob("*"):
        if path.is_file():
            path.chmod(stat.S_IREAD | stat.S_IWRITE)


def package_hub_installer(
    *,
    jdk_home: str = "",
    nsis_path: str = "",
    output_path: str = "",
    skip_build: bool = False,
) -> Path:
    jdk = _find_jdk(jdk_home)
    nsis = _find_nsis(nsis_path)
    if not skip_build:
        _build_hub(jdk)
    hub_jar = _hub_jar()
    version = _release_version()

    stage = reset_directory(
        INSTALLER_BUILD_ROOT,
        BUILD_ROOT,
        "Hub installer build directory",
    )
    input_directory = stage / "input"
    input_directory.mkdir()
    shutil.copy2(hub_jar, input_directory / hub_jar.name)
    stop_properties = stage / "stop-launcher.properties"
    stop_properties.write_text(
        "\n".join(
            (
                f"main-jar={hub_jar.name}",
                f"main-class={PROPERTIES_LAUNCHER}",
                f"java-options=-Dloader.main={WINDOWS_STOPPER}",
                f"icon={ICON_SOURCE.as_posix()}",
                "",
            )
        ),
        encoding="utf-8",
    )
    app_image_root = stage / "app-image"
    app_image_root.mkdir()
    jpackage = jdk / "bin" / "jpackage.exe"
    run(
        [
            jpackage,
            "--type",
            "app-image",
            "--dest",
            app_image_root,
            "--input",
            input_directory,
            "--name",
            LAUNCHER_NAME,
            "--icon",
            ICON_SOURCE,
            "--main-jar",
            hub_jar.name,
            "--main-class",
            PROPERTIES_LAUNCHER,
            "--java-options",
            f"-Dloader.main={WINDOWS_LAUNCHER}",
            "--java-options",
            "-Dfile.encoding=UTF-8",
            "--add-launcher",
            f"{STOP_LAUNCHER_NAME}={stop_properties}",
            "--add-modules",
            ",".join(RUNTIME_MODULES),
            "--jlink-options",
            "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=2",
            "--app-version",
            version,
            "--vendor",
            "AteAgents",
            "--description",
            "BreakHub breakpoint debugging hub",
        ],
        env=_jdk_environment(jdk),
    )
    image = app_image_root / LAUNCHER_NAME
    _make_image_replaceable(image)
    shutil.copy2(INSTALLER_SOURCE / "application.yml.template", image)
    shutil.copy2(INSTALLER_SOURCE / "README.txt", image)
    validate_hub_app_image(image)

    output = (
        Path(output_path).resolve()
        if output_path
        else DIST_ROOT / "hub" / f"BreakHub-Setup-{version}.exe"
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    run(
        [
            nsis,
            "/INPUTCHARSET",
            "UTF8",
            f"/DAPP_IMAGE={image}",
            f"/DICON_FILE={ICON_SOURCE}",
            f"/DOUTPUT_FILE={output}",
            f"/DPRODUCT_VERSION={version}",
            f"/DPRODUCT_FILE_VERSION={_file_version(version)}",
            INSTALLER_SOURCE / "BreakHub.nsi",
        ]
    )
    if not output.is_file() or output.stat().st_size == 0:
        raise TaskError(f"NSIS installer was not created: {output}")
    print(f"Bundled Java runtime: {jdk}")
    print(f"Packaged Hub installer: {output}")
    return output
