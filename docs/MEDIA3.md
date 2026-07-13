# Media3 — What It Is and What Else It Can Do for BitStreamer

## What is Media3?

[androidx.media3](https://developer.android.com/media/media3) is Google's official
Android media framework — the successor to the standalone ExoPlayer 2.x library, now
maintained as a Jetpack suite and used by YouTube, Netflix, Plex, Jellyfin, and
practically every serious Android media app. It ships as modular artifacts; the ones
that matter to us:

| Module | What it provides | Do we use it? |
|--------|------------------|---------------|
| `media3-exoplayer` | The player engine: renderers, `DefaultAudioSink` (our passthrough path), track selection, buffering/networking | ✅ core of the client |
| `media3-extractor` | Container demuxers (MKV, MP4, …) and parsers like `DtsUtil` | ✅ demuxing + DTS core extraction |
| `media3-ui` | `PlayerView`: controller overlay, D-pad handling, track-selection UI, `SubtitleView` rendering | ✅ the whole player UI |
| `media3-common` | `Player` interface, `Format`, `MimeTypes`, `AudioAttributes` | ✅ everywhere |
| `media3-session` | `MediaSession`/`MediaController`: system + voice integration | ❌ candidate (below) |
| `media3-cast` | Chromecast output | ❌ candidate |
| `media3-transformer` | On-device transcoding/editing | ❌ (violates our no-transcode rule) |
| `media3-exoplayer-dash` / `-hls` | Adaptive streaming protocols | ❌ not needed for single-file LAN |
| `media3-datasource-cache` | Persistent read-through cache for remote media | ❌ candidate |

Why it was the right horse for this project: everything hard in the client — MKV
demuxing over HTTP Range, HDMI audio passthrough negotiation, subtitle extraction and
rendering, TV remote/D-pad handling — is mature, Google-maintained code we configure
rather than write. The entire BitStreamer client is ~6 small Kotlin files because
Media3 does the heavy lifting.

## Features we already exploit

- Progressive HTTP playback with Range-based seeking (`DefaultHttpDataSource` + extractors)
- Hardware video decode incl. HEVC/HDR (`MediaCodecVideoRenderer`)
- **Audio passthrough/bitstreaming** via `DefaultAudioSink` (DD, DD+, Atmos, DTS), plus
  our `DtsCoreAudioSink` wrapper built on its `ForwardingAudioSink` extension point
- Track selection UI (audio via settings button, subtitles via CC button)
- Embedded subtitle extraction + rendering (SRT/ASS/PGS from MKV)
- Seek increments, controller overlay, `show_buffering`, D-pad key mapping
- Custom controller layout (`player_controls.xml`) for the TV D-pad focus layers
- Seek-bar chapter ticks via `setExtraAdGroupMarkers`, and `TrackSelectionDialogBuilder`
  for the audio/subtitle pickers
- Chapter selector (name + timestamp per row). On-device thumbnails via
  `MediaMetadataRetriever` were tried but return black/null on Fire TV (single HW decoder
  busy with playback) — moving to server-side ffmpeg; see [THUMBNAILS.md](THUMBNAILS.md)
- **Dolby Vision**: the native platform DV decoder is used for all DV content. Forcing the
  HEVC base layer (to dodge the Fire TV DV black screen) was tried and **reverted** — on
  Profile 7 FEL it produces no video decoder at all (ERROR_CODE_TIMEOUT), and it needlessly
  broke Profile 8 DV+HDR10+ that plays fine natively (verified on a Fire TV Stick 4K Max).
  Net: single-layer DV (Profile 5/8, incl. DV+HDR10+) plays with full DV; **Profile 7 FEL
  dual-layer is not playable on Fire TV without transcoding** (black screen), an OS/hardware
  limitation ([androidx/media #957](https://github.com/androidx/media/issues/957),
  [#1895](https://github.com/androidx/media/issues/1895)). The server still reports the DV
  profile and HDR10+ in `/info` (shown in the discovery details table and stats overlay), and
  prints an ffmpeg strip-to-HDR10 command for Profile 7 files. Full details:
  [HDR_DOLBY_VISION.md](HDR_DOLBY_VISION.md).
- Deep diagnostics via `AnalyticsListener` (`onAudioTrackInitialized`, decoder events,
  sink errors) feeding `RemoteLog`

## Feature menu — what else we could turn on

Ordered roughly by value-for-effort for this project. ⚠ marks anything that could
threaten the bitstream-audio guarantee (see AUDIO_PASSTHROUGH.md §4 before touching).

1. **`MediaSession` + Alexa voice control** (`media3-session`). Wrap the player in a
   `MediaSession` and the Fire TV remote's microphone works: "Alexa, pause / rewind
   2 minutes / resume". Also gives the system now-playing surface and cleaner
   media-button routing. Low risk, pure addition — the highest-value candidate.
2. **Tunneled playback** (`setTunnelingEnabled(true)` on the track selector). Video and
   audio sync are handled by the display pipeline itself — Amazon recommends it on
   Fire TV and it can *improve* AV sync with passthrough. Deliberately off in v1; turn
   on only if sync issues ever appear, and verify passthrough still negotiates.
3. **Playlists / queue**. `Player` natively handles `MediaItem` lists with gapless
   transitions, `seekToNextMediaItem()`, etc. Pairs with a server change (serve a
   directory instead of one file) for a "play the season" experience.
4. **Caption styling** *(done)*. Text subtitles (SRT and other text cues) are styled via
   `playerView.subtitleView.setStyle(CaptionStyleCompat(...))` in
   `PlayerActivity.styleSubtitles()`. The default (Fire TV system caption style) is white
   text on an opaque black *window* — a full-width black rectangle. We replace it with an
   Emby-like look: white text, a semi-transparent black background that hugs the text
   (`backgroundColor`, no window box), and a thin black outline (`EDGE_TYPE_OUTLINE`) for
   legibility over bright/HDR scenes. The knobs are fixed but cover the useful space:
   foreground/background/window colours, edge type (none/outline/drop-shadow/raised/
   depressed) + colour, typeface, `setFractionalTextSize`, and `setBottomPaddingFraction`.
   Bitmap subtitles (PGS/VOBSUB) are images and can't be restyled; embedded ASS/SSA
   styling is preserved (`setApplyEmbeddedStyles(true)`). For fully arbitrary rendering
   you'd replace `SubtitleView` — out of scope; the `CaptionStyleCompat` knobs suffice.
5. **Playback-position UX extras**: `setSeekParameters(SeekParameters.CLOSEST_SYNC)`
   for snappier seeks on big HEVC files; custom `LoadControl` to size buffers for
   high-bitrate remuxes over Wi-Fi (default is tuned for adaptive streaming).
6. **Read-through cache** (`media3-datasource-cache`). `CacheDataSource` over the HTTP
   source rides out Wi-Fi hiccups and makes re-watching seeks instant. Costs Fire TV
   storage (sticks have little) — use a small LRU cache if ever needed.
7. **Chromecast output** (`media3-cast`). `CastPlayer` mirrors our `Player` usage; the
   server URL is already LAN-reachable for the Cast device. Only worth it if a
   non-Fire-TV screen enters the picture.
8. **⚠ Playback speed** (`setPlaybackParameters`). Speed change forces PCM —
   incompatible with bitstreaming by definition. **Removed from the UI**: the player
   handed to `PlayerView` is wrapped by `PlayerFactory.withoutSpeedControls`, which
   drops `COMMAND_SET_SPEED_AND_PITCH` so the controller never offers it (and future
   MediaSession/voice surfaces can't trigger it either).
9. **⚠ Software decoder extensions** (ffmpeg/AV1 modules). Would decode exotic codecs
   the hardware can't — but the ffmpeg *audio* extension is the classic way apps
   silently lose passthrough. Policy stays: extensions off (`PlayerFactory`).
10. **Not applicable here**: DASH/HLS adaptive streaming, DRM (`media3-exoplayer` +
    Widevine), `Transformer` transcoding, audio offload (battery feature), Leanback
    adapter (we deliberately skipped Leanback).

When picking from this menu, the standing order of the repo applies: nothing may
compromise byte-identical video and bitstreamed audio.
