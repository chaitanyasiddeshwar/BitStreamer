package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// A failing ffmpeg run must be captured in the log (with its stderr), and each
// initFFmpegLog call must add a session header.
func TestFFmpegLogCapturesErrors(t *testing.T) {
	if findFFmpeg() == "" {
		t.Skip("ffmpeg not installed")
	}
	dir := t.TempDir()
	logPath := filepath.Join(dir, "ffmpeg-logs.txt")
	initFFmpegLog(logPath)
	t.Cleanup(func() {
		ffmpegLog.mu.Lock()
		if ffmpegLog.f != nil {
			ffmpegLog.f.Close()
			ffmpegLog.f = nil
		}
		ffmpegLog.mu.Unlock()
	})

	// Extract from a nonexistent file: ffmpeg exits non-zero and writes stderr.
	err := extractFrame(findFFmpeg(), filepath.Join(dir, "does-not-exist.mkv"),
		1.0, 240, 5, false, filepath.Join(dir, "out.jpg"))
	if err == nil {
		t.Fatal("expected ffmpeg to fail on a missing input")
	}

	data, readErr := os.ReadFile(logPath)
	if readErr != nil {
		t.Fatal(readErr)
	}
	s := string(data)
	if !strings.Contains(s, "session started") {
		t.Error("log missing session header")
	}
	if !strings.Contains(s, "ffmpeg") || !strings.Contains(s, "-> ") {
		t.Errorf("failed ffmpeg run not recorded; log:\n%s", s)
	}
}
