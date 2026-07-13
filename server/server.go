package main

import (
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// app holds the immutable configuration for one server run: exactly one media
// file, served as raw bytes.
type app struct {
	mediaPath   string
	mediaName   string
	mediaSize   int64
	mediaMod    time.Time
	mediaMime   string
	displayName string
	apkPath     string
	httpPort    int

	clientLogPath string
	clientLogMu   sync.Mutex
	resume        *resumeStore
	chapters      []Chapter
	thumbs        *thumbnailer
	story         *storyboard
	probe         mediaProbe
	cacheRoot     string
}

func newApp(mediaPath, displayName, apkPath, clientLogPath, resumePath string, httpPort int, storyboardIntervalMs int64) (*app, error) {
	info, err := os.Stat(mediaPath)
	if err != nil {
		return nil, fmt.Errorf("cannot open media file: %w", err)
	}
	if info.IsDir() {
		return nil, fmt.Errorf("%s is a directory, not a media file", mediaPath)
	}
	chapters := parseChapters(mediaPath)
	// Prefer ffprobe (reads the real stream + Dolby Vision profile); fall back
	// to the MKV container's colour tags if ffprobe isn't available.
	probe, ok := probeMedia(mediaPath)
	if !ok {
		probe = mediaProbe{isHDR: parseIsHDR(mediaPath), dvProfile: -1}
		probe.summary = fmt.Sprintf("HDR=%v (from MKV container tags; ffprobe unavailable)", probe.isHDR)
	}
	hdr := probe.isHDR
	// Keep the thumbnail/storyboard caches next to the executable (in cache/)
	// rather than the system temp dir, so cleanup is just deleting that folder.
	cacheRoot := filepath.Join(executableDir(), "cache")
	return &app{
		mediaPath:     mediaPath,
		mediaName:     filepath.Base(mediaPath),
		mediaSize:     info.Size(),
		mediaMod:      info.ModTime(),
		mediaMime:     mimeForPath(mediaPath),
		displayName:   displayName,
		apkPath:       apkPath,
		httpPort:      httpPort,
		clientLogPath: clientLogPath,
		resume:        newResumeStore(resumePath, mediaPath),
		chapters:      chapters,
		thumbs:        newThumbnailer(mediaPath, info.ModTime(), chapters, hdr, filepath.Join(cacheRoot, "thumbs")),
		story:         newStoryboard(mediaPath, parseDuration(mediaPath), storyboardIntervalMs, hdr, filepath.Join(cacheRoot, "storyboard")),
		probe:         probe,
		cacheRoot:     cacheRoot,
	}, nil
}

// executableDir returns the directory of the running binary (where cache/,
// client-logs.txt etc. live), or "." if it can't be determined.
func executableDir() string {
	if exe, err := os.Executable(); err == nil {
		return filepath.Dir(exe)
	}
	return "."
}

// mimeForPath maps by extension explicitly: OS mime tables often lack .mkv,
// and the client relies on a sensible Content-Type.
func mimeForPath(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".mp4", ".m4v":
		return "video/mp4"
	case ".mkv":
		return "video/x-matroska"
	case ".mov":
		return "video/quicktime"
	case ".webm":
		return "video/webm"
	default:
		return "application/octet-stream"
	}
}

func (a *app) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.handleIndex)
	mux.HandleFunc("/info", a.handleInfo)
	mux.HandleFunc("/stream", a.handleStream)
	mux.HandleFunc("/client.apk", a.handleAPK)
	mux.HandleFunc("/log", a.handleClientLog)
	mux.HandleFunc("/position", a.handlePosition)
	mux.HandleFunc("/chapter-thumb", a.handleChapterThumb)
	mux.HandleFunc("/storyboard.json", a.handleStoryboardManifest)
	mux.HandleFunc("/storyboard", a.handleStoryboardSheet)
	return logRequests(mux)
}

func (a *app) handleInfo(w http.ResponseWriter, r *http.Request) {
	chapters := a.chapters
	if chapters == nil {
		chapters = []Chapter{} // emit [] not null
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"v":          1,
		"name":       a.displayName,
		"file":       a.mediaName,
		"size":       a.mediaSize,
		"mime":       a.mediaMime,
		"chapters":   chapters,
		"thumbnails": a.thumbs.available(),
		"storyboard": a.story.enabled(),
		"video": map[string]any{
			"hdr":        a.probe.isHDR,
			"hdr10plus":  a.probe.hdr10plus,
			"transfer":   a.probe.colorTransfer,
			"colorSpace": a.probe.colorSpace,
			"dvProfile":  a.probe.dvProfile,
		},
	})
}

func (a *app) handleStream(w http.ResponseWriter, r *http.Request) {
	f, err := os.Open(a.mediaPath)
	if err != nil {
		http.Error(w, "media file is no longer readable", http.StatusInternalServerError)
		log.Printf("stream: %v", err)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", a.mediaMime)
	// ServeContent implements Range/206, If-Range and HEAD, and streams from
	// the file without buffering it. Empty name: Content-Type is already set.
	http.ServeContent(w, r, "", a.mediaMod, f)
}

func (a *app) handleAPK(w http.ResponseWriter, r *http.Request) {
	f, err := os.Open(a.apkPath)
	if err != nil {
		http.Error(w,
			fmt.Sprintf("client APK not found on the server (expected at %s); build the client and place it there or pass --apk", a.apkPath),
			http.StatusNotFound)
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		http.Error(w, "cannot stat APK", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="client.apk"`)
	http.ServeContent(w, r, "", info.ModTime(), f)
}

// handleClientLog appends diagnostic log batches POSTed by the Fire TV client
// to a file on disk, so playback issues can be inspected on the PC.
func (a *app) handleClientLog(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "POST plain-text log lines here", http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		http.Error(w, "read error", http.StatusBadRequest)
		return
	}
	if len(body) == 0 {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	a.clientLogMu.Lock()
	defer a.clientLogMu.Unlock()
	f, err := os.OpenFile(a.clientLogPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		http.Error(w, "cannot open client log file", http.StatusInternalServerError)
		log.Printf("client log: %v", err)
		return
	}
	defer f.Close()
	fmt.Fprintf(f, "==== %s %s ====\n", time.Now().Format(time.RFC3339), r.RemoteAddr)
	f.Write(body)
	if body[len(body)-1] != '\n' {
		f.WriteString("\n")
	}
	w.WriteHeader(http.StatusNoContent)
}

// handlePosition stores (POST ?ms=N) and reports (GET) the last playback
// position for the requesting client's IP, enabling resume-where-you-left-off.
// POSTing ms=0 clears the stored position (playback finished).
func (a *app) handlePosition(w http.ResponseWriter, r *http.Request) {
	clientIP, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		clientIP = r.RemoteAddr
	}
	switch r.Method {
	case http.MethodGet:
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"v":          1,
			"file":       a.mediaName,
			"positionMs": a.resume.get(clientIP),
		})
	case http.MethodPost:
		ms, err := strconv.ParseInt(r.URL.Query().Get("ms"), 10, 64)
		if err != nil || ms < 0 {
			http.Error(w, "ms must be a non-negative integer", http.StatusBadRequest)
			return
		}
		a.resume.set(clientIP, ms)
		w.WriteHeader(http.StatusNoContent)
	default:
		w.Header().Set("Allow", "GET, POST")
		http.Error(w, "GET or POST only", http.StatusMethodNotAllowed)
	}
}

// handleChapterThumb serves the JPEG thumbnail for ?index=N, generating it via
// ffmpeg on first request. 404 when ffmpeg is absent or the index is invalid.
func (a *app) handleChapterThumb(w http.ResponseWriter, r *http.Request) {
	index, err := strconv.Atoi(r.URL.Query().Get("index"))
	if err != nil {
		http.Error(w, "index must be an integer", http.StatusBadRequest)
		return
	}
	path, err := a.thumbs.get(index)
	if err != nil {
		http.Error(w, "thumbnail unavailable", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "max-age=86400")
	http.ServeFile(w, r, path)
}

// handleStoryboardManifest returns the scrubbing-preview layout. 404 until the
// sprite sheets have finished generating (client may retry).
func (a *app) handleStoryboardManifest(w http.ResponseWriter, r *http.Request) {
	if !a.story.isReady() {
		http.Error(w, "storyboard not ready", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(a.story.manifest())
}

// handleStoryboardSheet serves sprite sheet ?sheet=N.
func (a *app) handleStoryboardSheet(w http.ResponseWriter, r *http.Request) {
	n, err := strconv.Atoi(r.URL.Query().Get("sheet"))
	if err != nil {
		http.Error(w, "sheet must be an integer", http.StatusBadRequest)
		return
	}
	path, ok := a.story.sheetPath(n)
	if !ok {
		http.Error(w, "sheet unavailable", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "max-age=86400")
	http.ServeFile(w, r, path)
}

func (a *app) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html>
<title>BitStreamer</title>
<h1>BitStreamer &mdash; %s</h1>
<p>Serving <b>%s</b> (%s, <code>%s</code>)</p>
<ul>
  <li><a href="/stream">/stream</a> &mdash; the media file (Range supported)</li>
  <li><a href="/info">/info</a> &mdash; media metadata (JSON)</li>
  <li><a href="/client.apk">/client.apk</a> &mdash; Fire TV client APK</li>
  <li><code>POST /log</code> &mdash; client diagnostics, appended to <code>%s</code></li>
</ul>
`, html.EscapeString(a.displayName), html.EscapeString(a.mediaName), formatSize(a.mediaSize),
		a.mediaMime, html.EscapeString(a.clientLogPath))
}

// statusWriter captures the response code for request logging.
type statusWriter struct {
	http.ResponseWriter
	status int
}

func (s *statusWriter) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(sw, r)
		if r.URL.Path == "/position" && sw.status < 400 {
			return // 5-second heartbeat; logging it would drown the console
		}
		rng := r.Header.Get("Range")
		if rng != "" {
			rng = " " + rng
		}
		log.Printf("%s %s %s%s -> %d", r.RemoteAddr, r.Method, r.URL.Path, rng, sw.status)
	})
}
