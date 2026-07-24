package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func getPosition(t *testing.T, a *app, fileQuery string) int64 {
	t.Helper()
	q := ""
	if fileQuery != "" {
		q = "?" + fileQuery
	}
	req := httptest.NewRequest("GET", "/position"+q, nil)
	rec := httptest.NewRecorder()
	a.handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /position status = %d", rec.Code)
	}
	var out struct {
		PositionMs int64 `json:"positionMs"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	return out.PositionMs
}

func postPosition(t *testing.T, a *app, ms string, extraQuery string) int {
	t.Helper()
	q := "ms=" + ms
	if extraQuery != "" {
		q += "&" + extraQuery
	}
	req := httptest.NewRequest("POST", "/position?"+q, nil)
	rec := httptest.NewRecorder()
	a.handler().ServeHTTP(rec, req)
	return rec.Code
}

func TestResumePositionRoundTrip(t *testing.T) {
	a, _ := newTestApp(t)

	if got := getPosition(t, a, ""); got != 0 {
		t.Errorf("initial position = %d, want 0", got)
	}
	if code := postPosition(t, a, "90000", ""); code != http.StatusNoContent {
		t.Fatalf("POST status = %d, want 204", code)
	}
	if got := getPosition(t, a, ""); got != 90000 {
		t.Errorf("position after store = %d, want 90000", got)
	}
	// ms=0 clears the resume point (playback finished).
	if code := postPosition(t, a, "0", ""); code != http.StatusNoContent {
		t.Fatalf("POST clear status = %d, want 204", code)
	}
	if got := getPosition(t, a, ""); got != 0 {
		t.Errorf("position after clear = %d, want 0", got)
	}
	// Invalid values rejected.
	if code := postPosition(t, a, "-5", ""); code != http.StatusBadRequest {
		t.Errorf("negative ms status = %d, want 400", code)
	}
	if code := postPosition(t, a, "abc", ""); code != http.StatusBadRequest {
		t.Errorf("non-numeric ms status = %d, want 400", code)
	}
}

func TestResumeStorePersistsAndRetainsMultipleFiles(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "resume.json")

	rs := newResumeStore(path)
	rs.set("192.168.1.50", "film-a.mkv", 120000)

	// Same file on reload: position survives (server restart).
	rs2 := newResumeStore(path)
	if got := rs2.get("192.168.1.50", "film-a.mkv"); got != 120000 {
		t.Errorf("position after reload = %d, want 120000", got)
	}
	// Unknown client: nothing.
	if got := rs2.get("192.168.1.99", "film-a.mkv"); got != 0 {
		t.Errorf("unknown client position = %d, want 0", got)
	}

	// Multiple files retained in store simultaneously.
	rs2.set("192.168.1.50", "film-b.mkv", 45000)
	rs3 := newResumeStore(path)
	if got := rs3.get("192.168.1.50", "film-a.mkv"); got != 120000 {
		t.Errorf("film-a position = %d, want 120000", got)
	}
	if got := rs3.get("192.168.1.50", "film-b.mkv"); got != 45000 {
		t.Errorf("film-b position = %d, want 45000", got)
	}
}
