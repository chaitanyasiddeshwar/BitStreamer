package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
)

// discoveryProbe is the exact datagram payload a client broadcasts to find
// servers. Anything else is ignored. Versioned: breaking changes get _V2.
const discoveryProbe = "BITSTREAMER_DISCOVER_V1"

type discoveryReply struct {
	V        int    `json:"v"`
	App      string `json:"app"`
	Name     string `json:"name"`
	HTTPPort int    `json:"httpPort"`
}

// runDiscovery answers UDP discovery probes until the process exits.
func runDiscovery(udpPort int, displayName string, httpPort int) error {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: udpPort})
	if err != nil {
		return fmt.Errorf("listen udp :%d: %w", udpPort, err)
	}
	defer conn.Close()
	log.Printf("discovery listening on udp :%d", udpPort)

	reply, err := json.Marshal(discoveryReply{
		V:        1,
		App:      "bitstreamer",
		Name:     displayName,
		HTTPPort: httpPort,
	})
	if err != nil {
		return err
	}

	buf := make([]byte, 512)
	for {
		n, addr, err := conn.ReadFromUDP(buf)
		if err != nil {
			return fmt.Errorf("discovery read: %w", err)
		}
		if string(buf[:n]) != discoveryProbe {
			continue
		}
		if _, err := conn.WriteToUDP(reply, addr); err != nil {
			log.Printf("discovery reply to %s failed: %v", addr, err)
			continue
		}
		log.Printf("discovery probe from %s -> replied", addr)
	}
}
