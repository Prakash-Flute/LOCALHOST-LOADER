# Device validation checklist

Run these checks on a physical arm64 Android device after building and installing the debug APK.

## First launch

- [ ] Open Localhost Loader and grant notification permission when prompted.
- [ ] Confirm Apps, Browser, Engine, and Store screens render and scroll on a phone-size display.
- [ ] Confirm runtime cards report embedded Python and Node runtime status correctly.

## Static package

- [ ] Import `samples/Hello-Local-App.zip`.
- [ ] Trust and open the sample.
- [ ] Verify the page loads inside the in-app browser.
- [ ] Check its JavaScript, CSS, and root-relative resources load.
- [ ] Switch away and back to the app.

## Python package

- [ ] Import `samples/Python-Local-Server.zip`.
- [ ] Trust and open it.
- [ ] Confirm the app reports Python version and returns JSON from `/api/info`.
- [ ] Open app logs and verify the entrypoint output is recorded.
- [ ] Stop and restart the app.

## Node package

- [ ] Build using the workflow which fetches Node mobile native binaries.
- [ ] Import `samples/Node-Local-Server.zip`.
- [ ] Confirm Node version appears as `v18.20.4` in the sample API.
- [ ] Stop and restart the app.

## Storage and offline operation

- [ ] Attach a shared folder from Engine → Choose Vault.
- [ ] Import one static package and one backend sample; verify package backup appears under `packages/`.
- [ ] Import an offline `.llpack` whose files have valid SHA-256 digests.
- [ ] Enable offline-only mode and launch a package whose wheel/tarball cache is already present.
- [ ] Uninstall/reinstall the app, reselect the same vault, press Restore, and verify package/cache data is restored.

## UP&UPG

- [ ] Configure a test HTTPS catalogue with schema 1 and SHA-256-protected artifacts.
- [ ] Tap Check Now and verify the catalogue is cached.
- [ ] Verify update entries appear for review and are not silently applied.
- [ ] Verify an HTTP catalogue or HTTP redirect is rejected.

## Process and lifecycle

- [ ] Start multiple Python/Node apps within the device's available resources.
- [ ] Confirm active services show Android foreground notifications.
- [ ] Background the app and return to it.
- [ ] Force-stop/reclaim a server process and confirm reopening the app detects and attempts restart.
- [ ] Confirm a manifest requesting an unsupported runtime version fails with a specific message.

## Release checks

- [ ] Repeat on arm64-v8a and x86_64 emulator if supported.
- [ ] Test Android 14 and Android 15/16 behavior for foreground service restrictions.
- [ ] Verify 16 KB page-size native library compatibility before distribution to modern Play-enabled devices.
- [ ] Produce a signed release build, test the signing/update path, and complete store policy review before public release.
