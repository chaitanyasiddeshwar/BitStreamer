package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// mediaProbe holds the video colour characteristics used to decide HDR
// tonemapping, read from the actual stream via ffprobe (more reliable than the
// MKV container tags, and it can see the Dolby Vision profile).
type mediaProbe struct {
	isHDR         bool
	hdr10plus     bool
	colorTransfer string
	colorSpace    string
	dvProfile     int // -1 if not Dolby Vision
	summary       string
}

// probeMedia runs ffprobe on the video stream. Returns ok=false if ffprobe
// isn't available, so the caller can fall back to container-based detection.
func probeMedia(path string) (mediaProbe, bool) {
	ffprobe := findFFprobe()
	if ffprobe == "" {
		return mediaProbe{}, false
	}
	cmd := exec.Command(ffprobe,
		"-v", "error",
		"-select_streams", "v:0",
		"-show_entries", "stream=color_transfer,color_space,color_primaries:stream_side_data=dv_profile",
		"-of", "json", path,
	)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	ffmpegLog.record("ffprobe "+filepath.Base(path), stderr.Bytes(), err)
	if err != nil {
		return mediaProbe{}, false
	}

	var parsed struct {
		Streams []struct {
			ColorTransfer  string `json:"color_transfer"`
			ColorSpace     string `json:"color_space"`
			ColorPrimaries string `json:"color_primaries"`
			SideData       []struct {
				DVProfile *int `json:"dv_profile"`
			} `json:"side_data_list"`
		} `json:"streams"`
	}
	if json.Unmarshal(out, &parsed) != nil || len(parsed.Streams) == 0 {
		return mediaProbe{}, false
	}
	s := parsed.Streams[0]

	dv := -1
	for _, sd := range s.SideData {
		if sd.DVProfile != nil {
			dv = *sd.DVProfile
			break
		}
	}
	// PQ (HDR10/HDR10+) or HLG transfer, or any Dolby Vision layer, means we
	// should tonemap the extracted frames.
	isHDR := s.ColorTransfer == "smpte2084" || s.ColorTransfer == "arib-std-b67" || dv >= 0
	hdr10plus := probeHDR10Plus(ffprobe, path)

	p := mediaProbe{
		isHDR:         isHDR,
		hdr10plus:     hdr10plus,
		colorTransfer: s.ColorTransfer,
		colorSpace:    s.ColorSpace,
		dvProfile:     dv,
	}
	dvStr := "none"
	if dv >= 0 {
		dvStr = fmt.Sprintf("profile %d", dv)
	}
	p.summary = fmt.Sprintf("HDR=%v (transfer=%s, space=%s, dolby-vision=%s, hdr10+=%v)",
		isHDR, orNA(s.ColorTransfer), orNA(s.ColorSpace), dvStr, hdr10plus)
	return p, true
}

// probeHDR10Plus reports whether the stream carries HDR10+ dynamic metadata
// (SMPTE ST 2094-40), which is per-frame, so we scan the first several frames.
func probeHDR10Plus(ffprobe, path string) bool {
	cmd := exec.Command(ffprobe,
		"-v", "error",
		"-select_streams", "v:0",
		"-read_intervals", "%+#8",
		"-show_frames",
		"-show_entries", "frame_side_data=side_data_type",
		"-of", "default=noprint_wrappers=1:nokey=1", path,
	)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	ffmpegLog.record("ffprobe hdr10+ "+filepath.Base(path), stderr.Bytes(), err)
	if err != nil {
		return false
	}
	s := strings.ToLower(string(out))
	return strings.Contains(s, "2094") || strings.Contains(s, "dynamic hdr") || strings.Contains(s, "hdr10+")
}

func orNA(s string) string {
	if s == "" {
		return "n/a"
	}
	return s
}

// noDVPath returns the media path with "_no_dv" inserted before the extension,
// e.g. /movies/Titanic.mkv -> /movies/Titanic_no_dv.mkv.
func noDVPath(mediaPath string) string {
	ext := filepath.Ext(mediaPath)
	return strings.TrimSuffix(mediaPath, ext) + "_no_dv" + ext
}

// dolbyVisionAdvisory is the console message shown for Dolby Vision Profile 7
// files: they may be MEL (play fine) or FEL (audio + black screen on Fire TV).
// The printed ffmpeg command losslessly strips the DV RPU (NAL 62) and
// enhancement layer (NAL 63), leaving the HDR10 base layer.
func dolbyVisionAdvisory(mediaPath string) string {
	out := noDVPath(mediaPath)
	return "" +
		"\n⚠ Dolby Vision Profile 7 detected.\n" +
		"  If the video plays, ignore this. If you get audio but a BLACK screen\n" +
		"  (Profile 7 \"FEL\"), Fire TV cannot decode it. Convert to HDR10 once —\n" +
		"  lossless and fast (no re-encode), keeps all audio/subtitles:\n\n" +
		fmt.Sprintf("    ffmpeg -i \"%s\" -map 0 -c copy -bsf:v \"filter_units=remove_types=62|63\" \"%s\"\n\n", mediaPath, out) +
		"  Then run bitstreamer on the *_no_dv file instead.\n"
}

// findFFprobe looks next to the executable first, then on PATH. Returns "".
func findFFprobe() string {
	names := []string{"ffprobe", "ffprobe.exe"}
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		for _, n := range names {
			p := filepath.Join(dir, n)
			if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
				return p
			}
		}
	}
	if p, err := exec.LookPath("ffprobe"); err == nil {
		return p
	}
	return ""
}
