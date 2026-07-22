<!-- Full map of HDR/DV playback behaviour on Fire TV, the Profile 7 FEL limitation,
     and the ffmpeg strip-to-HDR10 workaround. -->
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
| Dolby Vision **Profile 5** (single-layer) | ✅ | Native DV (IPTPQc2 colorspace). |
| Dolby Vision **Profile 8.1** (single-layer, HDR10 base) | ✅ | Native DV — incl. files that also carry HDR10+ (e.g. *Ford v Ferrari*). |
| Dolby Vision **Profile 7 MEL** (dual-layer, minimal EL) | ✅ | Native DV — the base layer is a standalone HDR10 stream (e.g. *Aquaman* REMUX). |
| Dolby Vision **Profile 7 FEL** (dual-layer, full EL) | ❌ | **Audio plays, video is black.** Fire TV cannot decode the full enhancement layer (e.g. *Titanic* REMUX). |

The server detects the colour info with **ffprobe** ([`probe.go`](file:///n:/AI/ai_coder/BitStreamer/server/probe.go)) and reports it in `/info` (`video.hdr`, `video.hdr10plus`, `video.transfer`, `video.colorSpace`, `video.dvProfile`) — shown in the discovery details table and the in-player stats overlay, and printed at startup.

---

## The one failure: Dolby Vision Profile 7 FEL

Profile 7 is **dual-layer**: a Base Layer (BL, HDR10-compatible HEVC) plus an Enhancement Layer (EL) and RPU metadata, combined in one video track.

- **MEL** (Minimal Enhancement Layer): the EL is essentially empty; the BL is a complete HDR10 stream, so the decoder plays it fine. → **works.**
- **FEL** (Full Enhancement Layer): the EL carries real picture residual data coded into the bitstream. Fire TV's DV decoder black-screens on it, and a plain HEVC decoder can't be pointed at it either — the EL/RPU NAL units aren't droppable metadata, they corrupt/stall the decode. → **audio only, black video.**

`ffprobe` reports `Profile 7` for **both** MEL and FEL; telling them apart needs enhancement-layer parsing (`dovi_tool`), which we don't ship. So the server can't auto-classify — it warns for all Profile 7 and lets the outcome tell you which you have.

---

## On-the-Fly Fallback: NAL Stripping & Decoder Bypass

To handle Profile 7 FEL files (like *Titanic*) on the fly without permanent file conversion, BitStreamer supports real-time NAL unit stripping and decoder bypassing.

This can be triggered in two ways:
1. **In-Client Remote Menu (Recommended):** Pressing the **Menu (≡)** key on your remote while a Dolby Vision file is highlighted in single-file or folder list views brings up a selection menu: `Play Normally` or `Strip DV and Play`. Choosing the latter forces the fallback on demand.
2. **Server-side command-line flag:** Launch the server with the `-stripdv` command-line argument:
   ```cmd
   bitstreamer.exe -stripdv "Titanic.mkv"
   ```

### Profile 7 Fallback Isolation (Crucial)
The HEVC/HDR10 fallback mechanism is **strictly isolated to Dolby Vision Profile 7** files:
```kotlin
val fallbackToHdr10 = (srcDvProfile == 7) || srcStripDV
```
* **For Profile 7:** Dual-layer Profile 7 files (FEL/MEL) automatically trigger fallback to standard HEVC/HDR10 with on-the-fly NAL 62/63 stripping, ensuring crisp video playback on Fire TV without black screens.
* **For Profile 5 and 8:** The fallback is bypassed (unless explicitly forced via `srcStripDV`), so single-layer Dolby Vision files play natively in Dolby Vision.

### How the Fallback Works Under the Hood:
1. **Decoder Exclusions:** The client player intercepts the decoder selection pipeline inside [`PlayerFactory.kt`](file:///n:/AI/ai_coder/BitStreamer/client/app/src/main/java/com/bitstreamer/client/playback/PlayerFactory.kt#L113-L200), maps the track MIME type from `video/dolby-vision` to `video/hevc`, clears the Dolby Vision `codecs` metadata, and **excludes all hardware Dolby Vision decoders** (filtering out any decoder containing `"dolby"` or `"dovi"`). This forces the device to bind a standard HEVC/HDR10 hardware decoder.
2. **On-the-Fly NAL Stripping:** As the stream plays, [`PlayerActivity.kt`](file:///n:/AI/ai_coder/BitStreamer/client/app/src/main/java/com/bitstreamer/client/ui/PlayerActivity.kt) intercepts incoming video packets (`onQueueInputBuffer`) and uses a custom real-time **Annex B parser** to strip NAL units of type `62` (RPU) and `63` (EL) before they reach the hardware decoder. This delivers a clean HEVC/HDR10 stream to the standard decoder, preventing the display chip from switching the TV into Dolby Vision mode (which causes the black screen).

---

## Lossless Offline Conversion (Alternative Fix)

If the on-the-fly fallback doesn't work or is not preferred, you can permanently and losslessly strip the Dolby Vision layers from the file using `ffmpeg`. This takes only a few seconds as it is a direct stream copy (`-c copy`):

```cmd
ffmpeg -i "Movie.mkv" -map 0 -c copy -bsf:v "filter_units=remove_types=62|63" "Movie_no_dv.mkv"
```

Then serve `Movie_no_dv.mkv` — it plays in standard HDR10 mode. (Do not use this on **Profile 5** — its base layer is not HDR10-compatible, so the colors will be incorrect.)

---

## Thumbnails and HDR

Chapter thumbnails and scrubbing previews are generated server-side with ffmpeg. HDR sources are **tonemapped** BT.2020/PQ→BT.709 (via the `zscale`/`tonemap` filters) so they don't look washed out — see [THUMBNAILS.md](THUMBNAILS.md). This needs an ffmpeg built with `zscale` (libzimg); the server detects it and prints whether HDR thumbnails will be tonemapped. A build without it still produces thumbnails, just not tonemapped.

---

## Audio is independent

None of the above affects audio — Dolby/DTS bitstreaming over HDMI is a separate path (see [AUDIO_PASSTHROUGH.md](AUDIO_PASSTHROUGH.md)). A Profile 7 FEL file still passes its TrueHD/Atmos audio perfectly while the video is black; that's the giveaway for FEL.
