package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestFindSidecarSubtitles(t *testing.T) {
	dir := t.TempDir()
	movie := filepath.Join(dir, "Movie One.mkv")
	write := func(name, body string) {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("Movie One.mkv", "x")
	write("Movie One.srt", "sub")             // no tag -> "External"
	write("Movie One.en.srt", "sub")          // language
	write("Movie One.fr.forced.srt", "sub")   // language + flag
	write("Movie One.ass", "sub")             // ASS
	write("Other.srt", "sub")                 // different base -> ignored
	write("Movie One.txt", "notes")           // not a subtitle -> ignored

	subs := findSidecarSubtitles(movie, func(name string) string { return "/subtitle?name=" + name })
	if len(subs) != 4 {
		t.Fatalf("want 4 sidecar subs, got %d: %+v", len(subs), subs)
	}
	byLabel := map[string]subtitleTrack{}
	for _, s := range subs {
		byLabel[s.Label] = s
	}
	if s, ok := byLabel["English"]; !ok || s.Lang != "en" || s.Mime != "application/x-subrip" {
		t.Errorf("English track wrong: %+v", s)
	}
	if s, ok := byLabel["French (forced)"]; !ok || s.Lang != "fr" {
		t.Errorf("French forced track wrong: %+v", s)
	}
	if _, ok := byLabel["External"]; !ok {
		t.Errorf("untagged .srt should be labelled External: %+v", byLabel)
	}
	if s, ok := byLabel["ass"]; !ok || s.Mime != "text/x-ssa" {
		// untagged .ass -> tag "" -> label "External" too; ensure ASS mime present somewhere
		found := false
		for _, v := range subs {
			if v.Mime == "text/x-ssa" {
				found = true
			}
		}
		if !found {
			t.Errorf("ASS sidecar not detected: %+v", subs)
		}
		_ = s
		_ = ok
	}
}

// The /subtitle endpoint serves the sidecar file (single-file mode) and rejects
// path escapes.
func TestSubtitleEndpoint(t *testing.T) {
	dir := t.TempDir()
	movie := filepath.Join(dir, "film.mp4")
	if err := os.WriteFile(movie, []byte("video"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "film.srt"), []byte("hello sub"), 0o644); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(movie, "T", filepath.Join(dir, "c.apk"),
		filepath.Join(dir, "log.txt"), filepath.Join(dir, "resume.json"), 46898, 30000)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/subtitle?name=film.srt")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("subtitle status = %d, want 200", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "application/x-subrip" {
		t.Errorf("content-type = %q, want application/x-subrip", ct)
	}

	// Path traversal via name must be rejected (name must be a bare filename).
	bad, _ := http.Get(srv.URL + "/subtitle?name=../film.srt")
	if bad.StatusCode == http.StatusOK {
		t.Error("path traversal via ?name should not succeed")
	}
	bad.Body.Close()
}
