#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle was not found. Open this folder in Android Studio or install Gradle 8.9+ and run this script again." >&2
  exit 2
fi
if [[ ! -s app/src/main/jniLibs/arm64-v8a/libnode.so || ! -s app/src/main/cpp/vendor/node/include/node/node.h ]]; then
  echo "Preparing the Node.js mobile runtime bundle…"
  ./scripts/fetch-node-mobile.sh
fi
gradle --no-daemon :app:assembleDebug
printf '\nAPK output: app/build/outputs/apk/debug/app-debug.apk\n'
