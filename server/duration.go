package main

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/chaitanyasiddeshwar/bitstreamer/server/third_party/mkvparse"
)

// mediaDurationMs returns the total duration in milliseconds, reading the MKV
// container header when possible (no external tools) and otherwise falling back
// to ffprobe (for mp4/mov/etc., whose duration the MKV parser can't read).
// Returns 0 if it can't be determined — that just disables the storyboard.
func mediaDurationMs(path string) int64 {
	if ms := parseDuration(path); ms > 0 {
		return ms
	}
	return probeDurationMs(path)
}

// probeDurationMs asks ffprobe for the container duration (format=duration, in
// seconds). Returns 0 if ffprobe is unavailable or the file has no duration.
func probeDurationMs(path string) int64 {
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

// parseDuration reads the total duration of an MKV from its Segment\Info header
// (Duration, in TimecodeScale units). Returns 0 if unknown / not an MKV.
func parseDuration(path string) int64 {
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()

	h := &durationHandler{timecodeScale: 1_000_000} // default 1ms in ns
	if err := mkvparse.ParseSections(f, h, mkvparse.InfoElement); err != nil {
		return 0
	}
	if h.duration <= 0 {
		return 0
	}
	// duration is in TimecodeScale units; TimecodeScale is ns per unit.
	ns := h.duration * float64(h.timecodeScale)
	return int64(ns / 1_000_000) // -> milliseconds
}

type durationHandler struct {
	mkvparse.DefaultHandler
	duration      float64
	timecodeScale int64
}

func (h *durationHandler) HandleFloat(id mkvparse.ElementID, value float64, _ mkvparse.ElementInfo) error {
	if id == mkvparse.DurationElement {
		h.duration = value
	}
	return nil
}

func (h *durationHandler) HandleInteger(id mkvparse.ElementID, value int64, _ mkvparse.ElementInfo) error {
	if id == mkvparse.TimecodeScaleElement {
		h.timecodeScale = value
	}
	return nil
}
