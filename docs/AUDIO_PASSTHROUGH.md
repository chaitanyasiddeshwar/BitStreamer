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

## 7. DTS-HD strategy: core extraction (`DtsCoreAudioSink`)

Why DTS-HD MA fails out of the box: Media3's extractors *analyze* `A_DTS` tracks
(`DtsUtil.isSampleDtsHd`) and relabel DTS-HD content `audio/vnd.dts.hd`. Playing that
needs an `ENCODING_DTS_HD` passthrough AudioTrack — which Fire TV never advertises — or a
platform DTS-HD decoder, which the sticks don't have either. Net result: the track is
unplayable and players like Emby fall back to server-side transcoding. We have no
transcoder, so the client fixes it the way Plex and Kodi's "DTS core" mode do
([Plex forum confirmation](https://forums.plex.tv/t/dts-hd-passthrough-not-working-on-new-fire-tv-cube-3rd/825191/55)).

The key property: **every DTS-HD MA frame begins with a self-contained,
backward-compatible DTS core frame** (48 kHz, up to 5.1, sync `0x7FFE8001`), followed by
extension substreams (sync `0x64582025`) carrying the lossless/extra-channel data.
Truncate each sample to its core frames and you have a valid plain-DTS stream any
DTS-capable sink accepts as `ENCODING_DTS`.

`DtsCoreAudioSink` (a `ForwardingAudioSink` around `DefaultAudioSink`) implements this:

- `getFormatSupport()`: claims support for `audio/vnd.dts.hd` when the inner sink
  supports the equivalent DTS-core format — this is what makes the track selectable and
  routes it through ExoPlayer's decoder-less bypass path.
- `configure()`: forces core extraction for DTS-HD **even when the sink advertises
  `ENCODING_DTS_HD`**. Field finding (Fire TV Stick 4K Max, AFTKM, Fire OS 8): the HDMI
  caps include DTS-HD, the direct passthrough AudioTrack opens without error, and the
  output is silent — matching Plex forum reports for the Fire TV Cube. The advertisement
  is not trustworthy; only Kodi's IEC packing gets real DTS-HD through. The direct path
  is kept behind `PREFER_DIRECT_DTS_HD` (default false) for future devices that honor it.
- `handleBuffer()`: truncates each sample **in place** to its core frame(s) using
  `DtsUtil.getFrameType`/`getDtsFrameSize`. In-place matters: `DefaultAudioSink` requires
  a retried buffer to be the identical instance, so the sink never allocates and never
  re-transforms a pending buffer.
- DTS Express (`A_DTS/EXPRESS`) has *no* core substream — such samples are skipped with a
  log line rather than fed to a DTS AudioTrack. Rare in practice; revisit only if it
  shows up in logs.

What full DTS-HD MA would take (out of scope): Kodi bypasses Android's encoding checks by
hand-packing IEC 61937 bursts into a multichannel PCM track ("IEC packer") — fragile,
device-specific, and the only known way on Fire TV. If ever attempted, it's a new sink
mode behind a setting, not a change to the core-extraction path.

## 8. Recovering from transient audio-device loss (app switch)

Switching away and back — **Home → open another app → return to BitStreamer** — can leave
the passthrough output momentarily uncreatable. The symptom in `client-logs.txt`:

```
audio sink error: AudioSink$InitializationException: AudioTrack init failed ... audio/eac3
  Caused by: UnsupportedOperationException: Cannot create AudioTrack
player error ERROR_CODE_AUDIO_TRACK_INIT_FAILED
playback state: 1            # IDLE — wedged
```

(You may also see `AudioTrack write failed: -6`, i.e. `ERROR_DEAD_OBJECT` — the HAL object
died under the app.) The HDMI/AVR audio route hands back to the app before it's actually
ready for a compressed-passthrough `AudioTrack`, so `AudioTrack.Builder.build()` throws.
The device frees up within a few seconds, but ExoPlayer treats the error as terminal and
stays in `STATE_IDLE` — previously the player was stuck until Fire OS killed the app.

Fix (`PlayerActivity`): on `onPlayerError`, if the code is `ERROR_CODE_AUDIO_TRACK_INIT_FAILED`
or `ERROR_CODE_AUDIO_TRACK_WRITE_FAILED` (`isRecoverableAudioError`), call `player.prepare()`
again with a ~1–3 s backoff (`MAX_AUDIO_RETRIES` attempts, ~40 s total) instead of showing
the error. `prepare()` retries from the retained position and keeps `playWhenReady`, so the
resume point and the resume dialog choice both survive. The retry budget resets on
`STATE_READY` (and each fresh `initializePlayer`), and the pending retry is cancelled in
`onStop`/`releasePlayer`. Only after the budget is exhausted does the error banner show.

Not every app-switch triggers this — it depends on the AVR/eARC handshake timing — but when
it does, the player now heals itself rather than needing a force-kill.

Diagnostics: everything the audio pipeline decides is logged via `RemoteLog`, which the
server appends to `client-logs.txt` next to `bitstreamer.exe` (`POST /log`). For a DTS
issue, that file answers: what mime the extractor produced, what the sink chose
(direct/core-extraction/decoder), what AudioTrack encoding was opened, and any sink error
with stack trace.
