package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// fileCacheHash computes a 16-character hex hash for a file based on its
// clean absolute path, byte size, and modification timestamp.
func fileCacheHash(path string, size int64, modTime time.Time) string {
	h := sha256.New()
	clean := filepath.Clean(path)
	fmt.Fprintf(h, "%s:%d:%d", clean, size, modTime.UnixNano())
	return hex.EncodeToString(h.Sum(nil))[:16]
}

// cacheBaseDir returns the root cache directory next to the executable.
func cacheBaseDir() string {
	return filepath.Join(executableDir(), "cache")
}

// fileCacheDir returns the persistent cache folder for a specific file.
func fileCacheDir(path string, size int64, modTime time.Time) string {
	hash := fileCacheHash(path, size, modTime)
	return filepath.Join(cacheBaseDir(), hash)
}

// chapterCachePath returns the JSON file path inside cacheDir named after the movie file:
// e.g. cache/hash/Ford.v.Ferrari.2019.json
func chapterCachePath(cacheDir, mediaPath string) string {
	base := filepath.Base(mediaPath)
	ext := filepath.Ext(base)
	nameWithoutExt := strings.TrimSuffix(base, ext)
	return filepath.Join(cacheDir, nameWithoutExt+".json")
}

type MediaCache struct {
	File       string           `json:"file"`
	Path       string           `json:"path"`
	Size       int64            `json:"size"`
	DurationMs int64            `json:"durationMs,omitempty"`
	Probe      *MediaProbeCache `json:"probe,omitempty"`
	Chapters   []Chapter        `json:"chapters"`
}

type MediaProbeCache struct {
	IsHDR              bool              `json:"isHDR"`
	Hdr10plus          bool              `json:"hdr10plus"`
	ColorTransfer      string            `json:"colorTransfer,omitempty"`
	ColorSpace         string            `json:"colorSpace,omitempty"`
	DvProfile          int               `json:"dvProfile"`
	DvSubtype          string            `json:"dvSubtype,omitempty"`
	Summary            string            `json:"summary,omitempty"`
	VideoBitrate       int64             `json:"videoBitrate,omitempty"`
	AudioBitrate       int64             `json:"audioBitrate,omitempty"`
	VideoCodec         string            `json:"videoCodec,omitempty"`
	VideoProfile       string            `json:"videoProfile,omitempty"`
	VideoLevel         string            `json:"videoLevel,omitempty"`
	VideoRFrameRate    string            `json:"videoRFrameRate,omitempty"`
	VideoAvgFrameRate  string            `json:"videoAvgFrameRate,omitempty"`
	VideoPixFmt        string            `json:"videoPixFmt,omitempty"`
	VideoBitsPerSample int               `json:"videoBitsPerSample,omitempty"`
	AudioTracks        []audioTrackProbe `json:"audioTracks,omitempty"`
}

var mediaCacheMu sync.Mutex

func readMediaCache(jsonPath string) *MediaCache {
	if noCaching {
		return nil
	}
	mediaCacheMu.Lock()
	defer mediaCacheMu.Unlock()

	f, err := os.Open(jsonPath)
	if err != nil {
		return nil
	}
	defer f.Close()

	var mc MediaCache
	if err := json.NewDecoder(f).Decode(&mc); err != nil {
		return nil
	}
	return &mc
}

func updateMediaCache(jsonPath, mediaPath string, updateFn func(mc *MediaCache)) {
	if noCaching {
		return
	}
	mediaCacheMu.Lock()
	defer mediaCacheMu.Unlock()

	var mc MediaCache
	if f, err := os.Open(jsonPath); err == nil {
		json.NewDecoder(f).Decode(&mc)
		f.Close()
	}

	mc.File = filepath.Base(mediaPath)
	mc.Path = mediaPath
	if mc.Chapters == nil {
		mc.Chapters = []Chapter{}
	}

	updateFn(&mc)

	dir := filepath.Dir(jsonPath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	f, err := os.Create(jsonPath)
	if err != nil {
		return
	}
	defer f.Close()

	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	enc.Encode(mc)
}

// hasCachedThumbs reports whether all chapter thumbnail JPEGs for chapterCount
// exist in the file's cache thumbs directory.
func hasCachedThumbs(fileDir string, chapterCount int) bool {
	if chapterCount <= 0 {
		return false
	}
	thumbsDir := filepath.Join(fileDir, "thumbs")
	for i := 0; i < chapterCount; i++ {
		p := filepath.Join(thumbsDir, fmt.Sprintf("thumb_%03d.jpg", i))
		fi, err := os.Stat(p)
		if err != nil || fi.Size() == 0 {
			return false
		}
	}
	return true
}

// hasCachedStoryboard reports whether storyboard.json manifest and sprite sheets
// exist in the file's cache storyboard directory.
func hasCachedStoryboard(fileDir string) bool {
	sbDir := filepath.Join(fileDir, "storyboard")
	manifestPath := filepath.Join(sbDir, "storyboard.json")
	fi, err := os.Stat(manifestPath)
	return err == nil && fi.Size() > 0
}

// findExecutable looks next to the executable first (sidecar priority), then on PATH.
func findExecutable(name string) string {
	names := []string{name, name + ".exe"}
	dir := executableDir()
	for _, n := range names {
		p := filepath.Join(dir, n)
		if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
			return p
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p
	}
	return ""
}
