// bitstreamer serves a single media file byte-for-byte over HTTP so a
// BitStreamer client on the LAN can play it without any transcoding.
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
)

const (
	defaultHTTPPort = 46898
	discoveryPort   = 46899
)

func main() {
	log.SetFlags(log.Ltime)

	port := flag.Int("port", defaultHTTPPort, "HTTP port to serve on")
	name := flag.String("name", "", "display name announced to clients (default: hostname)")
	apk := flag.String("apk", "", "path to the client APK served at /client.apk (default: client.apk next to the executable)")
	clientLog := flag.String("clientlog", "", "file where client diagnostics POSTed to /log are appended (default: client-logs.txt next to the executable)")
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: %s [flags] <media-file>\n\nflags:\n", filepath.Base(os.Args[0]))
		flag.PrintDefaults()
	}
	flag.Parse()

	if flag.NArg() != 1 {
		flag.Usage()
		os.Exit(2)
	}

	if *name == "" {
		host, err := os.Hostname()
		if err != nil {
			host = "bitstreamer"
		}
		*name = host
	}
	exeDir := "."
	if exe, err := os.Executable(); err == nil {
		exeDir = filepath.Dir(exe)
	}
	if *apk == "" {
		*apk = filepath.Join(exeDir, "client.apk")
	}
	if *clientLog == "" {
		*clientLog = filepath.Join(exeDir, "client-logs.txt")
	}

	app, err := newApp(flag.Arg(0), *name, *apk, *clientLog, *port)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}

	go func() {
		if err := runDiscovery(discoveryPort, app.displayName, app.httpPort); err != nil {
			log.Printf("discovery disabled: %v", err)
		}
	}()

	fmt.Printf("Serving %q (%s)\n\n", app.mediaName, formatSize(app.mediaSize))
	for _, ip := range lanIPv4s() {
		fmt.Printf("  stream  http://%s:%d/stream\n", ip, app.httpPort)
		fmt.Printf("  client  http://%s:%d/client.apk\n\n", ip, app.httpPort)
	}
	fmt.Printf("Client diagnostics will be appended to %s\n\n", app.clientLogPath)
	fmt.Println("Waiting for a client to connect... (Ctrl+C to stop)")

	addr := fmt.Sprintf(":%d", app.httpPort)
	if err := http.ListenAndServe(addr, app.handler()); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func formatSize(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}
