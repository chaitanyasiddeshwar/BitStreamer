package main

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"sync"
	"time"
)

// cmdLogger appends ffmpeg/ffprobe output to a log file. Concurrency-safe
// (many thumbnail/storyboard ffmpeg runs happen at once). Best-effort: if the
// file can't be opened it silently does nothing.
type cmdLogger struct {
	mu sync.Mutex
	f  *os.File
}

var ffmpegLog = &cmdLogger{}

// initFFmpegLog opens the log in append mode and writes a session header — so
// each server start adds a new section rather than overwriting.
func initFFmpegLog(path string) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	ffmpegLog.mu.Lock()
	ffmpegLog.f = f
	fmt.Fprintf(f, "\n==== session started %s ====\n", time.Now().Format(time.RFC3339))
	ffmpegLog.mu.Unlock()
}

// record appends a command's captured output. Silent successful runs (no output,
// no error) are skipped, so the log stays focused on warnings and failures. Used
// for the high-volume ffmpeg frame extractions (hundreds per storyboard). For
// ffprobe metadata calls use recordProbe, which always logs.
func (l *cmdLogger) record(desc string, output []byte, err error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.f == nil {
		return
	}
	trimmed := bytes.TrimSpace(output)
	if len(trimmed) == 0 && err == nil {
		return
	}
	fmt.Fprintf(l.f, "[%s] %s\n", time.Now().Format("15:04:05"), desc)
	if len(trimmed) > 0 {
		l.f.Write(trimmed)
		l.f.WriteString("\n")
	}
	if err != nil {
		fmt.Fprintf(l.f, "  -> %v\n", err)
	}
}

// recordProbe always logs an ffprobe (or other metadata) command: the command's
// stdout (e.g. the JSON it returned), any stderr, and the exit status — even on a
// clean run. This is what makes the metadata the server reads actually appear in
// the log, instead of the previous "session started, then nothing".
func (l *cmdLogger) recordProbe(desc string, stdout, stderr []byte, err error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.f == nil {
		return
	}
	status := "ok"
	if err != nil {
		status = err.Error()
	}
	fmt.Fprintf(l.f, "[%s] %s -> %s\n", time.Now().Format("15:04:05"), desc, status)
	if s := bytes.TrimSpace(stdout); len(s) > 0 {
		l.f.Write(s)
		l.f.WriteString("\n")
	}
	if s := bytes.TrimSpace(stderr); len(s) > 0 {
		fmt.Fprintf(l.f, "  stderr: %s\n", s)
	}
}

// logf writes a free-form line to the log (used for the startup metadata dump).
func (l *cmdLogger) logf(format string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.f == nil {
		return
	}
	fmt.Fprintf(l.f, format+"\n", args...)
}

// runLogged runs cmd, capturing its stderr into the log, and returns the error.
func (l *cmdLogger) runLogged(desc string, cmd *exec.Cmd) error {
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	err := cmd.Run()
	l.record(desc, stderr.Bytes(), err)
	return err
}
