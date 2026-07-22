package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Chapter is one chapter marker: where it starts and its display name.
type Chapter struct {
	StartMs int64  `json:"startMs"`
	Name    string `json:"name"`
}

// chaptersFor reads a file's chapter markers, first checking the persistent cache
// JSON file (<movie_name>.json in cache/hash/). If found, ffprobe is completely
// bypassed. Otherwise, ffprobe extracts chapters and saves the JSON to cache.
func chaptersFor(path string) []Chapter {
	fi, err := os.Stat(path)
	if err != nil {
		return probeChaptersDirect(path)
	}
	return chaptersForFile(path, fi.Size(), fi.ModTime())
}

func chaptersForFile(path string, size int64, modTime time.Time) []Chapter {
	cDir := fileCacheDir(path, size, modTime)
	jsonPath := chapterCachePath(cDir, path)

	// 1. Check persistent cache JSON file (named after the movie)
	if mc := readMediaCache(jsonPath); mc != nil && len(mc.Chapters) > 0 {
		return mc.Chapters
	}

	// 2. Not cached or cached with 0 chapters: run ffprobe
	chapters := probeChaptersDirect(path)
	if chapters == nil {
		return nil
	}

	// 3. Save to persistent cache JSON file for human identification & instant re-use
	updateMediaCache(jsonPath, path, func(mc *MediaCache) {
		mc.Size = size
		mc.Chapters = chapters
	})
	log.Printf("[chapters] saved %d chapters to cache for %s", len(chapters), filepath.Base(path))

	return chapters
}

func probeChaptersDirect(path string) []Chapter {
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
			StartTime any               `json:"start_time"`
			Start     any               `json:"start"`
			TimeBase  string            `json:"time_base"`
			Tags      map[string]string `json:"tags"`
		} `json:"chapters"`
	}
	if json.Unmarshal(out, &parsed) != nil || len(parsed.Chapters) == 0 {
		return nil
	}
	chapters := make([]Chapter, 0, len(parsed.Chapters))
	for _, c := range parsed.Chapters {
		var secs float64
		if c.StartTime != nil {
			switch v := c.StartTime.(type) {
			case string:
				secs, _ = strconv.ParseFloat(strings.TrimSpace(v), 64)
			case float64:
				secs = v
			}
		} else if c.Start != nil && c.TimeBase != "" {
			var startVal float64
			switch v := c.Start.(type) {
			case float64:
				startVal = v
			case string:
				startVal, _ = strconv.ParseFloat(v, 64)
			}
			parts := strings.Split(c.TimeBase, "/")
			if len(parts) == 2 {
				num, _ := strconv.ParseFloat(parts[0], 64)
				den, _ := strconv.ParseFloat(parts[1], 64)
				if den > 0 {
					secs = startVal * (num / den)
				}
			}
		}
		title := extractTitle(c.Tags)
		chapters = append(chapters, Chapter{
			StartMs: int64(secs * 1000),
			Name:    title,
		})
	}
	return finalizeChapters(chapters)
}

func saveChapterCache(jsonPath, mediaPath string, chapters []Chapter) {
	dir := filepath.Dir(jsonPath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	f, err := os.Create(jsonPath)
	if err != nil {
		return
	}
	defer f.Close()
	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	enc.Encode(map[string]any{
		"file":     filepath.Base(mediaPath),
		"path":     mediaPath,
		"chapters": chapters,
	})
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
