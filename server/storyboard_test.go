package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestParseDurationFromFixture(t *testing.T) {
	// The fixture is a 30-second clip.
	ms := parseDuration(filepath.Join("testdata", "chapters_sample.mkv"))
	if ms < 29_000 || ms > 31_000 {
		t.Errorf("duration = %dms, want ~30000", ms)
	}
}

func TestStoryboardGeneration(t *testing.T) {
	path := filepath.Join("testdata", "chapters_sample.mkv")
	sb := newStoryboard(path, parseDuration(path), 5000, parseIsHDR(path), t.TempDir()) // 5s interval over ~30s
	t.Cleanup(sb.cleanup)
	if sb.ffmpegPath == "" {
		t.Skip("ffmpeg not installed")
	}
	if !sb.enabled() {
		t.Fatal("expected storyboard enabled with ffmpeg + duration")
	}
	sb.generate()
	if !sb.isReady() {
		t.Fatal("storyboard not ready after generate")
	}
	if sb.tileWidth <= 0 || sb.tileHeight <= 0 {
		t.Errorf("bad tile dims %dx%d", sb.tileWidth, sb.tileHeight)
	}
	if sb.tileCount < 5 || sb.sheetCount < 1 {
		t.Errorf("unexpected counts: tiles=%d sheets=%d", sb.tileCount, sb.sheetCount)
	}
	if _, ok := sb.sheetPath(0); !ok {
		t.Error("sheet 0 should exist")
	}
	if _, ok := sb.sheetPath(999); ok {
		t.Error("sheet 999 should not exist")
	}
}

func TestStoryboardEndpoints(t *testing.T) {
	dir := t.TempDir()
	src, _ := os.ReadFile(filepath.Join("testdata", "chapters_sample.mkv"))
	path := filepath.Join(dir, "movie.mkv")
	if err := os.WriteFile(path, src, 0o644); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(path, "T", filepath.Join(dir, "c.apk"),
		filepath.Join(dir, "log.txt"), filepath.Join(dir, "resume.json"), 46898, 5000)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(a.story.cleanup)
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	// Before generation: manifest 404s.
	resp, _ := http.Get(srv.URL + "/storyboard.json")
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("pre-generation manifest = %d, want 404", resp.StatusCode)
	}
	resp.Body.Close()

	if a.story.ffmpegPath == "" {
		return // ffmpeg absent: nothing more to verify
	}
	a.story.generate()

	resp, err = http.Get(srv.URL + "/storyboard.json")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("manifest status = %d, want 200", resp.StatusCode)
	}
	var m struct {
		IntervalMs int64 `json:"intervalMs"`
		TileCount  int   `json:"tileCount"`
		SheetCount int   `json:"sheetCount"`
		TileWidth  int   `json:"tileWidth"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		t.Fatal(err)
	}
	if m.IntervalMs != 5000 || m.TileCount < 5 || m.SheetCount < 1 || m.TileWidth <= 0 {
		t.Errorf("unexpected manifest: %+v", m)
	}

	img, err := http.Get(srv.URL + "/storyboard?sheet=0")
	if err != nil {
		t.Fatal(err)
	}
	defer img.Body.Close()
	if img.StatusCode != http.StatusOK || img.Header.Get("Content-Type") != "image/jpeg" {
		t.Errorf("sheet 0 = %d %s", img.StatusCode, img.Header.Get("Content-Type"))
	}
}
