package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestVideoFilterChains(t *testing.T) {
	if got := videoFilter(320, false); got != "scale=320:-2" {
		t.Errorf("SDR filter = %q", got)
	}
	hdr := videoFilter(240, true)
	for _, want := range []string{"zscale=t=linear", "tonemap=tonemap=hable", "scale=240:-2", "format=yuv420p"} {
		if !strings.Contains(hdr, want) {
			t.Errorf("HDR filter missing %q in %q", want, hdr)
		}
	}
}

// Forcing hdr=true on the SDR fixture must still yield a JPEG: either the HDR
// tonemap chain works (ffmpeg with zscale) or it falls back to plain extraction.
func TestExtractFrameHdrFallback(t *testing.T) {
	if findFFmpeg() == "" {
		t.Skip("ffmpeg not installed")
	}
	out := filepath.Join(t.TempDir(), "frame.jpg")
	err := extractFrame(findFFmpeg(), filepath.Join("testdata", "chapters_sample.mkv"),
		2.0, 240, 5 /* hdr= */, true, out)
	if err != nil {
		t.Fatalf("extractFrame(hdr=true) failed with no fallback: %v", err)
	}
	fi, err := os.Stat(out)
	if err != nil || fi.Size() == 0 {
		t.Fatalf("no frame produced: %v", err)
	}
	f, _ := os.Open(out)
	defer f.Close()
	magic := make([]byte, 3)
	f.Read(magic)
	if magic[0] != 0xFF || magic[1] != 0xD8 { // JPEG SOI
		t.Errorf("output is not a JPEG: % x", magic)
	}
}
