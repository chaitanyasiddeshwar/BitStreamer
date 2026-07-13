package main

import (
	"bytes"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// mediaDurationMs returns the media's total duration in milliseconds via ffprobe
// (format=duration), which works for every container ffprobe understands.
// Returns 0 if ffprobe is unavailable or the file has no duration — that just
// disables the scrubbing storyboard.
func mediaDurationMs(path string) int64 {
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
	ffmpegLog.record("ffprobe duration "+filepath.Base(path), stderr.Bytes(), err)
	if err != nil {
		return 0
	}
	secs, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if err != nil || secs <= 0 {
		return 0
	}
	return int64(secs * 1000)
}
