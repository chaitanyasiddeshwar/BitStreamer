package main

import (
	"encoding/json"
	"os"
	"sync"
)

// resumeStore remembers the last playback position per video file per client IP,
// persisted as a small JSON file so server restarts retain resume points across
// single-file mode and folder mode.
type resumeStore struct {
	mu        sync.Mutex
	path      string
	positions map[string]int64 // "clientIP|fileKey" -> playback position in ms
}

type resumeFileFormat struct {
	Positions map[string]int64 `json:"positions"`
}

func newResumeStore(path string) *resumeStore {
	rs := &resumeStore{
		path:      path,
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
	if rf.Positions != nil {
		rs.positions = rf.Positions
	}
}

func (rs *resumeStore) get(clientIP, fileKey string) int64 {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	key := clientIP + "|" + fileKey
	return rs.positions[key]
}

// set stores a position; ms <= 0 clears the client's resume point for fileKey.
func (rs *resumeStore) set(clientIP, fileKey string, ms int64) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	key := clientIP + "|" + fileKey
	if ms <= 0 {
		delete(rs.positions, key)
	} else {
		rs.positions[key] = ms
	}
	rs.persist()
}

func (rs *resumeStore) persist() {
	data, err := json.MarshalIndent(resumeFileFormat{
		Positions: rs.positions,
	}, "", "  ")
	if err != nil {
		return
	}
	os.WriteFile(rs.path, data, 0o644)
}
