package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNoDVPath(t *testing.T) {
	cases := map[string]string{
		filepath.FromSlash("/movies/Titanic.mkv"):        filepath.FromSlash("/movies/Titanic_no_dv.mkv"),
		filepath.FromSlash("/m/Ford v Ferrari.2019.mkv"): filepath.FromSlash("/m/Ford v Ferrari.2019_no_dv.mkv"),
		"movie.mkv": "movie_no_dv.mkv",
	}
	for in, want := range cases {
		if got := noDVPath(in); got != want {
			t.Errorf("noDVPath(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestContainerAdvisory(t *testing.T) {
	// m2ts/mts get a remux advisory; playable containers get none.
	m2ts := containerAdvisory(filepath.FromSlash("/m/00294.m2ts"))
	for _, want := range []string{"M2TS", "-c copy", filepath.FromSlash("/m/00294.mkv")} {
		if !strings.Contains(m2ts, want) {
			t.Errorf("m2ts advisory missing %q in:\n%s", want, m2ts)
		}
	}
	if containerAdvisory("movie.mkv") != "" || containerAdvisory("movie.ts") != "" {
		t.Error("expected no advisory for mkv/ts")
	}
}

func TestDolbyVisionAdvisoryContent(t *testing.T) {
	msg := dolbyVisionAdvisory(filepath.FromSlash("/movies/Titanic.mkv"))
	for _, want := range []string{
		"Profile 7",
		"filter_units=remove_types=62|63",
		noDVPath(filepath.FromSlash("/movies/Titanic.mkv")),
		"-c copy",
	} {
		if !strings.Contains(msg, want) {
			t.Errorf("advisory missing %q in:\n%s", want, msg)
		}
	}
}

func TestExtractBitrate(t *testing.T) {
	cases := []struct {
		bitRate string
		tags    map[string]string
		want    int64
	}{
		{"123456", nil, 123456},
		{"", map[string]string{"BPS": "654321"}, 654321},
		{"N/A", map[string]string{"BPS-eng": "98765"}, 98765},
		{"invalid", map[string]string{"bps": "123"}, 123},
		{"", nil, 0},
	}
	for _, tc := range cases {
		got := extractBitrate(tc.bitRate, tc.tags)
		if got != tc.want {
			t.Errorf("extractBitrate(%q, %v) = %d, want %d", tc.bitRate, tc.tags, got, tc.want)
		}
	}
}

func TestProbeDVSubtype(t *testing.T) {
	tmpDir := t.TempDir()

	// Missing file -> defaults to MEL
	if got := probeDVSubtype(filepath.Join(tmpDir, "nonexistent.mkv")); got != "MEL" {
		t.Errorf("probeDVSubtype non-existent = %q, want MEL", got)
	}

	// File with small NAL 63 (size < 400 bytes) -> MEL
	melPath := filepath.Join(tmpDir, "mel.mkv")
	var melData []byte
	// 5 samples of NAL 63 with payload size 50
	for i := 0; i < 5; i++ {
		melData = append(melData, 0, 0, 0, 1, 0x7E) // NAL 63 header
		melData = append(melData, make([]byte, 50)...)
	}
	if err := os.WriteFile(melPath, melData, 0644); err != nil {
		t.Fatal(err)
	}
	if got := probeDVSubtype(melPath); got != "MEL" {
		t.Errorf("probeDVSubtype small NAL63 = %q, want MEL", got)
	}

	// File with large NAL 63 (size > 400 bytes) -> FEL
	felPath := filepath.Join(tmpDir, "fel.mkv")
	var felData []byte
	// 5 samples of NAL 63 with payload size 600
	for i := 0; i < 5; i++ {
		felData = append(felData, 0, 0, 0, 1, 0x7E) // NAL 63 header
		felData = append(felData, make([]byte, 600)...)
	}
	if err := os.WriteFile(felPath, felData, 0644); err != nil {
		t.Fatal(err)
	}
	if got := probeDVSubtype(felPath); got != "FEL" {
		t.Errorf("probeDVSubtype large NAL63 = %q, want FEL", got)
	}
}

func TestInspectTitanic(t *testing.T) {
	titanic := `D:\MediaServer\Titanic.1997.UHD.BluRay.2160p.TrueHD.Atmos.7.1.DV.HEVC.REMUX-FraMeSToR\Titanic.1997.UHD.BluRay.2160p.TrueHD.Atmos.7.1.DV.HEVC.REMUX-FraMeSToR.mkv`
	if _, err := os.Stat(titanic); err == nil {
		sub := probeDVSubtype(titanic)
		t.Logf("Titanic probeDVSubtype = %s", sub)
	}
}
