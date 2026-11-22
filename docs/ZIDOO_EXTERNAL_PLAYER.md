# Zidoo external-player mode — plan

Status (2026-11-22): **Phases 1–3 implemented, tested on the Zidoo (fw 1.3.75) and Fire TV,
and committed** on branch `zidoo-external-player`.

## Back from the Zidoo player — resolved (build zidoo9, 2026-11-22)

`playback/ExternalPlayer.kt` `buildIntent` adds `FLAG_ACTIVITY_CLEAR_TOP` and
`FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`. These are the same flags JellyfinToZidoo uses (`Play.java`).
Back from the Zidoo player now returns to BitStreamer. The test-only version bump was reverted.

Not explicitly confirmed yet: the **resume test** in Zidoo mode (does a hand-off with `start=…`
start there, and is the stop position stored?).

## What was built

| File | Change |
|---|---|
| `settings/PlayerSettings.kt` (new) | Saved player choice (`bitstreamer_settings` / `player_mode`, default `internal`). `effectiveMode()` returns EXTERNAL only if it is saved **and** a Zidoo player is present. |
| `playback/ExternalPlayer.kt` (new) | Finds the Zidoo player activity, builds the launch intent, and wraps the 9529 API (`v2/openFile`, `getPlayStatus`, `seekTo`). |
| `ui/ExternalPlaybackActivity.kt` (new) | Resume / Start over / Mark-as-done prompt, then hand-off (activity route, else 9529 route). Polls position every 10 s and posts it; on return, posts the final position (0 when ≥ 90 % played, the same rule as `PlayerActivity`). Logs a device probe to `/log`: route, HDMI encodings, DV caps. |
| `ui/DiscoveryActivity.kt` + layout | **`Player: BitStreamer` / `Player: Zidoo`** button next to Rescan, toggled with OK. Shows a hint toast where no Zidoo player exists (Fire TV), so it can't be switched there. Single-file servers also follow the setting. |
| `ui/BrowserActivity.kt` | A plain click follows the setting. The Menu-key file menu gets "Play in Zidoo player" plus "Play in BitStreamer player" (Zidoo only). DV strip / DV8 options always use the internal player. |
| `AndroidManifest.xml` | `<queries>` for the Zidoo packages (Android 11 package visibility) + the new activity (not exported). |
| `DtsCoreAudioSink.kt` / `PlayerFactory.kt` | Forced DTS-core extraction is now **Fire TV only** (`Build.MANUFACTURER == "Amazon"`). Other devices get direct DTS-HD when the sink advertises it, otherwise the core as before. |

**Fire TV:** with no Zidoo player present, `effectiveMode()` is always INTERNAL and the DTS
policy is unchanged (core forced), so every Fire TV code path is identical to before.

## Test round 1 on build 1.0.0-zidoo1 (2026-11-18 17:39–17:55)

**Zidoo player (external):** the activity route resolved to
`com.zidoo.player/.activity.PlayerActivity` (check 0.1). Every file played, including DV7 FEL titles:
- `getPlayStatus` showed TrueHD Atmos with `DV(TV-LED)` output and DTS-HD MA 5.1.
- The result intent arrives with `resultCode=-1` (OK) and extras
  `position, duration, end_by, url, audio_idx, subtitle_idx` (0.8 passed). The stored position
  matched it.
- **Bug:** after the player closes (Back twice), the Zidoo shows its **apps page**, not
  BitStreamer. The result is still delivered and stored, so BitStreamer's task is only in the
  background. Likely cause: the Zidoo player runs in its own task (singleTask or
  singleInstance), so when it finishes, Android returns to the launcher. Proposed fix:
  `moveTaskToFront` (REORDER_TASKS) when the result arrives, plus logging the player's
  `launchMode`/`taskAffinity` to confirm.

**BitStreamer player (internal):**

| File | Video | Audio | Result |
|---|---|---|---|
| hdr10+test_lake (mp4) | HEVC HDR10+, hevc decoder | AAC → PCM | ✅ |
| HDR10Plus_PB_EAC3JOC | HEVC HDR10+ | DD+ passthrough | ✅ |
| Shazam2 trailer | HEVC | DTS-HD MA → **direct DTS-HD** (this branch) | ✅ plays; AVR label to confirm |
| About Time, Oppenheimer | HEVC | DTS-HD MA → direct DTS-HD | ✅ plays; AVR label to confirm |
| Arrival (Demos3) | HEVC | TrueHD passthrough | ✅ |
| Ford v Ferrari (DV **P8** + HDR10+) | DV decoder `dvhe.08` | TrueHD passthrough | ✅ |
| Colored Oils, Food, Aquaman, Predator Badlands, The Running Man (DV **P7**: `dvhe.07.06/07/09`) | DV decoder | — | ❌ `IllegalStateException` in `OMX.amlogic.dolby-vision.dvhe.decoder.awesome2`, both with CONVERT_P8 (auto) and NATIVE ("Play Normally") |

So in the internal player only **Dolby Vision Profile 7** fails, and only in video. P8 on the same
decoder works. That points at the decoder being configured as P7 (from the `dvhe.07.xx` codecs
string / `dvcC`) while ExoPlayer feeds it a single layer.

Owner confirmed: the AVR showed **DTS-HD** for Shazam and Oppenheimer in the internal player,
so direct DTS-HD passthrough works on the Zidoo.

**Which test files are FEL** (checked with ffprobe `-show_frames` on the DV RPU NLQ parameters;
MEL = trivial NLQ: `nlq_offset=0`, `linear_deadzone_slope=0`, `vdr_in_max=2^23`):
- **FEL:** Colored Oils, Food, Predator Badlands, The Running Man (`nlq_offset=512`, slope 2048).
- **MEL:** Aquaman.

On stable firmware 1.3.45 the Zidoo player plays FEL **as P8**: it decodes the base layer + RPU
and ignores the FEL residual. True dual-layer composition only comes with 1.3.65 (beta).

## Fixes in build 1.0.0-zidoo2 (not yet tested)

1. **Back lands on the apps page:** `ExternalPlaybackActivity` calls
   `ActivityManager.moveTaskToFront(taskId)` (new `REORDER_TASKS` permission) when the player
   returns, then finishes, so the browser is shown. It also logs the Zidoo player's
   `launchMode`/`taskAffinity`.
2. **DV P7 in the internal player (non-Fire-TV only):**
   - With CONVERT_P8, the codecs string is relabelled `dvhe.07.xx` → `dvhe.08.xx`
     (`PlayerFactory.relabelDvCodecsP7ToP8`), so the Amlogic decoder is configured for the
     single-layer 8.1 stream it actually receives.
   - If a P7 file still fails to decode, `PlayerActivity.tryDvHdr10Fallback` retries once with
     STRIP_HDR10 and shows a toast.
   - Fire TV is excluded from both (`DtsCoreAudioSink.isFireTv()`).
   - "Not converting" was already tested: the NATIVE runs failed the same way.
3. **Menu:** on a Zidoo, "Play in BitStreamer player" now uses automatic DV handling. "Play
   Normally (no DV processing)" is the explicit NATIVE option, shown for DV files only. Fire TV
   still shows the original "Play Normally".

## Test round 2 (zidoo2, 18:18–18:27) → fixes in 1.0.0-zidoo4 (untested)

- **The P8 relabel works:** P7 now decodes on the Amlogic DV decoder. But the FEL titles
  **flash**: Predator Badlands dark, Colored Oils bright. MEL Aquaman doesn't. The Zidoo player
  log shows `launchMode=1 (singleTop)`, `taskAffinity=com.zidoo.player`.
  - Likely cause: single-track dual-layer MKVs carry the EL (NAL 63) inline in each access unit.
    The ExoplayerHdrUtils converter (libdovi `dolby_vision` 3.3.2) rewrites the RPU (NAL 62) but
    the EL still reaches the decoder. Amlogic seems to apply the FEL residual partially; Fire
    TV's MTK decoder ignores it. MEL residual is zero, which is why Aquaman is fine.
  - zidoo4 drops NAL 63 before the decoder on this path (keeps the RPU) and logs
    `P7->P8 EL strip: N EL NAL units removed`.
- **Back still lands on the Zidoo home:** the logs show no `player returned` and no poll lines
  after the hand-off (Aquaman 17:49 and 18:22, Running Man 17:54, Colored Oils 18:26). BitStreamer's
  process is killed in the background during big remuxes, so the result is lost and the task is
  gone.
  - zidoo4 runs a foreground keep-alive service (`HandoffKeepAliveService`, type dataSync) for the
    duration of the hand-off.
  - It also logs `onStop` / `onRestart` / `onTrimMemory` / `onDestroy` with immediate flushes, to
    confirm.

## Side issue: Fire TV "spinner after resume" on big remuxes (zidoo3 diagnostics, 18:34)

The Odyssey (UHD remux, DTS-HD MA → DTS core) on the AFTKM:
- Paused at 15.9 s for about 2 s. On PLAY the state went to BUFFERING within 20 ms and stayed
  there.
- STALL snapshots showed `totalBuffered=31600ms isLoading=false bytes+0`. The buffer was full and
  nothing needed loading, so **this is not the network**: a renderer didn't come back after the
  pause.
- A seek recovered it.

zidoo5 adds three things:
- `video renderer ready=` transitions;
- a `DtsCoreAudioSink` "sink refusing data for N ms" log, which would mean the passthrough
  AudioTrack isn't draining;
- decoder counters in each STALL line, and an **auto-recover**: after 5 s stuck with ≥ 3 s buffered
  and nothing loading, `seekTo(currentPosition)`, at most twice per stall.

### zidoo5 results (18:41–18:44) → zidoo6

- **Fire TV stall cause:** after PLAY, `DtsCoreAudioSink` logged "sink refusing data" with a
  frozen position. Audio counters stopped (rendered=1737) and video waited (rendered=439). The
  **passthrough DTS AudioTrack doesn't resume after pause**.
  - The auto-recover `seekTo(currentPosition)` was a **no-op**: ExoPlayer ignores a seek to the
    exact current position.
  - The owner's real seek recreated the AudioTrack ("AudioTrack initialized" twice) and recovered.
  - zidoo6 recovers after 3 s by seeking **1 s back**.
- **Zidoo flashing persists** even with the EL stripped: 556 EL NALs were removed per 48 frames,
  so the EL *was* reaching the decoder, but that wasn't the cause.
  - It now affects MEL Aquaman too, so it's an RPU/conversion issue on the Amlogic DV decoder.
  - zidoo6 parses every converted frame with the converter's own libdovi (`getFrameInfo`) and
    logs the profile/EL-type mix plus any changes. A frame-to-frame mix of P7/P8/unparsable RPUs
    would explain the flashes.

### zidoo6 results (18:49–18:55) → decisions in 1.0.0-zidoo7

- **Fire TV DTS stall: fixed by the workaround.** The auto-recover (1 s seek back after 3 s
  stuck) fired three times on The Odyssey and resumed playback each time. The owner confirms it
  happens **only with DTS**, never TrueHD: the Fire TV DTS passthrough AudioTrack doesn't resume
  after pause. The diagnostics stay: PLAY/PAUSE/SEEK/STALL/LOAD ERROR lines and the sink-refusal
  line. The per-tick `loading=` log was removed (too chatty). A possible later root fix: recreate
  the AudioTrack on resume for DTS on Fire TV.
- **Zidoo P7→P8 in the internal player: abandoned.** The probe showed every converted frame was a
  valid P8 RPU (`{p8/NONE=480}, changes=0`) with the EL removed, yet the owner saw constant
  irregular light/dark flashes and now full black screens, on FEL *and* MEL. The Amlogic DV
  decoder doesn't handle this converted single-layer stream.
  - zidoo7 reverts the relabel, the EL strip and the RPU probe (`PlayerFactory.kt` restored from
    git plus the DTS policy).
  - **zidoo8 (owner's call):** on Amlogic DV decoders (`dvDecoderName` contains "amlogic"),
    `planDv` sends **P7 as-is (NATIVE)**. On fw 1.3.45 the decoder rejects it and
    `tryDvHdr10Fallback` retries once as HDR10 with a toast. On fw 1.3.65+ (FEL support) it
    may decode natively with no code change. Fire TV keeps CONVERT_P8. No convincing root cause
    was found for the conversion flashing.
  - For Dolby Vision on P7 titles, use Player: Zidoo.
- **Back → Zidoo home: not pursued** (owner's call). The keep-alive foreground service,
  `moveTaskToFront` / `REORDER_TASKS` and the lifecycle logging were removed. The results are
  still saved (`position` result plus 10 s polling) whenever BitStreamer survives the hand-off.

## Internal (BitStreamer) player on the Zidoo — what the logs show (2026-11-18 16:14–16:42)

Source: the server's `client-logs.txt`, from sessions run before this branch.

- **HDMI encodings offered to apps:** AC3, E-AC3, E-AC3-JOC, DTS, DTS-HD, IEC61937
  (`[2,4,5,6,7,8,13,18]`). TrueHD is not listed.
- **TrueHD / TrueHD Atmos still bitstreams** (`AudioTrack initialized: Dolby TrueHD [PASSTHROUGH]`).
  DD+ Atmos bitstreams too. This matches the owner's "Atmos plays well already".
- **DTS-HD MA was cut to the DTS core** by the old forced override, even though the sink
  advertises DTS-HD. This branch lets non-Fire-TV devices try direct DTS-HD; **the AVR must
  confirm "DTS-HD MA" (not silence, not plain DTS)**. If it fails, restore the core for this
  device as well.
- **Dolby Vision Profile 7 fails in the internal player:** plan=CONVERT_P8, then the Amlogic
  decoder `OMX.amlogic.dolby-vision.dvhe.decoder.awesome2` throws `IllegalStateException`
  (ERROR_CODE_DECODING_FAILED, `dvhe.07.07` / `dvhe.07.09`). The P7→P8 conversion that works on
  Fire TV doesn't work with this decoder. The Zidoo player handles P7 itself, so on the Zidoo
  **use Player: Zidoo for DV7**. A possible later fix: auto-retry with STRIP_HDR10, or rewrite
  the codecs string to `dvhe.08`, when the DV decoder fails.
- **HDR10 / HDR10+ / DV P8 / non-DV:** no device-specific workaround applies. The DV policy is
  capability-based (the Zidoo has a DV decoder, so no stripping), and the Zidoo player bypasses
  all of it anyway. Nothing else needed disabling.
Target device: Zidoo Z9X 8K (Amlogic S928X-K, Android 11, firmware 1.2.40 → 1.3.45).

## Why

On a Zidoo, only Zidoo's **own** video player bitstreams TrueHD Atmos, DTS-HD MA and DTS:X
untouched. Third-party apps, BitStreamer's ExoPlayer included, are reportedly offered only DD/DD+
by Zidoo's Android audio stack (unverified on the 8K; Phase 0 logs `AudioCaps` to check). The
Zidoo player also handles Dolby Vision itself, including Profile 7 FEL (dual-layer from the 1.3.65
beta).

So on a Zidoo, BitStreamer keeps doing what it's good at (discovery, browsing roots and folders,
resume points on the server) and hands **playback** to the Zidoo player, using the same HTTP
`/stream` URL it already plays. The one rule (never transcode, never remux) still holds: the
Zidoo player reads the raw file bytes over HTTP Range, the same way ExoPlayer does.

## Hard requirement: Fire TV must not change

- The default player setting is **Internal (BitStreamer)**. Existing installs keep that
  default, so a Fire TV behaves exactly as it does today.
- The External option can only be chosen when a supported Zidoo player component is installed.
  On a Fire TV the setting shows as "Internal" and the External choice is disabled.
- **No changes** to `PlayerActivity`, `PlayerFactory`, `DtsCoreAudioSink`, `AudioCaps` or any
  other audio or video path. The new code lives in new files, plus small hooks in
  `DiscoveryActivity` (the setting) and `BrowserActivity` (choosing which player to launch).
- The server is unchanged unless Phase 0 shows the Zidoo player needs a file extension in the
  URL. In that case we *add* an alias route (e.g. `/stream/<name>.mkv?...`) and leave the
  existing `/stream` untouched.
- Before merging, rerun the Fire TV regression list (Phase 4).

## What is known about the Zidoo player interface

From the source of [JellyfinToZidoo](https://github.com/magnetgrouplabs/JellyfinToZidoo)
(`Play.java`), which already drives the Zidoo player from another app:

**Launch intent** (`Intent.ACTION_VIEW`, data = media URI, type `video/*`):

| Firmware | Component |
|---|---|
| Newer (Z9X 8K 1.1.42+ per JellyfinToZidoo) | `com.zidoo.player` / `com.zidoo.player.activity.PlayerActivity` |
| Older | `com.android.gallery3d` / `com.android.gallery3d.app.MovieActivity` |

| Extra | Type | Meaning |
|---|---|---|
| `title` | String | Title shown in the player |
| `from_start` | Boolean | `true` = start at 0; `false` = use `position` |
| `position` | Int (ms) | Start position |
| `audio_idx` | Int | Audio track index to select |
| `subtitle_idx` | Int | Subtitle track index to select |
| `return_result` | Boolean | `true` = the player returns a result when it closes |

Flags: `FLAG_ACTIVITY_CLEAR_TOP`, `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`. It is started with
`startActivityForResult`. The result intent carries `position` (Int, ms) at exit.

**Local control API** on the device, `http://127.0.0.1:9529/` (also used by the
[wizmo2/zidoo-player](https://github.com/wizmo2/zidoo-player) Home Assistant integration):

- `ZidooVideoPlay/getPlayStatus` returns JSON with `status == 200` and a `video` object holding
  `status`, `path`, `title`, `duration`, `currentPosition`, `width`, `height`, `fps`, `bitrate`,
  `audioInfo`.
- `ZidooVideoPlay/seekTo?positon=<ms>` seeks. (Zidoo's API spells it "positon".)
- `ZidooVideoPlay/getAudioList`, `setAudio?index=`, `getSubtitleList`, `setSubtitle?index=`.
- If calls are refused, turn off validation in the Zidoo **Control Center** app.

**Not yet verified:** JellyfinToZidoo feeds the player **SMB** paths. That the Zidoo player
bitstreams lossless audio from an **HTTP** URL is the main open question; Phase 0 answers it.

## Design

### 1. Player setting on the first screen (`DiscoveryActivity`)

- Add a button to `activity_discovery.xml`, next to *Connect* / *Rescan*:
  **`Player: Internal`** or **`Player: Zidoo (external)`**. Pressing it toggles between the
  two. D-pad focusable, in the same style as the existing buttons.
- Store the choice in `SharedPreferences` (file `bitstreamer_settings`, key `player_mode`,
  values `internal` | `external`, default `internal`) so it survives app restarts.
- Put this in a new `settings/PlayerSettings.kt`, which:
  - reads and writes the preference;
  - reports whether external play is possible, using `ExternalPlayer.detect()`.
- If no Zidoo player is installed, the button reads `Player: Internal`, the press does nothing,
  and a short toast says "No Zidoo player found".
- If the saved value is `external` but the player has gone (e.g. after a firmware change), fall
  back to internal for that playback and show a toast. The saved value is kept.

### 2. Launcher (`playback/ExternalPlayer.kt`, new)

- `detect(context)` returns which component is installed: `com.zidoo.player` first, then
  `com.android.gallery3d`. It checks via `PackageManager.resolveActivity` on an explicit
  component, so it's cheap and fails safe.
- `buildIntent(url, title, startMs)` builds the intent from the tables above. `audio_idx` and
  `subtitle_idx` are left out for now; the Zidoo player's own default and remembered-track logic
  applies.

### 3. Hand-off screen (`ui/ExternalPlaybackActivity.kt`, new, not exported)

`BrowserActivity.playFile(...)` checks `PlayerSettings`. If the mode is internal, it launches
`PlayerActivity` exactly as today. Otherwise it launches `ExternalPlaybackActivity` with the URL,
title and info path. That activity:

1. fetches the resume point with `ServerApi.getResumePositionMs(path, root)` on a background
   thread;
2. starts the Zidoo player with `startActivityForResult`, using a simple black "Playing on Zidoo
   player…" screen;
3. polls `127.0.0.1:9529/ZidooVideoPlay/getPlayStatus` every 10 s while the player is open,
   remembers the last position, and posts it with the existing `ServerApi.postPosition`. This
   way a crash or power cut doesn't lose the resume point;
4. on result, takes the `position` extra (or the last polled position if it is 0 or missing),
   then:
   - posts it;
   - if the position is within the last ~2 % or 3 min of the duration (the same rule
     `PlayerActivity` uses), clears the resume point by posting 0;
   - finishes and goes back to the browser.

Resume stays keyed by **client IP** on the server, so the Zidoo gets its own resume points,
separate from the Fire TV's.

### 4. Per-file override (optional, low cost)

The long-press/file menu in `BrowserActivity` gets one extra item showing the *other* player
("Play in BitStreamer player" or "Play in Zidoo player"). This makes A/B comparisons easy. It
only appears when the Zidoo player exists.

### What external mode does not do (the Zidoo player's own screens replace these)

- Chapter thumbnails, the storyboard scrub and the stats overlay. The Zidoo shows its own
  chapter list and info.
- Sidecar `.srt`/`.ass` subtitles. Over HTTP the player can't find them next to the file;
  subtitles inside the MKV work. A later option: pass the server's `/subtitle` URL if the player
  accepts one (unknown).
- DV strip / DV8 convert options. They are hidden, because the Zidoo handles DV itself.
- Folder auto-advance. Each file is one hand-off. Set the Zidoo's *Quick Settings → Playback →
  Play mode* to **Single file** so the position it returns belongs to the file we launched.

## Phases

### Phase 0 findings so far (2026-11-18, Z9X 8K, firmware v1.3.45, Android 11)

- **There is no ADB on this box.** Port 5555 accepts TCP connections, but `adb connect` stays
  `offline` forever: no authorization prompt appears and there is no Developer-options toggle.
  The CoreELEC Z9X 8K notes confirm "ADB developer tools have been removed from this device". The
  ADB commands below therefore **cannot be used**. Probing goes through the control API
  instead, and later through BitStreamer itself (it logs to the server's `/log`).
- **The control API on port 9529 can be reached from the LAN**, not only from 127.0.0.1, with no
  validation prompt. `ZidooControlCenter/getModel` returns the model, firmware `v1.3.45`, 6 GB RAM
  and Android 11. `ZidooControlCenter/Apps/getApps` lists launcher apps only; the video player
  is not in that list, so its component name still has to be found from inside the app
  (`PackageManager`).
- **The Zidoo player plays BitStreamer's HTTP `/stream` URL** with no file extension needed (check
  0.9 passed). The working launch call, sent from the PC, was:
  `GET http://<zidoo>:9529/ZidooFileControl/v2/openFile?url=<urlencode("upnp://zidoo-rc/0.5.0?type=2&res=" + urlencode(streamUrl))>`.
  It returned `{"code":0,"msg":"Success"}`. (v1 `ZidooFileControl/openFile?path=<http url>` returns
  805: it only takes local or SMB paths.)
- **`getPlayStatus` works while playing (check 0.7 passed).** It returns `currentPosition`,
  `duration`, `audioInfo` ("English Dolby TrueHD 5.1 48.0KHz") and `output` ("1920X1080P @
  29.97Hz YUV444 10Bits SDR"). The title shown is derived from the URL
  (`stream?root=3&path=...`), so the hand-off should pass a proper title.
- **Second launch route:** BitStreamer could start playback through
  `127.0.0.1:9529/.../v2/openFile` instead of an intent. That needs no component name and no
  ADB. The drawback is that it gives no `return_result`, so position comes only from polling.
  Plan: intent first (gives the `position` result), with the 9529 route as the fallback when no
  component resolves.
- **Lossless bitstream over HTTP: PASSED (0.2–0.4).** Four clips were launched in sequence from
  the PC via `v2/openFile` with BitStreamer `/stream` URLs, and the owner read the AVR's display:

  | Clip | Zidoo `audioInfo` | AVR showed |
  |---|---|---|
  | Dolby_Countdown_v1_Lossless (1080p) | Dolby TrueHD 5.1 48 kHz | TrueHD 5.1 |
  | DOLBY_ATMOS_UNFOLD_2 (1080p23.976) | Dolby TrueHD with Dolby Atmos 48 kHz | Dolby Atmos |
  | IMAX_SONIC_ANTHEM (1080p24) | DTS-HD MA 5.1 48 kHz | DTS-HD MA |
  | A Beautiful Planet UHD remux, 2160p HDR10+ | DTS-HD MA + IMAX DTS:X 48 kHz | IMAX DTS:X |

  The Zidoo also matched the frame rate (29.97 / 23.976 / 24) and output 2160p HDR10+. The DTS:X
  run was stopped by the owner, so the seek test (0.6) was not done. The probe script is
  `zprobe.py` (session scratchpad; the logic is all in the calls above).
  End of file: `getPlayStatus` goes `video.status 1` → `0` (position 0) → HTTP-level
  `status 806` once the player has closed.
- **The go/no-go gate is passed**, so external mode uses HTTP and the SMB fallback is not needed.
- Still open: 0.1 (component name), 0.5 (start at `position`), 0.6 (seek on a big remux),
  0.8 (`position` result), 0.10 (`AudioCaps`). All of these can only be checked from inside the
  app, so they go into Phase 1 as logged probes.

### Phase 0: probe on the Zidoo (no code) — original ADB version, not usable on the 8K

Owner installs the current APK on the Zidoo (see *Install* below) and enables network ADB. Then,
from the PC:

```sh
adb connect <zidoo-ip>:5555
adb shell getprop ro.build.version.release        # Android version
adb shell getprop | grep -i -E "zidoo|version"    # firmware string
adb shell pm list packages | grep -i -E "zidoo|gallery3d"
adb shell cmd package resolve-activity --brief -a android.intent.action.VIEW -t video/* -d http://x/a.mkv
adb shell dumpsys package com.zidoo.player | grep -A3 -i "Activity\|filter"
adb shell dumpsys audio | grep -i -E "encodings|hdmi|passthrough"   # what Android offers apps
```

Start the Zidoo player on a BitStreamer stream (take the URL from the server's output, or
build it as `streamUrlForPath` does):

```sh
adb shell am start -a android.intent.action.VIEW -t "video/*" \
  -n com.zidoo.player/com.zidoo.player.activity.PlayerActivity \
  -d "http://<pc-ip>:<port>/stream?path=...&root=0" \
  --es title "Probe" --ez from_start false --ei position 600000 --ez return_result true
adb shell curl -s http://127.0.0.1:9529/ZidooVideoPlay/getPlayStatus   # or from the PC: http://<zidoo-ip>:9529/...
```

Run BitStreamer's own player once too, and collect the `AudioCaps` lines from the server's
client log (`/log`). This records what Android exposes to third-party apps on the Zidoo.

**Pass criteria (owner watches the AVR's front panel):**

| # | Check | Result |
|---|---|---|
| 0.1 | Which player component exists | |
| 0.2 | TrueHD Atmos film over HTTP: AVR shows *Dolby Atmos / TrueHD* | |
| 0.3 | DTS-HD MA film over HTTP: AVR shows *DTS-HD MA* | |
| 0.4 | DTS:X film: AVR shows *DTS:X* | |
| 0.5 | Start at `position` works (not overridden by the Zidoo's own resume) | |
| 0.6 | Seeking / chapter skip over HTTP works on a 50+ GB remux | |
| 0.7 | `getPlayStatus` returns `currentPosition` while playing | |
| 0.8 | Back/Stop returns to the launcher with a `position` result | |
| 0.9 | Does the player need a `.mkv` extension in the URL? (try `/stream?...` and an aliased URL) | |
| 0.10 | `AudioCaps` for third-party apps (BitStreamer's own player) | |

**Go / no-go:** if 0.2 or 0.3 fails over HTTP, stop. Revise the plan for SMB: the Zidoo mounts
the PC share, and BitStreamer maps server paths to `smb://` URIs the way JellyfinToZidoo does
with path substitution.

### Phase 1: setting + launcher

`PlayerSettings.kt`, `ExternalPlayer.kt`, the button in `activity_discovery.xml` and the
`DiscoveryActivity` hook. Build and install on both devices. The Fire TV shows `Player: Internal`
and nothing else changes.

### Phase 2: hand-off + resume

`ExternalPlaybackActivity.kt`, manifest entry (`exported="false"`), the `BrowserActivity.playFile`
switch, polling, posting the position, and clearing near the end.

### Phase 3: per-file override (optional) + polish

Menu item, toasts, a log line per hand-off through `RemoteLog` (component, URL path, start/end
position).

### Phase 4: test + docs

**Zidoo:**
- TrueHD Atmos, DTS-HD MA, DTS:X, DD+ Atmos, LPCM 7.1
- DV profile 5, profile 8, and profile 7 FEL (FEL needs the 1.3.65 beta with *FEL Processing Mode
  → Composition*), plus HDR10 and HDR10+
- resume after Back, resume after pulling power mid-film, clearing at the end
- setting persists across app restart and reboot

**Fire TV regression:**
- TrueHD/Atmos, DTS core, DD+ JOC
- resume, chapters, storyboard
- DV strip / DV8 convert
- the setting shows Internal and can't be changed

**Docs:** `USER_MANUAL.md` (player setting + Zidoo section), `docs/releasenotes`, and this file
(fill in the Phase 0 results).

## Install on the Zidoo

1. **From BitStreamer's own server (easiest).** Run `bitstreamer.exe` on the PC. On the Zidoo,
   open the browser (or a downloader app) at the `http://<pc-ip>:<port>/client.apk` URL the
   server prints, open the downloaded file, and allow installs from that app when Android asks.
2. **ADB (best for repeated test builds).**
   - On the Zidoo, go to Settings → About and click *Build* 7 times, then turn on USB/network
     debugging in Developer options. Menu names vary by firmware.
   - Note the IP under Settings → Network.
   - On the PC, run `install-client.bat <zidoo-ip>`. The script says "Fire TV" but works for any
     box with ADB on port 5555.
   - Accept the "Allow debugging" prompt on the TV.
3. **USB stick.** Copy `client.apk` onto it and open it in the Zidoo's File Manager.

BitStreamer then appears in the Zidoo's app list. Its banner and `LEANBACK_LAUNCHER` entry are
already in the manifest.

## Risks

- **HTTP vs SMB** (Phase 0 decides).
- **The Zidoo player's own resume may override `position`.** JellyfinToZidoo sets `from_start`
  for this reason; Phase 0 check 0.5.
- **Firmware updates may rename the component.** `detect()` falls back between the two known
  ones, and the internal player is always available.
- **The 9529 API may require Control Center validation to be off.** Polling is best-effort; the
  result `position` is the main source.

## DV7 on firmware 1.3.75 (2026-11-22)

- **Auto HDR10 fallback off:** `PlayerActivity.DV7_AUTO_HDR10_FALLBACK = false`. A DV decode
  failure now logs `auto HDR10 fallback disabled` and shows a DV error screen (plan, decoder,
  codecs, cause chain) instead of silently retrying as HDR10. Use the menu's Strip DV option to
  play as HDR10.
- **Convert to DV8 relabel restored:** on non-Fire-TV devices, CONVERT_P8 relabels the decoder
  codecs `dvhe.07.xx` → `dvhe.08.xx` again (`PlayerFactory.relabelDvCodecsP7ToP8`, logged as
  `CONVERT_P8: decoder codecs relabelled`). Without the relabel, the Amlogic decoder rejects the
  converted stream (log 2026-11-22 10:54: plan=CONVERT_P8, `dvhe.07.06`, IllegalStateException).
  The relabel caused flashing on fw 1.3.45; this re-tests it on 1.3.75.

### Convert to DV8 rewrite (2026-11-22) — verified on the Zidoo; Fire TV re-test pending

The relabel alone brought the flashing back on fw 1.3.75. Reading the library's native source
(`libdovi-android/src/lib.rs` on GitHub; unchanged since 2026-06-18, so 0.4.1 has the same code)
showed how it rewrites frames: the converted RPU is shorter, so it pads the gap **after the RPU**
with a filler NAL (type 38) or with zero bytes. It also leaves the EL (NAL 63) in the frame, never
relabels the codecs, and throws if the first RPU won't parse. Its `getRpuFrameInfo` loops forever
on an unparsable RPU.

- `playback/DvP8Converter.kt` replaces `HdrCompatMediaSourceFactory`. Only the library's native
  `processHevcFrame` (libdovi `convert_with_mode(2)`) is kept. It is fed one isolated RPU NAL,
  and its output is cut back to that single NAL.
- Each access unit is rebuilt: NAL 63 dropped, NAL 62 replaced by the converted RPU with no
  filler, other NALs copied verbatim, 4-byte start codes throughout. A failed conversion keeps
  the original RPU and is counted.
- The codecs relabel (`dvhe.07` → `dvhe.08`, non-Fire-TV only) moved from the renderer into
  `DvP8SampleStream` (the format handed to the renderer).
- Logs (tag `DvP8`): the first 3 frames in detail (NAL types, RPU size in→out, converted RPU
  profile/EL), then stats every 2400 frames (converted / failed / no-RPU / EL dropped).
- **Fire TV impact:** CONVERT_P8 is Fire TV's automatic P7 path. It now also gets the EL dropped
  and no filler (codecs unchanged). The 11:33 Fire TV run was still on build zidoo8 (old library
  glue), so **re-test a P7 title on the Fire TV with this build**. Expect `DvP8 frame 1..3` lines
  and no `codecs relabelled` line.
- **Zidoo result (zidoo9, 11:23–11:25, Aquaman and Predator Badlands):** `codecs relabelled
  dvhe.07.06 -> dvhe.08.06`. Every logged frame has its RPU converted (~220 → ~180 bytes,
  `profile=8 el=NONE`), 11–18 EL NALs dropped per frame, rpuFailed=0, and no flashing.
