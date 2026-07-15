# BitStreamer
_(I started this project because I was frustrated with Plex/Emby/Jellyfin since none could give me full bit streaming support (esp DTS) and mixed experience with Dolby Vision/HDR10+.This is still limited by what the FireTV can support, but serves my very specific purpose of being able to serve and watch one movie/episode with full bitstream and DV/HDR support. I hope it is useful to others)_

BitStreamer is a Free, open-source, extremely lightweight, zero-transcode **single file** local network media **streamer** with it's own lightweight **client App** that can be **sideloaded into FireTV**. You can find both the server and the client code in the github sources and the binaries in the release. It uses the native Exoplayer and supports bitstreaming of Audio codecs including Dolby TrueHD, Dolby Atmos and DTS-HD (only DTS core because of FireTV limitations). The server also serves the client APK to be used from Downloader app in FireTV for sideloading. No need to connect to external site or create an account. You can build and run the whole code from your local machine if you have the right tools.

- `server/` — Go server, one self-contained binary, serves the file over HTTP with Range support
- `client/` — Android TV app (Media3/ExoPlayer) for Fire TV
- `docs/` — [project plan](docs/PROJECT_PLAN.md) · [audio passthrough](docs/AUDIO_PASSTHROUGH.md) · [HDR & Dolby Vision](docs/HDR_DOLBY_VISION.md) · [Media3 notes](docs/MEDIA3.md) · [thumbnails](docs/THUMBNAILS.md) · [remote key map](docs/REMOTE.md)

## Supported files

The server only serves file types the Fire TV client (ExoPlayer) can actually read, and
**refuses anything else with a clear message listing what's supported**. MKV and MP4 are the
sweet spot. Supported extensions:

- **Video:** `.mkv` `.mp4` `.m4v` `.mov` `.webm` `.ts` `.flv` `.avi` `.mpg` `.mpeg` `.ps` `.vob` `.ogv`
- **Audio:** `.mp3` `.m4a` `.aac` `.ac3` `.eac3` `.ac4` `.flac` `.wav` `.ogg` `.opus` `.amr` `.mka`
- **Image:** `.jpg` `.png` `.webp` `.bmp` `.heic` `.heif` `.avif`

Two cases get a specific printed fix instead:
- **`.m2ts`/`.mts`** (Blu-ray transport streams) — ExoPlayer can't parse their 192-byte
  packets; the server prints a lossless `ffmpeg` remux-to-MKV command and exits.
- **Dolby Vision Profile 7 FEL** — plays audio only (black video); the server prints an
  `ffmpeg` strip-to-HDR10 command. See [docs/HDR_DOLBY_VISION.md](docs/HDR_DOLBY_VISION.md).

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
  bitstreamer.exe        (or bitstreamer / bitstreamer-macos)
  client.apk
```

Copy that folder to the machine that will serve your movies (add `ffmpeg.exe` too if you want
thumbnails), and run from inside it.

## Running

On the serving PC, from the `dist/` folder (allow it on Private networks when the firewall
prompt appears):

```
bitstreamer.exe "C:\Movies\film.mkv"                    # Windows
./bitstreamer "/Volumes/Movies/film.mkv"                # macOS/Linux
bitstreamer.exe --interval 20 "C:\Movies\film.mkv"      # denser scrub previews (every 20s)
```

The server prints the stream URL, the APK URL, and whether chapter thumbnails / scrub
previews are enabled. `--interval <secs>` (default 30) sets the spacing of the scrubbing
preview frames; the client also uses it as the seek-bar step, so each D-pad left/right on
the seek bar jumps one interval and lands on the next preview frame. The thumbnail/scrub
cache (`cache/` next to the binary) is deleted on exit; pass `--keep-cache` to keep it
across runs (faster restarts).

Silent firewall setup on Windows (admin prompt, one time):

```
netsh advfirewall firewall add rule name="BitStreamer HTTP" dir=in action=allow protocol=TCP localport=46898 profile=private
netsh advfirewall firewall add rule name="BitStreamer Discovery" dir=in action=allow protocol=UDP localport=46899 profile=private
```

### Install on the Fire TV

Install the **Downloader** app from the Amazon Appstore, enter the APK URL the server printed
(e.g. `http://192.168.1.20:46898/client.apk`), and install it (enable "Install unknown apps"
for Downloader when prompted).

### Play

Open BitStreamer on the Fire TV — it finds the server automatically and plays. Remote:
center/play toggles pause; D-pad left/right on the seek bar scrubs; RW/FF seek; Up focuses
play/pause; Down opens the Audio / Subtitles / Chapters / Stats icons; the **Menu** button
(or the Stats icon) toggles a "stats for nerds" overlay (codecs, resolution, frame rate,
HDR/colour, audio passthrough, dropped frames…); Back hides the controls, then exits. If you stopped mid-movie, the player offers to
resume where you left off (per device, remembered by the server until it's started with a
different file).

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
- **Workaround:** You can losslessly strip the Dolby Vision enhancement layer and metadata to leave the standard HDR10 base layer. When you select a Profile 7 file, the server will print a ready-to-run `ffmpeg` command in the console:
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
