package main

import (
	"encoding/json"
	"os"
	"sync"
)

// resumeStore remembers the last playback position per client IP for the
// currently served file, persisted as a small JSON file so a server restart
// with the same file keeps resume points. Positions stored for a different
// file are discarded on load — switching files clears all resume state.
type resumeStore struct {
	mu        sync.Mutex
	path      string
	mediaPath string
	positions map[string]int64 // client IP -> playback position in ms
}

type resumeFileFormat struct {
	MediaPath string           `json:"mediaPath"`
	Positions map[string]int64 `json:"positions"`
}

func newResumeStore(path, mediaPath string) *resumeStore {
	rs := &resumeStore{
		path:      path,
		mediaPath: mediaPath,
		positions: map[string]int64{},
	}
	rs.load()
	return rs
}

func (rs *resumeStore) load() {
	data, err := os.ReadFile(rs.path)
	if err != nil {
		return
	}
	var rf resumeFileFormat
	if json.Unmarshal(data, &rf) != nil {
		return
	}
	if rf.MediaPath != rs.mediaPath {
		os.Remove(rs.path) // new file being served: stale positions are meaningless
		return
	}
	if rf.Positions != nil {
		rs.positions = rf.Positions
	}
}

func (rs *resumeStore) get(clientIP string) int64 {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	return rs.positions[clientIP]
}

// set stores a position; ms <= 0 clears the client's resume point.
func (rs *resumeStore) set(clientIP string, ms int64) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	if ms <= 0 {
		delete(rs.positions, clientIP)
	} else {
		rs.positions[clientIP] = ms
	}
	rs.persist()
}

func (rs *resumeStore) persist() {
	data, err := json.MarshalIndent(resumeFileFormat{
		MediaPath: rs.mediaPath,
		Positions: rs.positions,
	}, "", "  ")
	if err != nil {
		return
	}
	os.WriteFile(rs.path, data, 0o644)
}
