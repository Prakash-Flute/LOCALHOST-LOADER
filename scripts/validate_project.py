#!/usr/bin/env python3
"""Offline structural validation for a Localhost Loader source archive."""
from __future__ import annotations

import ast
import hashlib
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath
from zipfile import ZipFile, BadZipFile

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
checks = 0


def check(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        errors.append(message)


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"Invalid JSON {path.relative_to(ROOT)}: {exc}")
        return {}


def safe_zip(path: Path):
    try:
        archive = ZipFile(path)
        names = archive.namelist()
        for name in names:
            normalized = name.replace("\\", "/")
            parts = PurePosixPath(normalized).parts
            check(not normalized.startswith("/") and ".." not in parts,
                  f"Unsafe ZIP path in {path.name}: {name}")
        return archive, names
    except (BadZipFile, OSError) as exc:
        errors.append(f"Invalid ZIP {path}: {exc}")
        return None, []


# Android project and manifest.
android_manifest = ROOT / "app/src/main/AndroidManifest.xml"
try:
    ET.parse(android_manifest)
    check(True, "AndroidManifest.xml parses")
except Exception as exc:
    check(False, f"AndroidManifest.xml parse error: {exc}")

app_gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
for required in ("com.android.application", "com.chaquo.python", "compileSdk 35", "version = '3.13'", "ANDROID_STL=c++_shared"):
    check(required in app_gradle, f"Missing app build setting: {required}")

# Python sources.
for py_file in (ROOT / "app/src/main/python/ll_runtime.py", ROOT / "samples/python-http-server/server.py"):
    try:
        ast.parse(py_file.read_text(encoding="utf-8"), filename=str(py_file))
        check(True, f"Python syntax: {py_file.relative_to(ROOT)}")
    except SyntaxError as exc:
        check(False, f"Python syntax error in {py_file}: {exc}")

# Sample package archives.
samples = {
    "Hello-Local-App.zip": "static",
    "Python-Local-Server.zip": "python",
    "Node-Local-Server.zip": "node",
}
for filename, expected_runtime in samples.items():
    archive_path = ROOT / "samples" / filename
    check(archive_path.is_file(), f"Sample archive exists: {filename}")
    archive, names = safe_zip(archive_path)
    if archive is None:
        continue
    manifest_name = next((n for n in names if n.endswith("/manifest.json") or n == "manifest.json"), None)
    check(manifest_name is not None, f"{filename} has manifest.json")
    if manifest_name:
        manifest = json.loads(archive.read(manifest_name).decode("utf-8"))
        check(manifest.get("runtime") == expected_runtime, f"{filename} runtime is {expected_runtime}")
        entry = manifest.get("entry")
        if entry:
            prefix = manifest_name.rsplit("/", 1)[0] if "/" in manifest_name else ""
            entry_name = f"{prefix}/{entry}" if prefix else entry
            check(entry_name in names, f"{filename} includes entry {entry}")
        if expected_runtime == "static":
            web_root = manifest.get("web_root", ".")
            prefix = manifest_name.rsplit("/", 1)[0] if "/" in manifest_name else ""
            if web_root not in ("", "."):
                index_rel = f"{web_root.rstrip('/')}/index.html"
            else:
                index_rel = "index.html"
            index_name = f"{prefix}/{index_rel}" if prefix else index_rel
            check(index_name in names, f"{filename} includes static index")
    archive.close()

# Runtime pack archive and SHA-256 validation.
for pack_path in (ROOT / "runtime-packs").glob("*.llpack"):
    archive, names = safe_zip(pack_path)
    if archive is None:
        continue
    check("pack.json" in names, f"{pack_path.name} has pack.json")
    try:
        manifest = json.loads(archive.read("pack.json").decode("utf-8"))
        check(manifest.get("schema") == 1, f"{pack_path.name} schema is supported")
        for item in manifest.get("files", []):
            rel = item["path"]
            payload_name = "payload/" + rel
            data = archive.read(payload_name)
            digest = hashlib.sha256(data).hexdigest()
            check(digest == item.get("sha256"), f"{pack_path.name} checksum for {rel}")
    except Exception as exc:
        errors.append(f"Runtime pack {pack_path.name} invalid: {exc}")
    archive.close()

# Feature wiring assertions: prevent accidental reversion to an inert demo app.
main_java = (ROOT / "app/src/main/java/com/laaprak/localhostloader/MainActivity.java").read_text(encoding="utf-8")
for required in (
    "PackageImporter.importZip", "PackageImporter.importDirectory", "startAppAfterTrust",
    "NpmLockInstaller.install", "RuntimePackInstaller.install", "PersistentVault.attach",
    "PeriodicWorkRequest.Builder", "LocalGatewayService.ACTION_REGISTER_BACKEND",
):
    check(required in main_java, f"Feature wiring missing: {required}")

if errors:
    print(f"FAILED: {len(errors)} issue(s) across {checks} checks")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print(f"PASS: {checks} offline structural checks")
