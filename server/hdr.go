package main

import (
	"os"

	"github.com/chaitanyasiddeshwar/bitstreamer/server/third_party/mkvparse"
)

// HDR transfer characteristics (Matroska \...\Colour\TransferCharacteristics).
const (
	transferPQ  = 16 // SMPTE ST 2084 (HDR10 / Dolby Vision base)
	transferHLG = 18 // ARIB STD-B67 (HLG)
)

// parseIsHDR reports whether the MKV's video is HDR, so thumbnails can be
// tonemapped to SDR (otherwise HDR frames look washed out). Reads the video
// track's Colour\TransferCharacteristics via go-mkvparse. False if unknown.
func parseIsHDR(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()

	h := &hdrHandler{}
	if err := mkvparse.ParseSections(f, h, mkvparse.TracksElement); err != nil {
		return false
	}
	return h.hdr
}

type hdrHandler struct {
	mkvparse.DefaultHandler
	hdr bool
}

func (h *hdrHandler) HandleInteger(id mkvparse.ElementID, value int64, _ mkvparse.ElementInfo) error {
	if id == mkvparse.TransferCharacteristicsElement && (value == transferPQ || value == transferHLG) {
		h.hdr = true
	}
	return nil
}
