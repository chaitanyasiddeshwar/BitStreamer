package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// makeSampleMP4 renders a short mp4 with two chapters via ffmpeg, or skips the
// test if ffmpeg/ffprobe aren't installed (they are optional sidecars).
func makeSampleMP4(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed; skipping ffprobe-fallback test")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed; skipping ffprobe-fallback test")
	}
	dir := t.TempDir()
	meta := filepath.Join(dir, "chap.txt")
	metaContent := ";FFMETADATA1\n" +
		"[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=Intro\n" +
		"[CHAPTER]\nTIMEBASE=1/1000\nSTART=1000\nEND=3000\ntitle=Main\n"
	if err := os.WriteFile(meta, []byte(metaContent), 0o644); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(dir, "sample.mp4")
	cmd := exec.Command("ffmpeg", "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=10",
		"-i", meta, "-map_metadata", "1",
		"-c:v", "libx264", "-pix_fmt", "yuv420p", out,
	)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("ffmpeg could not build sample mp4 (%v): %s", err, b)
	}
	return out
}

// An mp4 with chapter markers must surface them via ffprobe — the exact case
// the user hit (an mp4 whose chapters were previously ignored).
func TestChaptersForMP4ViaFFprobe(t *testing.T) {
	mp4 := makeSampleMP4(t)

	ch := chaptersFor(mp4)
	if len(ch) != 2 {
		t.Fatalf("chaptersFor: want 2 chapters, got %d (%v)", len(ch), ch)
	}
	if ch[0].Name != "Intro" || ch[0].StartMs != 0 {
		t.Errorf("chapter 0: got %+v, want {0 Intro}", ch[0])
	}
	if ch[1].Name != "Main" || ch[1].StartMs != 1000 {
		t.Errorf("chapter 1: got %+v, want {1000 Main}", ch[1])
	}
}

// mediaDurationMs must report an mp4's duration (via ffprobe) so the scrubbing
// storyboard is enabled for mp4 files.
func TestMediaDurationMP4ViaFFprobe(t *testing.T) {
	mp4 := makeSampleMP4(t)

	ms := mediaDurationMs(mp4)
	if ms < 2500 || ms > 3500 {
		t.Errorf("mediaDurationMs: got %d ms, want ~3000", ms)
	}
}
