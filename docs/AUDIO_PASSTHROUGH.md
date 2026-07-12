# Audio Passthrough on Fire TV — Design Notes

This is the technical core of BitStreamer. Read this before writing or changing any audio
code in the client. Everything here is client-side: the server never touches audio.

## 1. How passthrough works on Android

Android's `AudioTrack` accepts **compressed** bitstreams when created with an encoded
format (`ENCODING_AC3`, `ENCODING_E_AC3`, `ENCODING_E_AC3_JOC`, `ENCODING_DTS`,
`ENCODING_DTS_HD`, `ENCODING_DOLBY_TRUEHD`). The platform wraps the frames in IEC 61937
packets and sends them over HDMI unmodified; the TV/AVR decodes. `AudioTrack` creation
**fails if the connected HDMI endpoint doesn't advertise the encoding** (via EDID, as
filtered by Fire OS) — that failure, not a decode error, is what "sink doesn't support it"
looks like at runtime.

Media3/ExoPlayer automates this: `MediaCodecAudioRenderer` asks `DefaultAudioSink` whether
the format can be played *directly*; if yes, it skips the decoder entirely and feeds
demuxed audio samples straight to the passthrough `AudioTrack`. **Passthrough is the
default preference when supported — our job is to not defeat it and to verify it.**

Atmos over E-AC3 (streaming-style Atmos) is just `ENCODING_E_AC3_JOC` — a spatial
metadata layer inside a DD+ stream. A JOC stream passed as plain E-AC3 still decodes
(loses the height channels), which is also Media3's built-in fallback.

## 2. Fire TV device reality (capability ceiling)

What apps can achieve via the standard `AudioTrack` path (Kodi's IEC-packing hack is the
only known exception for lossless DTS, and we are not replicating it):

| Format | Stick 4K (1st gen) | Stick 4K Max (1st/2nd gen) | Notes |
|--------|--------------------|---------------------------|-------|
| AC3 (Dolby Digital) | ✅ | ✅ | |
| E-AC3 (DD+) | ✅ | ✅ | Fire OS auto-converts DD+→DD for DD-only sinks |
| E-AC3-JOC (Atmos) | ✅ | ✅ | Sink must be Atmos-capable |
| DTS | ✅ | ✅ | |
| DTS-HD MA | core only | core only (officially) | OS passes DTS core; full MA not app-reachable |
| TrueHD (+Atmos) | ❌ | 2nd gen only | Reported working on 4K Max 2nd gen |
| LPCM stereo | ✅ | ✅ | |
| LPCM multichannel | downmixed | downmixed | Sticks output 2.0 PCM |

Also required: Fire TV **Settings → Display & Sounds → Audio → Surround Sound = "Best
Available"** (the default). If set to PCM/Stereo, no passthrough will ever negotiate —
detect this (capabilities will report PCM-only) and tell the user in the error/overlay.

## 3. Capability detection on Fire OS

Per Amazon's [Dolby integration guidelines](https://developer.amazon.com/docs/fire-tv/dolby-integration-guidelines.html),
in order of preference (implement in `AudioCaps.kt`):

1. **`ACTION_HDMI_AUDIO_PLUG` sticky broadcast** — register a receiver; `EXTRA_ENCODINGS`
   carries the supported `ENCODING_*` array (includes `ENCODING_E_AC3_JOC` on Fire OS 7+).
   This is also what Media3's `AudioCapabilitiesReceiver` listens to internally.
2. **Fire OS 6 Atmos quirk**: API 25 has no `ENCODING_E_AC3_JOC` constant; check the global
   settings JSON `audio_platform_capabilities` → `audiocaps.atmos.enabled`.
3. **`AudioManager.getDevices(GET_DEVICES_OUTPUTS)`** → `AudioDeviceInfo.getEncodings()`
   on the HDMI device, as a cross-check.

Media3 does (1) automatically via `AudioCapabilities`; we implement `AudioCaps.kt` anyway
because we need the answer *before* playback for track selection (R3 in the plan) and for
the Fire OS 6 Atmos case, which stock Media3 on API 25 can't see.

Do **not** hardcode per-device tables in logic. The table in §2 is for documentation and
test expectations, not code — always trust the runtime-reported capabilities.

## 4. Rules for the Media3 setup (`PlayerFactory`)

1. `DefaultRenderersFactory` with `EXTENSION_RENDERER_MODE_OFF`. Never add the ffmpeg
   extension "for safety" — a software decoder that outranks the passthrough path is the
   classic way projects silently lose bitstreaming.
2. Never call `setEnableAudioTrackPlaybackParams(true)`; playback-speed processing forces
   PCM.
3. No `AudioProcessor`s, no equalizers, no `SonicAudioProcessor` paths. Any processing
   forces decode.
4. Leave tunneling off in v1 (`setTunnelingEnabled`) — it can help AV sync on Fire TV but
   has its own bug surface; revisit only if we see sync issues with passthrough on.
5. Track selection: query `AudioCaps` first; among the file's audio tracks prefer
   (a) passthrough-capable at highest quality, then (b) device-decodable (AAC, and
   AC3/E-AC3 — Fire TV has Dolby decoders; DTS decoders vary), else (c) fail with a
   specific message naming the format.
6. Bluetooth caveat: if audio output is Bluetooth (headphones), passthrough is impossible
   and Media3 will decode Dolby to PCM automatically — that's correct behavior, don't
   fight it.

## 5. Verification — never claim passthrough without this

The **only** ground truth is the AVR/TV front panel showing "Dolby Digital / Atmos / DTS".
Supporting evidence in-app:

- `AnalyticsListener.onAudioTrackInitialized(eventTime, audioTrackConfig)` — log encoding,
  sample rate, channel config on every playback start. `encoding == ENCODING_E_AC3_JOC`
  etc. ⇒ passthrough `AudioTrack` was created; `ENCODING_PCM_16BIT` ⇒ something decoded.
- Debug overlay (toggle with remote's Menu key): input format from the extractor
  (`Format.sampleMimeType`, e.g. `audio/eac3-joc`), negotiated AudioTrack encoding,
  sink capabilities snapshot from `AudioCaps`.
- `adb logcat` on the stick: `DefaultAudioSink` logs track creation; `AudioTrack` errors
  appear here when the sink rejects an encoding.

Emulators and phones cannot validate any of this — HDMI capability negotiation requires
the physical stick plugged into the actual TV/AVR chain.

## 6. Known pitfalls (learned from Emby/Jellyfin/Kodi)

- **Wrong layer blamed**: most "passthrough broken" reports in Jellyfin trace to the
  *server* deciding to transcode on bad capability info
  ([#1753](https://github.com/jellyfin/jellyfin-androidtv/issues/1753),
  [#5168](https://github.com/jellyfin/jellyfin-androidtv/issues/5168)). BitStreamer has no
  such layer — if audio is wrong, it's the client config or the platform ceiling (§2).
- **AVR chains matter**: a non-Atmos TV between stick and soundbar (ARC without eARC)
  caps what EDID advertises. When capabilities look wrong, check the physical chain and
  the Fire TV surround-sound setting before touching code.
- **E-AC3-JOC on DD+-only sinks**: plays as DD+ without height channels — expected, not
  a bug (Media3 handles the fallback since [ExoPlayer #6073](https://github.com/google/ExoPlayer/issues/6073)).
- **DTS-HD extraction**: fixed in recent Media3 (core+extension substreams combined into
  one sample) — one more reason to stay on the latest stable (1.10.x as of mid-2026).
