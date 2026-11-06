"""Localhost Loader's embedded Python runtime helpers.

This module is called by the native Android process manager. It never opens a
terminal and never evaluates a manifest-provided shell command. It invokes a
Python entry file directly with runpy and exposes pip through its Python API.
"""
from __future__ import annotations

import json
import os
import runpy
import sys
import traceback
from pathlib import Path


def _append_log(log_path: str, message: str) -> None:
    if not log_path:
        return
    try:
        Path(log_path).parent.mkdir(parents=True, exist_ok=True)
        with open(log_path, "a", encoding="utf-8") as stream:
            stream.write(message.rstrip() + "\n")
    except Exception:
        pass


def install_requirements(
    requirements_file: str,
    target_dir: str,
    wheelhouse: str,
    index_url: str,
    offline: bool,
    log_path: str,
) -> int:
    """Install a project's requirements into a versioned isolated target.

    Wheel files are retained in the shared wheelhouse. That directory can be
    copied to the user-selected SAF vault so it survives app removal.
    """
    req = Path(requirements_file)
    target = Path(target_dir)
    wheels = Path(wheelhouse)
    target.mkdir(parents=True, exist_ok=True)
    wheels.mkdir(parents=True, exist_ok=True)

    if not req.is_file() or not req.read_text(encoding="utf-8", errors="replace").strip():
        return 0

    from pip._internal.cli.main import main as pip_main

    common = [
        "--disable-pip-version-check",
        "--no-warn-script-location",
        "--retries", "2",
        "--timeout", "20",
    ]
    if not offline:
        download_args = [
            "download", "-r", str(req), "--dest", str(wheels),
            "--prefer-binary", "--only-binary=:all:", *common,
        ]
        if index_url:
            download_args += ["--index-url", index_url]
        _append_log(log_path, "Downloading Python dependencies into the reusable wheel cache…")
        download_code = pip_main(download_args)
        if download_code != 0:
            _append_log(log_path, f"pip download returned {download_code}; checking whether cached wheels are sufficient.")

    install_args = [
        "install", "-r", str(req), "--target", str(target),
        "--find-links", str(wheels), "--prefer-binary",
        "--no-warn-script-location", "--disable-pip-version-check", "--only-binary=:all:",
    ]
    if offline:
        install_args += ["--no-index"]
    elif index_url:
        install_args += ["--index-url", index_url]
    _append_log(log_path, "Installing dependencies into this app's isolated Python environment…")
    result = int(pip_main(install_args) or 0)
    _append_log(log_path, f"pip install finished with exit code {result}.")
    return result


def run_entry(
    package_root: str,
    entry_file: str,
    site_packages: str,
    port: int,
    host: str,
    app_name: str,
    log_path: str,
    environment_json: str = "{}",
) -> None:
    """Run a package's Python entry file in this runtime process."""
    root = Path(package_root).resolve()
    entry = (root / entry_file).resolve()
    if root != entry and root not in entry.parents:
        raise ValueError("The Python entry file escapes the imported package")
    if not entry.is_file():
        raise FileNotFoundError(str(entry))

    os.chdir(str(root))
    os.environ["HOME"] = os.environ.get("HOME", str(root))
    os.environ["PORT"] = str(int(port))
    os.environ["LOCALHOST_PORT"] = str(int(port))
    os.environ["HOST"] = host or "127.0.0.1"
    os.environ["LOCALHOST_HOST"] = host or "127.0.0.1"
    os.environ["LOCALHOST_APP_NAME"] = app_name
    os.environ["LOCALHOST_APP_ROOT"] = str(root)
    os.environ["LOCALHOST_APP_LOG"] = log_path

    launch_args: list[str] = []
    try:
        config = json.loads(environment_json or "{}")
        # Current format is {environment: {...}, args: [...]}; retain compatibility
        # with an older plain environment object without allowing reserved keys.
        extra = config.get("environment", config if isinstance(config, dict) else {})
        if not isinstance(extra, dict):
            extra = {}
        reserved = {"PORT", "HOST", "HOME", "PATH", "LD_PRELOAD", "LD_LIBRARY_PATH",
                    "LOCALHOST_PORT", "LOCALHOST_HOST", "LOCALHOST_APP_ROOT",
                    "LOCALHOST_APP_NAME", "LOCALHOST_APP_LOG"}
        for key, value in extra.items():
            if (isinstance(key, str) and key.replace("_", "").isalnum()
                    and key not in reserved and isinstance(value, (str, int, float, bool))):
                text = str(value)
                if len(text) <= 4096 and "\x00" not in text:
                    os.environ[key] = text
        args = config.get("args", []) if isinstance(config, dict) else []
        if isinstance(args, list):
            launch_args = [str(item) for item in args[:32]
                           if isinstance(item, (str, int, float, bool)) and len(str(item)) <= 2048
                           and "\x00" not in str(item)]
    except Exception:
        _append_log(log_path, "Manifest launch configuration could not be parsed; using standard variables only.")

    # Add the app's private package environment before global/runtime paths.
    for candidate in (str(root), str(Path(site_packages).resolve())):
        if candidate not in sys.path:
            sys.path.insert(0, candidate)

    sys.argv = [str(entry), *launch_args]
    _append_log(log_path, f"Starting {app_name}: {entry.name} on {host}:{port}")
    try:
        runpy.run_path(str(entry), run_name="__main__")
        _append_log(log_path, "Python entry file returned normally.")
    except BaseException:
        error = traceback.format_exc()
        _append_log(log_path, error)
        raise
