# Localhost Loader — Server Package and Runtime Pack Format

**Package schema:** `manifest.json` or `loader.json`  
**Runtime pack extension:** `.llpack`  
**Principle:** package source describes what it needs; Localhost Loader provisions a supported environment without asking the user to type commands.

## 1. Basic server package layout

```text
MY_LOCAL_APP/
├── manifest.json
├── README.md
├── backend/
│   └── server.py
├── frontend/
│   └── index.html
├── requirements.txt        # Python only, if needed
├── package.json             # Node only, if needed
└── package-lock.json        # Node dependencies: lockfile v2 or v3
```

A package may be imported as a ZIP, a selected folder, or a direct HTTPS ZIP URL. One outer project folder is accepted. The loader looks for the manifest and common project files without requiring the user to locate an entry point.

## 2. Manifest schema

The following fields are supported by the current beta:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Stable app ID; letters, numbers, `.`, `_`, `-` only |
| `name` | string | Human-readable launcher/browser title |
| `version` | string | App package version |
| `runtime` | string | `static`, `python`, or `node` |
| `runtime_version` | string | Exact runtime version requested |
| `entry` | string | Relative server entry file |
| `start` | string array | Structured startup argv, e.g. `["python", "server.py", "--mode", "local"]` |
| `args` | string array | Additional startup arguments |
| `homepage` | string | Initial internal browser route; default `/` |
| `web_root` | string | Static site directory relative to package root |
| `static` | boolean | Marks a package as having an independently served static site |
| `dependencies` | string array | Explicit Python dependency specs when no `requirements.txt` exists |
| `environment` | object | Additional string/number/boolean environment values |
| `permissions` | string array | Human-readable capability declarations shown in trust UI |
| `update_url` | string | Informational app update URL |

The loader does not evaluate arbitrary command strings, pipes, shell operators, or manifest shell scripts. `start` is parsed as an argument array and only the supported `python`/`node` launch forms are accepted. The loader reserves `PORT`, `HOST`, and other routing/runtime variables.

## 3. Static web package

```json
{
  "id": "notes-web",
  "name": "Notes",
  "version": "1.0.0",
  "runtime": "static",
  "static": true,
  "web_root": "site",
  "homepage": "/"
}
```

The selected `web_root` must contain `index.html`. The app is served by the loader's loopback gateway. Extension-less paths can fall back to the site's `index.html` for single-page apps. Static-only apps do not need Python or Node.

## 4. Python server package

```json
{
  "id": "my-python-app",
  "name": "My Python App",
  "version": "1.0.0",
  "runtime": "python",
  "runtime_version": "3.13",
  "entry": "backend/server.py",
  "homepage": "/",
  "environment": {"APP_MODE": "production"},
  "args": ["--local"]
}
```

`requirements.txt` example:

```text
flask==3.1.1
requests>=2.32,<3
```

Supported runtime in this APK: CPython **3.13**. Server code should bind to `127.0.0.1` and read the allocated port from `PORT` (also `LOCALHOST_PORT`). The host is provided in `HOST` and `LOCALHOST_HOST`. Example:

```python
import os
from flask import Flask

app = Flask(__name__)

@app.get("/")
def home():
    return "Hello from Localhost Loader"

if __name__ == "__main__":
    app.run(host=os.environ.get("HOST", "127.0.0.1"),
            port=int(os.environ["PORT"]))
```

Python dependencies are installed into the package's private `site-packages`. Downloaded wheels are cached and can be mirrored into the selected permanent vault. Offline-only mode uses cached wheels and does not fall back to the network. Use pure-Python or Android-compatible wheel distributions; native Python packages built only for desktop Linux/Windows/macOS will not work on Android.

## 5. Node server package

```json
{
  "id": "my-node-app",
  "name": "My Node App",
  "version": "1.0.0",
  "runtime": "node",
  "runtime_version": "18.20.4",
  "entry": "server.js",
  "homepage": "/",
  "environment": {"APP_MODE": "production"}
}
```

Node code should bind to `127.0.0.1` and `process.env.PORT`:

```javascript
const http = require('http');
const host = process.env.HOST || '127.0.0.1';
const port = Number(process.env.PORT);
http.createServer((_req, res) => res.end('Hello from Localhost Loader'))
  .listen(port, host);
```

If `package.json` declares dependencies, include a `package-lock.json` or `npm-shrinkwrap.json` with lockfile version 2 or 3 and integrity metadata for the resolved packages. The loader downloads tarballs referenced by the lockfile, verifies each `integrity` value, and extracts them into `node_modules`; it does not run `npm install` or execute lifecycle scripts. Packages containing native addons must ship Android-compatible ABI/Node-ABI builds.

Supported Node runtime in the current source build: **18.20.4**, supplied by the Node.js for Mobile Apps build step. Node packages that require a different runtime version are rejected, not silently run on this version.

## 6. Developer README startup block

A manifest is preferred. When no manifest exists, the scanner can read a limited key/value block in README:

```markdown
<!-- LOCALHOST-LOADER:START -->
name=My Server
runtime=python
runtime_version=3.13
entry=server.py
homepage=/
<!-- LOCALHOST-LOADER:END -->
```

The marker alternative `[LOCALHOST-LOADER]` and heading `## HOW TO START LOADER` are also recognized. The README fallback is intentionally simple; structured JSON is the canonical format.

## 7. Runtime pack (`.llpack`) format

A runtime pack is a ZIP archive with `pack.json` and a `payload/` directory. `pack.json` lists every payload file with a SHA-256 digest:

```json
{
  "schema": 1,
  "id": "python-wheel-cache",
  "kind": "python-wheels",
  "version": "1.0.0",
  "files": [
    {
      "path": "packaging-25.0-py3-none-any.whl",
      "sha256": "REPLACE_WITH_64_CHARACTER_LOWERCASE_SHA256"
    }
  ]
}
```

The archive layout is:

```text
my-runtime-pack.llpack
├── pack.json
└── payload/
    └── packaging-25.0-py3-none-any.whl
```

Supported `kind` values:

| Kind | Install destination | Purpose |
|---|---|---|
| `python-wheels` | `runtime/python/3.13/wheelhouse/` | Offline Python wheel cache |
| `node-tarballs` | `runtime/node/tarballs/` | Offline npm package tarballs |
| `runtime-assets` | `runtime/packs/{id}/{version}/` | Versioned auxiliary runtime assets |

Every file must be listed and pass SHA-256 verification. Paths must be relative and cannot traverse outside the pack. Current `runtime-assets` are stored and versioned for future runtime expansion; they do not yet replace the Python or Node engine binaries.

## 8. Permanent vault layout

When a user chooses a shared Android folder, the loader maintains a structure similar to:

```text
LOCALHOST_LOADER_VAULT/
├── LOCALHOST_LOADER_VAULT.json
├── packages/
│   ├── pkg-{app-id}.zip
│   └── folder-{app-id}/
├── runtime/
│   ├── python/3.13/wheelhouse/
│   ├── node/tarballs/
│   └── packs/{pack-id}/{version}/
├── runtime-packs/
├── exports/
└── logs/
```

The shared vault is user-selected with Android's Storage Access Framework. It can preserve package source and downloaded dependency assets after the app is uninstalled. After reinstall, Android requires the user to reselect the folder and restore its content. Embedded runtime binaries are currently packaged in the APK, not loaded independently from this vault.

## 9. Package security and limits

- Package import requires explicit user trust before executable code launches.
- Package content hashes are used to ask again when the imported package changes.
- ZIP import: up to 12,000 entries and 500 MB expanded content; HTTPS package downloads capped at 300 MB.
- Runtime pack import: up to 18,000 entries and 750 MB expanded content.
- npm tarballs: up to 64 MB each, with extraction bounded to 256 MB per package.
- HTTPS-only package downloads and redirects; npm lockfile integrity is verified.
- Only safe relative paths are accepted. Symbolic links and archive links are rejected for npm package extraction.
- Android permissions and app sandbox access still apply. `permissions` is a declaration shown in the UI, not an Android permission grant mechanism.
