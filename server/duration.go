package main

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// mediaDurationMs returns the media's total duration in milliseconds via ffprobe
// or from the persistent JSON cache file.
func mediaDurationMs(path string) int64 {
	fi, err := os.Stat(path)
	if err != nil {
		return probeDurationDirect(path)
	}
	cDir := fileCacheDir(path, fi.Size(), fi.ModTime())
	jsonPath := chapterCachePath(cDir, path)

	if mc := readMediaCache(jsonPath); mc != nil && mc.DurationMs > 0 {
		return mc.DurationMs
	}

	dur := probeDurationDirect(path)
	if dur > 0 {
		updateMediaCache(jsonPath, path, func(mc *MediaCache) {
			mc.Size = fi.Size()
			mc.DurationMs = dur
		})
	}
	return dur
}

func probeDurationDirect(path string) int64 {
	ffprobe := findFFprobe()
	if ffprobe == "" {
		return 0
	}
	cmd := exec.Command(ffprobe,
		"-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=noprint_wrappers=1:nokey=1", path,
	)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	ffmpegLog.recordProbe("ffprobe duration "+filepath.Base(path), out, stderr.Bytes(), err)
	if err != nil {
		return 0
	}
	secs, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if err != nil || secs <= 0 {
		return 0
	}
	return int64(secs * 1000)
}
