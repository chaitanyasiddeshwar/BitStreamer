# BitStreamer
_(I started this project because I was frustrated with Plex/Emby/Jellyfin since none could give me full bit streaming support (esp DTS) and mixed experience with Dolby Vision/HDR10+.This is still limited by what the FireTV can support, but serves my very specific purpose of being able to serve and watch one movie/episode with full bitstream and DV/HDR support. I hope it is useful to others)_

BitStreamer is a Free, open-source, extremely lightweight, zero-transcode **single file or multi-folder** local network media **streamer** with it's own lightweight **client App** that can be **sideloaded into FireTV**. You can find both the server and the client code in the github sources and the binaries in the release. It uses the native Exoplayer and supports bitstreaming of Audio codecs including Dolby TrueHD, Dolby Atmos and DTS-HD (only DTS core because of FireTV limitations). The server also serves the client APK to be used from Downloader app in FireTV for sideloading. No need to connect to external site for this. You can build and run the whole code from your local machine if you have the right tools.

- `server/` — Go server, one self-contained binary, serves the file over HTTP with Range support
- `client/` — Android TV app (Media3/ExoPlayer) for Fire TV
- `docs/` — [project plan](docs/PROJECT_PLAN.md) · [audio passthrough](docs/AUDIO_PASSTHROUGH.md) · [HDR & Dolby Vision](docs/HDR_DOLBY_VISION.md) · [Media3 notes](docs/MEDIA3.md) · [thumbnails](docs/THUMBNAILS.md) · [remote key map](docs/REMOTE.md)

**Note:** 
* Folder support is fully completed and stable! You can serve single or **multiple directories simultaneously** (`bitstreamer.exe "C:\Movies" "D:\TV Shows"`), browse them via a dedicated root selector screen, view video thumbnails, play slideshows, view chapters, and resume playback per file.
* Check [Known Issues](#known-issues) below to know what the Hardware itself doesn't support.
* This is untested on Nvidia Shield but might work there since it is built for Android TV - if someone does, let me know how it goes.


## Supported files

The server only serves file types the Fire TV client (ExoPlayer) can actually read, and **refuses anything else with a clear message listing what's supported**. MKV and MP4 are the
sweet spot. Supported extensions:

- **Video:** `.mkv` `.mp4` `.m4v` `.mov` `.webm` `.ts` `.flv` `.avi` `.mpg` `.mpeg` `.ps` `.vob` `.ogv`
- **Audio:** `.mp3` `.m4a` `.aac` `.ac3` `.eac3` `.ac4` `.flac` `.wav` `.ogg` `.opus` `.amr` `.mka`
- **Image:** `.jpg` `.png` `.webp` `.bmp` `.heic` `.heif` `.avif`

### HDR & Dolby Vision Playback Behavior

| Format | Supported? | Description / Handling |
|---|---|---|
| **SDR** (BT.709) | ✅ Yes | Direct native playback. |
| **HDR10** (PQ, BT.2020) | ✅ Yes | Direct native playback. |
| **HDR10+** (Dynamic metadata) | ✅ Yes | Direct native playback with dynamic metadata mapped. |
| **HLG** (Hybrid Log Gamma) | ✅ Yes | Direct native playback. |
| **Dolby Vision Profile 5** | ✅ Yes | Direct native playback (IPTPQc2 colorspace). |
| **Dolby Vision Profile 8.1** | ✅ Yes | Direct native playback (even if file also carries HDR10+ metadata, e.g. *Ford v Ferrari*). |
| **Dolby Vision Profile 7 MEL** | ✅ Yes | Direct native playback of the base layer which is a standard HDR10 or HDR10+ stream). |
| **Dolby Vision Profile 7 FEL** | ❌ No | **Audio plays, video remains black.** Fire TV hardware cannot decode dual-layer FEL. Plays using our real-time high-performance zero-allocation NAL stripping fallback (`Strip DV and Play` / `-stripdv`), or `Convert to DV8 and Play` option. |

Two cases get a specific printed fix instead:
- **`.m2ts`/`.mts`** (Blu-ray transport streams) — ExoPlayer can't parse their 192-byte
  packets; the server prints a lossless `ffmpeg` remux-to-MKV command and exits.
- **Dolby Vision Profile 7 FEL** — plays audio only (black video); the server offers on-the-fly real-time NAL stripping, on-the-fly Profile 8 conversion (`Convert to DV8 and Play`), or prints a lossless offline `ffmpeg` command to strip it. See [docs/HDR_DOLBY_VISION.md](docs/HDR_DOLBY_VISION.md).

(Playing still depends on the codec inside being one the Fire TV can decode — e.g. MPEG-2 in
`.vob` may not; the container is what's validated here.)

## Building

Both builds output into a shared **`dist/`** folder at the repo root: the server binary and
`client.apk` end up side by side, which is exactly the layout the server expects — it serves
`client.apk` (sitting next to it) at `/client.apk` for the Fire TV Downloader app. So a full
build leaves you with a ready-to-run `dist/`.

**One command builds both:**

```
./build.sh              # macOS/Linux — native server + client APK into dist/
./build.sh windows      # server as Windows x64 exe (+ client APK), from any host
build.bat               # Windows — bitstreamer.exe + client APK into dist/
```

The sections below cover building each half on its own.

### Prerequisites

- **Server**: [Go](https://go.dev/dl/) ≥ 1.23. (Optional: `make` for the convenience targets;
  on Windows without make, use `build.bat`.)
- **Client**: [Android Studio](https://developer.android.com/studio) (bundles the JDK and
  Android SDK), or a standalone JDK 17 + Android SDK to use the Gradle wrapper.
- **Optional**: `ffmpeg` on the server machine for chapter thumbnails (see below).

### Server

The server is pure Go and cross-compiles from any host, so you can build the Windows `.exe`
from a Mac. From `server/`:

```
make            # native binary for THIS machine -> dist/bitstreamer (or .exe on Windows)
make windows    # dist/bitstreamer.exe            (Windows x64, from any host)
make darwin     # dist/bitstreamer-macos          (universal arm64+x64, on a Mac)
make all        # windows + darwin
make test       # run the test suite
```

Without `make`:

```
# Windows:
build.bat                                             # -> ..\dist\bitstreamer.exe
# Any OS, directly with go:
go build -o ../dist/bitstreamer .                     # native
GOOS=windows GOARCH=amd64 go build -o ../dist/bitstreamer.exe .   # cross to Windows
```

### Client

Open the `client/` folder in Android Studio and **Run** (or **Build → Build APK**), or use
the Gradle wrapper from `client/`:

```
# macOS/Linux:
./gradlew assembleRelease      # or assembleDebug
# Windows:
gradlew.bat assembleRelease
```

The APK builds to `client/app/build/outputs/apk/<variant>/`, and a post-build step
**automatically copies it to `dist/client.apk`** — the spot the server serves it from. No
manual copying needed. (Android Studio's Run triggers `assembleDebug`, which copies too.)

To install the APK directly to your Fire TV over ADB:
```cmd
install-client.bat 192.168.1.50
```
*(Ensure ADB Debugging is enabled under **Settings -> My Fire TV -> Developer Options -> ADB Debugging -> ON**. Find your Fire TV IP under **Settings -> My Fire TV -> About -> Network**).*

App icon / banner: the launcher icon (`res/drawable/app_icon.xml`) and the Fire TV home
banner (`res/drawable/banner.xml`) are vector drawables — no image assets needed. To use
your own artwork, drop a PNG (e.g. `res/mipmap/ic_launcher.png` for the icon,
`res/drawable/banner.png` at 320×180 for the banner) and point `android:icon` /
`android:banner` at it in the manifest. PNG and WebP are both supported.

Signing: the release build is signed with the local debug keystore so `assembleRelease`
produces an installable APK with no keystore setup. That's fine for personal sideloading;
just keep building on the same machine so app upgrades share a signing key.

### Result

After building both, `dist/` contains:

```
dist/
  bitstreamer.exe        (Windows)
  bitstreamer-macos      (macOS Universal)
  bitstreamer-linux      (Linux)
  client.apk
```

Copy that folder to the machine that will serve your movies (add `ffmpeg.exe` too if you want
thumbnails), and run from inside it.

## Running

On the serving PC, from the `dist/` folder (allow it on Private networks when the firewall prompt appears):

```cmd
# Single-File Mode:
bitstreamer.exe "C:\Movies\film.mkv"                      # Windows
./bitstreamer-macos "/Volumes/Movies/film.mkv"            # macOS
./bitstreamer-linux "/path/to/film.mkv"                   # Linux

# Folder Mode (Browse single directory or drive root):
bitstreamer.exe "C:\Movies"                               # Serves the whole folder
bitstreamer.exe "D:\"                                     # Serves a whole drive root on Windows

# Multi-Folder Mode (Browse multiple roots):
bitstreamer.exe "C:\Movies" "D:\TV Shows" "E:\Videos"     # Client displays Root Selection screen
```

### Server Command Line Options:
* `--port <number>`: HTTP port to serve on (default: `46898`).
* `--name <string>`: Custom display name announced to clients (default: hostname).
* `--interval <seconds>`: Spacing between scrubbing-preview thumbnails (default: `30`, automatically drops to `10` for videos under 10 minutes). Also sets seek-bar step size.
* `-stripdv`: Forces client-side Annex B stripping of Dolby Vision metadata to fallback to HDR10 (only applies to Profile 7 files).
* `-skip-previews`: Skips generating the storyboard seek-bar preview sprites at startup.
* `--no-caching`: Disables persistent disk caching of metadata/thumbnails across server runs (persistent cache is enabled by default in `cache/`).
* `--keep-cache`: Legacy flag to preserve thumbnail/preview cache directory.

The server prints the stream URL, the APK URL, and whether chapter thumbnails / scrub previews are enabled.

Silent firewall setup on Windows (admin prompt, one time):

```cmd
netsh advfirewall firewall add rule name="BitStreamer HTTP" dir=in action=allow protocol=TCP localport=46898 profile=private
netsh advfirewall firewall add rule name="BitStreamer Discovery" dir=in action=allow protocol=UDP localport=46899 profile=private
```

### Install on the Fire TV

Install the **Downloader** app from the Amazon Appstore, enter the APK URL the server printed (e.g. `http://192.168.1.20:46898/client.apk`), and install it (enable "Install unknown apps" for Downloader when prompted).

### Play

Open BitStreamer on the Fire TV — it finds the server automatically and starts.
* **Navigation (List View):**
  * **Select/OK:** Opens folders or starts playback.
  * **Back:** Goes up one directory (retains your list selection position).
  * **Menu (≡):** On a movie file, opens option dialog (`Play Normally`, `Strip DV and Play`, `Convert to DV8 and Play`, plus `Generate Seekbar Previews` option).
* **Video Playback Controls:**
  * **DPAD Center / Play-Pause:** Toggles play and pause.
  * **DPAD Left / Right:** Jumps backwards/forwards by one preview interval (default: 30s) and shows the scrubbing preview frame.
  * **RW / FF (Rewind / Fast Forward):** Seeks to the previous / next chapter directly (falls back to standard seek if the file has no chapters).
  * **DPAD Up:** Focuses and opens the on-screen seek bar.
  * **DPAD Down:** Opens option menus (Audio tracks, Subtitles, Chapters, Stats).
  * **Previews Button (Film Strip Icon):** Triggers on-demand seek-bar preview generation with real-time progress bar UI.
  * **Menu (≡):** Toggles the **Stats for Nerds** overlay (codecs, resolution, frame rate, HDR profile, real-time bandwidth meter, audio passthrough, dropped frames).
  * **Back:** Hides controls, or exits playback back to the folder list (prevented while preview progress overlay is active).
* **Image Playback Controls:**
  * Plays each image for **5 seconds** and automatically advances to the next image in folder mode.
  * **DPAD Center:** Pauses or resumes the slideshow auto-advance.
  * **RW / FF (Rewind / Fast Forward):** Steps to the previous / next file in the folder immediately.
  * **Back:** Exits playback back to the folder list.

## Audio (the whole point)

For bitstreamed surround sound, set Fire TV **Settings → Display & Sounds → Audio → Surround
Sound** to **Best Available**, and verify the format on your AVR/TV front panel. DTS-HD tracks
are bitstreamed as **DTS core** (extracted on the fly — the same approach as Plex/Kodi), since
Fire TV never exposes full DTS-HD passthrough to apps; TrueHD works only on the Fire TV Stick
4K Max 2nd gen. Details in [docs/AUDIO_PASSTHROUGH.md](docs/AUDIO_PASSTHROUGH.md).

## Thumbnails (optional, needs ffmpeg)

Drop `ffmpeg` (`ffmpeg.exe` on Windows) next to the server binary — or have it on `PATH` — and
the server generates, in the background at startup:

- **Chapter thumbnails** shown in the chapter selector, and
- **Scrubbing previews** (a YouTube/Netflix-style frame preview that follows the seek-bar
  thumb), spaced every `--interval` seconds (default 30).

Without ffmpeg both are skipped: the chapter selector lists names/times only, and scrubbing
has no preview. The scrub-preview cache is per-session (cleared when the server exits). The
server prints which modes are enabled at startup. See [docs/THUMBNAILS.md](docs/THUMBNAILS.md).

## Known Issues

### DTS-HD / DTS:X Playback (Limited to DTS Core)
- **Issue:** Lossless DTS-HD Master Audio and DTS:X audio tracks are played as standard **DTS Core** (5.1 surround sound) instead of full lossless audio.
- **Cause:** This is a hardware limitation of the Fire TV platform. Fire OS does not expose the full DTS-HD lossless bitstream to third-party Android apps via standard `AudioTrack` APIs; it only makes the 5.1-channel DTS Core portion reachable. 
- **Workaround:** There is no direct app workaround. If you require lossless audio, a device that supports full Dolby TrueHD and DTS-HD passthrough (such as Nvidia Shield TV Pro) is required.

### Dolby Vision Profile 7 FEL (Black Screen, Audio Only)
- **Issue:** When attempting to play a Dolby Vision **Profile 7 FEL** (Full Enhancement Layer) dual-layer video track (commonly found on 4K UHD Blu-ray REMUX files), the audio plays perfectly but the **screen remains black**.
- **Cause:** Dual-layer Profile 7 files contain a Base Layer (HDR10 compatible) plus a Full Enhancement Layer (FEL) containing actual picture residual details. The Fire TV's hardware Dolby Vision decoder cannot decode the enhancement layer and stalls. (Note: Profile 7 **MEL** or Profile 8 files play natively as Dolby Vision without issues).
- **Workaround:** 
  * **On-the-Fly:** Use the `-stripdv` server-side flag, or press the **Menu (≡)** button on the remote while the file is selected in the list and choose `Strip DV and Play` to decode the stream on the fly as standard HEVC/HDR10.
  * **Offline Conversion:** You can losslessly strip the Dolby Vision enhancement layer and metadata offline to leave the standard HDR10 base layer. When you select a Profile 7 file, the server will print a ready-to-run `ffmpeg` command in the console:
    ```bash
    ffmpeg -i "Movie.mkv" -map 0 -c copy -bsf:v "filter_units=remove_types=62|63" "Movie_no_dv.mkv"
    ```
    Run this command on your PC. The output `Movie_no_dv.mkv` will be created losslessly without re-encoding, preserving all audio and subtitle tracks, and can be played perfectly on the client in HDR10 mode.

## Troubleshooting

The client streams its playback diagnostics to the server, which appends them to
`client-logs.txt` next to the server binary — check that file (or share it) when something
doesn't play or the audio format looks wrong.

If thumbnails or scrub previews look off (or don't appear), check `ffmpeg-logs.txt` next to
the server binary — every ffmpeg/ffprobe warning or error is appended there, with a session
header per server start.
