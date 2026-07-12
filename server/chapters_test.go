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
func TestParseChaptersFromFixture(t *testing.T) {
	chapters := parseChapters(filepath.Join("testdata", "chapters_sample.mkv"))
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

func TestParseChaptersNonMkv(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "notmkv.bin")
	if err := os.WriteFile(path, []byte("this is not a matroska file"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := parseChapters(path); got != nil {
		t.Errorf("expected nil chapters for non-MKV, got %+v", got)
	}
}

func TestParseChaptersMissingFile(t *testing.T) {
	if got := parseChapters("/no/such/file.mkv"); got != nil {
		t.Errorf("expected nil chapters for missing file, got %+v", got)
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
		filepath.Join(dir, "log.txt"), filepath.Join(dir, "resume.json"), 46898)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/info")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	var info struct {
		Chapters []Chapter `json:"chapters"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if len(info.Chapters) != 3 || info.Chapters[1].Name != "The Middle" {
		t.Errorf("unexpected chapters in /info: %+v", info.Chapters)
	}
}

// A file with no chapters must return [] in /info, never null.
func TestInfoChaptersEmptyIsArray(t *testing.T) {
	a, _ := newTestApp(t) // random-bytes .mkv → no chapters
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/info")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := os.ReadFile(os.DevNull)
	_ = body
	var raw map[string]json.RawMessage
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		t.Fatal(err)
	}
	if string(raw["chapters"]) != "[]" {
		t.Errorf("chapters = %s, want []", raw["chapters"])
	}
}
