package main

import (
	"crypto/sha1"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"
)

var (
	errThumbsUnavailable = errors.New("thumbnails unavailable")
	errBadChapterIndex   = errors.New("chapter index out of range")
)

// thumbnailer generates one JPEG per chapter using an ffmpeg sidecar, cached to
// disk. ffmpeg is optional: if it isn't found, available() is false and the
// server serves no thumbnails (the client then shows chapter names only).
type thumbnailer struct {
	ffmpegPath string
	mediaPath  string
	mediaMod   int64
	cacheDir   string
	chapters   []Chapter

	sem   chan struct{} // caps concurrent ffmpeg processes
	mu    sync.Mutex
	locks map[int]*sync.Mutex // per-index lock: no duplicate generation
}

func newThumbnailer(mediaPath string, mediaMod time.Time, chapters []Chapter) *thumbnailer {
	cacheDir := filepath.Join(os.TempDir(), "bitstreamer-thumbs")
	_ = os.MkdirAll(cacheDir, 0o755)
	return &thumbnailer{
		ffmpegPath: findFFmpeg(),
		mediaPath:  mediaPath,
		mediaMod:   mediaMod.Unix(),
		cacheDir:   cacheDir,
		chapters:   chapters,
		sem:        make(chan struct{}, 3),
		locks:      map[int]*sync.Mutex{},
	}
}

// available reports whether thumbnails can be served (ffmpeg present and the
// file actually has chapters).
func (t *thumbnailer) available() bool {
	return t.ffmpegPath != "" && len(t.chapters) > 0
}

// get returns the on-disk path of chapter [index]'s thumbnail, generating and
// caching it on first request.
func (t *thumbnailer) get(index int) (string, error) {
	if !t.available() {
		return "", errThumbsUnavailable
	}
	if index < 0 || index >= len(t.chapters) {
		return "", errBadChapterIndex
	}
	path := t.thumbPath(index)
	if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
		return path, nil
	}

	lock := t.lockFor(index)
	lock.Lock()
	defer lock.Unlock()
	if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
		return path, nil // produced while we waited for the lock
	}

	t.sem <- struct{}{}
	defer func() { <-t.sem }()

	// Seek a few seconds past the chapter start to dodge black fade-ins.
	// -ss before -i is a fast keyframe seek; one frame; scaled to 320px wide
	// (-2 keeps aspect and an even height, required by the JPEG encoder).
	seekSec := float64(t.chapters[index].StartMs+5000) / 1000.0
	tmp := fmt.Sprintf("%s.%d.tmp", path, os.Getpid())
	// -f mjpeg forces the JPEG encoder explicitly: the temp file ends in .tmp,
	// so ffmpeg can't infer the format from the extension.
	cmd := exec.Command(t.ffmpegPath,
		"-nostdin", "-loglevel", "error",
		"-ss", fmt.Sprintf("%.3f", seekSec),
		"-i", t.mediaPath,
		"-frames:v", "1",
		"-vf", "scale=320:-2",
		"-q:v", "4",
		"-f", "mjpeg",
		"-y", tmp,
	)
	if err := cmd.Run(); err != nil {
		os.Remove(tmp)
		return "", err
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return "", err
	}
	return path, nil
}

func (t *thumbnailer) lockFor(index int) *sync.Mutex {
	t.mu.Lock()
	defer t.mu.Unlock()
	if l, ok := t.locks[index]; ok {
		return l
	}
	l := &sync.Mutex{}
	t.locks[index] = l
	return l
}

// thumbPath is stable per (file, mtime, index) so cached thumbnails survive a
// server restart but are regenerated if the file changes.
func (t *thumbnailer) thumbPath(index int) string {
	key := fmt.Sprintf("%s|%d|%d", t.mediaPath, t.mediaMod, index)
	sum := sha1.Sum([]byte(key))
	return filepath.Join(t.cacheDir, fmt.Sprintf("%x_%d.jpg", sum[:8], index))
}

// findFFmpeg looks next to the executable first (the documented sidecar spot),
// then on PATH. Returns "" if not found.
func findFFmpeg() string {
	names := []string{"ffmpeg", "ffmpeg.exe"}
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		for _, n := range names {
			p := filepath.Join(dir, n)
			if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
				return p
			}
		}
	}
	if p, err := exec.LookPath("ffmpeg"); err == nil {
		return p
	}
	return ""
}
