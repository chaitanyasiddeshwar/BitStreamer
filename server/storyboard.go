package main

import (
	"flag"
	"fmt"
	"image"
	"image/draw"
	"image/jpeg"
	"log"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
)

// Sprite-sheet grid: cols*rows tiles per sheet.
const (
	sbCols  = 10
	sbRows  = 10
	sbTileW = 480 // tile width in px; height derives from the video aspect.
	// 480 matches the client's on-screen preview size (240dp @ xhdpi = 480px) for
	// a 1:1, crisp preview. The client decodes only the requested tile region from
	// each sheet (BitmapRegionDecoder), so the larger sheets don't cost RAM.
	sbJPEGQuality = 90 // sprite-sheet JPEG quality (higher = crisper previews)
)

// storyboard produces a dense grid of frame thumbnails ("trickplay") for
// scrubbing previews: one frame every intervalMs, packed into sprite-sheet
// JPEGs. Frames are grabbed with fast keyframe seeks (ffmpeg -ss per interval,
// which decodes almost nothing — not a full-file decode) and tiled in Go.
// Generated at startup into a per-session temp dir, deleted on shutdown.
// See docs/THUMBNAILS.md. Best-effort: unavailable without ffmpeg or a duration.
type storyboard struct {
	ffmpegPath string
	mediaPath  string
	durationMs int64
	intervalMs int64
	cacheDir   string
	hdr        bool
	sem        chan struct{} // caps concurrent ffmpeg seeks

	mu         sync.RWMutex
	ready      bool
	tileWidth  int
	tileHeight int
	tileCount  int
	sheetCount int
}

func newStoryboard(mediaPath string, durationMs, intervalMs int64, hdr bool, cacheDir string) *storyboard {
	skipPreviews := false
	if f := flag.Lookup("skip-previews"); f != nil && f.Value.String() == "true" {
		skipPreviews = true
	}

	if skipPreviews {
		cacheDir = ""
	} else {
		// Per-session: wipe any leftovers from a previous run and start fresh.
		os.RemoveAll(cacheDir)
		if err := os.MkdirAll(cacheDir, 0o755); err != nil {
			cacheDir = "" // disables the storyboard (enabled() checks cacheDir)
		}
	}
	return &storyboard{
		ffmpegPath: findFFmpeg(),
		mediaPath:  mediaPath,
		durationMs: durationMs,
		intervalMs: intervalMs,
		cacheDir:   cacheDir,
		hdr:        hdr,
		sem:        make(chan struct{}, 4),
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

// generate grabs one keyframe per interval (parallel, capped) and packs them
// into sprite sheets. Call in the background at startup.
func (s *storyboard) generate() {
	if !s.enabled() {
		return
	}
	tileCount := int((s.durationMs + s.intervalMs - 1) / s.intervalMs)
	if tileCount <= 0 {
		return
	}
	tileDir := filepath.Join(s.cacheDir, "tiles")
	if err := os.MkdirAll(tileDir, 0o755); err != nil {
		log.Printf("storyboard: %v", err)
		return
	}

	// 1. Extract each interval's frame with a fast keyframe seek.
	var wg sync.WaitGroup
	var failed int64
	for i := 0; i < tileCount; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			s.sem <- struct{}{}
			defer func() { <-s.sem }()
			if err := s.extractTile(idx, tileDir); err != nil {
				atomic.AddInt64(&failed, 1)
			}
		}(i)
	}
	wg.Wait()

	// 2. Measure tile dimensions from the first frame produced.
	tileW, tileH := s.firstTileSize(tileDir, tileCount)
	if tileW == 0 {
		log.Printf("storyboard FAILED: could not extract any of the %d preview frames from %q "+
			"(every ffmpeg seek failed — see the ffmpeg log for the reason). Scrubbing previews are OFF.",
			tileCount, filepath.Base(s.mediaPath))
		return
	}
	if failed > 0 {
		log.Printf("storyboard: %d of %d preview frames failed to extract (see the ffmpeg log)", failed, tileCount)
	}

	// 3. Pack into sprite sheets, then drop the individual tiles.
	sheetCount := (tileCount + sbCols*sbRows - 1) / (sbCols * sbRows)
	for sheet := 0; sheet < sheetCount; sheet++ {
		if err := s.packSheet(sheet, tileDir, tileW, tileH); err != nil {
			log.Printf("storyboard sheet %d: %v", sheet, err)
		}
	}
	os.RemoveAll(tileDir)

	s.mu.Lock()
	s.tileWidth = tileW
	s.tileHeight = tileH
	s.tileCount = tileCount
	s.sheetCount = sheetCount
	s.ready = true
	s.mu.Unlock()
	log.Printf("storyboard ready: %d tiles across %d sheets (%dx%d px, every %ds, keyframe seeks)",
		tileCount, sheetCount, tileW, tileH, s.intervalMs/1000)
}

// extractTile grabs the frame near tile idx's timestamp. Fast: -ss before -i is
// an input (keyframe) seek, and -frames:v 1 outputs a single frame. A failure
// (e.g. seeking past the end) just leaves a gap — that sheet cell stays black.
func (s *storyboard) extractTile(idx int, tileDir string) error {
	seekSec := float64(int64(idx)*s.intervalMs) / 1000.0
	out := filepath.Join(tileDir, fmt.Sprintf("t_%05d.jpg", idx))
	if err := extractFrame(s.ffmpegPath, s.mediaPath, seekSec, sbTileW, 5, s.hdr, out); err != nil {
		os.Remove(out)
		return err
	}
	return nil
}

// firstTileSize returns the pixel size of the first extracted tile (all tiles
// share it — same source video).
func (s *storyboard) firstTileSize(tileDir string, tileCount int) (int, int) {
	for i := 0; i < tileCount; i++ {
		f, err := os.Open(filepath.Join(tileDir, fmt.Sprintf("t_%05d.jpg", i)))
		if err != nil {
			continue
		}
		cfg, _, err := image.DecodeConfig(f)
		f.Close()
		if err == nil {
			return cfg.Width, cfg.Height
		}
	}
	return 0, 0
}

// packSheet draws up to cols*rows tiles into one sprite-sheet JPEG. Missing
// tiles leave black cells.
func (s *storyboard) packSheet(sheet int, tileDir string, tileW, tileH int) error {
	canvas := image.NewRGBA(image.Rect(0, 0, sbCols*tileW, sbRows*tileH))
	base := sheet * sbCols * sbRows
	for j := 0; j < sbCols*sbRows; j++ {
		f, err := os.Open(filepath.Join(tileDir, fmt.Sprintf("t_%05d.jpg", base+j)))
		if err != nil {
			continue
		}
		tile, err := jpeg.Decode(f)
		f.Close()
		if err != nil {
			continue
		}
		col := j % sbCols
		row := j / sbCols
		r := image.Rect(col*tileW, row*tileH, col*tileW+tileW, row*tileH+tileH)
		draw.Draw(canvas, r, tile, image.Point{}, draw.Src)
	}
	out, err := os.Create(filepath.Join(s.cacheDir, fmt.Sprintf("sb_%03d.jpg", sheet+1)))
	if err != nil {
		return err
	}
	defer out.Close()
	return jpeg.Encode(out, canvas, &jpeg.Options{Quality: sbJPEGQuality})
}

func (s *storyboard) sheetPath(n int) (string, bool) {
	if !s.isReady() || n < 0 || n >= s.sheetCount {
		return "", false
	}
	return filepath.Join(s.cacheDir, fmt.Sprintf("sb_%03d.jpg", n+1)), true // sheets numbered from 1
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
