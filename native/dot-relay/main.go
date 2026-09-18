// Command dot-relay is a minimal DNS-over-TLS (RFC 7858) front-end for
// pdnsd, which has no TLS support of its own (see the project's CLAUDE.md/
// commit history — this repo never invents protocol behavior it can't
// verify, so rather than guess at bolting TLS onto pdnsd, DoT is handled by
// this small standalone relay instead).
//
// It listens locally for plain DNS-over-TCP connections — the exact wire
// format pdnsd's own `query_method=tcp_only` already speaks to its
// upstream — and for each query dials a real DoT server over TLS, in the
// same length-prefixed wire format (RFC 7858 §3.3: identical to classic
// DNS-over-TCP per RFC 1035 §4.2.2, just carried inside TLS). The
// server-selection/dial/framing logic below is ported near-verbatim from
// a working iOS build of this same OpenFlux core (saharev1/OpenFlux,
// ios-testflight branch, export_ios.go/export_ios_packet.go's
// dotServer/dotQueryOne/OpenFluxSetDoTResolver) rather than written from
// scratch, since that DoT implementation is already proven in production
// there — pdnsd's role here (client-facing caching/serving) has no iOS
// equivalent to port, so this file is new, but the DoT wire logic itself
// is not.
package main

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"flag"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

// dotServer is a single DNS-over-TLS endpoint (addr:853 + TLS SNI/cert
// hostname). Mirrors the iOS core's own struct of the same name exactly.
type dotServer struct {
	addr string
	sni  string
}

// parseDoTSpec parses a ";"-separated list of "addr[:port]@sni" entries —
// the exact same spec format the iOS core's OpenFluxSetDoTResolver accepts
// (e.g. "1.1.1.1@cloudflare-dns.com"), reused here so a "custom DoT server"
// field in the Android UI can use the identical syntax. Port defaults to
// 853; SNI defaults to the host if "@sni" is omitted.
func parseDoTSpec(spec string) ([]dotServer, error) {
	var servers []dotServer
	for _, part := range strings.Split(spec, ";") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		addr, sni := part, ""
		if i := strings.LastIndex(part, "@"); i >= 0 {
			addr, sni = strings.TrimSpace(part[:i]), strings.TrimSpace(part[i+1:])
		}
		if !strings.Contains(addr, ":") {
			addr += ":853"
		}
		if sni == "" {
			if host, _, err := net.SplitHostPort(addr); err == nil {
				sni = host
			} else {
				sni = addr
			}
		}
		servers = append(servers, dotServer{addr: addr, sni: sni})
	}
	if len(servers) == 0 {
		return nil, &parseError{spec}
	}
	return servers, nil
}

type parseError struct{ spec string }

func (e *parseError) Error() string { return "dot-relay: no valid server in spec: " + e.spec }

// dotQueryOne sends one length-prefixed DNS query to a single DoT server and
// returns the raw answer bytes (framing stripped). Ported from the iOS
// core's dotQueryOne: dial timeout, a deadline on the whole exchange, TLS
// 1.2 minimum. caPool is nil in production (main() never sets it), which
// makes crypto/tls fall back to x509.SystemCertPool() — on GOOS=android that
// reads the real on-device trust store (/system/etc/security/cacerts,
// confirmed directly from this Go toolchain's own src/crypto/x509/
// root_linux.go — android build-tag-matches _linux.go files per `go help
// buildconstraint`), so no CA bundle needs to be embedded or shipped. Tests
// pass a pool for a local self-signed test server instead — this sandbox
// has no route to real DoT servers on :853 to test against live.
func dotQueryOne(s dotServer, query []byte, timeout time.Duration, caPool *x509.CertPool) ([]byte, error) {
	d := tls.Dialer{
		NetDialer: &net.Dialer{Timeout: timeout},
		Config:    &tls.Config{ServerName: s.sni, MinVersion: tls.VersionTLS12, RootCAs: caPool},
	}
	conn, err := d.Dial("tcp", s.addr)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(timeout))

	var lp [2]byte
	binary.BigEndian.PutUint16(lp[:], uint16(len(query)))
	if _, err := conn.Write(append(lp[:], query...)); err != nil {
		return nil, err
	}
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return nil, err
	}
	ans := make([]byte, binary.BigEndian.Uint16(hdr))
	if _, err := io.ReadFull(conn, ans); err != nil {
		return nil, err
	}
	return ans, nil
}

// dotQuery tries each configured server in order, same fallback behavior as
// the iOS core's dnsOverTLS/dialSecureDNS.
func dotQuery(servers []dotServer, query []byte, timeout time.Duration, caPool *x509.CertPool) ([]byte, error) {
	var lastErr error
	for _, s := range servers {
		ans, err := dotQueryOne(s, query, timeout, caPool)
		if err == nil {
			return ans, nil
		}
		lastErr = err
		log.Printf("dot-relay: %s failed: %v", s.addr, err)
	}
	return nil, lastErr
}

// maxDNSMessage is the length-prefix field's own ceiling (uint16) — also
// used to bound how much a single frame read will allocate, so a client
// that sends a bogus/huge length can't be used to force an oversized
// allocation (the field is 16-bit, so this is already the true maximum,
// not an arbitrary extra limit).
const maxDNSMessage = 65535

// serveConn relays every length-prefixed query pdnsd sends on this one TCP
// connection to the configured DoT server(s), writing each length-prefixed
// answer back. pdnsd may or may not reuse a connection across queries; this
// loops until the client closes or a read/write fails, so either behavior
// works without needing to know which one pdnsd actually does.
func serveConn(conn net.Conn, servers []dotServer, timeout time.Duration, caPool *x509.CertPool) {
	defer conn.Close()
	for {
		conn.SetReadDeadline(time.Now().Add(timeout))
		hdr := make([]byte, 2)
		if _, err := io.ReadFull(conn, hdr); err != nil {
			return // EOF or timeout: client done with this connection
		}
		n := binary.BigEndian.Uint16(hdr)
		if n == 0 || n > maxDNSMessage {
			return
		}
		query := make([]byte, n)
		if _, err := io.ReadFull(conn, query); err != nil {
			return
		}

		answer, err := dotQuery(servers, query, timeout, caPool)
		if err != nil {
			log.Printf("dot-relay: query failed on all servers: %v", err)
			return // pdnsd will treat the closed connection as a failed lookup
		}

		var lp [2]byte
		binary.BigEndian.PutUint16(lp[:], uint16(len(answer)))
		conn.SetWriteDeadline(time.Now().Add(timeout))
		if _, err := conn.Write(lp[:]); err != nil {
			return
		}
		if _, err := conn.Write(answer); err != nil {
			return
		}
	}
}

func main() {
	listenAddr := flag.String("listen", "127.0.0.1:8853", "local address to accept plain DNS-over-TCP connections from pdnsd")
	spec := flag.String("servers", "", `DoT server spec, ";"-separated "addr[:port]@sni" entries, e.g. "1.1.1.1@cloudflare-dns.com"`)
	pidFile := flag.String("pidfile", "", "optional: write this process's PID here on startup, matching pdnsd's own pid_file convention")
	timeout := flag.Duration("timeout", 8*time.Second, "per-query dial+exchange timeout")
	flag.Parse()

	servers, err := parseDoTSpec(*spec)
	if err != nil {
		log.Fatalf("dot-relay: %v", err)
	}

	if *pidFile != "" {
		if err := os.WriteFile(*pidFile, []byte(strconv.Itoa(os.Getpid())), 0o644); err != nil {
			log.Printf("dot-relay: warning: failed to write pidfile %s: %v", *pidFile, err)
		}
	}

	ln, err := net.Listen("tcp", *listenAddr)
	if err != nil {
		log.Fatalf("dot-relay: listen %s: %v", *listenAddr, err)
	}
	log.Printf("dot-relay: listening on %s, upstream servers: %v", *listenAddr, servers)

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("dot-relay: accept error: %v", err)
			continue
		}
		go serveConn(conn, servers, *timeout, nil)
	}
}
