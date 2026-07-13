package main

import (
	"bytes"
	"fmt"
	"log"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
)

var (
	zscaleOnce   sync.Once
	zscaleOK     bool
	noZscaleOnce sync.Once
)

// hasZscale reports whether this ffmpeg build has the zscale filter (libzimg),
// which the HDR tonemap chain needs. Detected once from `ffmpeg -filters`.
func hasZscale(ffmpegPath string) bool {
	zscaleOnce.Do(func() {
		out, err := exec.Command(ffmpegPath, "-hide_banner", "-filters").Output()
		zscaleOK = err == nil && bytes.Contains(out, []byte("zscale"))
	})
	return zscaleOK
}

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
// keyframe seek. HDR frames are tonemapped when this ffmpeg has zscale; if it
// doesn't, or a particular frame's HDR pass fails (e.g. a bad seek), it falls
// back to a plain extraction so a thumbnail is still produced.
func extractFrame(ffmpegPath, mediaPath string, seekSec float64, width, quality int, hdr bool, outPath string) error {
	useHdr := hdr && hasZscale(ffmpegPath)
	if hdr && !useHdr {
		noZscaleOnce.Do(func() {
			log.Printf("HDR tonemapping unavailable: this ffmpeg has no zscale filter " +
				"(build with --enable-libzimg); thumbnails will not be tonemapped")
		})
	}
	err := runFFmpegExtract(ffmpegPath, mediaPath, seekSec, width, quality, useHdr, outPath)
	if err != nil && useHdr {
		// This frame's HDR pass failed (the real error is in the ffmpeg log);
		// fall back to a plain extraction for this one frame.
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
