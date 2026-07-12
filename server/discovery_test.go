package main

import (
	"encoding/json"
	"net"
	"testing"
	"time"
)

// startResponder runs the discovery loop on an ephemeral port and returns its
// address. Mirrors runDiscovery but with port 0 so tests never collide.
func startResponder(t *testing.T) *net.UDPAddr {
	t.Helper()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })

	reply, _ := json.Marshal(discoveryReply{V: 1, App: "bitstreamer", Name: "TestServer", HTTPPort: 46898})
	go func() {
		buf := make([]byte, 512)
		for {
			n, addr, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if string(buf[:n]) == discoveryProbe {
				conn.WriteToUDP(reply, addr)
			}
		}
	}()
	return conn.LocalAddr().(*net.UDPAddr)
}

func TestDiscoveryRoundTrip(t *testing.T) {
	serverAddr := startResponder(t)

	client, err := net.DialUDP("udp4", nil, serverAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	if _, err := client.Write([]byte(discoveryProbe)); err != nil {
		t.Fatal(err)
	}
	client.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 512)
	n, err := client.Read(buf)
	if err != nil {
		t.Fatalf("no discovery reply: %v", err)
	}

	var reply discoveryReply
	if err := json.Unmarshal(buf[:n], &reply); err != nil {
		t.Fatalf("reply is not valid JSON: %v", err)
	}
	if reply.V != 1 || reply.App != "bitstreamer" || reply.HTTPPort != 46898 {
		t.Errorf("unexpected reply: %+v", reply)
	}
}

func TestDiscoveryIgnoresGarbage(t *testing.T) {
	serverAddr := startResponder(t)

	client, err := net.DialUDP("udp4", nil, serverAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	if _, err := client.Write([]byte("SSDP-DISCOVER-SOMETHING-ELSE")); err != nil {
		t.Fatal(err)
	}
	client.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	buf := make([]byte, 512)
	if n, err := client.Read(buf); err == nil {
		t.Errorf("expected no reply to garbage probe, got %q", buf[:n])
	}
}
