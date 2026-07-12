# BitStreamer

Play a movie file from a Windows PC on a Fire TV Stick 4K — video and audio served
**byte-for-byte, no transcoding**, with the original Dolby Digital / DD+ / Atmos / DTS
audio bitstreamed over HDMI to your TV or AV receiver.

- `server/` — Go server, one static exe, serves the file over HTTP with Range support
- `client/` — Android TV app (Media3/ExoPlayer) for Fire TV
- `docs/` — [project plan](docs/PROJECT_PLAN.md) · [audio passthrough design](docs/AUDIO_PASSTHROUGH.md)

## Quick start

**1. Build the server** (any OS; Go ≥ 1.23):

```
cd server
GOOS=windows GOARCH=amd64 go build -o bitstreamer.exe .
```

**2. Build the client** (Android Studio, or `gradlew.bat assembleRelease` in `client/`),
then copy `client/app/build/outputs/apk/release/app-release.apk` next to
`bitstreamer.exe` and rename it `client.apk`.

**3. Run** on the Windows PC (allow it on Private networks when the firewall asks):

```
bitstreamer.exe "C:\Movies\film.mkv"
```

Silent firewall setup (admin prompt, one time):

```
netsh advfirewall firewall add rule name="BitStreamer HTTP" dir=in action=allow protocol=TCP localport=46898 profile=private
netsh advfirewall firewall add rule name="BitStreamer Discovery" dir=in action=allow protocol=UDP localport=46899 profile=private
```

**4. Install on the Fire TV**: install the **Downloader** app from the Amazon Appstore,
enter the APK URL the server printed (e.g. `http://192.168.1.20:46898/client.apk`),
and install (enable "Install unknown apps" for Downloader when prompted).

**5. Play**: open BitStreamer on the Fire TV — it finds the server automatically and
plays. Remote: center/play = pause, left/right or RW/FF = seek, Menu = audio debug
overlay, Back = stop.

For bitstreamed surround sound, set Fire TV **Settings → Display & Sounds → Audio →
Surround Sound** to **Best Available**, and verify the format on your AVR/TV display.
Known platform limits (not fixable by any app): DTS-HD passes as DTS core; TrueHD only
on Fire TV Stick 4K Max 2nd gen. Details in [docs/AUDIO_PASSTHROUGH.md](docs/AUDIO_PASSTHROUGH.md).
