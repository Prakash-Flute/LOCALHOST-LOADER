# Localhost Loader — Android architecture map

## App lifecycle

```text
Import ZIP / folder / HTTPS link
              |
              v
       Package staging
              |
              v
   manifest + project scanner
              |
              v
     package hash / trust
              |
              v
     runtime dependency prep
         /       |       \
     static    Python     Node
       |         |         |
       |    Chaquopy 3.13  libnode 18.20.4
       |         |         |
       +---------+---------+
                 |
                 v
      loopback backend / static root
                 |
                 v
        named app gateway route
                 |
                 v
       integrated Android WebView
```

## Android components

- `MainActivity`: launcher, importer, package scanner, app registry, trust prompt, browser shell, storage/runtime controls, catalogue UI.
- `LocalGatewayService`: starts and owns the single loopback gateway.
- `LocalGatewayServer`: serves static content and reverse-proxies backend HTTP requests. The server port is internal. Root-relative asset/API requests are routed to the currently selected app.
- `PythonRuntimeService[1-4]`: independent service processes hosting the embedded CPython interpreter and app-specific Python environments.
- `NodeRuntimeService[1-4]`: independent service processes invoking Node.js Mobile through JNI.
- `RuntimeServiceSlots`: assigns a process slot to each active app/runtime.
- `NpmLockInstaller`: downloads exact tarballs from an npm lockfile, verifies integrity, and extracts without npm lifecycle hooks.
- `RuntimePackInstaller`: validates and installs `.llpack` content only into predefined cache locations.
- `PersistentVault`: copies package and cache data into a user-authorized Android shared folder and restores it later.
- `RuntimeUpdateWorker`: checks the configured HTTPS catalogue every 48 hours when connected.
- `ll_runtime.py`: Python dependency download/install and Python entrypoint runner.

## Data separation

- Imported source packages are extracted under the app's private `files/packages/` directory.
- Per-app Python packages are installed under `files/runtime/python/3.13/apps/{app-id}/site-packages/`.
- Reusable Python wheels are cached under `files/runtime/python/3.13/wheelhouse/`.
- npm tarballs are cached under `files/runtime/node/tarballs/`.
- Node app dependencies are installed in the app package's `node_modules/`.
- Logs are stored under `files/logs/{app-id}.log`.
- A chosen SAF vault mirrors package source and reusable assets to shared storage. The embedded interpreter and native Node library remain part of the APK in this release.

## Current process capacity

Four isolated Android service processes are declared for Python and four for Node. This is a slot cap in the current code, not a promise that a given handset can keep all eight CPU-heavy services active simultaneously. Android can still reclaim services under memory, power, or thermal pressure. The foreground service notification communicates that user-started local services are active.

## Routing contract

The integrated browser opens a named path such as `/app/{id}/`. The gateway forwards requests to the selected static package or local backend. Assets and API calls requested from the origin root are resolved against the currently selected app. The browser toolbar shows the app name and does not show the ephemeral gateway/backend ports.

## Safety boundaries

- No arbitrary shell command execution is exposed.
- Python/Node server entrypoints run after the trust prompt.
- The Python environment and Node modules remain inside Localhost Loader's Android app sandbox.
- Imported packages do not receive an independent kernel/container UID. They run with the permissions and file access available to the host app.
- The local gateway binds to `127.0.0.1`; it is not designed as a public LAN server in this beta.
