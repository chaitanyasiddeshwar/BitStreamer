# Fire TV Remote — Buttons, Keycodes, and the App's Key Map

Reference for what a Fire TV remote sends, what an app can intercept, and how BitStreamer's
player maps each button. Keycodes are Android `KeyEvent.KEYCODE_*` constants; the app
handles them in `PlayerActivity.dispatchKeyEvent` and via Media3's `PlayerView` controller.

## What the remote sends, and what an app can use

| Remote button | Keycode | App-mappable? | Notes |
|---|---|---|---|
| **Menu (≡)** | `KEYCODE_MENU` | ✅ Yes | The dedicated "options" button. Free — the player doesn't otherwise use it. |
| D-pad up/down/left/right | `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT` | ✅ (navigation) | Focus movement + seek-bar scrub. |
| Select / OK | `KEYCODE_DPAD_CENTER` | ✅ (confirm) | Play/pause, confirm selections. |
| Back | `KEYCODE_BACK` | ✅ | Hide controls, then exit. |
| Play/Pause | `KEYCODE_MEDIA_PLAY_PAUSE` | ✅ (transport) | Handled by the player. |
| Rewind / Fast-forward | `KEYCODE_MEDIA_REWIND` / `KEYCODE_MEDIA_FAST_FORWARD` | ✅ (transport) | Seek back/forward. |
| Home | `KEYCODE_HOME` | ❌ | Reserved by Fire OS — always returns to the launcher. |
| Volume / Mute | `KEYCODE_VOLUME_UP/DOWN/MUTE` | ❌ | System/CEC-handled; not delivered to apps. |
| Voice / Alexa (mic) | — | ❌ | Not a keycode; goes to Alexa. |
| Power | — | ❌ | System. |
| App-shortcut keys (Prime, Netflix, …) | — | ❌ | Launch those apps; not interceptable. |
| Info / Guide (only on some universal/CEC remotes) | `KEYCODE_INFO` (165) / `KEYCODE_GUIDE` | ✅ if present | Most Fire TV remotes don't have these. |

**Practical takeaway:** on a stock Fire TV remote, **Menu is the only genuinely "spare"
button** — everything else is a navigation/transport key the player needs, or a system key
apps can't intercept. 
* **In File/Server Lists:** Pressing **Menu (≡)** brings up an options menu: `Play Normally` or `Strip DV and Play` (forcing HEVC/HDR10 fallback for Profile 7 files).
* **In Playback:** Pressing **Menu (≡)** toggles the "stats for nerds" overlay.

## BitStreamer player key map

| Button | Action |
|---|---|
| Select / OK (clean frame) | Toggle play/pause. There are no center transport buttons — OK on the movie image is play/pause. |
| Select / OK (while seeking) | Commit the seek and keep the current play/pause state — playing resumes from the new point, paused stays paused. On a control icon, activates it. |
| D-pad left / right (on seek bar) | Scrub by the storyboard interval (default 30 s), landing on the next preview frame |
| Rewind / Fast-forward | Seek −10 s / +30 s |
| D-pad down (from seek bar) | Move to the Audio / Subtitles / Chapters / Stats icon row; down again opens Chapters |
| **Menu (≡)** | Toggle the "stats for nerds" overlay (also the Stats icon) |
| Back | Hide the controls if shown; otherwise stop and exit (playback position is saved for resume) |

Notes:
- **No center transport cluster**: the middle-of-screen rewind / play-pause / fast-forward
  buttons are removed — play/pause is OK on the clean frame, seeking is the D-pad.
- **Pausing doesn't dim**: the controller does not auto-show on pause (`controllerAutoShow`
  off), so pausing leaves the picture undimmed. Controls appear only on a D-pad press and
  auto-hide after 2.5 s (`controllerShowTimeoutMs`).
- The stats overlay is **independent of the control bar** — once toggled on (Menu or the
  Stats icon) it stays until toggled off, even after the controls auto-hide.
- Releasing a scrub starts playback ~2 s before the chosen point, so the scene shown in the
  preview actually plays.
- Playback speed is deliberately not exposed (it would break audio passthrough — see
  [MEDIA3.md](MEDIA3.md)).

## Discovering a specific remote's keycodes

Fancier remotes (Fire TV Cube, universal/CEC remotes) may expose extra buttons. To find
what a given remote emits, connect over `adb` and run `adb shell getevent -l` while pressing
buttons, or ask for a temporary key-event logger to be added to `RemoteLog` (logs every
keycode to `client-logs.txt`) so you can probe without adb.
