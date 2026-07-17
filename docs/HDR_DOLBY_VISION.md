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

### On-the-Fly Fallback: The `-stripdv` Server Flag

To handle Profile 7 FEL files (like *Titanic*) on the fly without permanent file conversion, you can run the server with the `-stripdv` command-line flag:

```cmd
bitstreamer.exe -stripdv "Titanic.mkv"
```

When this flag is active:
1. **Decoder Exclusions:** The client player intercepts the decoder selection pipeline, maps the track MIME type from `video/dolby-vision` to `video/hevc`, clears the Dolby Vision `codecs` metadata, and **excludes all hardware Dolby Vision decoders** (filtering out any decoder containing `"dolby"` or `"dovi"`). This forces the device to bind a standard HEVC/HDR10 hardware decoder.
2. **On-the-Fly NAL Stripping:** As the stream plays, the client player intercepts incoming video packets (`onQueueInputBuffer`) and uses a custom real-time **Annex B parser** to strip NAL units of type `62` (RPU) and `63` (EL) before they reach the hardware decoder. This delivers a clean HEVC/HDR10 stream to the standard decoder, preventing the display chip from switching the TV into Dolby Vision mode (which causes the black screen).
3. **Automatic HEVC Mapping for HDR10+ (No Flags Required):** Hybrid files that contain **HDR10+ metadata** (such as *Thunderbolts\\* and *The Running Man*) will **always** trigger the HEVC fallback (`fallbackToHdr10 = true`) by default. This forces them to decode using standard HEVC and display in HDR10+ mode automatically without requiring the `-stripdv` flag.
4. **Preserving Native Playback for MEL:** For normal Dolby Vision Profile 7 files (like *Aquaman*), if `-stripdv` is not passed to the server, the fallback logic remains disabled, allowing them to play natively in Dolby Vision. If `-stripdv` is passed, they fall back to standard HEVC/HDR10.

### Lossless Offline Conversion (Alternative Fix)

If the on-the-fly `-stripdv` fallback doesn't work or is not preferred, you can permanently and losslessly strip the Dolby Vision layers from the file using `ffmpeg`. This takes only a few seconds as it is a direct stream copy (`-c copy`):

```cmd
ffmpeg -i "Movie.mkv" -map 0 -c copy -bsf:v "filter_units=remove_types=62|63" "Movie_no_dv.mkv"
```

Then serve `Movie_no_dv.mkv` — it plays in standard HDR10 mode. (Do not use this on **Profile 5** — its base layer is not HDR10-compatible, so the colors will be incorrect.)

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
