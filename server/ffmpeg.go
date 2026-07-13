package main

import (
	"fmt"
	"log"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
)

var hdrFallbackOnce sync.Once

// videoFilter builds the -vf chain for a thumbnail frame scaled to [width].
// For HDR sources it tonemaps BT.2020/PQ (or HLG) down to BT.709 SDR so colours
// aren't washed out; this requires an ffmpeg built with zscale (libzimg).
func videoFilter(width int, hdr bool) string {
	if hdr {
		return fmt.Sprintf(
			"zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,"+
				"tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,"+
				"scale=%d:-2,format=yuv420p", width)
	}
	return fmt.Sprintf("scale=%d:-2", width)
}

// extractFrame writes a single JPEG at [seekSec] into [outPath] using a fast
// keyframe seek. For HDR it tonemaps; if that fails (e.g. an ffmpeg without
// zscale) it falls back to a plain extraction so a thumbnail is still produced
// (washed out, but present).
func extractFrame(ffmpegPath, mediaPath string, seekSec float64, width, quality int, hdr bool, outPath string) error {
	err := runFFmpegExtract(ffmpegPath, mediaPath, seekSec, width, quality, hdr, outPath)
	if err != nil && hdr {
		hdrFallbackOnce.Do(func() {
			log.Printf("HDR tonemap failed (ffmpeg may lack zscale/libzimg); using plain extraction — thumbnails may look washed out")
		})
		return runFFmpegExtract(ffmpegPath, mediaPath, seekSec, width, quality, false, outPath)
	}
	return err
}

func runFFmpegExtract(ffmpegPath, mediaPath string, seekSec float64, width, quality int, hdr bool, outPath string) error {
	cmd := exec.Command(ffmpegPath,
		"-nostdin", "-loglevel", "error",
		"-ss", fmt.Sprintf("%.3f", seekSec),
		"-i", mediaPath,
		"-frames:v", "1",
		"-vf", videoFilter(width, hdr),
		"-q:v", strconv.Itoa(quality),
		"-f", "mjpeg",
		"-y", outPath,
	)
	return ffmpegLog.runLogged(fmt.Sprintf("ffmpeg %.3fs hdr=%v -> %s", seekSec, hdr, filepath.Base(outPath)), cmd)
}
