# Localhost Loader — Android Local Runtime Platform

**Project version:** 1.0.0 beta  
**Package namespace:** `com.laaprak.localhostloader`  
**Application model:** Native Android app + embedded runtimes + private local gateway + integrated WebView

Localhost Loader is an Android-native implementation of the Localhost Loader vision: users import a portable server package, the loader inspects it, provisions a supported runtime and dependencies, starts the process as a managed local service, and opens the app by its name in the integrated browser. It does not depend on Termux and does not expose a terminal or port number in the normal UI.

This archive contains the complete current source project, build workflow, runtime integration code, package-format specification, and test packages. It is a substantial beta implementation, not a claim that every future ecosystem feature is finished.

## What is implemented in this source build

### User-facing Android app

- Native launcher with **Apps**, **Browser**, **Engine**, and **Store** areas.
- Import a ZIP package, import a project folder using Android's Storage Access Framework, or paste an HTTPS ZIP link.
- Automatic package inspection for `manifest.json`, `loader.json`, a structured README startup block, Python files, Node projects, static sites, `requirements.txt`, `package.json`, and lockfiles.
- Package integrity fingerprinting and an explicit **Trust & Open** prompt before executable code is run.
- App list with launch/stop/remove actions, running-app switching, an in-app browser, log viewing, and status reporting.
- Internal app routes use a stable app ID. The browser chrome displays the app name, not the server's ephemeral port.

### Runtime execution

- **Static web:** HTML, CSS, JavaScript, assets, and SPA fallback are served by a local loopback gateway.
- **Python:** CPython 3.13 is embedded through Chaquopy. Python server packages are started by calling the entry file directly; package dependencies are installed into an app-specific `site-packages` folder.
- **Node.js:** Node.js for Mobile Apps 18.20.4 is fetched by the build workflow and connected through a JNI bridge. Node lockfiles are processed by a deterministic installer which downloads exact package tarballs, verifies npm integrity values, and extracts into the app's `node_modules`.
- Python and Node services use separate Android process slots. The implementation currently provides up to four Python and four Node server slots; static sites share the gateway.
- The loader selects a free loopback port internally, registers it behind the gateway, and waits for the backend socket before showing the app as ready.
- A foreground-service notification is shown while long-running local servers are active. Android may stop services under resource pressure; the launcher detects a stale service record and attempts to restart the app when it is opened again.

### Dependencies, offline packs, storage, updates

- Python wheels are downloaded into a reusable wheelhouse and installed per app. Only Android-compatible wheels or pure-Python wheels can run in the embedded Android interpreter; source distributions are not compiled on-device.
- npm dependencies are taken from a v2/v3 `package-lock.json`; integrity hashes are checked and tarballs are cached for reuse.
- The Engine screen accepts `.llpack` archives with `pack.json` plus SHA-256-verified payload files. Supported pack kinds are `python-wheels`, `node-tarballs`, and `runtime-assets`.
- A user-selected shared folder acts as the **permanent vault** for package archives, imported folders, wheel caches, npm tarballs, `.llpack` archives, and runtime assets. The user can restore packages and caches from it.
- `UP&UPG` can check a user-configured HTTPS JSON catalogue immediately or on a 48-hour periodic schedule. Releases require SHA-256 values; version updates are offered for review and are not silently overwritten.
- Python package index and npm registry/mirror URLs are configurable; offline-only mode prevents network fallback for missing dependencies.
- Runtime/storage controls include vault attach/restore, pack import, cached runtime data management, catalogue settings, and cleanup of selected cached files.

## Included sample packages

| Sample | Runtime | Purpose |
|---|---|---|
| `samples/Hello-Local-App.zip` | Static | Fast first test of import and the internal browser |
| `samples/Python-Local-Server.zip` | Python 3.13 | Tests the embedded Python engine using only the standard library |
| `samples/Node-Local-Server.zip` | Node.js 18.20.4 | Tests the native Node mobile engine using only built-in modules |

Import a sample from the Apps screen. Executable packages display a trust prompt. The Node sample only runs in a build which includes the fetched Node native library.

## Build an Android APK

### Requirements

- Android Studio or a command-line Android build environment.
- JDK 17.
- Android SDK Platform 35, Build Tools 35.0.0.
- Android NDK 27.2.12479018 and CMake 3.22.1.
- Internet access during the first Gradle build to resolve AndroidX, Chaquopy, and native Node build inputs.

The project uses Android Gradle Plugin 8.7.3, Gradle 8.9, Chaquopy 17.2.0, and Python 3.13. Chaquopy 17.2 supports Python 3.10–3.15 and Android Gradle Plugin 7.3–9.5. See the [Chaquopy version summary](https://chaquo.com/chaquopy/doc/current/versions.html).

### Android Studio

1. Extract the ZIP and open the `localhost-loader-android` directory as an existing Gradle project.
2. Install the SDK/NDK/CMake versions listed above if prompted.
3. Allow Gradle to download the project plugins and dependencies.
4. Run the Node runtime fetch script once if Node support is required: `./scripts/fetch-node-mobile.sh`.
5. Build **app → Tasks → build → assembleDebug**, or use **Build → Build APK(s)**.
6. The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

On Windows, run the Node fetch step in WSL or Git Bash with `curl`, `jq`, and `unzip`, then build from Android Studio or a Gradle-enabled shell.

### GitHub Actions

The included workflow `.github/workflows/android-build.yml` sets up JDK/SDK/NDK, fetches the Node mobile libraries and builds an arm64-v8a + x86_64 debug APK. Push this source to a GitHub repository and run the **Build Localhost Loader Android APK** workflow. Download the resulting `localhost-loader-android-debug` artifact from the workflow run.

**Build result in this preparation environment:** no APK has been compiled here. This container does not have the Android SDK, AAPT2, D8/R8, NDK, or a local Gradle installation. The archive is the Android source project and its build pipeline; the CI workflow is the build route to an installable APK.

## App data and the permanent vault

Without a vault, packages and app-specific environments remain under the app's private storage and Android removes that data when the app is uninstalled. To keep imported package archives and runtime caches outside app-private storage, open **Engine → Choose Vault** and select a shared folder with Android's folder picker.

The vault stores files the loader can restore, including package archives/folders, Python wheelhouse files, npm tarballs, and runtime packs. This preserves these files across uninstall/reinstall. Android revokes the app's Storage Access Framework grant when the app is uninstalled, so after reinstall the user must select the vault folder again and press **Restore**. The embedded Python runtime and native Node library are currently supplied by the APK build; they are not independently bootstrapped from the vault yet.

## Package developer guide

See [`PACKAGE_FORMAT.md`](PACKAGE_FORMAT.md) for the package manifest schema, runtime launch contract, README fallback format, `.llpack` format, and restrictions.

## Security model

- Imported executable code requires a trust confirmation keyed to the package content hash. Changes to package contents trigger a new confirmation.
- ZIP and `.llpack` extraction rejects absolute paths, traversal paths, links, oversized archives, and unsafe output paths.
- Node tarballs require a supported lockfile and valid npm integrity metadata.
- Catalogue, package-index, and npm downloads require HTTPS and HTTPS-only redirects.
- Static and backend apps bind to loopback by default. The integrated browser reaches them through a random internal gateway route.
- The WebView disables file/content access, mixed content, and JavaScript-to-native bridging.
- This is an Android app sandbox, not a separate kernel container. An imported program runs with the permissions and access available to Localhost Loader's Android process. Only import packages from developers you trust.
- A visible Android foreground-service notification is used for active long-running services; this cannot be hidden as if it were a background process with no user-facing disclosure.

## Current beta scope and known boundaries

- One embedded Python version is currently bundled: **3.13**. One native Node version is currently supported: **18.20.4**. The loader rejects manifests asking for a different runtime version instead of silently substituting one. Parallel version-specific runtime installations are not yet wired into process launching.
- PHP, Java/Spring, Rust, compiled Linux binaries, arbitrary shell scripts, Docker images, and general-purpose Linux distributions are not supported runtimes in this build.
- Python dependency installation requires a compatible Android wheel or pure-Python wheel. Chaquopy's current pip integration is binary-wheel oriented; packages requiring native extensions may need Android-specific builds. See the [Chaquopy FAQ](https://chaquo.com/chaquopy/doc/current/faq.html).
- Node native addons are only usable if they are built for the Android ABI and compatible Node ABI. Standard JavaScript-only npm packages are the safer target.
- The catalogue is user-configured. There is no public marketplace service or central signed publisher identity service included.
- Catalogue signatures are not implemented; artifacts are checked against their catalogue-provided SHA-256 value. A trusted HTTPS catalogue is still important.
- Runtime packs of kind `runtime-assets` are stored and versioned but are not automatically loaded as alternate Python/Node interpreters.
- The Node mobile package currently uses Node.js 18.20.4. Verify Android 16 KB page-size alignment and native library policy before Play Store distribution; this build workflow is designed for development/debug sideload testing, not production store certification.

## Project structure

```text
app/src/main/java/                 Android launcher, scanner, vault and services
app/src/main/java/.../runtime/     Gateway, Python/Node workers, packs and updates
app/src/main/python/               Embedded Python launch/dependency helper
app/src/main/cpp/                  JNI bridge for Node mobile
app/src/main/AndroidManifest.xml   Android components and service declarations
samples/                           Static, Python and Node test packages
runtime-packs/                     Pack format examples
scripts/                           Node runtime fetch + source validation
.github/workflows/                 Android debug APK build
PACKAGE_FORMAT.md                  Developer package contract
BUILD_STATUS.txt                   Build and implementation status
```
