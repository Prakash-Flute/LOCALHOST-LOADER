#!/usr/bin/env bash
set -euo pipefail

REPO="nodejs-mobile/nodejs-mobile"
TAG="v18.20.4"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 2; }

curl -fsSL --retry 8 --retry-all-errors --retry-delay 3 --retry-max-time 180 --connect-timeout 30 \
  -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/${REPO}/releases/tags/${TAG}" -o "$TMP/release.json"

mapfile -t ASSETS < <(jq -r '.assets[] | select(.name | ascii_downcase | endswith(".zip")) | .browser_download_url' "$TMP/release.json")
if [[ ${#ASSETS[@]} -eq 0 ]]; then
  echo "No ZIP assets were published for ${REPO}@${TAG}." >&2
  jq -r '.assets[].name' "$TMP/release.json" >&2 || true
  exit 3
fi

mkdir -p "$TMP/extracted"
for asset in "${ASSETS[@]}"; do
  filename="${asset##*/}"
  curl -fL --retry 8 --retry-all-errors --retry-delay 3 --retry-max-time 300 --connect-timeout 30 "$asset" -o "$TMP/$filename"
  unzip -oq "$TMP/$filename" -d "$TMP/extracted"
done

JNI="$ROOT/app/src/main/jniLibs"
HEADERS="$ROOT/app/src/main/cpp/vendor/node/include/node"
mkdir -p "$HEADERS"
for abi in arm64-v8a x86_64; do
  source="$(find "$TMP/extracted" -type f -path "*/$abi/libnode.so" -print -quit)"
  if [[ -z "$source" ]]; then
    source="$(find "$TMP/extracted" -type f -name libnode.so | grep -E "/${abi}/|${abi}" | head -n1 || true)"
  fi
  if [[ -z "$source" ]]; then
    echo "Node mobile release did not contain libnode.so for ${abi}." >&2
    find "$TMP/extracted" -type f | head -100 >&2
    exit 4
  fi
  mkdir -p "$JNI/$abi"
  cp "$source" "$JNI/$abi/libnode.so"
  libcxx="$(find "$TMP/extracted" -type f -path "*/$abi/libc++_shared.so" -print -quit)"
  if [[ -n "$libcxx" ]]; then
    cp "$libcxx" "$JNI/$abi/libc++_shared.so"
  else
    ndk_root="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk/27.2.12479018}}"
    case "$abi" in
      arm64-v8a) ndk_abi="aarch64-linux-android" ;;
      x86_64) ndk_abi="x86_64-linux-android" ;;
      *) echo "Unsupported ABI: $abi" >&2; exit 6 ;;
    esac
    candidate="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$ndk_abi/libc++_shared.so"
    if [[ ! -f "$candidate" ]]; then
      echo "libc++_shared.so not found for $abi (checked $candidate)." >&2
      exit 6
    fi
    cp "$candidate" "$JNI/$abi/libc++_shared.so"
  fi
done

header_root="$(find "$TMP/extracted" -type f -name node.h -printf '%h\n' | grep '/include/node$' | head -n1 || true)"
if [[ -z "$header_root" ]]; then
  header_root="$(find "$TMP/extracted" -type f -name node.h -printf '%h\n' | head -n1 || true)"
fi
if [[ -z "$header_root" ]]; then echo "Node headers were not found in release asset." >&2; exit 5; fi
cp -a "$header_root"/. "$HEADERS"/

echo "Installed Node.js for Mobile Apps ${TAG} native files for arm64-v8a and x86_64."
echo "Node.js core release: https://github.com/${REPO}/releases/tag/${TAG}"
