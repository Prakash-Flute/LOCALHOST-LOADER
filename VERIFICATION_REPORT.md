# Localhost Loader — verification report

Generated: 2026-11-06

## Checks completed in this preparation environment

| Check | Result | Scope |
|---|---|---|
| Offline source validator | PASS (44 checks) | Manifest, package format, project structure and expected components |
| Python syntax compilation | PASS | Embedded Python helper, Python sample server and validator |
| Shell syntax | PASS | Node mobile fetch script and build script |
| Android XML parsing | PASS | App manifest, theme, network security config and launcher drawable |
| Python sample HTTP smoke test | PASS | Host CPython 3.13.5; `/api/info` returned valid JSON |
| Node sample HTTP smoke test | PASS | Host Node.js 22.16.0; `/api/info` returned valid JSON |
| Java syntax scan | No parser/syntax diagnostics found | Android/AndroidX/Chaquopy libraries are not installed here, so full type checking is unavailable |
| Android APK build | NOT RUN | Android SDK, Build Tools, NDK, CMake and Gradle executable are absent from this environment |

The host smoke tests confirm the sample server source can start and answer HTTP requests. They do **not** prove that the Android-embedded Python interpreter, native Node library, Android services, or WebView have been run on a device. Those require the Android build and device test plan.

## Functionality represented in this source beta

- Native Android launcher with app list, package import, browser, runtime and update screens.
- Static web serving and an internal loopback gateway with name-based routes.
- Python 3.13 embedding through Chaquopy, app-scoped dependency folders and Android-wheel cache handling.
- Node.js for Mobile Apps 18.20.4 build-time native integration, plus lockfile/tarball cache installation.
- ZIP, SAF folder and HTTPS package-link imports, package scanning, manifest parsing and README startup hints.
- Package content hashing and a trust prompt before executing imported code.
- Background runtime services, process slots and persistent notifications.
- User-selected external vault for package archives, caches and runtime pack payloads.
- `.llpack` import with path safety and SHA-256 verification.
- UP&UPG catalogue checks on demand and every 48 hours, with manual review before applying updates.
- Static, Python and Node sample packages.

## Not complete in this beta

- Side-by-side selectable Python and Node runtime versions.
- Executable interpreter binaries independently stored and reloaded from the vault after APK uninstall.
- Automatic activation of alternate runtime binaries from `runtime-assets` packs.
- PHP, Java/Spring, Rust, arbitrary Linux binaries, Docker images or a general Linux distribution.
- Full marketplace service, publisher identity, catalogue signing and staged rollout backend.
- Full Android-device and Android-version compatibility test coverage.
- Production release signing and Play Store qualification.

## Build output

The included GitHub Actions workflow is configured to fetch Node.js for Mobile Apps 18.20.4 and build a Debug APK for `arm64-v8a` and `x86_64`. It must be run in a GitHub Actions environment or a local machine with the documented Android toolchain. No compiled APK is included in this ZIP.
