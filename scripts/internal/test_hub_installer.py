from __future__ import annotations

import hashlib
import stat
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
INTERNAL_ROOT = REPO_ROOT / "scripts" / "internal"
if str(INTERNAL_ROOT) not in sys.path:
    sys.path.insert(0, str(INTERNAL_ROOT))

from hub_installer_tasks import (  # noqa: E402
    LAUNCHER_NAME,
    RUNTIME_MODULES,
    _extract_jdk_archive,
    _find_jdk,
)
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
            "RequestExecutionLevel admin",
            r'$PROGRAMFILES64\BreakHub',
            "BreakHub.exe",
            "BreakHub-Stop.exe",
            "CreateShortCut",
            "WriteUninstaller",
            'Icon "${ICON_FILE}"',
            '!define MUI_ICON "${ICON_FILE}"',
            '!define MUI_UNICON "${ICON_FILE}"',
            "SetShellVarContext all",
            r'CreateDirectory "$INSTDIR\data"',
            r'CreateDirectory "$INSTDIR\logs"',
            r'$INSTDIR\application.yml',
            '!define APP_COMPATIBILITY_KEY',
            r'"$INSTDIR\BreakHub.exe" "~ RUNASADMIN"',
            r'"$INSTDIR\BreakHub-Stop.exe" "~ RUNASADMIN"',
            "SetRegView 64",
            "IfSilent uninstall_preserve_data",
            "MessageBox MB_ICONQUESTION|MB_YESNO|MB_DEFBUTTON2",
            "Var ExistingInstallDirectory",
            "Var LegacyInstallDirectory",
            "legacy_install_detected",
            "install_directory_changed",
            "install_payload_failed",
            "install_registry_failed",
            "uninstall_data_failed",
            "uninstall_registry_cleanup_failed",
            'FindFirst $0 $1 "$INSTDIR\\*.*"',
            'IfFileExists "$INSTDIR\\${INSTALL_MARKER_FILE}" marker_write_done',
            '"$INSTDIR\\${INSTALL_MARKER_FILE}.tmp"',
        ):
            self.assertIn(required, script)
        self.assertNotIn("$LOCALAPPDATA", script)
        self.assertNotIn(r'RMDir /r "$INSTDIR"', script)
        self.assertIn('!insertmacro MUI_PAGE_DIRECTORY', script)
        self.assertIn('InstallDirRegKey HKLM', script)
        self.assertIn('WriteRegStr HKLM "${PRODUCT_REGISTRY_KEY}"', script)
        self.assertNotIn('!define MUI_FINISHPAGE_RUN ', script)
        self.assertNotIn(r'CreateDirectory "$LOCALAPPDATA\BreakHub"', script)
        self.assertNotIn(r'StrCpy $INSTDIR "$LOCALAPPDATA\Programs\BreakHub"', script)
        self.assertIn('!define INSTALL_MARKER_FILE ".breakhub-install-root"', script)
        self.assertIn('Call ValidateInstallRoot', script)
        self.assertIn('Call un.ValidateInstallRoot', script)
        self.assertIn(r'IfFileExists "$INSTDIR\BreakHub-Start.exe"', script)
        self.assertIn('ReadRegStr $2 HKCU "${PRODUCT_REGISTRY_KEY}" "InstallLocation"', script)
        self.assertIn(r'IfFileExists "$INSTDIR\app\*.*" upgrade_cleanup_failed', script)
        self.assertIn(r'IfFileExists "$INSTDIR\runtime\*.*" upgrade_cleanup_failed', script)
        self.assertIn(r'IfFileExists "$INSTDIR\app\*.jar"', script)
        self.assertIn(r'IfFileExists "$INSTDIR\runtime\bin\server\jvm.dll"', script)
        self.assertLess(
            script.index("uninstall_delete_data:"),
            script.index("!insertmacro RemoveProgramFiles", script.index('Section "Uninstall"')),
        )
        self.assertLess(
            script.index("uninstall_data_failed:"),
            script.index(r'Delete "$INSTDIR\Uninstall.exe"', script.index('Section "Uninstall"')),
        )
        uninstall_section = script.index('Section "Uninstall"')
        self.assertLess(
            script.index('DeleteRegKey HKLM "${PRODUCT_REGISTRY_KEY}"', uninstall_section),
            script.index(r'Delete "$INSTDIR\Uninstall.exe"', uninstall_section),
        )
        self.assertNotIn(
            r'CreateShortCut "$DESKTOP\BreakHub - 停止.lnk"', script
        )
        self.assertEqual(
            2,
            script.count(
                "System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'"
            ),
        )
        self.assertGreaterEqual(
            script.count(r'Delete "$DESKTOP\BreakHub - 停止.lnk"'), 2
        )
        self.assertEqual(3, script.count("BreakHub-Start.exe"))
        self.assertNotIn("breakhub-mcp", script.lower())

    def test_main_launcher_is_named_breakhub(self) -> None:
        self.assertEqual("BreakHub", LAUNCHER_NAME)

    def test_breakhub_icon_assets_are_present(self) -> None:
        installer = REPO_ROOT / "bp-hub" / "installer"
        png = (installer / "breakhub.png").read_bytes()
        ico = (installer / "breakhub.ico").read_bytes()

        self.assertEqual(
            "c78554ef0b41323c7b0bd81c2b1cea062658b8e776838457a0ccaac5d63b4730",
            hashlib.sha256(png).hexdigest(),
        )
        self.assertEqual(
            "043e3172bba993fab80820f36ae36ab48a9172996f39f7973cfc21ede3179bee",
            hashlib.sha256(ico).hexdigest(),
        )
        self.assertEqual(b"\x00\x00\x01\x00", ico[:4])
        image_count = struct.unpack_from("<H", ico, 4)[0]
        dimensions = []
        for index in range(image_count):
            width, height = struct.unpack_from("BB", ico, 6 + index * 16)
            dimensions.append((width or 256, height or 256))
        self.assertEqual(
            [(size, size) for size in (16, 20, 24, 32, 40, 48, 64, 128, 256)],
            dimensions,
        )

    def test_extracts_a_portable_java_17_jdk_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory_value:
            directory = Path(directory_value)
            archive = directory / "temurin-17.zip"
            destination = directory / "extracted"
            with zipfile.ZipFile(archive, "w") as bundled:
                bundled.writestr("jdk-17/release", 'JAVA_VERSION="17.0.20"\n')
                bundled.writestr("jdk-17/bin/jpackage.exe", b"jpackage")
                bundled.writestr("jdk-17/bin/jlink.exe", b"jlink")
            archive.with_suffix(".zip.sha256").write_text(
                hashlib.sha256(archive.read_bytes()).hexdigest(), encoding="ascii"
            )

            jdk = _extract_jdk_archive(archive, destination)

            self.assertTrue(jdk.samefile(destination / "jdk-17"))
            self.assertTrue((jdk / "bin" / "jpackage.exe").is_file())

    def test_rejects_a_portable_jdk_without_a_checksum_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory_value:
            directory = Path(directory_value)
            archive = directory / "temurin-17.zip"
            destination = directory / "extracted"
            with zipfile.ZipFile(archive, "w") as bundled:
                bundled.writestr("jdk-17/release", 'JAVA_VERSION="17.0.20"\n')

            with self.assertRaisesRegex(TaskError, "checksum file"):
                _extract_jdk_archive(archive, destination)

    def test_rejects_a_malformed_portable_jdk_checksum(self) -> None:
        with tempfile.TemporaryDirectory() as directory_value:
            directory = Path(directory_value)
            archive = directory / "temurin-17.zip"
            destination = directory / "extracted"
            with zipfile.ZipFile(archive, "w") as bundled:
                bundled.writestr("jdk-17/release", 'JAVA_VERSION="17.0.20"\n')
            archive.with_suffix(".zip.sha256").write_text(
                "not-a-sha256", encoding="ascii"
            )

            with self.assertRaisesRegex(TaskError, "checksum file is invalid"):
                _extract_jdk_archive(archive, destination)

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

    def test_installer_configuration_uses_the_installation_home_property(self) -> None:
        template = (
            REPO_ROOT / "bp-hub" / "installer" / "application.yml.template"
        ).read_text(encoding="utf-8")
        self.assertIn("${breakhub.home}/data", template)
        self.assertIn("${breakhub.home}/logs/breakhub.log", template)
        self.assertNotIn("@BREAKHUB_HOME@", template)

    def test_build_directory_cleanup_handles_read_only_jpackage_files(self) -> None:
        (REPO_ROOT / "build").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=REPO_ROOT / "build") as parent_value:
            parent = Path(parent_value)
            target = parent / "app-image"
            target.mkdir()
            executable = target / "BreakHub.exe"
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
