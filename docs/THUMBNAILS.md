# Chapter & Seekbar Thumbnails — Architecture & Implementation Plan

> [!NOTE]
> Single-file chapter thumbnails and scrubbing previews (storyboards) are implemented. This document details the updated architectural plan for **Default Video Folder-Mode Thumbnails**, **Immediate `ffprobe` Metadata Return**, the new **`--no-caching` Server Flag**, and **100% Persistent Disk Caching** across all server runs.

---

## 1. Feature Overview & Goals

1. **Default Operation for Video Files**:
   - **Automatic Generation**: Every video file playback action (single-file mode, single-folder mode, or multi-root folder mode) automatically extracts chapters via `ffprobe` AND generates chapter thumbnail JPEGs and seekbar storyboard sprite sheets via `ffmpeg`.
   - **Video Files Only**: Excluded for audio files (`.mp3`, `.flac`, etc.) and image files.
   - **No Menu Extensions Needed**: `KEYCODE_MENU` options menu remains unchanged (used for Dolby Vision Profile 7 fallback options).

2. **Immediate `ffprobe` Metadata & Chapter Return (Fast Startup)**:
   - `ffprobe` extracts container metadata, stream colorspace, duration, and the full `chapters` array (names + timestamps) in ~50ms.
   - `/info` **returns immediately** with the full `chapters` list so playback starts without delay and the Chapters menu in `PlayerActivity` is available instantly at playback start (with titles and timestamps).
   - Chapter thumbnail JPEGs (`/chapter-thumb?index=N`) and seekbar sprite sheets (`/storyboard.json`) generate asynchronously in the background. The Chapters UI shows text/placeholders instantly and fills in thumbnail JPEGs as they finish.

3. **New `--no-caching` Server Flag**:
   - Command-line flag: `-no-caching` / `--no-caching`.
   - **When `--no-caching` is set**:
     - Disk caching and generation of chapter thumbnail JPEGs and seekbar storyboard sprite sheets are **disabled** (zero thumbnail disk usage, zero ffmpeg CPU load for previews).
     - **Chapter Discovery Always Enabled**: `ffprobe` still executes fast (~50ms) to discover container chapters and populate `info.chapters`, ensuring chapter navigation always works!

4. **Binary Discovery Priority (`ffmpeg` & `ffprobe`)**:
   - Executable lookup order:
     1. **Executable Directory** (e.g. `ffmpeg.exe` / `ffprobe.exe` placed next to `bitstreamer.exe` — takes priority).
     2. **System `PATH`** (fallback if absent next to the binary).
   - Thumbnail and seekbar storyboard generation are enabled **only** if both `ffmpeg` and `ffprobe` are discovered.
   - If binaries are absent, chapter discovery falls back gracefully (or container parsing), and thumbnail/storyboard flags report `false`.

5. **100% Persistent Disk Cache**:
   - Make all chapter JPEGs and seekbar storyboard sprite sheets **permanently persistent** under `cache/<file_hash>/` across server restarts.
   - Key every cache directory by `SHA256(canonical_path + size + mtime)[:16]`.
   - Re-use cached thumbnails automatically whenever a video is played—no duplicate `ffmpeg` processes.

---

## 2. Persistent Cache & Flag Architecture

### Server Command-Line Flags (`main.go`)

```go
var (
    noCaching    bool
    stripDV      bool
    interval     int
    skipPreviews bool
)

func main() {
    flag.BoolVar(&noCaching, "no-caching", false, "disable disk caching of thumbnail JPEGs and seekbar sprite sheets (chapters discovery remains enabled)")
    // ...
}
```

### Cache Directory Structure

```text
server/cache/
├── <file_hash_1>/
│   ├── metadata.json           # Cached ffprobe JSON (duration, chapters, colour transfer, dvProfile)
│   ├── thumbs/                 # Chapter thumbnails
│   │   ├── thumb_000.jpg
│   │   └── thumb_001.jpg
│   └── storyboard/             # Seekbar scrubbing preview sprite sheets
│       ├── storyboard.json     # Manifest (intervalMs, tileW, tileH, cols, rows, tileCount)
│       ├── sheet_000.jpg
│       └── sheet_001.jpg
└── <file_hash_2>/
    ...
```

### Cache Key Formula

```go
func fileCacheHash(path string, size int64, modTime time.Time) string {
    h := sha256.New()
    fmt.Fprintf(h, "%s:%d:%d", filepath.Clean(path), size, modTime.UnixNano())
    return hex.EncodeToString(h.Sum(nil))[:16]
}
```

---

## 3. Server-Side Changes (`server/`)

### A. Executable Discovery Priority (`findFFmpeg` & `findFFprobe`)

Both lookup functions check the sidecar directory first, then system `PATH`:

```go
func findExecutable(name string) string {
    names := []string{name, name + ".exe"}
    if exe, err := os.Executable(); err == nil {
        dir := filepath.Dir(exe)
        for _, n := range names {
            p := filepath.Join(dir, n)
            if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
                return p // 1. Executable directory (Priority 1)
            }
        }
    }
    if p, err := exec.LookPath(name); err == nil {
        return p // 2. System PATH (Priority 2)
    }
    return ""
}
```

### B. Fast `/info` Metadata Return & Async Thumbnail Worker

- **Fast `/info` Response**: When `/info?path=...&root=N` is requested:
  1. `ffprobe` is executed synchronously (~50ms execution time).
  2. Populates `info.chapters` (`[{ startMs, name }]`), `info.video`, and stream metadata immediately.
  3. Returns `200 OK` JSON response to the client right away.
- **Async Background Generation**:
  - If `noCaching` is `false` and video file is played:
    - If `cache/<file_hash>/` already contains thumbs & storyboard → mark `thumbnails: true`, `storyboard: true`.
    - If not cached → spawn background goroutine `go generateVideoThumbnailsAsync(file)` to run `ffmpeg` chapter stills & seekbar sprite sheet generation in the background.

### C. Endpoints Summary

| Endpoint | Query Parameters | Description |
|---|---|---|
| `GET /info` | `path=<rel>&root=<N>` | Returns metadata + `chapters` array **immediately**. Spawns async background `ffmpeg` generation if `no-caching` is false. |
| `GET /chapter-thumb` | `path=<rel>&root=<N>&index=<I>` | Serves chapter thumbnail `I` from `cache/<hash>/thumbs/`. Returns 404 if still generating or `no-caching` is active. |
| `GET /storyboard.json` | `path=<rel>&root=<N>` | Serves `storyboard.json` manifest. Returns 404 if generating or `no-caching` is active. |
| `GET /storyboard` | `path=<rel>&root=<N>&sheet=<S>` | Serves sprite sheet `S` from `cache/<hash>/storyboard/`. |

---

## 4. Client-Side Flow (`client/`)

### A. Instant Playback & Chapter List

1. **Playback Launch**:
   - Selecting any video file in `BrowserActivity` or `DiscoveryActivity` launches `PlayerActivity`.
2. **Immediate Chapters Menu**:
   - `PlayerActivity` receives `/info` response in ~50ms containing the full `chapters` list.
   - Chapters menu opens instantly with full chapter names and timestamps.
   - Chapter thumbnails (`/chapter-thumb?index=N`) show text/placeholders until JPEG bytes finish loading.
3. **Seekbar Storyboard Polling**:
   - If `storyboardEnabled` is true (or generating), `PlayerActivity` polls `/storyboard.json` in the background and attaches `StoryboardLoader` as soon as sprite sheets finish.

### B. Remote `KEYCODE_MENU`
- Retains existing behavior: opens Options Menu for Dolby Vision files (`Play Normally` / `Strip DV and Play`).

---

## 5. Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App as Client (BrowserActivity / PlayerActivity)
    participant Server as BitStreamer Server
    participant Cache as Disk Cache (cache/<hash>/)
    participant FFmpeg as ffmpeg / ffprobe

    User->>App: Select video file to play
    App->>Server: GET /info?path=movie.mkv&root=0
    Server->>FFmpeg: Run fast ffprobe (~50ms)
    FFmpeg-->>Server: Return duration, streams & chapters
    Server->>Cache: Check cache/<hash>/
    
    Server-->>App: JSON { chapters: [...], video: {...}, thumbnails: bool, storyboard: bool }
    App->>User: Start playback IMMEDIATELY & populate Chapters Menu with names/timestamps

    alt noCaching is true
        Note over Server: Disk caching disabled. Skip ffmpeg thumbnail generation.
    else Cache Hit
        Cache-->>Server: Thumbs & Storyboard ready
        App->>Server: GET /storyboard.json & GET /chapter-thumb
        Server-->>App: Manifest & Bitmaps
        App->>User: Seekbar previews ready immediately
    else Cache Miss & noCaching is false & ffmpeg+ffprobe found
        Server->>FFmpeg: Spawn ASYNC background ffmpeg jobs
        loop Poll until ready
            App->>Server: GET /storyboard.json?path=movie.mkv&root=0
            FFmpeg->>Cache: Write JPEGs & sprite sheets & storyboard.json
            Server-->>App: 200 OK Manifest ready
        end
        App->>User: Seekbar previews & chapter JPEGs load as completed
    end
```

---

## 6. Action Plan & Checklist

### Phase 1: Server Discovery & CLI Flags
- [ ] Add `-no-caching` flag to `main.go`.
- [ ] Verify `findFFmpeg()` and `findFFprobe()` check executable directory first, then system `PATH`.

### Phase 2: Server Persistent Cache & Fast `/info`
- [ ] Implement `fileCacheHash(path, size, mtime)` persistent hash keying.
- [ ] Update `/info` in single-file and folder modes to return fast `ffprobe` metadata & `chapters` list immediately.
- [ ] Implement background async `ffmpeg` generator for video files (disabled if `-no-caching` or missing binaries).
- [ ] Support folder mode query parameters (`path=<rel>&root=<N>`) for `/chapter-thumb`, `/storyboard.json`, and `/storyboard`.

### Phase 3: Client Player Integration
- [ ] Display `info.chapters` immediately in the Chapters menu with titles and timestamps.
- [ ] Support background loading of chapter JPEGs as `/chapter-thumb` endpoints complete.
- [ ] Poll `/storyboard.json?path=...&root=N` in folder mode until ready and attach `StoryboardLoader`.

### Phase 4: Verification & Testing
- [ ] Verify fast `ffprobe` returns chapters immediately on playback start.
- [ ] Verify video files trigger background thumbnail and seekbar generation by default.
- [ ] Verify `-no-caching` server flag disables disk thumbnail caching while preserving chapter discovery.
- [ ] Verify persistent cache retains thumbnails across server restarts.
- [ ] Verify executable directory priority for `ffmpeg.exe` / `ffprobe.exe`.

---

## What shipped

- Server (`thumbnails.go`): detects `ffmpeg` next to the exe or on `PATH`; if present,
  `GET /chapter-thumb?index=N` returns a JPEG generated on first request (`ffmpeg -ss
  <start+5s> -i file -frames:v 1 -vf scale=320:-2 -f mjpeg`), cached to
  `cache/thumbs/` next to the executable, keyed by file+mtime+hdr+index, concurrency capped at 3.
  `/info` reports `"thumbnails": true` only when ffmpeg is available.
- Client: `ChapterThumbnailLoader` fetches `/chapter-thumb?index=N` over HTTP and caches
  the bitmaps. When `/info` says `thumbnails:false`, the chapter selector hides the image
  and shows a compact name + timestamp list.
- ffmpeg is a **user-supplied sidecar** (not bundled): drop `ffmpeg.exe` next to
  `bitstreamer.exe` to enable thumbnails; without it, everything else works unchanged.
- **HDR tonemapping**: HDR sources are tonemapped BT.2020→BT.709 with ffmpeg's
  `zscale`/`tonemap` filters (`ffmpeg.go`) so thumbnails aren't washed out — for both
  chapter thumbnails and storyboard tiles. Detection is via **ffprobe** (`probe.go`),
  which reads the real stream's colour transfer (PQ/HLG) and the Dolby Vision profile;
  it falls back to the MKV container tags (`hdr.go`) if ffprobe isn't present. Works for
  HDR10, HDR10+, and Dolby Vision profiles with a PQ/HLG base (7/8.x); DV **profile 5**
  can't be colour-accurate without DV RPU processing (an ffmpeg/libplacebo limitation).
  If the ffmpeg build lacks `zscale` (libzimg), it falls back to plain extraction (washed
  out, but present) and logs once. The detected colour info is printed at startup.
- **Eager generation**: on startup the server pre-generates all chapter thumbnails in the
  background (`thumbnailer.warm()`, concurrency-capped), so the chapter menu is instant the
  first time it's opened instead of triggering ffmpeg on demand. On-demand generation
  remains as a fallback (a request for a not-yet-warmed chapter shares the same per-index
  lock, so there's no duplicate work).

The rest of this doc records the original investigation and rationale.

---

## Scrubbing preview thumbnails (YouTube/Netflix-style "storyboard")

Status: **implemented.** As you drag the seek bar, a small preview of the frame at that
position follows the thumb — a "trickplay"/"storyboard" preview. Unlike chapter thumbnails
(one per chapter), this needs a **dense, regular grid** of frames across the whole movie.

What shipped (matching the design below): server `storyboard.go` generates sprite sheets at
startup via ffmpeg into `cache/storyboard/` next to the executable (wiped at start and on
Ctrl+C/SIGTERM — per-session), duration comes
from ffprobe (`mediaDurationMs` in `duration.go`), and `/storyboard.json` + `/storyboard?sheet=N` serve the
manifest and sheets. The interval is the **`--interval <secs>` flag (default 30)**. The
client (`StoryboardLoader` + the scrub overlay in `PlayerActivity`) fetches sheets, decodes the
tile for the scrub position, and shows it; it also sets the seek-bar D-pad step to the same
interval so each left/right press lands on the next preview frame (and fixes the old
"jumps several minutes" behavior). If the server is still generating when a movie opens, the
client polls `/storyboard.json` and enables previews once ready.

### How mature players do it (and we should too): sprite sheets

Jellyfin/Emby/Plex all use the same trick — don't store thousands of tiny files, store a
few **sprite sheets**: big images that tile many small frames in a grid, plus a manifest
describing the interval and tile layout. The client crops the right tile for the scrub
position. ffmpeg produces these in one pass:

```
ffmpeg -i <file> -vf "fps=1/<T>,scale=240:-2,tile=10x10" -q:v 5 <cache>/sb_%03d.jpg
```

- `fps=1/T` samples one frame every `T` seconds (e.g. T=10).
- `scale=240:-2` shrinks each frame; `tile=10x10` packs 100 frames per sheet.
- Number of tiles = ceil(duration / T); sheets = ceil(tiles / 100).

### Server

- **Duration**: needed to know how many tiles. Read via `ffprobe -show_entries
  format=duration` (`mediaDurationMs` in `duration.go`) — the same ffprobe sidecar used for
  chapters and HDR detection, so no code path is MKV-specific and any container ffprobe
  understands works.
- **Generation**: at startup, in the background (same as chapter warm), run the ffmpeg
  command above into a **per-session** temp dir. This is the expensive part — see cost.
- **Endpoints**:
  - `GET /storyboard.json` → `{intervalMs, tileW, tileH, cols, rows, tileCount, sheetCount}`.
  - `GET /storyboard?sheet=N` → the Nth sprite-sheet JPEG.
  - `/info` gains `"storyboard": true` when ready.
- **Per-session lifecycle** (as requested): generate on start, **delete the storyboard
  cache dir on shutdown** (Ctrl+C / SIGTERM handler). Chapter thumbnails stay disk-cached
  across runs; the dense storyboard does not (it's large and file-specific).

### Client

- On player start, if `/info` says `storyboard:true`, fetch `/storyboard.json`.
- Attach a scrub listener to the `DefaultTimeBar` (`TimeBar.OnScrubListener.onScrubMove`
  gives the scrubbed position). From position → tile index (`pos/intervalMs`) →
  sheet number + row/col → decode that tile's region out of the (cached) sheet → show it in an
  overlay `ImageView` positioned above the scrubber thumb.
- Cache each sheet's **encoded JPEG bytes** (not the decoded bitmap) in an `LruCache`, and
  decode only the requested tile with a `BitmapRegionDecoder`. Memory stays a few MB no
  matter how large the sheets are — which is what lets the tiles be full-resolution (480px
  wide, 1:1 with the on-screen preview at 240dp/xhdpi) without OOMing the Fire TV Stick.
  A whole-sheet decode would be ~52 MB per 4800×2700 sheet.

### Generation: fast keyframe seeks (not a full decode)

An early version used one `fps` filter pass, which makes ffmpeg **decode the whole video** —
minutes of CPU for a 2-hour 4K file. The shipped version instead does a **fast keyframe seek
per interval**: `ffmpeg -ss <i*interval> -i file -frames:v 1` uses the container index to jump
to the nearest keyframe and decode almost nothing. These run in parallel (capped), then Go
tiles the frames into sprite sheets (`image/draw`, stdlib). For a 2h film at 30s that's ~240
targeted seeks — seconds, not minutes — and every tile is effectively a keyframe.

### Why fixed intervals (not keyframe-driven variable spacing)

Considered letting the keyframes themselves set the spacing (extract *every* I-frame at its
natural position). Rejected — it's worse on every axis that matters here:
- **Slower to generate**, not faster: grabbing all keyframes needs a full-file pass
  (`-skip_frame nokey` still demuxes the whole container and decodes every I-frame, often
  1000s). Targeted seeks at fixed intervals touch far less of the file.
- **Unpredictable, unbounded count**: keyframe density varies wildly by encode; a fast-cut
  film could yield thousands of tiles (dozens of sheets), a static one very few.
- **Complex client**: irregular timestamps break the trivial `tileIndex = time/interval`
  mapping — you'd need a per-tile timestamp manifest and a nearest-tile binary search.
- **Marginal UX gain**: for scrubbing, a fixed grid is plenty; nobody needs a preview at
  every scene cut.

Fixed interval + keyframe seek already gives keyframe-quality frames with fast, bounded,
simple generation. Want finer granularity? Lower `--interval` (e.g. `--interval 10`) — it
stays fast because of the seek approach.

### Effort

Server: duration parse + storyboard generation + 2 endpoints + shutdown cleanup
(~150–200 lines, testable on the Mac with ffmpeg). Client: scrub-listener overlay + tile
cropping + positioning (~150 lines). Medium feature; the startup decode time is the thing
to validate on a real long 4K file before committing.


## Why Option A failed (confirmed on hardware)

The client tried `MediaMetadataRetriever.getScaledFrameAtTime()` over the stream URL — the
device already hardware-decodes the movie, so in theory it can grab frames too. Diagnostics
in `client-logs.txt` (Fire TV Stick 4K Max, AFTKM) showed it does not:

```
thumbnail source: 3840x2160 mime=video/x-matroska
frame at 5000ms: 320x180 avgLum=0 (appears BLACK …)     # 4K HDR  -> black
frame NULL at 5000ms (decoder could not produce a frame) # another file -> null
```

Both failure modes appear, and the user confirms it also fails for 1080p. Root cause: a
Fire TV stick has a **single video hardware decoder**, already in use by playback, so the
retriever's concurrent decode returns black or null frames (HDR/10-bit makes the black case
worse). This is a platform ceiling, not a bug we can code around on the client.

## Plan: extract thumbnails on the server with ffmpeg

The PC has spare CPU and no decoder contention. Generate one thumbnail per chapter there
and serve them; the client just displays images (no on-device decode).

### Server

- **Dependency**: `ffmpeg.exe` as an external sidecar (NOT bundled — see decision below).
  Detect it on `PATH` or next to `bitstreamer.exe`; if absent, log once and serve no
  thumbnails (chapters still work, just without images). This keeps the zero-dependency
  default: users who want thumbnails drop in one `ffmpeg.exe`.
- **Generation** (lazy, on first request, cached to disk): for chapter at time T,
  ```
  ffmpeg -ss <T+5s> -i <file> -frames:v 1 -vf "scale=320:-1" -q:v 4 -f mjpeg <cache>/<hash>_<idx>.jpg
  ```
  `-ss` before `-i` = fast keyframe seek; `-frames:v 1` = one frame. ~50–150 ms each.
  Cache under a temp dir keyed by (file path + mtime + chapter index); reuse across runs.
  Cap concurrency (a small worker pool) so 20 chapters don't spawn 20 ffmpeg processes.
- **Endpoint**: `GET /chapter-thumb?index=N` → the JPEG (200), or 404 if unavailable /
  ffmpeg missing. Optionally warm the cache in the background at startup.
- **/info**: add `"thumbnails": true` when ffmpeg is available, so the client knows whether
  to request them.

### Client

- Replace `ChapterThumbnailLoader` (MediaMetadataRetriever) with a plain HTTP image fetch:
  `GET /chapter-thumb?index=N` → decode JPEG → cache in the existing `LruCache`. The
  `ChapterListAdapter` UI stays exactly the same (it already loads async and shows a
  placeholder until the bitmap arrives).
- If `/info` reports `thumbnails=false`, skip requests and just show name + timestamp.

### Decision to settle at implementation time: bundle ffmpeg or not?

- **Sidecar, user-supplied (leaning this way)**: keep shipping one small `bitstreamer.exe`;
  document "drop `ffmpeg.exe` next to it for chapter thumbnails." Preserves the
  lightweight, single-binary promise; thumbnails are opt-in.
- **Bundle ffmpeg** (~80–100 MB): thumbnails work out of the box, but the download balloons
  and it contradicts the project's lightweight premise.

Recommendation: sidecar. Revisit only if the manual step proves annoying in practice.

### Effort

Server: ffmpeg detection + generation + cache + endpoint (~150 lines, testable on the Mac
where ffmpeg is already installed). Client: swap the loader's internals (~40 lines), no UI
change. Half a focused session.

### Not doing

Sprite sheets / scrubbing-preview thumbnails (one image strip for hover-scrub) — larger
feature; per-chapter stills are enough for the chapter selector.
