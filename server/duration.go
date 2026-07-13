package main

import (
	"os"

	"github.com/chaitanyasiddeshwar/bitstreamer/server/third_party/mkvparse"
)

// parseDuration reads the total duration of an MKV from its Segment\Info header
// (Duration, in TimecodeScale units). Returns 0 if unknown / not an MKV.
func parseDuration(path string) int64 {
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()

	h := &durationHandler{timecodeScale: 1_000_000} // default 1ms in ns
	if err := mkvparse.ParseSections(f, h, mkvparse.InfoElement); err != nil {
		return 0
	}
	if h.duration <= 0 {
		return 0
	}
	// duration is in TimecodeScale units; TimecodeScale is ns per unit.
	ns := h.duration * float64(h.timecodeScale)
	return int64(ns / 1_000_000) // -> milliseconds
}

type durationHandler struct {
	mkvparse.DefaultHandler
	duration      float64
	timecodeScale int64
}

func (h *durationHandler) HandleFloat(id mkvparse.ElementID, value float64, _ mkvparse.ElementInfo) error {
	if id == mkvparse.DurationElement {
		h.duration = value
	}
	return nil
}

func (h *durationHandler) HandleInteger(id mkvparse.ElementID, value int64, _ mkvparse.ElementInfo) error {
	if id == mkvparse.TimecodeScaleElement {
		h.timecodeScale = value
	}
	return nil
}
