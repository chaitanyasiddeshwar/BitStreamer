# BitStreamer

A zero-transcode LAN media streamer: a tiny Windows server that serves a single media file
byte-for-byte over HTTP, and an Android TV client for Fire TV 4K sticks that discovers the
server, plays the file with hardware video decode, and **bitstreams the audio (Dolby Digital,
DD+, Atmos, DTS, LPCM) untouched over HDMI**.

## The one rule

**Never transcode, never remux, never decode audio in software.** The server serves raw file
bytes (HTTP with Range support). The client demuxes in ExoPlayer and hands compressed audio
frames directly to the HDMI sink via Android audio passthrough. If a change requires touching
media samples on the server, it is the wrong change.

## Repository layout

```
server/   Go media server (stdlib + one vendored MKV parser; single self-contained
          binary — bitstreamer.exe on Windows, bitstreamer on macOS/Linux)
client/   Android TV app for Fire TV (Kotlin, Media3/ExoPlayer, Gradle)
docs/     Project plan and design docs — read before implementing
```

Key docs:
- `docs/PROJECT_PLAN.md` — architecture, decisions, protocol spec, milestones, test plan
- `docs/AUDIO_PASSTHROUGH.md` — how audio bitstreaming works on Fire TV; read before touching
  any audio code in the client
- `docs/HDR_DOLBY_VISION.md` — what HDR/DV formats play on Fire TV, the Profile 7 FEL black-screen
  limitation, and the ffmpeg strip-to-HDR10 workaround the server prints
- `docs/MEDIA3.md` — what Media3 is, which of its features we use, and the vetted menu of
  features we could adopt (with passthrough-hazard warnings)
- `docs/REMOTE.md` — Fire TV remote buttons/keycodes, what's mappable, and the player's key map
- `docs/THUMBNAILS.md` — chapter thumbnails + scrubbing storyboard (ffmpeg sidecar, HDR tonemapping)

## Tech stack (decided — see PROJECT_PLAN.md for rationale)

- **Server**: Go, standard library only (`net/http` + `http.ServeContent` for Range
  serving, `net` for UDP discovery). No cgo, no third-party modules, no platform-specific
  code → develops and tests on any OS, cross-compiles to a dependency-free
  `bitstreamer.exe`. No media libraries at all — the server never parses the file.
- **Client**: Kotlin, androidx.media3 (ExoPlayer) 1.10.x, classic Views + Media3 `PlayerView`
  (built-in D-pad support), minSdk 25 (Fire OS 6 / Fire TV Stick 4K 1st gen), no Compose,
  no Leanback library.

## Build commands

Both builds output to a shared repo-root **`dist/`** so the server binary and `client.apk`
sit together (the server serves `client.apk` next to it at `/client.apk`). See README for
the full walkthrough.

Server (from `server/`, works on macOS/Linux/Windows):
```
make                # native binary -> dist/bitstreamer (or .exe on Windows)
make windows        # dist/bitstreamer.exe          (Windows x64, from any host)
make darwin         # dist/bitstreamer-macos        (universal arm64+x64, macOS host)
make test
# Windows without make: run build.bat. Or go directly: go build -o ../dist/bitstreamer .
```

Client (from `client/`; on Windows use `gradlew.bat`):
```
./gradlew assembleRelease   # or assembleDebug (Android Studio Run uses this)
# APK builds under client/app/build/outputs/apk/, then a Gradle copy step
# (copy{Release,Debug}ApkToDist) auto-copies it to dist/client.apk.
```

The APK→`dist/client.apk` copy is automatic; don't hand-copy. Override the served path with
`--apk <path>` if needed.

## Running

```
bitstreamer.exe "C:\Movies\film.mkv"
```
Prints the stream URL and the APK URL. First run: accept the Windows Firewall prompt (or
pre-add rules — see PROJECT_PLAN.md §Server). The client discovers the server via UDP
broadcast (port 46899); HTTP serves on 46898.

## Conventions

- Ports are fixed: **46898/TCP** (HTTP), **46899/UDP** (discovery). Don't make them dynamic;
  discovery and firewall rules depend on them.
- Discovery and `/info` payloads are versioned JSON (`"v": 1`). Additive changes only;
  bump `v` on breaking changes.
- Server code: Go stdlib only, with one vetted exception — the pure-Go, MIT
  `go-mkvparse` parser is **vendored** in `server/third_party/mkvparse/` (used by
  `chapters.go` to read MKV chapter markers; reason: EBML binary parsing is fiddly and a
  parser bug already bit us once on the client). It is stdlib-only itself, so the single
  static exe and cross-compile are unaffected. Any *further* dependency needs a written
  reason here. No global state beyond the single served-file config.
- Client: all playback configuration lives in one factory (`PlayerFactory`); never scatter
  ExoPlayer tweaks across activities. Log the negotiated audio pipeline on every playback
  start (see AUDIO_PASSTHROUGH.md §Verification).
- Client diagnostics go through `RemoteLog` (tees logcat + `POST /log` to the server,
  which appends to `client-logs.txt` next to the exe). Log every audio-pipeline decision
  there — it is the only visibility into the Fire TV without adb.
- DTS-HD plays via DTS-core extraction in `DtsCoreAudioSink` (AUDIO_PASSTHROUGH.md §7);
  don't "simplify" it to a plain `DefaultAudioSink`.
- Testing anything audio-related requires real hardware (Fire TV stick + AVR/TV that shows
  the incoming format). Emulators cannot verify passthrough — don't claim it works from an
  emulator run.
