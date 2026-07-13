# BitStreamer

Play a movie file from a PC on a Fire TV Stick 4K — video and audio served
**byte-for-byte, no transcoding**, with the original Dolby Digital / DD+ / Atmos / DTS
audio bitstreamed over HDMI to your TV or AV receiver.

- `server/` — Go server, one self-contained binary, serves the file over HTTP with Range support
- `client/` — Android TV app (Media3/ExoPlayer) for Fire TV
- `docs/` — [project plan](docs/PROJECT_PLAN.md) · [audio passthrough](docs/AUDIO_PASSTHROUGH.md) · [Media3 notes](docs/MEDIA3.md) · [thumbnails](docs/THUMBNAILS.md) · [remote key map](docs/REMOTE.md)

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
the seek bar jumps one interval and lands on the next preview frame.

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

## Troubleshooting

The client streams its playback diagnostics to the server, which appends them to
`client-logs.txt` next to the server binary — check that file (or share it) when something
doesn't play or the audio format looks wrong.

If thumbnails or scrub previews look off (or don't appear), check `ffmpeg-logs.txt` next to
the server binary — every ffmpeg/ffprobe warning or error is appended there, with a session
header per server start.
