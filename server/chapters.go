package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Chapter is one chapter marker: where it starts and its display name.
type Chapter struct {
	StartMs int64  `json:"startMs"`
	Name    string `json:"name"`
}

// chaptersFor reads a file's chapter markers via ffprobe -show_chapters, which
// works for every container ffprobe understands (mkv, mp4, mov, ...). Chapters
// are an optional enhancement and must never fail the server: returns nil if
// ffprobe is unavailable, the file has no chapters, or parsing fails.
func chaptersFor(path string) []Chapter {
	ffprobe := findFFprobe()
	if ffprobe == "" {
		return nil
	}
	cmd := exec.Command(ffprobe,
		"-v", "error",
		"-show_chapters",
		"-of", "json", path,
	)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	ffmpegLog.recordProbe("ffprobe chapters "+filepath.Base(path), out, stderr.Bytes(), err)
	if err != nil {
		return nil
	}
	var parsed struct {
		Chapters []struct {
			StartTime string `json:"start_time"` // seconds, e.g. "12.345000"
			Tags      struct {
				Title string `json:"title"`
			} `json:"tags"`
		} `json:"chapters"`
	}
	if json.Unmarshal(out, &parsed) != nil || len(parsed.Chapters) == 0 {
		return nil
	}
	chapters := make([]Chapter, 0, len(parsed.Chapters))
	for _, c := range parsed.Chapters {
		secs, err := strconv.ParseFloat(strings.TrimSpace(c.StartTime), 64)
		if err != nil {
			continue
		}
		chapters = append(chapters, Chapter{
			StartMs: int64(secs * 1000),
			Name:    c.Tags.Title,
		})
	}
	return finalizeChapters(chapters)
}

// finalizeChapters sorts chapters by start time and fills in fallback names for
// any that are unnamed. Returns nil for an empty slice.
func finalizeChapters(chapters []Chapter) []Chapter {
	if len(chapters) == 0 {
		return nil
	}
	sort.SliceStable(chapters, func(i, j int) bool {
		return chapters[i].StartMs < chapters[j].StartMs
	})
	for i := range chapters {
		if chapters[i].Name == "" {
			chapters[i].Name = fmt.Sprintf("Chapter %d", i+1)
		}
	}
	return chapters
}
