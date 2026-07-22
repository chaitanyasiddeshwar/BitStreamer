package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
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
