package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newTestApp serves a small fake .mkv whose content is the byte pattern
// 0..255 repeated, so range responses are verifiable by value.
func newTestApp(t *testing.T) (*app, []byte) {
	t.Helper()
	content := make([]byte, 4096)
	for i := range content {
		content[i] = byte(i % 256)
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "movie.mkv")
	if err := os.WriteFile(path, content, 0o644); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(path, "TestServer", filepath.Join(dir, "client.apk"),
		filepath.Join(dir, "client-logs.txt"), 46898)
	if err != nil {
		t.Fatal(err)
	}
	return a, content
}

func TestInfo(t *testing.T) {
	a, content := newTestApp(t)
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/info")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	var info struct {
		V    int    `json:"v"`
		Name string `json:"name"`
		File string `json:"file"`
		Size int64  `json:"size"`
		Mime string `json:"mime"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if info.V != 1 || info.Name != "TestServer" || info.File != "movie.mkv" ||
		info.Size != int64(len(content)) || info.Mime != "video/x-matroska" {
		t.Errorf("unexpected /info payload: %+v", info)
	}
}

func TestStreamFull(t *testing.T) {
	a, content := newTestApp(t)
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/stream")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	if got := resp.Header.Get("Accept-Ranges"); got != "bytes" {
		t.Errorf("Accept-Ranges = %q, want bytes", got)
	}
	if got := resp.Header.Get("Content-Type"); got != "video/x-matroska" {
		t.Errorf("Content-Type = %q", got)
	}
	body, _ := io.ReadAll(resp.Body)
	if string(body) != string(content) {
		t.Error("full body does not match file content")
	}
}

func TestStreamRange(t *testing.T) {
	a, content := newTestApp(t)
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	tests := []struct {
		rangeHdr    string
		wantStart   int
		wantEnd     int // inclusive
		wantConthdr string
	}{
		{"bytes=0-99", 0, 99, "bytes 0-99/4096"},
		{"bytes=1000-2000", 1000, 2000, "bytes 1000-2000/4096"},
		{"bytes=4000-", 4000, 4095, "bytes 4000-4095/4096"},
		{"bytes=-100", 3996, 4095, "bytes 3996-4095/4096"},
	}
	for _, tc := range tests {
		req, _ := http.NewRequest("GET", srv.URL+"/stream", nil)
		req.Header.Set("Range", tc.rangeHdr)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()

		if resp.StatusCode != http.StatusPartialContent {
			t.Errorf("%s: status = %d, want 206", tc.rangeHdr, resp.StatusCode)
			continue
		}
		if got := resp.Header.Get("Content-Range"); got != tc.wantConthdr {
			t.Errorf("%s: Content-Range = %q, want %q", tc.rangeHdr, got, tc.wantConthdr)
		}
		want := content[tc.wantStart : tc.wantEnd+1]
		if string(body) != string(want) {
			t.Errorf("%s: body mismatch (len %d, want %d)", tc.rangeHdr, len(body), len(want))
		}
	}
}

func TestStreamHead(t *testing.T) {
	a, content := newTestApp(t)
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Head(srv.URL + "/stream")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	if resp.ContentLength != int64(len(content)) {
		t.Errorf("Content-Length = %d, want %d", resp.ContentLength, len(content))
	}
}

func TestAPKMissing(t *testing.T) {
	a, _ := newTestApp(t)
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/client.apk")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "--apk") {
		t.Error("404 body should tell the user how to provide the APK")
	}
}

func TestAPKServed(t *testing.T) {
	a, _ := newTestApp(t)
	if err := os.WriteFile(a.apkPath, []byte("fake-apk-bytes"), 0o644); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/client.apk")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	if got := resp.Header.Get("Content-Type"); got != "application/vnd.android.package-archive" {
		t.Errorf("Content-Type = %q", got)
	}
}

func TestClientLog(t *testing.T) {
	a, _ := newTestApp(t)
	srv := httptest.NewServer(a.handler())
	defer srv.Close()

	for _, batch := range []string{"line one\nline two", "line three\n"} {
		resp, err := http.Post(srv.URL+"/log", "text/plain", strings.NewReader(batch))
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusNoContent {
			t.Fatalf("status = %d, want 204", resp.StatusCode)
		}
	}

	content, err := os.ReadFile(a.clientLogPath)
	if err != nil {
		t.Fatalf("client log file not written: %v", err)
	}
	text := string(content)
	for _, want := range []string{"line one", "line two", "line three", "===="} {
		if !strings.Contains(text, want) {
			t.Errorf("client log missing %q; got:\n%s", want, text)
		}
	}

	resp, err := http.Get(srv.URL + "/log")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("GET /log status = %d, want 405", resp.StatusCode)
	}
}

func TestMimeForPath(t *testing.T) {
	cases := map[string]string{
		"a.mp4": "video/mp4", "b.MKV": "video/x-matroska", "c.mov": "video/quicktime",
		"d.webm": "video/webm", "e.avi": "application/octet-stream",
	}
	for path, want := range cases {
		if got := mimeForPath(path); got != want {
			t.Errorf("mimeForPath(%q) = %q, want %q", path, got, want)
		}
	}
}
