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

// newFolderTestApp builds a temp tree and returns a folder-mode app + server.
//
//	root/m1.mkv, root/s1.mp3, root/notes.txt (ignored),
//	root/L1/e1.mkv, root/L1/L2/L3/L4/deep.mkv  (for the depth cap)
func newFolderTestApp(t *testing.T) (*app, *httptest.Server) {
	t.Helper()
	root := t.TempDir()
	write := func(rel string, data string) {
		p := filepath.Join(root, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(data), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("m1.mkv", "movie-one-bytes")
	write("s1.mp3", "song")
	write("notes.txt", "ignore me")
	write("L1/e1.mkv", "episode")
	write("L1/L2/L3/L4/deep.mkv", "too deep")

	a, err := newApp(root, "T", filepath.Join(root, "c.apk"),
		filepath.Join(root, "log.txt"), filepath.Join(root, "resume.json"), 46898, 30000)
	if err != nil {
		t.Fatal(err)
	}
	if !a.folderMode {
		t.Fatal("expected folder mode for a directory arg")
	}
	srv := httptest.NewServer(a.handler())
	t.Cleanup(srv.Close)
	return a, srv
}

type listResp struct {
	Path    string `json:"path"`
	Depth   int    `json:"depth"`
	Entries []struct {
		Name string `json:"name"`
		Dir  bool   `json:"dir"`
		Size int64  `json:"size"`
		Mime string `json:"mime"`
	} `json:"entries"`
}

func getList(t *testing.T, base, path string) listResp {
	t.Helper()
	resp, err := http.Get(base + "/list?path=" + path)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("/list?path=%q status=%d", path, resp.StatusCode)
	}
	var lr listResp
	if err := json.NewDecoder(resp.Body).Decode(&lr); err != nil {
		t.Fatal(err)
	}
	return lr
}

func names(lr listResp) map[string]bool {
	m := map[string]bool{}
	for _, e := range lr.Entries {
		m[e.Name] = true
	}
	return m
}

func TestFolderListRoot(t *testing.T) {
	_, srv := newFolderTestApp(t)
	lr := getList(t, srv.URL, "")
	n := names(lr)
	if !n["L1"] || !n["m1.mkv"] || !n["s1.mp3"] {
		t.Errorf("root listing missing expected entries: %+v", lr.Entries)
	}
	if n["notes.txt"] {
		t.Error("unsupported .txt should not be listed")
	}
	// dirs come before files
	if lr.Entries[0].Name != "L1" || !lr.Entries[0].Dir {
		t.Errorf("expected dir first, got %+v", lr.Entries[0])
	}
}

func TestFolderListSubdirAndDepthCap(t *testing.T) {
	_, srv := newFolderTestApp(t)
	if !names(getList(t, srv.URL, "L1"))["e1.mkv"] {
		t.Error("L1 should list e1.mkv")
	}
	if !names(getList(t, srv.URL, "L1/L2"))["L3"] {
		t.Error("L1/L2 (depth 2) should still list the L3 subfolder")
	}
	// L1/L2/L3 is depth 3 = cap; deeper folders are not offered.
	if names(getList(t, srv.URL, "L1/L2/L3"))["L4"] {
		t.Error("depth cap: L4 should not be listed at depth 3")
	}
}

func TestFolderStreamAndInfo(t *testing.T) {
	_, srv := newFolderTestApp(t)
	resp, err := http.Get(srv.URL + "/stream?path=m1.mkv")
	if err != nil {
		t.Fatal(err)
	}
	body := make([]byte, 64)
	n, _ := resp.Body.Read(body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || resp.Header.Get("Content-Type") != "video/x-matroska" {
		t.Errorf("stream status=%d ct=%s", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
	if string(body[:n]) != "movie-one-bytes" {
		t.Errorf("stream body = %q", body[:n])
	}

	ir, err := http.Get(srv.URL + "/info?path=m1.mkv")
	if err != nil {
		t.Fatal(err)
	}
	defer ir.Body.Close()
	var info struct {
		Mode     string    `json:"mode"`
		File     string    `json:"file"`
		Mime     string    `json:"mime"`
		Size     int64     `json:"size"`
		Chapters []Chapter `json:"chapters"`
	}
	json.NewDecoder(ir.Body).Decode(&info)
	if info.Mode != "folder" || info.File != "m1.mkv" || info.Mime != "video/x-matroska" {
		t.Errorf("per-file /info unexpected: %+v", info)
	}
	if info.Chapters == nil {
		t.Error("expected chapters field to be present in /info response")
	}

	// Root /info marks folder mode.
	rr, _ := http.Get(srv.URL + "/info")
	var root struct {
		Mode string `json:"mode"`
	}
	json.NewDecoder(rr.Body).Decode(&root)
	rr.Body.Close()
	if root.Mode != "folder" {
		t.Errorf("root /info mode = %q, want folder", root.Mode)
	}
}

func TestFolderPathTraversalIsConfined(t *testing.T) {
	a, srv := newFolderTestApp(t)
	// resolvePath must never escape rootDir.
	rootAbs, _ := filepath.Abs(a.rootDir)
	for _, evil := range []string{"../../../etc/passwd", "..\\..\\secret", "/etc/hosts"} {
		full, ok := a.resolvePath(evil)
		if ok {
			abs, _ := filepath.Abs(full)
			if abs != rootAbs && !strings.HasPrefix(abs, rootAbs+string(os.PathSeparator)) {
				t.Errorf("resolvePath(%q) escaped root: %q", evil, full)
			}
		}
	}
	// A traversal stream request must not return outside content.
	resp, err := http.Get(srv.URL + "/stream?path=../../../etc/hosts")
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode == http.StatusOK {
		t.Error("traversal stream unexpectedly returned 200")
	}
	resp.Body.Close()
}

func TestFolderModeDisablesSingleFileEndpoints(t *testing.T) {
	_, srv := newFolderTestApp(t)
	for _, path := range []string{"/chapter-thumb?index=0", "/storyboard.json", "/position"} {
		resp, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusNotFound {
			t.Errorf("%s in folder mode = %d, want 404", path, resp.StatusCode)
		}
	}
}
