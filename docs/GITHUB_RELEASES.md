# BitStreamer Releases & Versioning Guide

This document describes how versioning is structured in BitStreamer, how releases are created, and how compiled binaries/APKs are published to GitHub Releases.

---

## 1. Versioning Architecture

BitStreamer uses **Semantic Versioning (SemVer)** (e.g. `1.0.0`). Both the Go media server and the Android TV client are versioned in lockstep.

### Server Version
- Defined in `server/version.go` as `const Version = "1.0.0"`.
- Printed in the console banner on startup: `BitStreamer v1.0.0 (build <commit> <timestamp>)`.
- Supports the `-version` flag to print version and exit.
- Included in the JSON payload under `"version"` when requesting the `/info` endpoint (in all modes: file, folder, and folder file details).
- Annouced to clients via UDP discovery packets under `"version"`.

### Client Version
- Defined in `client/app/build.gradle.kts` as `versionName = "1.0.0"`.
- Appended programmatically to the main title text in `DiscoveryActivity` launcher screen (`BitStreamer v1.0.0`).
- Sent automatically in client log payloads to the server, logged under `client version: 1.0.0` in the `client-logs.txt` diagnostic file.

---

## 2. Automated Releases (GitHub Actions)

We have set up an automated release workflow in [.github/workflows/release.yml](file:///n:/AI/ai_coder/BitStreamer/.github/workflows/release.yml).

### How to trigger a release:
Whenever you tag a commit with a version tag prefix `v` (such as `v1.0.0`) and push it to GitHub, the workflow triggers automatically:

1. **Tag the commit locally:**
   ```bash
   git tag v1.0.0
   ```
2. **Push the tag to GitHub:**
   ```bash
   git push origin v1.0.0
   ```

### What the workflow does:
- **Build Server Cross-Compiled Binaries (Ubuntu runner):**
  - Compiles the Windows x64 binary (`bitstreamer.exe`).
  - Compiles the Linux x64 binary (`bitstreamer-linux`).
- **Build Server macOS Universal Binary (macOS runner):**
  - Compiles Go binaries for Apple Silicon (`arm64`) and Intel (`amd64`).
  - Combines them using `lipo` into a single universal binary (`bitstreamer-macos`).
- **Build Client Android APK (Ubuntu runner):**
  - Installs JDK 17 and Android SDK.
  - Builds the release APK (`client.apk`) using Gradle, signed with the debug keystore for easy side-loading.
- **Publish Release:**
  - Aggregates all artifacts.
  - Creates a GitHub Release matching the tag.
  - Uploads the binaries (`bitstreamer.exe`, `bitstreamer-linux`, `bitstreamer-macos`, and `client.apk`) and `dist/USER_MANUAL.pdf` directly to the release.
  - Generates automatic release changelog notes.

---

## 3. Semi-Automated Releases (GitHub CLI)

If you prefer to build locally on your own machine and push releases from your command line, you can use the **GitHub CLI (`gh`)**:

1. **Build all binaries locally:**
   - **On Windows:** Run `build.bat` in the root directory.
   - **On macOS/Linux:** Run `./build.sh` in the root directory (optionally run `make all` in `server` first to build macOS and Windows binaries).
   - Verify the `dist/` directory holds your server binaries and `client.apk`.
2. **Tag your release locally:**
   ```bash
   git tag v1.0.0
   ```
3. **Run the GitHub CLI release command:**
   ```bash
   gh release create v1.0.0 dist/* --title "v1.0.0" --notes "Initial stable v1.0.0 release"
   ```
   *(Note: Add `--draft` at the end if you want to inspect/edit the release on the web page before publishing).*

---

## 4. Manual Releases (GitHub Web UI)

If you want to create the release entirely by hand using GitHub's website:

1. **Build all binaries locally:**
   - Compile the client and server binaries so that they are present in the `dist/` directory.
2. **Push the tag to GitHub:**
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. **Go to GitHub Web UI:**
   - Open your repository homepage on GitHub.
   - Click **Releases** on the right sidebar, then click **Draft a new release**.
   - Select **Choose a tag** and pick `v1.0.0`.
   - Enter `v1.0.0` as the **Release Title**.
   - Write your release description in the text box.
   - Drag and drop the following files into the attachment box:
     - `bitstreamer.exe` (from `dist/`)
     - `bitstreamer-macos` (or `bitstreamer` depending on host OS, from `dist/`)
     - `client.apk` (from `dist/`)
     - `USER_MANUAL.pdf` (compiled from `docs/USER_MANUAL.md`)
   - Click **Publish release**.
