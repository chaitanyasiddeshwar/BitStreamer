// bitstreamer serves a single media file byte-for-byte over HTTP so a
// BitStreamer client on the LAN can play it without any transcoding.
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime/debug"
	"strings"
	"syscall"
)

const (
	defaultHTTPPort = 46898
	discoveryPort   = 46899
)

func main() {
	log.SetFlags(log.Ltime)

	port := flag.Int("port", defaultHTTPPort, "HTTP port to serve on")
	name := flag.String("name", "", "display name announced to clients (default: hostname)")
	apk := flag.String("apk", "", "path to the client APK served at /client.apk (default: client.apk next to the executable)")
	clientLog := flag.String("clientlog", "", "file where client diagnostics POSTed to /log are appended (default: client-logs.txt next to the executable)")
	resumeFile := flag.String("resumefile", "", "file where per-client resume positions are stored (default: resume.json next to the executable)")
	interval := flag.Int("interval", 30, "seconds between scrubbing-preview thumbnails (storyboard); also the seek-bar step on the client")
	keepCache := flag.Bool("keep-cache", false, "keep the thumbnail/storyboard cache on exit instead of deleting it")
	ffmpegLogFile := flag.String("ffmpeglog", "", "file where ffmpeg/ffprobe output is appended (default: ffmpeg-logs.txt next to the executable)")
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: %s [flags] <media-file>\n\nflags:\n", filepath.Base(os.Args[0]))
		flag.PrintDefaults()
	}
	flag.Parse()

	if flag.NArg() != 1 {
		flag.Usage()
		os.Exit(2)
	}

	if *name == "" {
		host, err := os.Hostname()
		if err != nil {
			host = "bitstreamer"
		}
		*name = host
	}
	exeDir := "."
	if exe, err := os.Executable(); err == nil {
		exeDir = filepath.Dir(exe)
	}
	if *apk == "" {
		*apk = filepath.Join(exeDir, "client.apk")
	}
	if *clientLog == "" {
		*clientLog = filepath.Join(exeDir, "client-logs.txt")
	}
	if *resumeFile == "" {
		*resumeFile = filepath.Join(exeDir, "resume.json")
	}
	if *ffmpegLogFile == "" {
		*ffmpegLogFile = filepath.Join(exeDir, "ffmpeg-logs.txt")
	}
	// Start the ffmpeg/ffprobe log before newApp, so the startup ffprobe is captured.
	initFFmpegLog(*ffmpegLogFile)

	if *interval < 1 {
		*interval = 1
	}

	// Reject unplayable file types (single-file mode only; a folder is browsed).
	mediaPath := flag.Arg(0)
	if fi, statErr := os.Stat(mediaPath); statErr == nil && !fi.IsDir() {
		ext := strings.ToLower(filepath.Ext(mediaPath))
		if ext == ".m2ts" || ext == ".mts" {
			fmt.Fprint(os.Stderr, containerAdvisory(mediaPath))
			os.Exit(1)
		}
		if !isPlayable(mediaPath) {
			fmt.Fprintf(os.Stderr, "Unsupported file type %q — the Fire TV client can't play it.\n\n", ext)
			fmt.Fprintf(os.Stderr, "Supported extensions:\n  %s\n\n", strings.Join(supportedExtensions(), " "))
			fmt.Fprintln(os.Stderr, "(.m2ts/.mts Blu-ray streams: remux to .mkv first — see docs/HDR_DOLBY_VISION.md and the README.)")
			os.Exit(1)
		}
	}

	app, err := newApp(mediaPath, *name, *apk, *clientLog, *resumeFile, *port, int64(*interval)*1000)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}

	go func() {
		if err := runDiscovery(discoveryPort, app.displayName, app.httpPort); err != nil {
			log.Printf("discovery disabled: %v", err)
		}
	}()

	fmt.Printf("BitStreamer (build %s)\n", buildStamp())
	if app.folderMode {
		fmt.Printf("Serving folder %q (browse it from the client)\n\n", app.rootDir)
	} else {
		fmt.Printf("Serving %q (%s)\n\n", app.mediaName, formatSize(app.mediaSize))
	}
	for _, ip := range lanIPv4s() {
		fmt.Printf("  client  http://%s:%d/client.apk\n", ip, app.httpPort)
		if !app.folderMode {
			fmt.Printf("  stream  http://%s:%d/stream\n", ip, app.httpPort)
		}
		fmt.Println()
	}
	fmt.Printf("Client diagnostics will be appended to %s\n", app.clientLogPath)

	if app.folderMode {
		fmt.Println("Folder mode: no chapters/thumbnails/scrub previews (single-file mode only).")
	} else {
		ffprobeFound := findFFprobe() != ""
		ffmpegFound := findFFmpeg() != ""
		// Full metadata dump (to the console and the ffmpeg/ffprobe log file).
		app.logMetadata(ffprobeFound, ffmpegFound)
		// Chapters + duration come from ffprobe; thumbnails + scrubbing previews
		// come from ffmpeg. If either is missing, say so explicitly — these are
		// the only features that need the external tools.
		if !ffprobeFound || !ffmpegFound {
			var missing []string
			if !ffmpegFound {
				missing = append(missing, "ffmpeg")
			}
			if !ffprobeFound {
				missing = append(missing, "ffprobe")
			}
			fmt.Printf("⚠ %s not found (looked next to the executable and on PATH).\n", strings.Join(missing, " and "))
			fmt.Println("  Chapter markers, chapter thumbnails, and scrubbing previews are disabled.")
			fmt.Println("  Put ffmpeg and ffprobe next to the executable, or install them on PATH, to enable these.")
			fmt.Println()
		}
		if len(app.chapters) > 0 {
			if app.thumbs.available() {
				fmt.Printf("Chapters: %d, generating thumbnails in the background (ffmpeg found)\n", len(app.chapters))
				go app.thumbs.warm() // pre-generate so the chapter menu is instant
			} else {
				fmt.Printf("Chapters: %d, names only (ffmpeg not found; drop ffmpeg.exe next to bitstreamer.exe for thumbnails)\n", len(app.chapters))
			}
		}
		if app.probe.summary != "" {
			fmt.Printf("Video: %s\n", app.probe.summary)
		}
		if app.probe.isHDR && app.thumbs.ffmpegPath != "" {
			if hasZscale(app.thumbs.ffmpegPath) {
				fmt.Println("HDR thumbnails: tonemapped to SDR (ffmpeg zscale present)")
			} else {
				fmt.Println("HDR thumbnails: not tonemapped (this ffmpeg has no zscale; build with --enable-libzimg)")
			}
		}
		if app.probe.dvProfile == 7 {
			fmt.Print(dolbyVisionAdvisory(mediaPath))
		}
		if app.thumbs.available() || app.story.enabled() {
			fmt.Printf("ffmpeg/ffprobe output is appended to %s\n", *ffmpegLogFile)
		}
		if app.story.enabled() {
			fmt.Printf("Scrubbing previews: generating in the background (every %ds)\n", *interval)
			go app.story.generate()
		} else if app.story.ffmpegPath != "" {
			// ffmpeg is present but the storyboard is still off: the only other
			// requirement is a duration, which comes from ffprobe.
			fmt.Println("Scrubbing previews: DISABLED — couldn't read the media duration (needs ffprobe).")
		}
	}

	// On Ctrl+C / termination, delete the whole cache (thumbnails + storyboard)
	// unless --keep-cache was passed.
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		if !*keepCache {
			os.RemoveAll(app.cacheRoot)
		}
		os.Exit(0)
	}()

	fmt.Println()
	fmt.Println("Waiting for a client to connect... (Ctrl+C to stop)")

	addr := fmt.Sprintf(":%d", app.httpPort)
	if err := http.ListenAndServe(addr, app.handler()); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

// buildStamp returns a short identifier for the running binary — the git commit
// and build time stamped in by `go build` inside the repo. Lets you tell at a
// glance whether you're running a freshly built exe or a stale one.
func buildStamp() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "dev"
	}
	var rev, ts string
	modified := false
	for _, s := range info.Settings {
		switch s.Key {
		case "vcs.revision":
			rev = s.Value
		case "vcs.time":
			ts = s.Value
		case "vcs.modified":
			modified = s.Value == "true"
		}
	}
	if rev == "" {
		return "dev"
	}
	if len(rev) > 12 {
		rev = rev[:12]
	}
	if modified {
		rev += "+dirty"
	}
	if ts != "" {
		return rev + " " + ts
	}
	return rev
}

func formatSize(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}
