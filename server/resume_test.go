package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func getPosition(t *testing.T, url string) int64 {
	t.Helper()
	resp, err := http.Get(url + "/position")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out struct {
		PositionMs int64 `json:"positionMs"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	return out.PositionMs
}

func postPosition(t *testing.T, url string, ms string) int {
	t.Helper()
	resp, err := http.Post(url+"/position?ms="+ms, "text/plain", strings.NewReader(""))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

func TestResumePositionRoundTrip(t *testing.T) {
	a, _ := newTestApp(t)
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	if got := getPosition(t, srv.URL); got != 0 {
		t.Errorf("initial position = %d, want 0", got)
	}
	if code := postPosition(t, srv.URL, "90000"); code != http.StatusNoContent {
		t.Fatalf("POST status = %d, want 204", code)
	}
	if got := getPosition(t, srv.URL); got != 90000 {
		t.Errorf("position after store = %d, want 90000", got)
	}
	// ms=0 clears the resume point (playback finished).
	if code := postPosition(t, srv.URL, "0"); code != http.StatusNoContent {
		t.Fatalf("POST clear status = %d, want 204", code)
	}
	if got := getPosition(t, srv.URL); got != 0 {
		t.Errorf("position after clear = %d, want 0", got)
	}
	// Invalid values rejected.
	if code := postPosition(t, srv.URL, "-5"); code != http.StatusBadRequest {
		t.Errorf("negative ms status = %d, want 400", code)
	}
	if code := postPosition(t, srv.URL, "abc"); code != http.StatusBadRequest {
		t.Errorf("non-numeric ms status = %d, want 400", code)
	}
}

func TestResumeStorePersistsAndClearsOnNewFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "resume.json")

	rs := newResumeStore(path, "C:\\Movies\\film-a.mkv")
	rs.set("192.168.1.50", 120000)

	// Same file on reload: position survives (server restart).
	rs2 := newResumeStore(path, "C:\\Movies\\film-a.mkv")
	if got := rs2.get("192.168.1.50"); got != 120000 {
		t.Errorf("position after reload = %d, want 120000", got)
	}
	// Unknown client: nothing.
	if got := rs2.get("192.168.1.99"); got != 0 {
		t.Errorf("unknown client position = %d, want 0", got)
	}

	// Different file: stale positions cleared.
	rs3 := newResumeStore(path, "C:\\Movies\\film-b.mkv")
	if got := rs3.get("192.168.1.50"); got != 0 {
		t.Errorf("position after file switch = %d, want 0", got)
	}
}
