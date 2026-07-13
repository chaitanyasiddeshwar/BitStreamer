package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func fixtureThumbnailer(t *testing.T) *thumbnailer {
	t.Helper()
	path := filepath.Join("testdata", "chapters_sample.mkv")
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return newThumbnailer(path, info.ModTime(), parseChapters(path), parseIsHDR(path))
}

func TestThumbnailGeneration(t *testing.T) {
	th := fixtureThumbnailer(t)
	if th.ffmpegPath == "" {
		t.Skip("ffmpeg not installed; sidecar thumbnails unavailable here")
	}
	if !th.available() {
		t.Fatal("expected thumbnailer available with ffmpeg + chapters")
	}

	path, err := th.get(0)
	if err != nil {
		t.Fatalf("get(0): %v", err)
	}
	fi, err := os.Stat(path)
	if err != nil || fi.Size() == 0 {
		t.Fatalf("thumbnail file missing/empty: %v", err)
	}
	// Cached second call returns the same path without regenerating.
	path2, err := th.get(0)
	if err != nil || path2 != path {
		t.Errorf("expected cached path %q, got %q (err %v)", path, path2, err)
	}

	if _, err := th.get(99); err == nil {
		t.Error("expected error for out-of-range index")
	}
}

func TestChapterThumbEndpoint(t *testing.T) {
	dir := t.TempDir()
	src, _ := os.ReadFile(filepath.Join("testdata", "chapters_sample.mkv"))
	path := filepath.Join(dir, "movie.mkv")
	if err := os.WriteFile(path, src, 0o644); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(path, "T", filepath.Join(dir, "c.apk"),
		filepath.Join(dir, "log.txt"), filepath.Join(dir, "resume.json"), 46898, 30000)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/chapter-thumb?index=0")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if a.thumbs.ffmpegPath == "" {
		if resp.StatusCode != http.StatusNotFound {
			t.Errorf("without ffmpeg expected 404, got %d", resp.StatusCode)
		}
		return
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "image/jpeg" {
		t.Errorf("Content-Type = %q, want image/jpeg", ct)
	}
}

func TestThumbnailerUnavailableWithoutChapters(t *testing.T) {
	// A file with no chapters is never thumbnail-able even if ffmpeg exists.
	th := newThumbnailer("nonexistent.mkv", time.Unix(0, 0), nil, false)
	if th.available() {
		t.Error("expected unavailable with no chapters")
	}
	if _, err := th.get(0); err == nil {
		t.Error("expected error from get with no chapters")
	}
}
