package main

import (
	"fmt"
	"image"
	_ "image/jpeg" // register JPEG decoder for image.DecodeConfig
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
)

// tilesPerSheet is the grid packed into each sprite sheet (cols*rows).
const (
	sbCols  = 10
	sbRows  = 10
	sbTileW = 240 // tile width in px; height derives from the video aspect
)

// storyboard produces a dense grid of frame thumbnails ("trickplay") for
// scrubbing previews: one frame every intervalMs, tiled into sprite-sheet JPEGs
// with ffmpeg. Generated once at startup into a per-session temp dir and deleted
// on shutdown — see docs/THUMBNAILS.md. Best-effort: unavailable without ffmpeg
// or a known duration.
type storyboard struct {
	ffmpegPath string
	mediaPath  string
	durationMs int64
	intervalMs int64
	cacheDir   string

	mu         sync.RWMutex
	ready      bool
	tileWidth  int
	tileHeight int
	tileCount  int
	sheetCount int
}

func newStoryboard(mediaPath string, durationMs, intervalMs int64) *storyboard {
	dir, err := os.MkdirTemp("", "bitstreamer-sb-")
	if err != nil {
		dir = ""
	}
	return &storyboard{
		ffmpegPath: findFFmpeg(),
		mediaPath:  mediaPath,
		durationMs: durationMs,
		intervalMs: intervalMs,
		cacheDir:   dir,
	}
}

// enabled reports whether a storyboard can be produced at all.
func (s *storyboard) enabled() bool {
	return s.ffmpegPath != "" && s.durationMs > 0 && s.cacheDir != ""
}

func (s *storyboard) isReady() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.ready
}

// generate runs ffmpeg to produce all sprite sheets, then measures a sheet to
// record tile dimensions. Call in the background at startup.
func (s *storyboard) generate() {
	if !s.enabled() {
		return
	}
	intervalSec := float64(s.intervalMs) / 1000.0
	pattern := filepath.Join(s.cacheDir, "sb_%03d.jpg")

	// One pass: sample a frame every interval, scale, pack into cols x rows tiles.
	vf := fmt.Sprintf("fps=1/%.3f,scale=%d:-2,tile=%dx%d", intervalSec, sbTileW, sbCols, sbRows)
	cmd := exec.Command(s.ffmpegPath,
		"-nostdin", "-loglevel", "error",
		"-i", s.mediaPath,
		"-vf", vf,
		"-q:v", "5",
		"-y", pattern,
	)
	if err := cmd.Run(); err != nil {
		log.Printf("storyboard generation failed: %v", err)
		return
	}

	sheets, _ := filepath.Glob(filepath.Join(s.cacheDir, "sb_*.jpg"))
	if len(sheets) == 0 {
		log.Printf("storyboard: ffmpeg produced no sheets")
		return
	}
	tileW, tileH := s.measureTile(sheets[0])
	if tileW == 0 {
		return
	}

	tileCount := int((s.durationMs + s.intervalMs - 1) / s.intervalMs)

	s.mu.Lock()
	s.tileWidth = tileW
	s.tileHeight = tileH
	s.sheetCount = len(sheets)
	s.tileCount = tileCount
	s.ready = true
	s.mu.Unlock()
	log.Printf("storyboard ready: %d tiles across %d sheets (%dx%d px, every %ds)",
		tileCount, len(sheets), tileW, tileH, s.intervalMs/1000)
}

// measureTile reads a sheet's pixel size (without decoding it fully) and divides
// by the grid to get per-tile dimensions.
func (s *storyboard) measureTile(sheetPath string) (int, int) {
	f, err := os.Open(sheetPath)
	if err != nil {
		return 0, 0
	}
	defer f.Close()
	cfg, _, err := image.DecodeConfig(f)
	if err != nil {
		return 0, 0
	}
	return cfg.Width / sbCols, cfg.Height / sbRows
}

func (s *storyboard) sheetPath(n int) (string, bool) {
	if !s.isReady() || n < 0 || n >= s.sheetCount {
		return "", false
	}
	return filepath.Join(s.cacheDir, fmt.Sprintf("sb_%03d.jpg", n+1)), true // ffmpeg numbers from 1
}

// manifest describes the layout so the client can map a scrub time to a tile.
func (s *storyboard) manifest() map[string]any {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return map[string]any{
		"v":             1,
		"intervalMs":    s.intervalMs,
		"durationMs":    s.durationMs,
		"tileWidth":     s.tileWidth,
		"tileHeight":    s.tileHeight,
		"cols":          sbCols,
		"rows":          sbRows,
		"tilesPerSheet": sbCols * sbRows,
		"tileCount":     s.tileCount,
		"sheetCount":    s.sheetCount,
	}
}

// cleanup removes the per-session sprite-sheet cache.
func (s *storyboard) cleanup() {
	if s.cacheDir != "" {
		os.RemoveAll(s.cacheDir)
	}
}
