package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// testdata/chapters_sample.mkv is a tiny ffmpeg-generated MKV with three
// named chapters at 0s / 10s / 20s.
func TestChaptersForFixture(t *testing.T) {
	if findFFprobe() == "" {
		t.Skip("ffprobe not installed; chapters come from ffprobe")
	}
	chapters := chaptersFor(filepath.Join("testdata", "chapters_sample.mkv"))
	if len(chapters) != 3 {
		t.Fatalf("got %d chapters, want 3: %+v", len(chapters), chapters)
	}
	want := []Chapter{
		{StartMs: 0, Name: "Opening"},
		{StartMs: 10000, Name: "The Middle"},
		{StartMs: 20000, Name: "Finale"},
	}
	for i, w := range want {
		if chapters[i] != w {
			t.Errorf("chapter[%d] = %+v, want %+v", i, chapters[i], w)
		}
	}
}

func TestChaptersForNonMedia(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "notmedia.bin")
	if err := os.WriteFile(path, []byte("this is not a media file"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := chaptersFor(path); got != nil {
		t.Errorf("expected nil chapters for non-media file, got %+v", got)
	}
}

func TestChaptersForMissingFile(t *testing.T) {
	if got := chaptersFor("/no/such/file.mkv"); got != nil {
		t.Errorf("expected nil chapters for missing file, got %+v", got)
	}
}

func TestChapterJsonCacheBypassesFFprobe(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "MyMovie.mkv")
	if err := os.WriteFile(path, []byte("dummy mkv content"), 0o644); err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	cDir := fileCacheDir(path, fi.Size(), fi.ModTime())
	jsonPath := chapterCachePath(cDir, path)

	// Pre-create the movie-named JSON file in cache
	cachedCh := []Chapter{
		{StartMs: 0, Name: "Cached Intro"},
		{StartMs: 50000, Name: "Cached Climax"},
	}
	saveChapterCache(jsonPath, path, cachedCh)

	// Verify chaptersFor reads from JSON cache directly without calling ffprobe
	got := chaptersFor(path)
	if len(got) != 2 {
		t.Fatalf("got %d chapters from cache, want 2", len(got))
	}
	if got[0].Name != "Cached Intro" || got[1].Name != "Cached Climax" {
		t.Errorf("unexpected cached chapters: %+v", got)
	}
}

// /info exposes chapters as a JSON array (the client reads this).
func TestInfoIncludesChapters(t *testing.T) {
	dir := t.TempDir()
	src, err := os.ReadFile(filepath.Join("testdata", "chapters_sample.mkv"))
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "movie.mkv")
	if err := os.WriteFile(path, src, 0o644); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(path, "T", filepath.Join(dir, "c.apk"),
		filepath.Join(dir, "log.txt"), filepath.Join(dir, "resume.json"), 46898, 30000)
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()

	req := httptest.NewRequest("GET", "/info", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var info struct {
		Chapters []Chapter `json:"chapters"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if len(info.Chapters) != 3 || info.Chapters[1].Name != "The Middle" {
		t.Errorf("unexpected chapters in /info: %+v", info.Chapters)
	}
}

// A file with no chapters must return [] in /info, never null.
func TestInfoChaptersEmptyIsArray(t *testing.T) {
	a, _ := newTestApp(t) // random-bytes .mkv → no chapters
	h := a.handler()

	req := httptest.NewRequest("GET", "/info", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var raw map[string]json.RawMessage
	if err := json.NewDecoder(rec.Body).Decode(&raw); err != nil {
		t.Fatal(err)
	}
	if string(raw["chapters"]) != "[]" {
		t.Errorf(`chapters field in /info = %s, want "[]"`, raw["chapters"])
	}
}
