package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

type audioTrackProbe struct {
	Index         int    `json:"index"`
	CodecName     string `json:"codec_name"`
	Bitrate       int64  `json:"bitrate"`
	Channels      int    `json:"channels"`
	ChannelLayout string `json:"channel_layout"`
	Language      string `json:"language"`
	Title         string `json:"title"`
}

// mediaProbe holds the video colour characteristics used to decide HDR
// tonemapping, read from the actual stream via ffprobe (more reliable than the
// MKV container tags, and it can see the Dolby Vision profile).
type mediaProbe struct {
	isHDR              bool
	hdr10plus          bool
	colorTransfer      string
	colorSpace         string
	dvProfile          int // -1 if not Dolby Vision
	summary            string
	videoBitrate       int64
	audioBitrate       int64
	videoCodec         string
	videoProfile       string
	videoLevel         string
	videoRFrameRate    string
	videoAvgFrameRate  string
	videoPixFmt        string
	videoBitsPerSample int
	audioTracks        []audioTrackProbe
}

// extractBitrate parses bit rate from standard ffprobe output or BPS tags.
func extractBitrate(bitRate string, tags map[string]string) int64 {
	if bitRate != "" && bitRate != "N/A" {
		if val, err := strconv.ParseInt(bitRate, 10, 64); err == nil {
			return val
		}
	}
	for _, key := range []string{"BPS", "BPS-eng", "bps", "bps-eng"} {
		if valStr, ok := tags[key]; ok && valStr != "" && valStr != "N/A" {
			if val, err := strconv.ParseInt(valStr, 10, 64); err == nil {
				return val
			}
		}
	}
	return 0
}

func parseString(v any) string {
	if v == nil {
		return ""
	}
	switch val := v.(type) {
	case string:
		return val
	case float64:
		return strconv.FormatFloat(val, 'f', -1, 64)
	case int64:
		return strconv.FormatInt(val, 10)
	case bool:
		return strconv.FormatBool(val)
	}
	return fmt.Sprintf("%v", v)
}

func parseInt(v any) int {
	if v == nil {
		return 0
	}
	switch val := v.(type) {
	case float64:
		return int(val)
	case int64:
		return int(val)
	case string:
		if i, err := strconv.Atoi(val); err == nil {
			return i
		}
	}
	return 0
}

func extractLanguage(tags map[string]string) string {
	for _, key := range []string{"language", "LANGUAGE", "language-eng", "LANGUAGE-ENG"} {
		if val, ok := tags[key]; ok && val != "" {
			return val
		}
	}
	return ""
}

func extractTitle(tags map[string]string) string {
	for _, key := range []string{"title", "TITLE"} {
		if val, ok := tags[key]; ok && val != "" {
			return val
		}
	}
	return ""
}

// probeMedia runs ffprobe on the video/audio streams. Returns ok=false if ffprobe
// isn't available, so the caller can fall back to container-based detection.
func probeMedia(path string) (mediaProbe, bool) {
	ffprobe := findFFprobe()
	if ffprobe == "" {
		return mediaProbe{}, false
	}
	cmd := exec.Command(ffprobe,
		"-v", "error",
		"-show_entries", "stream=codec_type,codec_name,profile,level,bit_rate,r_frame_rate,avg_frame_rate,pix_fmt,bits_per_raw_sample,color_transfer,color_space,color_primaries,channels,channel_layout:stream_tags=BPS,BPS-eng,language,title:stream_side_data=dv_profile",
		"-of", "json", path,
	)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	ffmpegLog.recordProbe("ffprobe streams "+filepath.Base(path), out, stderr.Bytes(), err)
	if err != nil {
		return mediaProbe{}, false
	}

	var parsed struct {
		Streams []struct {
			CodecType      string            `json:"codec_type"`
			CodecName      string            `json:"codec_name"`
			Profile        string            `json:"profile"`
			Level          any               `json:"level"`
			BitRate        string            `json:"bit_rate"`
			RFrameRate     string            `json:"r_frame_rate"`
			AvgFrameRate   string            `json:"avg_frame_rate"`
			PixFmt         string            `json:"pix_fmt"`
			BitsPerSample  any               `json:"bits_per_raw_sample"`
			ColorTransfer  string            `json:"color_transfer"`
			ColorSpace     string            `json:"color_space"`
			ColorPrimaries string            `json:"color_primaries"`
			Channels       int               `json:"channels"`
			ChannelLayout  string            `json:"channel_layout"`
			Tags           map[string]string `json:"tags"`
			SideData       []struct {
				DVProfile *int `json:"dv_profile"`
			} `json:"side_data_list"`
		} `json:"streams"`
	}
	if json.Unmarshal(out, &parsed) != nil || len(parsed.Streams) == 0 {
		return mediaProbe{}, false
	}

	var videoStream struct {
		CodecName     string
		Profile       string
		Level         string
		RFrameRate    string
		AvgFrameRate  string
		PixFmt        string
		BitsPerSample int
		ColorTransfer string
		ColorSpace    string
		SideData      []struct {
			DVProfile *int
		}
		BitRate int64
	}
	var audioTracks []audioTrackProbe
	hasVideo := false
	audioIdx := 0

	for i := range parsed.Streams {
		s := &parsed.Streams[i]
		if s.CodecType == "video" && !hasVideo {
			videoStream.CodecName = s.CodecName
			videoStream.Profile = s.Profile
			videoStream.Level = parseString(s.Level)
			videoStream.RFrameRate = s.RFrameRate
			videoStream.AvgFrameRate = s.AvgFrameRate
			videoStream.PixFmt = s.PixFmt
			videoStream.BitsPerSample = parseInt(s.BitsPerSample)
			videoStream.ColorTransfer = s.ColorTransfer
			videoStream.ColorSpace = s.ColorSpace
			videoStream.SideData = make([]struct {
				DVProfile *int
			}, len(s.SideData))
			for j, sd := range s.SideData {
				videoStream.SideData[j].DVProfile = sd.DVProfile
			}
			videoStream.BitRate = extractBitrate(s.BitRate, s.Tags)
			hasVideo = true
		} else if s.CodecType == "audio" {
			track := audioTrackProbe{
				Index:         audioIdx,
				CodecName:     s.CodecName,
				Bitrate:       extractBitrate(s.BitRate, s.Tags),
				Channels:      s.Channels,
				ChannelLayout: s.ChannelLayout,
				Language:      extractLanguage(s.Tags),
				Title:         extractTitle(s.Tags),
			}
			audioTracks = append(audioTracks, track)
			audioIdx++
		}
	}

	if !hasVideo {
		return mediaProbe{}, false
	}

	dv := -1
	for _, sd := range videoStream.SideData {
		if sd.DVProfile != nil {
			dv = *sd.DVProfile
			break
		}
	}
	// PQ (HDR10/HDR10+) or HLG transfer, or any Dolby Vision layer, means we
	// should tonemap the extracted frames.
	isHDR := videoStream.ColorTransfer == "smpte2084" || videoStream.ColorTransfer == "arib-std-b67" || dv >= 0
	hdr10plus := probeHDR10Plus(ffprobe, path)

	var primaryAudioBitrate int64
	if len(audioTracks) > 0 {
		primaryAudioBitrate = audioTracks[0].Bitrate
	}

	p := mediaProbe{
		isHDR:              isHDR,
		hdr10plus:          hdr10plus,
		colorTransfer:      videoStream.ColorTransfer,
		colorSpace:         videoStream.ColorSpace,
		dvProfile:          dv,
		videoBitrate:       videoStream.BitRate,
		audioBitrate:       primaryAudioBitrate,
		videoCodec:         videoStream.CodecName,
		videoProfile:       videoStream.Profile,
		videoLevel:         videoStream.Level,
		videoRFrameRate:    videoStream.RFrameRate,
		videoAvgFrameRate:  videoStream.AvgFrameRate,
		videoPixFmt:        videoStream.PixFmt,
		videoBitsPerSample: videoStream.BitsPerSample,
		audioTracks:        audioTracks,
	}
	dvStr := "none"
	if dv >= 0 {
		dvStr = fmt.Sprintf("profile %d", dv)
	}
	p.summary = fmt.Sprintf("HDR=%v (transfer=%s, space=%s, dolby-vision=%s, hdr10+=%v, v-rate=%d, a-rate=%d)",
		isHDR, orNA(videoStream.ColorTransfer), orNA(videoStream.ColorSpace), dvStr, hdr10plus, videoStream.BitRate, primaryAudioBitrate)
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
	ffmpegLog.recordProbe("ffprobe hdr10+ "+filepath.Base(path), out, stderr.Bytes(), err)
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
		"  If the video plays, ignore this (it might be Profile 7 \"MEL\").\n" +
		"  If you get audio but a BLACK screen (Profile 7 \"FEL\"), try running the server with the '-stripdv' flag first:\n\n" +
		"    bitstreamer -stripdv ...\n\n" +
		"  If that also doesn't work, losslessly convert the file to HDR10 (no re-encode):\n\n" +
		fmt.Sprintf("    ffmpeg -i \"%s\" -map 0 -c copy -bsf:v \"filter_units=remove_types=62|63\" \"%s\"\n\n", mediaPath, out) +
		"  Then run bitstreamer on the *_no_dv file instead.\n"
}
// findFFprobe looks next to the executable first, then on PATH. Returns "".
func findFFprobe() string {
	return findExecutable("ffprobe")
}
