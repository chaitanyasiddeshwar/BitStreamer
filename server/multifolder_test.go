package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newMultiRootTestApp creates a temp tree with two roots and returns the app + server.
//
//	root1/m1.mkv, root1/sub/e1.mkv
//	root2/s1.mp3, root2/notes.txt (ignored)
func newMultiRootTestApp(t *testing.T) (*app, *httptest.Server) {
	t.Helper()
	base := t.TempDir()
	root1 := filepath.Join(base, "Movies")
	root2 := filepath.Join(base, "Music")
	write := func(rel string, data string) {
		p := filepath.Join(base, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(data), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("Movies/m1.mkv", "movie-one-bytes")
	write("Movies/sub/e1.mkv", "episode")
	write("Music/s1.mp3", "song")
	write("Music/notes.txt", "ignore me")

	a, err := newMultiRootApp(
		[]string{root1, root2},
		"TestMulti", filepath.Join(base, "c.apk"),
		filepath.Join(base, "log.txt"), 46898,
	)
	if err != nil {
		t.Fatal(err)
	}
	if !a.multiRoot {
		t.Fatal("expected multiRoot to be true")
	}
	if !a.folderMode {
		t.Fatal("expected folderMode to be true in multi-root mode")
	}
	if len(a.roots) != 2 {
		t.Fatalf("expected 2 roots, got %d", len(a.roots))
	}
	srv := httptest.NewServer(a.handler())
	t.Cleanup(srv.Close)
	return a, srv
}

// multiInfoResp is the /info response when no ?root is supplied.
type multiInfoResp struct {
	V     int    `json:"v"`
	Mode  string `json:"mode"`
	Name  string `json:"name"`
	Roots []struct {
		Index int    `json:"index"`
		Name  string `json:"name"`
	} `json:"roots"`
}

// getMultiList fetches /list with a raw query string (e.g. "root=0&path=sub").
func getMultiList(t *testing.T, base, query string) listResp {
	t.Helper()
	resp, err := http.Get(base + "/list?" + query)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("/list?%s status=%d", query, resp.StatusCode)
	}
	var lr listResp
	if err := json.NewDecoder(resp.Body).Decode(&lr); err != nil {
		t.Fatal(err)
	}
	return lr
}

func TestMultiRootInfoReturnsRoots(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	resp, err := http.Get(srv.URL + "/info")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("/info status=%d", resp.StatusCode)
	}
	var info multiInfoResp
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if info.Mode != "multi" {
		t.Errorf("mode=%q, want multi", info.Mode)
	}
	if len(info.Roots) != 2 {
		t.Fatalf("roots count=%d, want 2", len(info.Roots))
	}
	if info.Roots[0].Name != "Movies" || info.Roots[1].Name != "Music" {
		t.Errorf("root names: %v", info.Roots)
	}
	if info.Roots[0].Index != 0 || info.Roots[1].Index != 1 {
		t.Errorf("root indices: %v", info.Roots)
	}
}

func TestMultiRootListRoot0(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	lr := getMultiList(t, srv.URL, "root=0&path=")
	n := names(lr)
	if !n["m1.mkv"] {
		t.Error("root 0 should list m1.mkv")
	}
	if !n["sub"] {
		t.Error("root 0 should list 'sub' directory")
	}
}

func TestMultiRootListRoot1(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	lr := getMultiList(t, srv.URL, "root=1&path=")
	n := names(lr)
	if !n["s1.mp3"] {
		t.Error("root 1 should list s1.mp3")
	}
	if n["notes.txt"] {
		t.Error("unsupported .txt should not be listed")
	}
}

func TestMultiRootListSubdir(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	lr := getMultiList(t, srv.URL, "root=0&path=sub")
	n := names(lr)
	if !n["e1.mkv"] {
		t.Error("root 0 sub/ should list e1.mkv")
	}
}

func TestMultiRootListNoRootParam(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	resp, err := http.Get(srv.URL + "/list?path=")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("/list without root= should be 400, got %d", resp.StatusCode)
	}
}

func TestMultiRootListInvalidRoot(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	resp, err := http.Get(srv.URL + "/list?root=99&path=")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("/list with root=99 should be 400, got %d", resp.StatusCode)
	}
}

func TestMultiRootStream(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	resp, err := http.Get(srv.URL + "/stream?root=0&path=m1.mkv")
	if err != nil {
		t.Fatal(err)
	}
	body := make([]byte, 64)
	n, _ := resp.Body.Read(body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Errorf("stream status=%d", resp.StatusCode)
	}
	if resp.Header.Get("Content-Type") != "video/x-matroska" {
		t.Errorf("stream content-type=%s", resp.Header.Get("Content-Type"))
	}
	if string(body[:n]) != "movie-one-bytes" {
		t.Errorf("stream body = %q", body[:n])
	}
}

func TestMultiRootStreamNoRoot(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	resp, err := http.Get(srv.URL + "/stream?path=m1.mkv")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("/stream without root= should be 400, got %d", resp.StatusCode)
	}
}

func TestMultiRootInfoPerFile(t *testing.T) {
	_, srv := newMultiRootTestApp(t)
	resp, err := http.Get(srv.URL + "/info?root=0&path=m1.mkv")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("/info?root=0&path=m1.mkv status=%d", resp.StatusCode)
	}
	var info struct {
		Mode string `json:"mode"`
		File string `json:"file"`
		Mime string `json:"mime"`
	}
	json.NewDecoder(resp.Body).Decode(&info)
	if info.Mode != "multi" {
		t.Errorf("mode=%q, want multi", info.Mode)
	}
	if info.File != "m1.mkv" {
		t.Errorf("file=%q, want m1.mkv", info.File)
	}
	if info.Mime != "video/x-matroska" {
		t.Errorf("mime=%q, want video/x-matroska", info.Mime)
	}
}

func TestMultiRootPathTraversal(t *testing.T) {
	a, srv := newMultiRootTestApp(t)
	// Ensure resolveRootPath doesn't allow escaping a root.
	root0Abs, _ := filepath.Abs(a.roots[0].dir)
	for _, evil := range []string{"../../../etc/passwd", "..\\..\\secret", "/etc/hosts"} {
		full, ok := a.resolveRootPath(0, evil)
		if ok {
			abs, _ := filepath.Abs(full)
			if abs != root0Abs && !strings.HasPrefix(abs, root0Abs+string(os.PathSeparator)) {
				t.Errorf("resolveRootPath(0, %q) escaped root: %q", evil, full)
			}
		}
	}
	// Traversal stream request must not return outside content.
	resp, err := http.Get(srv.URL + "/stream?root=0&path=../../../etc/hosts")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		t.Error("traversal stream unexpectedly returned 200")
	}
}

func TestMultiRootCrossRootTraversal(t *testing.T) {
	a, srv := newMultiRootTestApp(t)
	// Verify root 1 can't access root 0's files.
	root1Abs, _ := filepath.Abs(a.roots[1].dir)
	full, ok := a.resolveRootPath(1, "../Movies/m1.mkv")
	if ok {
		abs, _ := filepath.Abs(full)
		if abs != root1Abs && !strings.HasPrefix(abs, root1Abs+string(os.PathSeparator)) {
			t.Errorf("root 1 escaped to root 0's file: %q", full)
		}
	}
	resp, err := http.Get(srv.URL + "/stream?root=1&path=../Movies/m1.mkv")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		t.Error("cross-root traversal unexpectedly returned 200")
	}
}
