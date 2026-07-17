package main

import (
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
