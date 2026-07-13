package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/chaitanyasiddeshwar/bitstreamer/server/third_party/mkvparse"
)

// Chapter is one chapter marker: where it starts and its display name.
type Chapter struct {
	StartMs int64  `json:"startMs"`
	Name    string `json:"name"`
}

// chaptersFor returns a file's chapter markers, reading them from the MKV
// container directly when possible (no external tools) and otherwise falling
// back to ffprobe (for mp4/mov/etc., whose chapters the MKV parser can't read).
// Best-effort: nil if there are none or no reader is available.
func chaptersFor(path string) []Chapter {
	if ch := parseChapters(path); len(ch) > 0 {
		return ch
	}
	return probeChapters(path)
}

// probeChapters reads chapter markers via ffprobe -show_chapters. Returns nil if
// ffprobe is unavailable, the file has no chapters, or parsing fails.
func probeChapters(path string) []Chapter {
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
	ffmpegLog.record("ffprobe chapters "+filepath.Base(path), stderr.Bytes(), err)
	if err != nil {
		return nil
	}
	var parsed struct {
		Chapters []struct {
			StartTime string `json:"start_time"` // seconds, e.g. "12.345000"
			Tags      struct {
				Title string `json:"title"`
			} `json:"tags"`
		} `json:"chapters"`
	}
	if json.Unmarshal(out, &parsed) != nil || len(parsed.Chapters) == 0 {
		return nil
	}
	chapters := make([]Chapter, 0, len(parsed.Chapters))
	for _, c := range parsed.Chapters {
		secs, err := strconv.ParseFloat(strings.TrimSpace(c.StartTime), 64)
		if err != nil {
			continue
		}
		chapters = append(chapters, Chapter{
			StartMs: int64(secs * 1000),
			Name:    c.Tags.Title,
		})
	}
	return finalizeChapters(chapters)
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

// parseChapters reads the default chapter edition from an MKV file. Returns nil
// for non-MKV files, files without chapters, or any parse error — chapters are
// an optional enhancement and must never fail the server.
func parseChapters(path string) []Chapter {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()

	h := &chapterHandler{}
	// ParseSections uses the file's SeekHead index to read only the Chapters
	// element, seeking past the (potentially huge) media clusters.
	if err := mkvparse.ParseSections(f, h, mkvparse.ChaptersElement); err != nil {
		return nil
	}
	return h.result()
}

// chapterHandler collects chapters from mkvparse's streaming callbacks. Matroska
// nests Chapters > EditionEntry > ChapterAtom (which can itself nest) >
// ChapterDisplay > ChapString; we track the atom nesting with a stack and flatten.
type chapterHandler struct {
	mkvparse.DefaultHandler
	editions   []*chapterEdition
	curEdition *chapterEdition
	atomStack  []*chapterAtomState
	inDisplay  bool
}

type chapterEdition struct {
	isDefault bool
	chapters  []Chapter
}

type chapterAtomState struct {
	startMs  int64
	hasStart bool
	hidden   bool
	name     string
}

func (h *chapterHandler) HandleMasterBegin(id mkvparse.ElementID, _ mkvparse.ElementInfo) (bool, error) {
	switch id {
	case mkvparse.EditionEntryElement:
		h.curEdition = &chapterEdition{}
	case mkvparse.ChapterAtomElement:
		h.atomStack = append(h.atomStack, &chapterAtomState{})
	case mkvparse.ChapterDisplayElement:
		h.inDisplay = true
	}
	return true, nil
}

func (h *chapterHandler) HandleMasterEnd(id mkvparse.ElementID, _ mkvparse.ElementInfo) error {
	switch id {
	case mkvparse.ChapterDisplayElement:
		h.inDisplay = false
	case mkvparse.ChapterAtomElement:
		if n := len(h.atomStack); n > 0 {
			atom := h.atomStack[n-1]
			h.atomStack = h.atomStack[:n-1]
			if h.curEdition != nil && atom.hasStart && !atom.hidden {
				h.curEdition.chapters = append(h.curEdition.chapters, Chapter{
					StartMs: atom.startMs,
					Name:    atom.name,
				})
			}
		}
	case mkvparse.EditionEntryElement:
		if h.curEdition != nil {
			h.editions = append(h.editions, h.curEdition)
			h.curEdition = nil
		}
	}
	return nil
}

func (h *chapterHandler) HandleInteger(id mkvparse.ElementID, value int64, _ mkvparse.ElementInfo) error {
	switch id {
	case mkvparse.ChapterTimeStartElement:
		if top := h.topAtom(); top != nil {
			top.startMs = value / 1_000_000 // ChapterTimeStart is in nanoseconds
			top.hasStart = true
		}
	case mkvparse.ChapterFlagHiddenElement:
		if top := h.topAtom(); top != nil && value == 1 {
			top.hidden = true
		}
	case mkvparse.EditionFlagDefaultElement:
		if h.curEdition != nil && value == 1 {
			h.curEdition.isDefault = true
		}
	}
	return nil
}

func (h *chapterHandler) HandleString(id mkvparse.ElementID, value string, _ mkvparse.ElementInfo) error {
	// First ChapterDisplay/ChapString wins (files may carry multiple languages).
	if id == mkvparse.ChapStringElement && h.inDisplay {
		if top := h.topAtom(); top != nil && top.name == "" {
			top.name = value
		}
	}
	return nil
}

func (h *chapterHandler) topAtom() *chapterAtomState {
	if n := len(h.atomStack); n > 0 {
		return h.atomStack[n-1]
	}
	return nil
}

// result returns the default edition's chapters, sorted by start time, with
// fallback names for any unnamed chapters. nil if there are none.
func (h *chapterHandler) result() []Chapter {
	if len(h.editions) == 0 {
		return nil
	}
	chosen := h.editions[0]
	for _, e := range h.editions {
		if e.isDefault {
			chosen = e
			break
		}
	}
	return finalizeChapters(chosen.chapters)
}
