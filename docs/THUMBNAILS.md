# Chapter Thumbnails — ffmpeg sidecar (Option B)

Status: **implemented.** On-device extraction (Option A) was tried and does not work on
Fire TV; this documents why, and the server-side ffmpeg approach that replaced it.

## What shipped

- Server (`thumbnails.go`): detects `ffmpeg` next to the exe or on `PATH`; if present,
  `GET /chapter-thumb?index=N` returns a JPEG generated on first request (`ffmpeg -ss
  <start+5s> -i file -frames:v 1 -vf scale=320:-2 -f mjpeg`), cached to
  `%TEMP%/bitstreamer-thumbs` keyed by file+mtime+index, with concurrency capped at 3.
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
startup via ffmpeg into a per-session temp dir (deleted on Ctrl+C/SIGTERM), duration comes
from `go-mkvparse` (`duration.go`), and `/storyboard.json` + `/storyboard?sheet=N` serve the
manifest and sheets. The interval is the **`--interval <secs>` flag (default 30)**. The
client (`StoryboardLoader` + the scrub overlay in `PlayerActivity`) fetches sheets, crops the
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

- **Duration**: needed to know how many tiles. Get it from the MKV header via the vendored
  `go-mkvparse` (SegmentInfo `Duration` × `TimecodeScale`) — no extra dependency. (ffprobe
  would also work but adds a second sidecar binary.)
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
  sheet number + row/col → crop that tile out of the (cached) sheet bitmap → show it in an
  overlay `ImageView` positioned above the scrubber thumb.
- Cache sheet bitmaps in an `LruCache`; only a couple are ever needed at once.

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
