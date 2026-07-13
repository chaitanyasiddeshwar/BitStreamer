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
// no error) are skipped, so the log stays focused on warnings and failures.
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

// runLogged runs cmd, capturing its stderr into the log, and returns the error.
func (l *cmdLogger) runLogged(desc string, cmd *exec.Cmd) error {
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	err := cmd.Run()
	l.record(desc, stderr.Bytes(), err)
	return err
}
