from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

from integration_tasks import test_breakpoint_debugging_install
from release_tasks import (
    package_ateagent,
    package_breakpoint_debugging,
    package_repository,
    test_ateagent_package,
    test_release_layout,
)
from task_support import (
    REPO_ROOT,
    SCRIPTS_ROOT,
    TaskError,
    build_manager_executable,
    build_mcp_executable,
    remove_matching,
    run,
    safe_child,
)


def build_repository(python: str = "python") -> None:
    web_root = REPO_ROOT / "bp-hub" / "web"
    run(["npm", "ci"], cwd=web_root)
    run(["npm", "run", "build"], cwd=web_root)
    run(["mvn", "-f", REPO_ROOT / "bp-probe" / "java" / "pom.xml", "clean", "install", "-DskipTests"])
    run(["mvn", "-f", REPO_ROOT / "example" / "java" / "pom.xml", "clean", "package", "-DskipTests"])
    run(["mvn", "-f", REPO_ROOT / "bp-hub" / "pom.xml", "clean", "package", "-DskipTests"])
    build_mcp_executable(python)


def package_java_demo(output_path: str = "") -> Path:
    probe_pom = REPO_ROOT / "bp-probe" / "java" / "pom.xml"
    demo_root = REPO_ROOT / "example" / "java"
    run(["mvn", "-f", probe_pom, "clean", "install", "-DskipTests"])
    run(["mvn", "-f", demo_root / "pom.xml", "clean", "package"])
    artifacts = [
        path
        for path in (demo_root / "target").glob("*.jar")
        if not re.search(r"-(sources|javadoc|tests)\.jar$", path.name)
    ]
    if len(artifacts) != 1:
        raise TaskError(f"Expected exactly one Java Demo JAR, found {len(artifacts)}.")
    if output_path:
        output = Path(output_path).resolve()
    else:
        output_directory = safe_child(
            REPO_ROOT / "dist" / "java-demo",
            REPO_ROOT / "dist",
            "Java Demo output directory",
        )
        output_directory.mkdir(parents=True, exist_ok=True)
        remove_matching(output_directory, ("instrument-demo*.jar", "start.ps1", "start.cmd"))
        output = output_directory / artifacts[0].name
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(artifacts[0], output)
    shutil.copy2(SCRIPTS_ROOT / "release" / "java-demo" / "start.cmd", output.parent)
    print(f"Packaged Java demo: {output}")
    return output


def test_command_surface() -> None:
    run(
        [sys.executable, "-m", "unittest", "scripts.internal.test_script_surface", "-v"],
        cwd=REPO_ROOT,
    )


def test_repository(python: str = "python") -> None:
    test_command_surface()
    web_root = REPO_ROOT / "bp-hub" / "web"
    run(["npm", "test"], cwd=web_root)
    run(["npm", "run", "build"], cwd=web_root)
    run(["mvn", "-f", REPO_ROOT / "bp-probe" / "java" / "pom.xml", "install"])
    run(["mvn", "-f", REPO_ROOT / "example" / "java" / "pom.xml", "test"])
    run(["mvn", "-f", REPO_ROOT / "bp-hub" / "pom.xml", "test"])
    mcp_root = REPO_ROOT / "bp-mcp"
    run([python, "-m", "pytest", "-q"], cwd=mcp_root)
    run([python, "-m", "ruff", "check", "."], cwd=mcp_root)
    run([python, "-m", "mypy", "src/bp_mcp"], cwd=mcp_root)
    manager_root = SCRIPTS_ROOT / "breakpoint-debugging-manager"
    run([python, "-m", "unittest", "test_manager.py", "-v"], cwd=manager_root)
    package_breakpoint_debugging(python=python)
    test_breakpoint_debugging_install(python=python)
    package_ateagent(python=python, skip_mcp_build=True)
    test_ateagent_package()


def _add_python(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("-Python", "--python", default="python")


def _add_output(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("-OutputPath", "--output-path", default="")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="BreakHub repository tasks")
    subparsers = parser.add_subparsers(dest="task", required=True)

    build = subparsers.add_parser("build")
    _add_python(build)
    build.set_defaults(handler=lambda args: build_repository(args.python))

    test = subparsers.add_parser("test")
    _add_python(test)
    test.set_defaults(handler=lambda args: test_repository(args.python))

    package = subparsers.add_parser("package")
    _add_python(package)
    package.set_defaults(
        handler=lambda args: package_repository(args.python, build_repository)
    )

    java_demo = subparsers.add_parser("package-java-demo")
    _add_output(java_demo)
    java_demo.set_defaults(handler=lambda args: package_java_demo(args.output_path))

    mcp = subparsers.add_parser("build-mcp-exe")
    _add_python(mcp)
    mcp.add_argument("-OutputDirectory", "--output-directory", default="")
    mcp.set_defaults(
        handler=lambda args: build_mcp_executable(args.python, args.output_directory)
    )

    manager = subparsers.add_parser("build-manager-exe")
    _add_python(manager)
    manager.add_argument("-OutputDirectory", "--output-directory", default="")
    manager.set_defaults(
        handler=lambda args: build_manager_executable(args.python, args.output_directory)
    )

    skill = subparsers.add_parser("package-breakpoint-debugging")
    _add_python(skill)
    _add_output(skill)
    skill.add_argument("-SkipMcpBuild", "--skip-mcp-build", action="store_true")
    skill.add_argument("-SkipManagerBuild", "--skip-manager-build", action="store_true")
    skill.set_defaults(
        handler=lambda args: package_breakpoint_debugging(
            python=args.python,
            output_path=args.output_path,
            skip_mcp_build=args.skip_mcp_build,
            skip_manager_build=args.skip_manager_build,
        )
    )

    ateagent = subparsers.add_parser("package-ateagent")
    _add_python(ateagent)
    _add_output(ateagent)
    ateagent.add_argument("-Version", "--version", default="0.1.0")
    ateagent.add_argument("-SkipMcpBuild", "--skip-mcp-build", action="store_true")
    ateagent.set_defaults(
        handler=lambda args: package_ateagent(
            python=args.python,
            version=args.version,
            output_path=args.output_path,
            skip_mcp_build=args.skip_mcp_build,
        )
    )

    test_ateagent = subparsers.add_parser("test-ateagent-package")
    test_ateagent.add_argument("-PackagePath", "--package-path", default="")
    test_ateagent.add_argument("-Version", "--version", default="0.1.0")
    test_ateagent.set_defaults(
        handler=lambda args: test_ateagent_package(args.package_path, args.version)
    )

    test_install = subparsers.add_parser("test-breakpoint-install")
    _add_python(test_install)
    test_install.add_argument("-PackagePath", "--package-path", default="")
    test_install.add_argument("-ManagerPath", "--manager-path", default="")
    test_install.set_defaults(
        handler=lambda args: test_breakpoint_debugging_install(
            package_path=args.package_path,
            manager_path=args.manager_path,
            python=args.python,
        )
    )

    layout = subparsers.add_parser("test-release-layout")
    layout.add_argument("-DistPath", "--dist-path", default="")
    layout.set_defaults(handler=lambda args: test_release_layout(args.dist_path))

    surface = subparsers.add_parser("test-command-surface")
    surface.set_defaults(handler=lambda _args: test_command_surface())
    return parser


def main() -> int:
    parser = create_parser()
    args = parser.parse_args()
    try:
        args.handler(args)
    except (TaskError, OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"[BreakHub] ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
