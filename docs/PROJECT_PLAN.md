# BitStreamer — Project Plan

Status: **v1 shipped and verified end-to-end on hardware** (Windows PC → Fire TV Stick
4K Max → AVR). All milestones below are complete, including DTS-HD playback via DTS core
extraction, remote client diagnostics, resume, and subtitle/audio track selection. This
document remains the reference spec; deltas discovered during implementation are folded
in below and in AUDIO_PASSTHROUGH.md.

---

## 1. Goal

Play a local movie file from a Windows PC on a Fire TV Stick 4K, with:

- **Video as-is**: the original H.264/HEVC/AV1/VP9 stream hardware-decoded on the stick.
- **Audio as-is**: the original compressed audio (Dolby Digital, Dolby Digital Plus,
  Dolby Atmos, DTS, or LPCM) **bitstreamed over HDMI** to the TV/AVR, which does the
  decoding. No transcoding, no remuxing, no software audio decode — ever.
- Dead-simple operation: run one exe with a file path on Windows; the Fire TV app finds it
  on the Wi-Fi network automatically and plays with normal remote controls
  (play/pause, stop, seek forward/back).

### Non-goals (v1)

- No library/multiple files (one file per server run; switching files = restart the exe).
- No transcoding fallback of any kind. If the sink can't take a format and the device can't
  hardware-decode it, we surface a clear error instead (see §7 risk R3).
- No subtitles in v1 (planned v1.1 — ExoPlayer already extracts SRT/ASS/PGS from MKV; UI only).
- No internet/remote access, no auth, no HTTPS. LAN-only tool, trusted network.
- Windows is the only *supported* server target, but the Go server is pure stdlib and
  runs identically on macOS/Linux — used for development and testing on the Mac.

---

## 2. Architecture

```
┌──────────── Windows PC ────────────┐        ┌────────── Fire TV Stick 4K ──────────┐
│  bitstreamer.exe                   │        │  BitStreamer client (Media3)         │
│                                    │        │                                      │
│  UDP 46899  ◄── discovery probe ───┼────────┼── broadcast "who's serving?"         │
│             ─── JSON reply ────────┼──────► │                                      │
│                                    │        │                                      │
│  TCP 46898 (HTTP)                  │        │  ExoPlayer                           │
│    GET /info      (media metadata) │◄───────┼── DefaultHttpDataSource              │
│    GET /stream    (Range bytes)    │────────┼─► MKV/MP4 extractor (demux only)     │
│    GET /client.apk                 │        │     ├─ video → MediaCodec → HDMI     │
│    GET /          (status page)    │        │     └─ audio → AudioTrack            │
└────────────────────────────────────┘        │          (IEC61937 passthrough)──► HDMI ─► AVR/TV
                                              └──────────────────────────────────────┘
```

The insight that keeps this simple: **"bitstreaming" requires nothing special from the
server.** ExoPlayer plays MP4 and MKV progressively over plain HTTP, using Range requests
to seek. Demuxing happens on the client. So the server is a static byte server + discovery
beacon — no media code on the server at all. All of the real work (and risk) is in the
client's audio path, which is why it gets its own doc (`AUDIO_PASSTHROUGH.md`).

Precedent: this is exactly the "direct play" path of Emby/Jellyfin/Plex, minus their
transcoding machinery. Their Android TV clients use ExoPlayer the same way; their failure
modes (see [jellyfin-androidtv #1753](https://github.com/jellyfin/jellyfin-androidtv/issues/1753),
[#5168](https://github.com/jellyfin/jellyfin-androidtv/issues/5168)) come almost entirely
from *server-side* decisions to transcode when capability detection is wrong. We have no
transcoder, so our only job is getting client capability handling right.

---

## 3. Decisions and rationale

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Server in **Go** (stdlib only) | User priority is lightweight + the code is written on macOS and deployed on Windows. Go's `http.ServeContent` implements Range/206 semantics correctly for free (the hardest part of the server), `net` covers UDP discovery, and `GOOS=windows go build` cross-compiles a single dependency-free `bitstreamer.exe` (~8 MB) straight from the Mac — no toolchain needed on the Windows machine. No media APIs needed — the server never opens the file as media, only as bytes. *(Supersedes the original C++/cpp-httplib choice, which was made when Windows media APIs seemed potentially necessary; research showed they aren't.)* |
| D2 | **No remuxing on the server** | ExoPlayer's `MatroskaExtractor`/`Mp4Extractor` demux MKV/MP4 natively over HTTP+Range. Remuxing would add a media framework dependency and a whole class of bugs for zero benefit. |
| D3 | Discovery via **custom UDP broadcast** (not mDNS/SSDP) | ~40 lines on each side, trivially debuggable with netcat. Emby uses the same pattern (UDP 7359 "who is EmbyServer?"). mDNS (`DnsServiceRegister`, Win10 1809+) is the fallback if broadcast proves unreliable on some routers — isolate discovery behind an interface so it's swappable. |
| D4 | Fixed ports **46898/TCP + 46899/UDP** | Fixed ports make discovery replies verifiable, firewall rules one-time, and URLs predictable for the Downloader app. |
| D5 | Client in **Kotlin + Media3 ExoPlayer 1.10.x** (latest stable, [releases](https://developer.android.com/jetpack/androidx/releases/media3)) | Media3 is the maintained ExoPlayer line; 1.10.x has mature E-AC3-JOC passthrough, DTS-HD extraction fixes, and `onAudioTrackInitialized` for verifying the negotiated pipeline. |
| D6 | **Passthrough via Media3 defaults**, not custom renderers | Media3's `MediaCodecAudioRenderer` + `DefaultAudioSink` already prefer passthrough when the HDMI sink advertises the encoding. Our job is: don't break it (no ffmpeg extension, no playback-speed processing on the audio path), detect capabilities correctly on Fire OS, and verify. See AUDIO_PASSTHROUGH.md. |
| D7 | Classic **Views + Media3 `PlayerView`**, no Compose, no Leanback | `PlayerView`'s controller is D-pad aware out of the box. Two screens total. Compose-for-TV and Leanback add weight for zero v1 benefit. |
| D8 | **minSdk 25, targetSdk 34** | Fire TV Stick 4K (1st gen, still common) runs Fire OS 6 = Android 7.1 (API 25). 4K Max runs Fire OS 7/8 (API 28/30). API 25 keeps all of them. |
| D9 | Server serves the **client APK** at `/client.apk` | Sideload flow: install "Downloader" on the Fire TV, enter `http://<pc-ip>:46898/client.apk`. No USB, no adb needed for end users. |

---

## 4. Component spec — server (`server/`)

### CLI

```
bitstreamer.exe <media-file> [--port 46898] [--name <display-name>] [--apk <path-to-apk>]
```

On start, print: resolved LAN IPv4(s), stream URL, APK URL, and "waiting for client…".
Log one line per request (`GET /stream bytes=0- → 206`) and client connect/disconnect.
Exit on Ctrl+C. If the file doesn't exist or isn't readable: clear error, exit 1.

### HTTP endpoints (TCP 46898)

| Endpoint | Behavior |
|----------|----------|
| `GET /info` | JSON: `{"v":1, "name":"<display-name>", "file":"film.mkv", "size":123456789, "mime":"video/x-matroska"}` |
| `GET /stream` | The file bytes. **Must** support: `Accept-Ranges: bytes`, single-range `Range` requests → `206` with correct `Content-Range`, full requests → `200`, `HEAD`. Correct `Content-Type` by extension (`.mp4`→`video/mp4`, `.mkv`→`video/x-matroska`, `.mov`→`video/quicktime`, else `application/octet-stream`) — set explicitly; don't rely on OS mime tables for `.mkv`. Implemented with `http.ServeContent` (streams from the open file, never loads it into memory). |
| `GET /client.apk` | The APK (`application/vnd.android.package-archive`). Default path: `client.apk` next to the exe; overridable with `--apk`. 404 with a helpful body if missing. |
| `POST /log` | Client diagnostics channel: appends the plain-text body to `client-logs.txt` next to the exe (`--clientlog` to override), with a timestamp/remote-addr header per batch. The Fire TV client's `RemoteLog` batches its playback logs here so issues can be diagnosed from the PC without adb. |
| `GET /info` (chapters) | `/info` also carries `"chapters": [{"startMs":N,"name":"…"}]` parsed from the MKV at startup (vendored `go-mkvparse`, default edition, hidden chapters skipped) and `"thumbnails": bool` (true when ffmpeg is available). The client draws seek-bar ticks, a chapter selector, and — when thumbnails are on — a preview per chapter. |
| `GET /chapter-thumb?index=N` | JPEG thumbnail for chapter N, generated via the ffmpeg sidecar (pre-warmed at startup) and cached to disk. 404 when ffmpeg is absent or the index is invalid. See [THUMBNAILS.md](THUMBNAILS.md). |
| `GET /storyboard.json` · `GET /storyboard?sheet=N` | Scrubbing-preview manifest + sprite sheets, generated at startup (interval = `--interval` secs, default 30) into a per-session cache cleared on shutdown. Manifest 404s until generation finishes. See [THUMBNAILS.md](THUMBNAILS.md). |
| `GET/POST /position` | Resume support, keyed by client IP. `POST ?ms=N` stores the playback position (client heartbeats every 5 s and reports on stop; `ms=0` clears — sent when playback finishes). `GET` returns `{"v":1,"file":…,"positionMs":N}`; the client offers "Resume from X / Start from beginning" when N ≥ 10 s. Persisted to `resume.json` next to the exe (`--resumefile`), invalidated automatically when the server is started with a different file. |
| `GET /` | Minimal HTML status page: file name/size, stream URL, APK URL. Sanity-check target for a browser. |

Range support is the part to get right: **seeking on the client is entirely dependent on
correct 206 responses.** ExoPlayer will issue `Range: bytes=N-` on every seek (MKV cues /
MP4 moov tell it where to land). Test with `curl -r` before ever involving the client.

### UDP discovery responder (UDP 46899)

- Bind `0.0.0.0:46899`. On receiving a datagram whose payload is exactly
  `BITSTREAMER_DISCOVER_V1`, reply (unicast, to sender addr:port):
  `{"v":1, "app":"bitstreamer", "name":"<display-name>", "httpPort":46898}`.
- Ignore anything else silently. Stateless, one thread.

### Build & Windows specifics

- Pure Go stdlib, no cgo, no third-party modules. LAN IPs via `net.Interfaces()` —
  fully cross-platform, so there is no platform-specific code at all.
- Develop/test anywhere: `go build && go test ./...` in `server/`.
  Windows binary from any machine: `GOOS=windows GOARCH=amd64 go build -o bitstreamer.exe .`
- Firewall: first run on Windows triggers the Firewall prompt (allow on **Private**
  networks). Document a `netsh advfirewall firewall add rule` one-liner in the README for
  silent setup. This is the #1 predicted support issue — the status page + `curl` test
  exists to triage it.

### Server file layout

```
server/
  go.mod
  main.go          # CLI parsing, wiring, lifecycle
  server.go        # HTTP endpoints, range serving via http.ServeContent
  discovery.go     # UDP responder
  netinfo.go       # LAN IPv4 enumeration
  *_test.go        # httptest-based range/endpoint tests, discovery round-trip test
```

---

## 5. Component spec — client (`client/`)

### Manifest / packaging

- `LEANBACK_LAUNCHER` intent category, `android.software.leanback` required=false,
  `android.hardware.touchscreen` required=false, TV banner (320×180).
- Permissions: `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`
  (acquire `MulticastLock` before UDP broadcast receive — many Android builds drop
  broadcast/multicast without it).
- Release build signed with a plain keystore checked into nowhere (document creation);
  Fire TV sideload doesn't care about the signer, but upgrades need a stable key.

### Screens

1. **DiscoveryActivity** (launcher): sends `BITSTREAMER_DISCOVER_V1` to
   `255.255.255.255:46899` **and** the subnet-directed broadcast (computed from
   `WifiManager`/`ConnectivityManager` LinkProperties), 3 probes × 1 s timeout.
   Shows found servers (name + file from `/info`) in a focusable list; also a manual
   IP entry row (fallback when broadcast is filtered) and a Retry button.
   One result → auto-navigate after a beat.
2. **PlayerActivity**: full-screen Media3 `PlayerView`.
   - `MediaItem.fromUri("http://<ip>:46898/stream")`.
   - Controls (Fire TV remote): play/pause (center + play/pause key), FF/RW keys and
     D-pad left/right → seek (back 10 s, forward 30 s via
     `setSeekBackIncrementMs`/`setSeekForwardIncrementMs`). Back dismisses the controller
     overlay if it is visible; Back on the clean movie frame stops & exits. Menu toggles
     the audio debug overlay.
   - Track selection: the controller's settings button switches audio tracks; the CC
     button switches subtitle tracks (`show_subtitle_button` — MKV-embedded
     SRT/ASS/PGS are extracted and rendered by Media3 automatically).
   - Resume: on open the client fetches `GET /position`; ≥10 s stored → dialog
     "Resume from X / Start from beginning". While playing it heartbeats the position
     to `POST /position` every 5 s and reports a final position on stop (0 when
     playback finished, clearing the entry).
   - `keepScreenOn`, release player in `onStop`.
   - Error surface: on playback error show codec/track info and the reason, not a toast;
     everything is also mirrored to the server via `RemoteLog` (`POST /log`).

### Player construction (`PlayerFactory` — the load-bearing class)

- `DefaultRenderersFactory` with `EXTENSION_RENDERER_MODE_OFF` (no software decoders that
  could silently win over passthrough) and decoder fallback enabled.
- Do **not** enable `setEnableAudioTrackPlaybackParams` (speed change is incompatible with
  passthrough; we don't need it).
- `AudioAttributes`: `USAGE_MEDIA` / `CONTENT_TYPE_MOVIE`, `handleAudioFocus = true`.
- Attach an `AnalyticsListener`; on `onAudioTrackInitialized` log/store the
  `AudioTrackConfig` (encoding, sample rate, channel config) → this is the ground truth
  for "is it actually bitstreaming" (debug overlay shows it; see AUDIO_PASSTHROUGH.md §5).
- Track selection: prefer the highest-priority audio track the sink supports *as
  passthrough*; expose a track-selection dialog (Media3's built-in
  `TrackSelectionDialogBuilder` is fine) for multi-audio files.

### Client file layout

```
client/
  settings.gradle.kts, gradle/, gradlew.bat …
  app/
    src/main/
      AndroidManifest.xml
      java/…/bitstreamer/
        discovery/DiscoveryClient.kt     # UDP probe/parse, MulticastLock
        discovery/ServerApi.kt           # /info, /position client
        logging/RemoteLog.kt             # tees logcat + POST /log to the server
        playback/PlayerFactory.kt        # ALL ExoPlayer config lives here
        playback/DtsCoreAudioSink.kt     # DTS-HD -> DTS core extraction (AUDIO_PASSTHROUGH.md §7)
        playback/AudioCaps.kt            # Fire OS capability detection (see AUDIO_PASSTHROUGH.md)
        ui/DiscoveryActivity.kt
        ui/PlayerActivity.kt
      res/…                              # banner, layouts, strings
```

---

## 6. Milestones

**All complete** (verified on a Fire TV Stick 4K Max against a real AVR, July 2026).
Kept for reference; each milestone had an acceptance test.

| M | Deliverable | Acceptance |
|---|-------------|------------|
| **M1** | Server: HTTP `/stream` with Range + `/info` + `/` | From another machine: `curl -I`, `curl -r 1000-2000` returns correct 206/`Content-Range`; VLC on a laptop plays the URL and seeks. |
| **M2** | Server: UDP discovery + `/client.apk` + firewall docs | `nc -u <ip> 46899` probe gets the JSON reply from across the LAN; browser downloads the APK. |
| **M3** | Client: discovery screen | Fire TV stick finds the server on real Wi-Fi in <3 s; manual IP fallback works. |
| **M4** | Client: playback of MP4 (H.264 + AAC) with all controls | Play/pause/seek/back all work from the remote; seeks land in <2 s. |
| **M5** | **Audio passthrough** (the point of the project) | Test matrix of §7 passes: AVR/TV front panel shows *Dolby Digital*, *Dolby Digital Plus*, *Atmos*, *DTS* for the respective test files; debug overlay shows the passthrough encoding; no PCM 2.0 downmixes where passthrough is expected. |
| **M6** | Polish: errors, multi-audio track selection, resume-on-reconnect, README | A non-developer can go from `bitstreamer.exe film.mkv` + Downloader app to watching, using only the README. |

Suggested order of implementation within a session: M1→M2 (server is a day), then M3→M4,
then M5 (budget the most time here — it's hardware-in-the-loop debugging).

M1/M2 are OS-independent and were built and tested on macOS; on the Windows machine they
only need a cross-compile + firewall check, not a rebuild-from-source workflow.

---

## 7. Test plan & risks

### Passthrough test matrix (M5)

Prepare these files (rip/remux with MKVToolNix; keep them short, ~1 min):

| File | Expect on AVR/TV display | Notes |
|------|--------------------------|-------|
| MKV, HEVC + **AC3 5.1** | Dolby Digital | Baseline — works on every Fire TV |
| MKV, HEVC + **E-AC3 5.1** | Dolby Digital Plus | |
| MKV, HEVC + **E-AC3-JOC (Atmos)** | Dolby Atmos | Needs Atmos-capable sink + Fire TV surround setting "Best Available" |
| MP4, H.264 + **AAC stereo** | PCM (decoded) | Control case — AAC is *supposed* to decode to PCM |
| MKV, HEVC + **DTS 5.1** | DTS | |
| MKV, HEVC + **DTS-HD MA** | DTS (core) — see R2 | |
| MKV, HEVC + **TrueHD/Atmos** | Works only on 4K Max 2nd gen — see R2 | |
| MKV, **LPCM stereo** | PCM | Multichannel LPCM will downmix on sticks — document |

Also test: seek during playback ×10, pause >5 min then resume, server killed mid-play
(client shows error, not hang), Wi-Fi drop and reconnect.

### Risks

- **R1 — Capability detection lies on Fire OS.** Fire OS has its own detection quirks
  (surround-sound setting, `ACTION_HDMI_AUDIO_PLUG` extras, Fire OS 6 Atmos flag).
  Mitigation: implement detection per Amazon's
  [Dolby integration guidelines](https://developer.amazon.com/docs/fire-tv/dolby-integration-guidelines.html)
  (detailed in AUDIO_PASSTHROUGH.md), and always show the negotiated pipeline in the
  debug overlay so failures are diagnosable.
- **R2 — Lossless formats are platform-capped, not app-fixable.** Fire TV sticks pass
  DTS-HD as **DTS core** in normal apps (only Kodi's IEC-packing achieves full DTS-HD MA);
  TrueHD passthrough exists only on the 4K Max 2nd gen. This is a hardware/OS ceiling
  shared by Emby/Jellyfin/Plex. DTS-HD → DTS-core is now **implemented** in the client
  (`DtsCoreAudioSink`, see AUDIO_PASSTHROUGH.md §7); the remaining lossless gap
  (full MA/TrueHD) stays an accepted limitation.
- **R3 — Unplayable audio track** (e.g. TrueHD file, no passthrough, no device decoder).
  With no transcoder there is no rescue. Mitigation: detect at track selection, show
  "audio format X not supported by this device/sink — pick another audio track", and prefer
  a playable track automatically when the file has several.
- **R4 — UDP broadcast blocked** (AP isolation, guest networks). Mitigation: manual IP
  entry (M3) is a first-class UI path, not a hidden setting; mDNS is the designed-in
  plan B (D3).
- **R5 — MKV without cues seeks poorly** (rare, unfinalized files). ExoPlayer falls back
  to approximate seeking; accept, document in README ("remux with mkvmerge to fix").

---

## 8. Shipped beyond the original v1 scope

- **DTS-HD playback via DTS core extraction** (`DtsCoreAudioSink`) — AUDIO_PASSTHROUGH.md §7
- **Remote client diagnostics** (`RemoteLog` → `POST /log` → `client-logs.txt`)
- **Resume where you left off** (per client IP, `resume.json`, cleared on file switch)
- **Subtitle track selector** (controller CC button; MKV SRT/ASS/PGS)
- **TV-correct Back behavior** (dismiss controller overlay before exiting)
- **MKV chapters**: server parses markers (`chapters.go` + vendored `go-mkvparse`), client
  shows seek-bar ticks and a chapter selector with on-device thumbnails
  (`ChapterThumbnailLoader`, Option A — `MediaMetadataRetriever` on the stick)
- **D-pad controller redesign**: time bar is default focus (left/right scrubs), Up →
  play/pause, Down → Audio/Subtitles/Chapters icon row (custom `player_controls.xml`)
- **Chapter thumbnails via ffmpeg sidecar** (`thumbnails.go` + `/chapter-thumb`), after
  on-device extraction proved impossible on Fire TV; see [THUMBNAILS.md](THUMBNAILS.md)
- **Scrubbing previews** (`storyboard.go` sprite sheets + `/storyboard*`, client scrub
  overlay), `--interval` secs (default 30), which is also the client seek-bar step

## 9. Future candidates

- Multiple files / tiny queue, server tray icon, mDNS discovery, Linux/macOS server build.
- Player-side ideas (voice control via MediaSession, tunneling, Cast, subtitle styling,
  playlists): curated menu in [MEDIA3.md](MEDIA3.md).

## References

- [Amazon: Dolby integration guidelines (Fire TV)](https://developer.amazon.com/docs/fire-tv/dolby-integration-guidelines.html)
- [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3) ·
  [ExoPlayer supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats)
- Prior art on Fire TV passthrough behavior: [Kodi forum — 4K Max passthrough](https://forum.kodi.tv/showthread.php?tid=375681),
  [AVS Forum — 4K Max 2nd gen](https://www.avsforum.com/threads/fire-tv-stick-4k-max-2nd-gen-2023-16gb.3284411/page-19),
  [jellyfin-androidtv #1753](https://github.com/jellyfin/jellyfin-androidtv/issues/1753)
