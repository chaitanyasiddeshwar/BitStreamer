#!/usr/bin/env bash
#
# Build both the BitStreamer server and the Fire TV client into dist/.
#
#   ./build.sh            server = native binary for this machine
#   ./build.sh windows    server = Windows x64 exe (cross-compiled)
#   ./build.sh darwin     server = universal macOS binary (arm64 + x64)
#
# Result: dist/ holds the server binary and client.apk side by side — run the
# server from there and it serves the APK at /client.apk for the Downloader.
# (Windows without bash: use build.bat.)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:-build}"

echo "==> Building server ($TARGET) -> dist/"
make -C "$ROOT/server" "$TARGET"

echo "==> Building client (assembleRelease) -> dist/client.apk"
( cd "$ROOT/client" && ./gradlew assembleRelease )

echo
echo "==> Build complete. dist/:"
ls -1 "$ROOT/dist"
