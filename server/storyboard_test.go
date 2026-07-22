package main

import (
	"encoding/json"
	"flag"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestMediaDurationFromFixture(t *testing.T) {
	if findFFprobe() == "" {
		t.Skip("ffprobe not installed; duration comes from ffprobe")
	}
	// The fixture is a 30-second clip.
	ms := mediaDurationMs(filepath.Join("testdata", "chapters_sample.mkv"))
	if ms < 29_000 || ms > 31_000 {
		t.Errorf("duration = %dms, want ~30000", ms)
	}
}

func TestShortVideoDynamicInterval(t *testing.T) {
	if findFFprobe() == "" {
		t.Skip("ffprobe not installed")
	}
	dir := t.TempDir()
	src, _ := os.ReadFile(filepath.Join("testdata", "chapters_sample.mkv")) // 30s video (<10m)
	path := filepath.Join(dir, "short.mkv")
	if err := os.WriteFile(path, src, 0o644); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(path, "T", filepath.Join(dir, "c.apk"),
		filepath.Join(dir, "log.txt"), filepath.Join(dir, "resume.json"), 46898, 30000)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(a.story.cleanup)
	if a.story.intervalMs != 10000 {
		t.Errorf("intervalMs = %d, want 10000 (10s) for <10m video", a.story.intervalMs)
	}
}

func TestStoryboardGeneration(t *testing.T) {
	path := filepath.Join("testdata", "chapters_sample.mkv")
	sb := newStoryboard(path, mediaDurationMs(path), 5000, false, t.TempDir()) // 5s interval over ~30s
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
	resp, err := http.Get(srv.URL + "/storyboard.json")
	if err != nil {
		t.Fatal(err)
	}
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

func TestStoryboardSkipPreviews(t *testing.T) {
	if f := flag.Lookup("skip-previews"); f == nil {
		flag.Bool("skip-previews", false, "")
	}
	flag.Set("skip-previews", "true")
	defer flag.Set("skip-previews", "false")

	path := filepath.Join("testdata", "chapters_sample.mkv")
	sb := newStoryboard(path, 30000, 5000, false, t.TempDir())
	if sb.enabled() {
		t.Error("expected storyboard to be disabled when -skip-previews is true")
	}
}

func TestOnDemandPreviewEndpoints(t *testing.T) {
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
	h := a.handler()

	// 1. Initial status is idle or ready
	req := httptest.NewRequest("GET", "/preview-status", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("preview-status status = %d, want 200", rec.Code)
	}
	var st struct {
		Status  string `json:"status"`
		Percent int    `json:"percent"`
	}
	json.NewDecoder(rec.Body).Decode(&st)
	if st.Status != "idle" && st.Status != "ready" {
		t.Errorf("initial preview-status = %s, want idle or ready", st.Status)
	}

	// 2. Trigger generation
	genReq := httptest.NewRequest("POST", "/generate-previews", nil)
	genRec := httptest.NewRecorder()
	h.ServeHTTP(genRec, genReq)
	if genRec.Code != http.StatusOK {
		t.Fatalf("generate-previews status = %d, want 200", genRec.Code)
	}
}
