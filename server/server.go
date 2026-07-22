package main

import (
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// rootEntry is one served directory in multi-root mode.
type rootEntry struct {
	name string // display label (directory basename)
	dir  string // absolute path
}

// app holds the configuration for one server run — either a single media file
// (with chapters/thumbnails/storyboard/resume), a single folder browsed by the
// client, or multiple folder roots.
type app struct {
	mediaPath   string
	mediaName   string
	mediaSize   int64
	mediaMod    time.Time
	mediaMime   string
	displayName string
	apkPath     string
	httpPort    int

	// Folder mode: serve a directory tree for client browsing. In this mode the
	// single-file fields above and the caches below are unused (no chapters/
	// thumbnails/storyboard/resume — see docs/PROJECT_PLAN.md folder support).
	folderMode bool
	rootDir    string

	// Multi-root mode: multiple folder roots served simultaneously. The client
	// picks a root first, then browses it like a single-folder.
	multiRoot bool
	roots     []rootEntry

	clientLogPath string
	clientLogMu   sync.Mutex
	resume        *resumeStore
	chapters      []Chapter
	thumbs        *thumbnailer
	story         *storyboard
	genOnce       sync.Once
	probe         mediaProbe
	subtitles     []subtitleTrack // sidecar .srt/.ass/... next to the movie (single-file mode)
	cacheRoot     string

	storyboardIntervalMs int64
	folderMediaMu        sync.Mutex
	folderMedia          map[string]*folderMediaItem
}

// folderMaxDepth caps how deep the client can browse below the root folder.
const folderMaxDepth = 3

func newApp(mediaPath, displayName, apkPath, clientLogPath, resumePath string, httpPort int, storyboardIntervalMs int64) (*app, error) {
	if len(mediaPath) == 2 && mediaPath[1] == ':' {
		mediaPath += string(os.PathSeparator)
	}
	info, err := os.Stat(mediaPath)
	if err != nil {
		return nil, fmt.Errorf("cannot open %s: %w", mediaPath, err)
	}
	if info.IsDir() {
		return &app{
			folderMode:           true,
			rootDir:              mediaPath,
			displayName:          displayName,
			apkPath:              apkPath,
			httpPort:             httpPort,
			clientLogPath:        clientLogPath,
			storyboardIntervalMs: storyboardIntervalMs,
		}, nil
	}
	chapters := chaptersFor(mediaPath)
	// ffprobe reads the real stream (colour + Dolby Vision profile). Without it
	// there's no metadata at all, so default to SDR — thumbnails/storyboard are
	// disabled anyway (they need ffmpeg), making the HDR flag moot.
	probe, ok := probeMedia(mediaPath)
	if !ok {
		probe = mediaProbe{isHDR: false, dvProfile: -1}
		probe.summary = "ffprobe unavailable — HDR/Dolby Vision detection disabled"
	}
	hdr := probe.isHDR
	// Keep the thumbnail/storyboard caches next to the executable (in cache/)
	// rather than the system temp dir, so cleanup is just deleting that folder.
	cDir := fileCacheDir(mediaPath, info.Size(), info.ModTime())
	return &app{
		mediaPath:            mediaPath,
		mediaName:            filepath.Base(mediaPath),
		mediaSize:            info.Size(),
		mediaMod:             info.ModTime(),
		mediaMime:            mimeForPath(mediaPath),
		displayName:          displayName,
		apkPath:              apkPath,
		httpPort:             httpPort,
		clientLogPath:        clientLogPath,
		resume:               newResumeStore(resumePath, mediaPath),
		chapters:             chapters,
		thumbs:               newThumbnailer(mediaPath, info.ModTime(), chapters, hdr, filepath.Join(cDir, "thumbs")),
		story:                newStoryboard(mediaPath, mediaDurationMs(mediaPath), storyboardIntervalMs, hdr, filepath.Join(cDir, "storyboard")),
		probe:                probe,
		subtitles: findSidecarSubtitles(mediaPath, func(name string) string {
			return "/subtitle?name=" + url.QueryEscape(name)
		}),
		cacheRoot:            cDir,
		storyboardIntervalMs: storyboardIntervalMs,
	}, nil
}

// newMultiRootApp creates a multi-root folder-mode app from multiple directories.
// Each directory becomes a browsable root the client can select.
func newMultiRootApp(dirs []string, displayName, apkPath, clientLogPath string, httpPort int, storyboardIntervalMs int64) (*app, error) {
	roots := make([]rootEntry, 0, len(dirs))
	for _, d := range dirs {
		if len(d) == 2 && d[1] == ':' {
			d += string(os.PathSeparator)
		}
		info, err := os.Stat(d)
		if err != nil {
			return nil, fmt.Errorf("cannot open %s: %w", d, err)
		}
		if !info.IsDir() {
			return nil, fmt.Errorf("%s is not a directory (multi-root mode requires all arguments to be directories)", d)
		}
		roots = append(roots, rootEntry{name: filepath.Base(d), dir: d})
	}
	return &app{
		folderMode:           true,
		multiRoot:            true,
		roots:                roots,
		displayName:          displayName,
		apkPath:              apkPath,
		httpPort:             httpPort,
		clientLogPath:        clientLogPath,
		storyboardIntervalMs: storyboardIntervalMs,
	}, nil
}

// logMetadata writes everything the server knows about the served file — the
// consolidated result of every ffprobe/ffmpeg query — to the ffmpeg/ffprobe log
// and echoes a copy to the console. Combined with the raw per-command output
// (recordProbe), this makes the log a complete record of the media. No-op in
// folder mode.
func (a *app) logMetadata(ffprobeFound, ffmpegFound bool) {
	if a.folderMode {
		return
	}
	var b strings.Builder
	fmt.Fprintf(&b, "==== media metadata ====\n")
	fmt.Fprintf(&b, "file:       %s\n", a.mediaName)
	fmt.Fprintf(&b, "path:       %s\n", a.mediaPath)
	fmt.Fprintf(&b, "size:       %d bytes (%s)\n", a.mediaSize, formatSize(a.mediaSize))
	fmt.Fprintf(&b, "mime:       %s\n", a.mediaMime)
	fmt.Fprintf(&b, "modified:   %s\n", a.mediaMod.Format(time.RFC3339))
	fmt.Fprintf(&b, "ffprobe:    %s\n", foundStr(ffprobeFound))
	fmt.Fprintf(&b, "ffmpeg:     %s\n", foundStr(ffmpegFound))
	dur := "unknown"
	if a.story.durationMs > 0 {
		dur = formatDuration(a.story.durationMs)
	}
	fmt.Fprintf(&b, "duration:   %s (%d ms)\n", dur, a.story.durationMs)
	fmt.Fprintf(&b, "video:      %s\n", orNA(a.probe.summary))
	fmt.Fprintf(&b, "  transfer=%s space=%s hdr=%v hdr10+=%v dvProfile=%d\n",
		orNA(a.probe.colorTransfer), orNA(a.probe.colorSpace),
		a.probe.isHDR, a.probe.hdr10plus, a.probe.dvProfile)
	fmt.Fprintf(&b, "thumbnails: available=%v\n", a.thumbs.available())
	fmt.Fprintf(&b, "storyboard: enabled=%v interval=%dms\n", a.story.enabled(), a.story.intervalMs)
	fmt.Fprintf(&b, "subtitles:  %d sidecar\n", len(a.subtitles))
	for _, s := range a.subtitles {
		fmt.Fprintf(&b, "  %s [%s] %s\n", s.Label, s.Mime, s.URL)
	}
	fmt.Fprintf(&b, "chapters:   %d\n", len(a.chapters))
	for i, c := range a.chapters {
		fmt.Fprintf(&b, "  [%02d] %-9s %s\n", i+1, formatDuration(c.StartMs), c.Name)
	}
	s := strings.TrimRight(b.String(), "\n")
	ffmpegLog.logf("%s", s)
	fmt.Println(s)
}

func foundStr(found bool) string {
	if found {
		return "found"
	}
	return "NOT FOUND"
}

// formatDuration renders a non-negative millisecond timestamp as HH:MM:SS.
// (0 is a valid timestamp, e.g. a chapter at the very start.)
func formatDuration(ms int64) string {
	if ms < 0 {
		return "unknown"
	}
	s := ms / 1000
	return fmt.Sprintf("%02d:%02d:%02d", s/3600, (s%3600)/60, s%60)
}

// executableDir returns the directory of the running binary (where cache/,
// client-logs.txt etc. live), or "." if it can't be determined.
func executableDir() string {
	if exe, err := os.Executable(); err == nil {
		return filepath.Dir(exe)
	}
	return "."
}

// extToMime lists every file type the Fire TV client (ExoPlayer) can read, mapped
// to its Content-Type. These mirror ExoPlayer's default extractors (Mp4, Matroska,
// Ts, Ps, Flv, Avi, Ogg, Mp3, Aac, Ac3, Ac4, Flac, Wav, Amr, and the image
// extractors). NOTE: .m2ts/.mts are TS but use 192-byte packets ExoPlayer can't
// parse, so they are deliberately absent (handled with a remux advisory instead).
var extToMime = map[string]string{
	// video containers
	".mp4": "video/mp4", ".m4v": "video/mp4", ".mov": "video/quicktime",
	".mkv": "video/x-matroska", ".webm": "video/webm", ".ts": "video/mp2t",
	".flv": "video/x-flv", ".avi": "video/x-msvideo", ".ogv": "video/ogg",
	".mpg": "video/mpeg", ".mpeg": "video/mpeg", ".ps": "video/mpeg", ".vob": "video/mpeg",
	// audio
	".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".aac": "audio/aac", ".adts": "audio/aac",
	".ac3": "audio/ac3", ".eac3": "audio/eac3", ".ec3": "audio/eac3", ".ac4": "audio/ac4",
	".flac": "audio/flac", ".wav": "audio/wav", ".ogg": "audio/ogg", ".oga": "audio/ogg",
	".opus": "audio/opus", ".amr": "audio/amr", ".mka": "audio/x-matroska",
	// images
	".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp",
	".bmp": "image/bmp", ".heic": "image/heif", ".heif": "image/heif", ".avif": "image/avif",
}

// mimeForPath returns the Content-Type for a supported file, else octet-stream.
func mimeForPath(path string) string {
	if m, ok := extToMime[strings.ToLower(filepath.Ext(path))]; ok {
		return m
	}
	return "application/octet-stream"
}

// isPlayable reports whether the client can play this file type.
func isPlayable(path string) bool {
	_, ok := extToMime[strings.ToLower(filepath.Ext(path))]
	return ok
}

// supportedExtensions returns all playable extensions, sorted, for error messages.
func supportedExtensions() []string {
	exts := make([]string, 0, len(extToMime))
	for e := range extToMime {
		exts = append(exts, e)
	}
	sort.Strings(exts)
	return exts
}

// remuxMkvPath returns the media path with its extension changed to .mkv.
func remuxMkvPath(mediaPath string) string {
	ext := filepath.Ext(mediaPath)
	return strings.TrimSuffix(mediaPath, ext) + ".mkv"
}

// containerAdvisory returns a console message for containers the Fire TV client
// can't play, or "" for playable ones. .m2ts/.mts are Blu-ray BDAV transport
// streams with 192-byte packets, which ExoPlayer's TsExtractor (188-byte only)
// cannot parse; the fix is a lossless remux to MKV.
func containerAdvisory(mediaPath string) string {
	switch strings.ToLower(filepath.Ext(mediaPath)) {
	case ".m2ts", ".mts":
		out := remuxMkvPath(mediaPath)
		return "" +
			"\n⚠ Blu-ray transport stream (" + filepath.Ext(mediaPath) + ") detected.\n" +
			"  The Fire TV client (ExoPlayer) can't parse 192-byte M2TS packets, so this\n" +
			"  file won't play. Remux to MKV once — lossless and fast (no re-encode),\n" +
			"  keeps every video/audio/subtitle track:\n\n" +
			fmt.Sprintf("    ffmpeg -i \"%s\" -map 0 -c copy \"%s\"\n\n", mediaPath, out) +
			"  Then run bitstreamer on the .mkv instead.\n"
	}
	return ""
}

func (a *app) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.handleIndex)
	mux.HandleFunc("/info", a.handleInfo)
	mux.HandleFunc("/stream", a.handleStream)
	mux.HandleFunc("/client.apk", a.handleAPK)
	mux.HandleFunc("/log", a.handleClientLog)
	mux.HandleFunc("/subtitle", a.handleSubtitle) // sidecar .srt/.ass/... (both modes)
	mux.HandleFunc("/position", a.handlePosition)
	mux.HandleFunc("/chapter-thumb", a.handleChapterThumb)
	mux.HandleFunc("/storyboard.json", a.handleStoryboardManifest)
	mux.HandleFunc("/storyboard", a.handleStoryboardSheet)
	mux.HandleFunc("/generate-previews", a.handleGeneratePreviews)
	mux.HandleFunc("/preview-status", a.handlePreviewStatus)
	if a.folderMode {
		mux.HandleFunc("/list", a.handleList)
	}
	return logRequests(mux)
}

// parseRoot extracts the ?root=N query parameter for multi-root mode. Returns
// the root index and true, or writes an HTTP error and returns false.
func (a *app) parseRoot(w http.ResponseWriter, r *http.Request) (int, bool) {
	rs := r.URL.Query().Get("root")
	if rs == "" {
		http.Error(w, "root parameter required in multi-root mode", http.StatusBadRequest)
		return 0, false
	}
	idx, err := strconv.Atoi(rs)
	if err != nil || idx < 0 || idx >= len(a.roots) {
		http.Error(w, "invalid root index", http.StatusBadRequest)
		return 0, false
	}
	return idx, true
}

// resolveRootPath is like resolvePath but resolves against a specific root
// directory instead of rootDir. Used in multi-root mode.
func (a *app) resolveRootPath(rootIdx int, rel string) (string, bool) {
	rootDir := a.roots[rootIdx].dir
	clean := filepath.Clean("/" + strings.ReplaceAll(rel, "\\", "/"))
	full := filepath.Join(rootDir, filepath.FromSlash(clean))
	rootAbs, err1 := filepath.Abs(rootDir)
	fullAbs, err2 := filepath.Abs(full)
	if err1 != nil || err2 != nil {
		return "", false
	}
	prefix := rootAbs
	if !strings.HasSuffix(prefix, string(os.PathSeparator)) {
		prefix = prefix + string(os.PathSeparator)
	}
	if fullAbs != rootAbs && !strings.HasPrefix(fullAbs, prefix) {
		return "", false
	}
	return full, true
}

// resolvePath maps a client-supplied relative path to an absolute path confined
// to rootDir. The leading-slash + Clean collapses any ".." so it can't escape;
// the prefix check is a belt-and-braces guard. Returns ok=false on escape.
func (a *app) resolvePath(rel string) (string, bool) {
	clean := filepath.Clean("/" + strings.ReplaceAll(rel, "\\", "/"))
	full := filepath.Join(a.rootDir, filepath.FromSlash(clean))
	rootAbs, err1 := filepath.Abs(a.rootDir)
	fullAbs, err2 := filepath.Abs(full)
	if err1 != nil || err2 != nil {
		return "", false
	}
	prefix := rootAbs
	if !strings.HasSuffix(prefix, string(os.PathSeparator)) {
		prefix = prefix + string(os.PathSeparator)
	}
	if fullAbs != rootAbs && !strings.HasPrefix(fullAbs, prefix) {
		return "", false
	}
	return full, true
}

// pathDepth counts path segments in a cleaned relative path ("" -> 0).
func pathDepth(rel string) int {
	rel = strings.Trim(strings.ReplaceAll(rel, "\\", "/"), "/")
	if rel == "" {
		return 0
	}
	return strings.Count(rel, "/") + 1
}

// handleList returns a directory listing (folder mode): subfolders (up to depth
// folderMaxDepth) plus playable files, dirs first, alphabetical.
func (a *app) handleList(w http.ResponseWriter, r *http.Request) {
	var full string
	var ok bool
	rel := r.URL.Query().Get("path")
	if a.multiRoot {
		rootIdx, rok := a.parseRoot(w, r)
		if !rok {
			return
		}
		full, ok = a.resolveRootPath(rootIdx, rel)
	} else {
		full, ok = a.resolvePath(rel)
	}
	if !ok {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
	fi, err := os.Stat(full)
	if err != nil || !fi.IsDir() {
		http.Error(w, "not a directory", http.StatusNotFound)
		return
	}
	depth := pathDepth(rel)
	entries, err := os.ReadDir(full)
	if err != nil {
		http.Error(w, "cannot read directory", http.StatusInternalServerError)
		return
	}

	type item struct {
		Name string `json:"name"`
		Dir  bool   `json:"dir"`
		Size int64  `json:"size"`
		Mime string `json:"mime"`
	}
	dirs := []item{}
	files := []item{}
	for _, e := range entries {
		name := e.Name()
		if strings.HasPrefix(name, ".") {
			continue // hide dotfiles
		}
		if e.IsDir() {
			if depth < folderMaxDepth { // don't offer folders deeper than the cap
				dirs = append(dirs, item{Name: name, Dir: true})
			}
		} else if isPlayable(name) {
			files = append(files, item{Name: name, Size: 0, Mime: mimeForPath(name)})
		}
	}
	sort.Slice(dirs, func(i, j int) bool { return strings.ToLower(dirs[i].Name) < strings.ToLower(dirs[j].Name) })
	sort.Slice(files, func(i, j int) bool { return strings.ToLower(files[i].Name) < strings.ToLower(files[j].Name) })

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"v":       1,
		"path":    strings.Trim(strings.ReplaceAll(rel, "\\", "/"), "/"),
		"depth":   depth,
		"entries": append(dirs, files...),
	})
}

func (a *app) handleInfo(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if a.folderMode {
		a.writeFolderInfo(w, r)
		return
	}
	chapters := a.chapters
	if chapters == nil {
		chapters = []Chapter{} // emit [] not null
	}
	json.NewEncoder(w).Encode(map[string]any{
		"v":           1,
		"version":     Version,
		"mode":        "file",
		"name":        a.displayName,
		"file":        a.mediaName,
		"size":        a.mediaSize,
		"mime":        a.mediaMime,
		"chapters":    chapters,
		"thumbnails":  a.thumbs.available(),
		"storyboard":  a.story.enabled(),
		"video":       a.videoInfo(a.probe),
		"audioTracks": a.probe.audioTracks,
		"subtitles":   subsOrEmpty(a.subtitles),
	})
}

// rootJSON returns a JSON-serializable representation of the roots list.
func (a *app) rootsJSON() []map[string]any {
	result := make([]map[string]any, len(a.roots))
	for i, r := range a.roots {
		result[i] = map[string]any{"index": i, "name": r.name}
	}
	return result
}

func (a *app) videoInfo(p mediaProbe) map[string]any {
	return map[string]any{
		"hdr":              p.isHDR,
		"hdr10plus":        p.hdr10plus,
		"transfer":         p.colorTransfer,
		"colorSpace":       p.colorSpace,
		"dvProfile":        p.dvProfile,
		"videoBitrate":     p.videoBitrate,
		"audioBitrate":     p.audioBitrate,
		"codec":            p.videoCodec,
		"profile":          p.videoProfile,
		"level":            p.videoLevel,
		"rFrameRate":       p.videoRFrameRate,
		"avgFrameRate":     p.videoAvgFrameRate,
		"pixFmt":           p.videoPixFmt,
		"bitsPerRawSample": p.videoBitsPerSample,
		"stripDV":          stripDV,
	}
}

type folderMediaItem struct {
	full     string
	size     int64
	modTime  time.Time
	probe    mediaProbe
	chapters []Chapter
	thumbs   *thumbnailer
	story    *storyboard
	genOnce  sync.Once
}

func isVideoFile(path string) bool {
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".mkv", ".mp4", ".m4v", ".mov", ".avi", ".webm", ".ts", ".m2ts":
		return true
	}
	return false
}

func (a *app) getOrCreateFolderMedia(full string, fi os.FileInfo, probe mediaProbe, chapters []Chapter) *folderMediaItem {
	a.folderMediaMu.Lock()
	if a.folderMedia == nil {
		a.folderMedia = make(map[string]*folderMediaItem)
	}
	item, exists := a.folderMedia[full]
	if exists && item.modTime.Equal(fi.ModTime()) {
		a.folderMediaMu.Unlock()
		return item
	}
	cDir := fileCacheDir(full, fi.Size(), fi.ModTime())
	thDir := filepath.Join(cDir, "thumbs")
	sbDir := filepath.Join(cDir, "storyboard")
	th := newThumbnailer(full, fi.ModTime(), chapters, probe.isHDR, thDir)
	sb := newStoryboard(full, mediaDurationMs(full), a.storyboardIntervalMs, probe.isHDR, sbDir)

	item = &folderMediaItem{
		full:     full,
		size:     fi.Size(),
		modTime:  fi.ModTime(),
		probe:    probe,
		chapters: chapters,
		thumbs:   th,
		story:    sb,
	}
	a.folderMedia[full] = item
	a.folderMediaMu.Unlock()
	return item
}

func (a *app) getFolderMediaByRequest(r *http.Request) *folderMediaItem {
	rel := r.URL.Query().Get("path")
	if rel == "" {
		return nil
	}
	var full string
	var ok bool
	if a.multiRoot {
		rootIdx, rok := a.parseRoot(nil, r)
		if !rok {
			return nil
		}
		full, ok = a.resolveRootPath(rootIdx, rel)
	} else if a.folderMode {
		full, ok = a.resolvePath(rel)
	}
	if !ok {
		return nil
	}
	fi, err := os.Stat(full)
	if err != nil || fi.IsDir() {
		return nil
	}
	probe, _ := probeMedia(full)
	chapters := chaptersFor(full)
	return a.getOrCreateFolderMedia(full, fi, probe, chapters)
}

// writeFolderInfo serves /info in folder mode. With no ?path it returns the
// folder root marker; with a ?path it returns that file's metadata (ffprobe
// colour on demand — no caching in folder mode). In multi-root mode, ?root=N
// selects the root; without ?root returns the roots list.
func (a *app) writeFolderInfo(w http.ResponseWriter, r *http.Request) {
	rel := r.URL.Query().Get("path")
	rootParam := r.URL.Query().Get("root")

	// Multi-root mode: no ?root → return the roots list.
	if a.multiRoot && rootParam == "" {
		json.NewEncoder(w).Encode(map[string]any{
			"v":       1,
			"version": Version,
			"mode":    "multi",
			"name":    a.displayName,
			"roots":   a.rootsJSON(),
		})
		return
	}

	// Resolve against the appropriate root.
	var rootIdx int
	if a.multiRoot {
		var rok bool
		rootIdx, rok = a.parseRoot(w, r)
		if !rok {
			return
		}
	}

	if rel == "" {
		// Per-root folder marker.
		rootName := ""
		if a.multiRoot {
			rootName = a.roots[rootIdx].name
		} else {
			rootName = filepath.Base(a.rootDir)
		}
		mode := "folder"
		if a.multiRoot {
			mode = "multi"
		}
		json.NewEncoder(w).Encode(map[string]any{
			"v":       1,
			"version": Version,
			"mode":    mode,
			"name":    a.displayName,
			"file":    rootName,
		})
		return
	}

	var full string
	var ok bool
	if a.multiRoot {
		full, ok = a.resolveRootPath(rootIdx, rel)
	} else {
		full, ok = a.resolvePath(rel)
	}
	if !ok {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
	fi, err := os.Stat(full)
	if err != nil || fi.IsDir() {
		http.Error(w, "not a file", http.StatusNotFound)
		return
	}
	probe, pok := probeMedia(full)
	if !pok {
		probe = mediaProbe{dvProfile: -1}
	}
	chapters := chaptersFor(full)
	if chapters == nil {
		chapters = []Chapter{}
	}
	var hasThumbs, hasStory bool
	if !fi.IsDir() && isVideoFile(full) {
		item := a.getOrCreateFolderMedia(full, fi, probe, chapters)
		hasThumbs = item.thumbs.available()
		hasStory = item.story.enabled()
	}
	mode := "folder"
	if a.multiRoot {
		mode = "multi"
	}
	// Build subtitle URLs with ?root=N in multi-root mode.
	var subs []subtitleTrack
	if a.multiRoot {
		subs = multiRootFolderSubtitlesFor(full, rel, rootIdx)
	} else {
		subs = folderSubtitlesFor(full, rel)
	}
	json.NewEncoder(w).Encode(map[string]any{
		"v":           1,
		"version":     Version,
		"mode":        mode,
		"file":        filepath.Base(full),
		"size":        fi.Size(),
		"mime":        mimeForPath(full),
		"chapters":    chapters,
		"thumbnails":  hasThumbs,
		"storyboard":  hasStory,
		"video":       a.videoInfo(probe),
		"audioTracks": probe.audioTracks,
		"subtitles":   subsOrEmpty(subs),
	})
}

func (a *app) handleStream(w http.ResponseWriter, r *http.Request) {
	path := a.mediaPath
	mime := a.mediaMime
	if a.folderMode {
		var full string
		var ok bool
		if a.multiRoot {
			rootIdx, rok := a.parseRoot(w, r)
			if !rok {
				return
			}
			full, ok = a.resolveRootPath(rootIdx, r.URL.Query().Get("path"))
		} else {
			full, ok = a.resolvePath(r.URL.Query().Get("path"))
		}
		if !ok {
			http.Error(w, "bad path", http.StatusBadRequest)
			return
		}
		path = full
		mime = mimeForPath(full)
	}
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "file is no longer readable", http.StatusInternalServerError)
		log.Printf("stream: %v", err)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil || fi.IsDir() {
		http.Error(w, "not a file", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", mime)
	// ServeContent implements Range/206, If-Range and HEAD, and streams from
	// the file without buffering it. Empty name: Content-Type is already set.
	http.ServeContent(w, r, "", fi.ModTime(), f)
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

func (a *app) handlePosition(w http.ResponseWriter, r *http.Request) {
	if a.resume == nil {
		http.Error(w, "position endpoint disabled in folder mode", http.StatusNotFound)
		return
	}
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
	var th *thumbnailer
	if a.folderMode || a.multiRoot {
		if r.URL.Query().Get("path") == "" {
			http.Error(w, "path required in folder mode", http.StatusNotFound)
			return
		}
		item := a.getFolderMediaByRequest(r)
		if item != nil {
			th = item.thumbs
		}
	} else {
		th = a.thumbs
	}
	if th == nil {
		http.Error(w, "thumbnail unavailable", http.StatusNotFound)
		return
	}
	path, err := th.get(index)
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
	var sb *storyboard
	if a.folderMode || a.multiRoot {
		if r.URL.Query().Get("path") == "" {
			http.Error(w, "path required in folder mode", http.StatusNotFound)
			return
		}
		item := a.getFolderMediaByRequest(r)
		if item != nil {
			sb = item.story
		}
	} else {
		sb = a.story
	}
	if sb == nil || !sb.isReady() {
		http.Error(w, "storyboard not ready", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(sb.manifest())
}

// handleStoryboardSheet serves sprite sheet ?sheet=N.
func (a *app) handleStoryboardSheet(w http.ResponseWriter, r *http.Request) {
	n, err := strconv.Atoi(r.URL.Query().Get("sheet"))
	if err != nil {
		http.Error(w, "sheet must be an integer", http.StatusBadRequest)
		return
	}
	var sb *storyboard
	if a.folderMode || a.multiRoot {
		if r.URL.Query().Get("path") == "" {
			http.Error(w, "path required in folder mode", http.StatusNotFound)
			return
		}
		item := a.getFolderMediaByRequest(r)
		if item != nil {
			sb = item.story
		}
	} else {
		sb = a.story
	}
	if sb == nil {
		http.Error(w, "sheet unavailable", http.StatusNotFound)
		return
	}
	path, ok := sb.sheetPath(n)
	if !ok {
		http.Error(w, "sheet unavailable", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "max-age=86400")
	http.ServeFile(w, r, path)
}

func (a *app) handleGeneratePreviews(w http.ResponseWriter, r *http.Request) {
	var th *thumbnailer
	var sb *storyboard
	var full string
	if a.folderMode || a.multiRoot {
		if r.URL.Query().Get("path") == "" {
			http.Error(w, "path required in folder mode", http.StatusBadRequest)
			return
		}
		item := a.getFolderMediaByRequest(r)
		if item != nil {
			th = item.thumbs
			sb = item.story
			full = item.full
			if !noCaching && isVideoFile(full) {
				item.genOnce.Do(func() {
					if th.available() {
						go th.warm()
					}
					if sb.enabled() {
						go sb.generate()
					}
				})
			}
		}
	} else {
		th = a.thumbs
		sb = a.story
		full = a.mediaPath
		if !noCaching && isVideoFile(full) {
			a.genOnce.Do(func() {
				if th.available() {
					go th.warm()
				}
				if sb.enabled() {
					go sb.generate()
				}
			})
		}
	}
	if sb == nil {
		http.Error(w, "previews disabled or unavailable", http.StatusNotFound)
		return
	}
	done, total, percent, ready := sb.progress()
	status := "generating"
	if ready {
		status = "ready"
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"status":  status,
		"percent": percent,
		"done":    done,
		"total":   total,
	})
}

func (a *app) handlePreviewStatus(w http.ResponseWriter, r *http.Request) {
	var sb *storyboard
	if a.folderMode || a.multiRoot {
		if r.URL.Query().Get("path") == "" {
			http.Error(w, "path required in folder mode", http.StatusBadRequest)
			return
		}
		item := a.getFolderMediaByRequest(r)
		if item != nil {
			sb = item.story
		}
	} else {
		sb = a.story
	}
	if sb == nil {
		http.Error(w, "previews unavailable", http.StatusNotFound)
		return
	}
	done, total, percent, ready := sb.progress()
	status := "idle"
	if ready {
		status = "ready"
	} else if total > 0 {
		status = "generating"
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"status":  status,
		"percent": percent,
		"done":    done,
		"total":   total,
	})
}

func (a *app) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if a.multiRoot {
		fmt.Fprintf(w, `<!doctype html>
<title>BitStreamer</title>
<h1>BitStreamer &mdash; %s</h1>
<p>Serving %d folder roots &mdash; browse them from the Fire TV client.</p>
<ul>
`, html.EscapeString(a.displayName), len(a.roots))
		for i, r := range a.roots {
			fmt.Fprintf(w, "  <li><a href=\"/list?root=%d\">/list?root=%d</a> &mdash; %s</li>\n",
				i, i, html.EscapeString(r.name))
		}
		fmt.Fprintf(w, `  <li><a href="/client.apk">/client.apk</a> &mdash; Fire TV client APK</li>
</ul>
`)
		return
	}
	if a.folderMode {
		fmt.Fprintf(w, `<!doctype html>
<title>BitStreamer</title>
<h1>BitStreamer &mdash; %s</h1>
<p>Serving folder <b>%s</b> &mdash; browse it from the Fire TV client.</p>
<ul>
  <li><a href="/list">/list</a> &mdash; directory listing (JSON)</li>
  <li><a href="/client.apk">/client.apk</a> &mdash; Fire TV client APK</li>
</ul>
`, html.EscapeString(a.displayName), html.EscapeString(filepath.Base(a.rootDir)))
		return
	}
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
