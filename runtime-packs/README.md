# Offline runtime packs

A `.llpack` is a ZIP archive with `pack.json` and a `payload/` directory. Every payload file must have a SHA-256 value in the manifest. See `PACKAGE_FORMAT.md` for the full contract.

The installer accepts three kinds:

- `python-wheels`: cache of Android-compatible wheels for embedded Python 3.13.
- `node-tarballs`: npm tarballs verified against package-lock integrity strings.
- `runtime-assets`: versioned auxiliary data stored for future runtime expansion. It does not replace the current interpreter binary.

The example pack `sample-runtime-assets-1.0.0.llpack` is only a safe test of format parsing and checksum validation; it is not a language runtime.
