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
server/   Go media server (pure stdlib, cross-compiles to a single bitstreamer.exe)
client/   Android TV app for Fire TV (Kotlin, Media3/ExoPlayer, Gradle)
docs/     Project plan and design docs — read before implementing
```

Key docs:
- `docs/PROJECT_PLAN.md` — architecture, decisions, protocol spec, milestones, test plan
- `docs/AUDIO_PASSTHROUGH.md` — how audio bitstreaming works on Fire TV; read before touching
  any audio code in the client

## Tech stack (decided — see PROJECT_PLAN.md for rationale)

- **Server**: Go, standard library only (`net/http` + `http.ServeContent` for Range
  serving, `net` for UDP discovery). No cgo, no third-party modules, no platform-specific
  code → develops and tests on any OS, cross-compiles to a dependency-free
  `bitstreamer.exe`. No media libraries at all — the server never parses the file.
- **Client**: Kotlin, androidx.media3 (ExoPlayer) 1.10.x, classic Views + Media3 `PlayerView`
  (built-in D-pad support), minSdk 25 (Fire OS 6 / Fire TV Stick 4K 1st gen), no Compose,
  no Leanback library.

## Build commands

Server (from `server/`, works on macOS/Linux/Windows):
```
go build -o bitstreamer .          # native binary for this machine
go test ./...
GOOS=windows GOARCH=amd64 go build -o bitstreamer.exe .   # Windows binary from anywhere
```

Client (from `client/`; on Windows use `gradlew.bat`):
```
./gradlew assembleRelease
# → client/app/build/outputs/apk/release/app-release.apk
```

Copy the built APK next to `bitstreamer.exe` as `client.apk` (or pass `--apk <path>`); the
server serves it at `/client.apk` for sideloading via the Fire TV "Downloader" app.

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
- Server code: Go stdlib only — adding a dependency needs a written reason in this file.
  No global state beyond the single served-file config; keep it small enough to audit in
  one sitting.
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
