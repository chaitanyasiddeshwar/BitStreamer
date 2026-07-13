<!-- Full map of HDR/DV playback behaviour on Fire TV, the Profile 7 FEL limitation,
     and the ffmpeg strip-to-HDR10 workaround the server prints. -->
# HDR & Dolby Vision on Fire TV — what plays, what doesn't, and why

BitStreamer serves the file byte-for-byte and lets the Fire TV decode it. That means HDR /
Dolby Vision playback is entirely down to what the Fire TV's decoder can handle. This is the
map of what works, the one case that doesn't, and the fixes.

## What plays (Fire TV Stick 4K / 4K Max, via Media3/ExoPlayer)

| Source format | Plays? | Notes |
|---|---|---|
| SDR (BT.709) | ✅ | — |
| HDR10 (PQ, BT.2020) | ✅ | — |
| HDR10+ (HDR10 + dynamic metadata) | ✅ | Dynamic metadata honored by capable displays. |
| HLG | ✅ | — |
| Dolby Vision **Profile 5** (single-layer) | ✅ | Native DV. |
| Dolby Vision **Profile 8.1** (single-layer, HDR10 base) | ✅ | Native DV — incl. files that also carry HDR10+ (e.g. *Ford v Ferrari*). |
| Dolby Vision **Profile 7 MEL** (dual-layer, minimal EL) | ✅ | Native DV — the base layer is a standalone HDR10 stream (e.g. *Aquaman* REMUX). |
| Dolby Vision **Profile 7 FEL** (dual-layer, full EL) | ❌ | **Audio plays, video is black.** Fire TV cannot decode the full enhancement layer (e.g. *Titanic* REMUX). |

The server detects the colour info with **ffprobe** (`probe.go`) and reports it in `/info`
(`video.hdr`, `video.hdr10plus`, `video.transfer`, `video.colorSpace`, `video.dvProfile`) —
shown in the discovery details table and the in-player stats overlay, and printed at startup.

## The one failure: Dolby Vision Profile 7 FEL

Profile 7 is **dual-layer**: a Base Layer (BL, HDR10-compatible HEVC) plus an Enhancement
Layer (EL) and RPU metadata, combined in one video track.

- **MEL** (Minimal Enhancement Layer): the EL is essentially empty; the BL is a complete
  HDR10 stream, so the decoder plays it fine. → **works.**
- **FEL** (Full Enhancement Layer): the EL carries real picture residual data coded into the
  bitstream. Fire TV's DV decoder black-screens on it, and a plain HEVC decoder can't be
  pointed at it either — the EL/RPU NAL units aren't droppable metadata, they corrupt/stall
  the decode. → **audio only, black video.**

`ffprobe` reports `Profile 7` for **both** MEL and FEL; telling them apart needs
enhancement-layer parsing (`dovi_tool`), which we don't ship. So the server can't
auto-classify — it warns for all Profile 7 and lets the outcome tell you which you have.

### What we tried and reverted

Forcing Media3 to decode the HDR10 base layer instead of DV (a custom `MediaCodecSelector`
returning no DV decoder → Media3's HEVC fallback) was implemented and **reverted**:

- On Profile 7 FEL it produced **no video decoder at all** → `ERROR_CODE_TIMEOUT` (worse than
  the black screen).
- It **broke** Profile 7 MEL and Profile 8 DV that play fine natively (they'd time out too).

So the native DV decoder is used for everything. See [MEDIA3.md](MEDIA3.md).

### The fix for a FEL file: strip DV → HDR10 (lossless)

When started with a Profile 7 file, the server **prints a ready-to-run ffmpeg command**. It
removes the DV RPU (NAL type 62) and enhancement layer (NAL type 63), leaving the HDR10 base
— `-c copy`, so it's fast and lossless and keeps every audio/subtitle track:

```
ffmpeg -i "Movie.mkv" -map 0 -c copy -bsf:v "filter_units=remove_types=62|63" "Movie_no_dv.mkv"
```

Then serve `Movie_no_dv.mkv` — it plays as HDR10. (This also converts any Profile 8 DV to
HDR10, but that isn't needed since Profile 8 plays as-is. Don't use it on **Profile 5** — its
base layer isn't HDR10-compatible, so the result would have wrong colours.)

A heavier, fully-automatic server-side version (`--strip-dv`: detect FEL, run the strip into
`cache/`, serve the result) was scoped and deferred — it needs `dovi_tool`, ~file-sized scratch
space, and minutes of pre-processing per title, for a narrow benefit. The printed command is
the pragmatic answer.

## Thumbnails and HDR

Chapter thumbnails and scrubbing previews are generated server-side with ffmpeg. HDR sources
are **tonemapped** BT.2020/PQ→BT.709 (via the `zscale`/`tonemap` filters) so they don't look
washed out — see [THUMBNAILS.md](THUMBNAILS.md). This needs an ffmpeg built with `zscale`
(libzimg); the server detects it and prints whether HDR thumbnails will be tonemapped. A
build without it still produces thumbnails, just not tonemapped.

## Audio is independent

None of the above affects audio — Dolby/DTS bitstreaming over HDMI is a separate path
(see [AUDIO_PASSTHROUGH.md](AUDIO_PASSTHROUGH.md)). A Profile 7 FEL file still passes its
TrueHD/Atmos audio perfectly while the video is black; that's the giveaway for FEL.
