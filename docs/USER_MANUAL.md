# BitStreamer User Manual

Welcome to BitStreamer! This manual covers everything you need to know to set up, run, and troubleshoot the BitStreamer server and client.

---

## 1. Overview
BitStreamer is a Free, open-source, extremely lightweight, zero-transcode **single file** local network media **streamer** with it's own lightweight **client App** that can be **sideloaded into FireTV**. You can find both the server and the client code in the github sources and the binaries in the release. It uses the native Exoplayer and supports bitstreaming of Audio codecs including Dolby TrueHD, Dolby Atmos and DTS-HD (only DTS core because of FireTV limitations). The server also serves the client APK to be used from Downloader app in FireTV for sideloading. No need to connect to external site for this. You can build and run the whole code from your local machine if you have the right tools.
- **The Server** (run on your PC/Mac/Linux box) serves your media file byte-for-byte over HTTP plus the client apk. It also supports generation of Chapter and seek-bar thumbnails/preview (like in netflix/youtube) if you have ffmpeg and ffprobe executibles in the path or the same folder as server (see [Chapters & Scrubbing Previews](#chapters--scrubbing-previews-optional) below). Also supports external subtitles with same filename as the movie file.
- **The Client** (run on your Fire TV) discovers the server automatically, plays the file with hardware video decoding, and bitstreams the audio untouched over HDMI to your TV/AV receiver. It also has corresponding features to the server like chapter thumbnails, seek-bar thumbnails depending on whether server has ffmpeg/ffprobe in the PATH/dir.

**Note:** 
* There is folder support in server to select a folder of movies/videos, but it is still in very early stages and experimental - wait till next version for a stable release to use that.
* Also check [Known Issues](#5-known-issues) below to know what the Hardware itself doesn't support
* This is untested on Nvidia Shield but might work there since it is built for Android TV - if someone does, let me know how it goes

---

## 2. Server Setup & Usage

### Running the Server
On your PC, open a command prompt/terminal and run:
```bash
# Windows
bitstreamer.exe "C:\Movies\your-movie.mkv"

# macOS / Linux
./bitstreamer "/path/to/your-movie.mkv"
```
The server will print:
- Your local network IP addresses
- The streaming URL (for checking in a browser)
- The Client APK download link

### Windows Firewall Setup (One-Time)
If you run the server on Windows, you must allow incoming traffic. Run these commands as **Administrator**:
```cmd
netsh advfirewall firewall add rule name="BitStreamer HTTP" dir=in action=allow protocol=TCP localport=46898 profile=private
netsh advfirewall firewall add rule name="BitStreamer Discovery" dir=in action=allow protocol=UDP localport=46899 profile=private
```

### Advanced Server Options
- `--port 46898`: Set a custom port for the HTTP server.
- `--interval 30`: Set the scrubbing-preview interval in seconds (default: 30).
- `--keep-cache`: Do not delete thumbnails and scrub preview cache when stopping the server (makes subsequent starts faster).

---

## 3. Client Installation & Usage

### Installing the Android TV Client
1. On your Fire TV, search for and install the **Downloader** app from the Amazon Appstore.
2. Open Downloader and enter the APK URL printed by your server (e.g., `http://192.168.1.20:46898/client.apk`).
3. Download and install the APK. *(Note: You will need to allow Downloader to "Install unknown apps" in Fire TV Settings).*

### Playing Media
1. Launch **BitStreamer** on your Fire TV.
2. The app will search for servers on the local network. Select the found server.
3. If the server is not found automatically, enter your PC's IP address manually in the input box at the bottom.

### Remote Control Mapping
- **Center / Play-Pause Button**: Play or pause the video.
- **D-pad Left / Right**: Jump backwards/forwards by one preview interval (--interval, default: 30s) and show the scrubbing preview frame.
- **Rewind / Fast Forward Buttons**: Continuous seek.
- **D-pad Up**: Show playback controls.
- **D-pad Down**: Open the option menus (Audio tracks, Subtitles, Chapters, Stats).
- **Menu Button**: Toggle the **Stats for Nerds** overlay.
- **Back Button**: Hide playback controls, or exit playback.

---

## 4. Getting Best Audio & Video

### Audio Bitstreaming (HDMI Passthrough)
To ensure raw compressed audio is handed directly to your soundbar/receiver:
1. On your Fire TV, go to **Settings → Display & Sounds → Audio → Surround Sound**.
2. Select **Best Available** (or **Dolby Digital Plus / Atmos** depending on your OS version).
3. Check your AV Receiver or TV display panel to confirm it shows the correct audio stream (e.g., *Dolby Atmos*, *DTS*, *Dolby Digital*).

> [!NOTE]
> **DTS-HD** streams are played as **DTS Core** (5.1 surround sound extracted on the fly), since Fire TV hardware does not support full DTS-HD passthrough.
> **Dolby TrueHD** is supported only on newer models like the Fire TV Stick 4K Max (2nd Gen).

### Subtitles
- BitStreamer automatically detects sidecar subtitle files (e.g., `.srt`, `.ass`, `.vtt`) placed next to the movie file on the server.
- Use the D-pad Down menu to toggle subtitles on/off or change tracks.

### Chapters & Scrubbing Previews (Optional)
To enable visual chapter markers and Netflix-style scrubbing previews:
1. Download `ffmpeg` and `ffprobe` binaries.
2. Place `ffmpeg.exe` and `ffprobe.exe` (or your OS equivalents) in the same directory as the `bitstreamer` server executable, or put them on your system's `PATH`.
3. The server will automatically generate chapter thumbnails and storyboard images at startup.

---

## 5. Known Issues

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

---

## 6. Troubleshooting

If you encounter issues during playback, check these logs located next to the server executable on your PC:
- **`client-logs.txt`**: Contains playback logs sent dynamically from the Fire TV client. Use this to verify codec selection and audio track initialization.
- **`ffmpeg-logs.txt`**: Contains detailed output from background chapter/scrubbing thumbnail generation.
