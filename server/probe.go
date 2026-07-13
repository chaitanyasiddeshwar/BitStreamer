package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

// mediaProbe holds the video colour characteristics used to decide HDR
// tonemapping, read from the actual stream via ffprobe (more reliable than the
// MKV container tags, and it can see the Dolby Vision profile).
type mediaProbe struct {
	isHDR         bool
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
	out, err := exec.Command(ffprobe,
		"-v", "error",
		"-select_streams", "v:0",
		"-show_entries", "stream=color_transfer,color_space,color_primaries:stream_side_data=dv_profile",
		"-of", "json", path,
	).Output()
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

	p := mediaProbe{
		isHDR:         isHDR,
		colorTransfer: s.ColorTransfer,
		colorSpace:    s.ColorSpace,
		dvProfile:     dv,
	}
	dvStr := "none"
	if dv >= 0 {
		dvStr = fmt.Sprintf("profile %d", dv)
	}
	p.summary = fmt.Sprintf("HDR=%v (transfer=%s, space=%s, dolby-vision=%s)",
		isHDR, orNA(s.ColorTransfer), orNA(s.ColorSpace), dvStr)
	return p, true
}

func orNA(s string) string {
	if s == "" {
		return "n/a"
	}
	return s
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
