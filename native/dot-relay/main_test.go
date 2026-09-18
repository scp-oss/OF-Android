package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"io"
	"math/big"
	"net"
	"testing"
	"time"
)

// startFakeDoTServer runs a local TLS server speaking the exact DoT wire
// format (length-prefixed DNS message in, echo-transformed length-prefixed
// message out) so the relay's real network path can be exercised end to
// end — this sandbox has no route to a real public DoT server on :853.
// Returns the listen address, the leaf cert's own CA pool (for the client
// side to trust it), and a stop func.
func startFakeDoTServer(t *testing.T, sni string, handler func(query []byte) []byte) (addr string, caPool *x509.CertPool, stop func()) {
	t.Helper()

	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("keygen: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: sni},
		DNSNames:              []string{sni},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IsCA:                  true,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatalf("create cert: %v", err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatalf("parse cert: %v", err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(cert)

	tlsCert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: priv}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{tlsCert}})
	if err != nil {
		t.Fatalf("tls listen: %v", err)
	}

	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				for {
					hdr := make([]byte, 2)
					if _, err := io.ReadFull(c, hdr); err != nil {
						return
					}
					n := binary.BigEndian.Uint16(hdr)
					query := make([]byte, n)
					if _, err := io.ReadFull(c, query); err != nil {
						return
					}
					resp := handler(query)
					var lp [2]byte
					binary.BigEndian.PutUint16(lp[:], uint16(len(resp)))
					c.Write(lp[:])
					c.Write(resp)
				}
			}()
		}
	}()

	return ln.Addr().String(), pool, func() { ln.Close() }
}

// dialFramed opens a plain TCP connection to the relay and sends/receives
// one length-prefixed frame — standing in for what pdnsd does.
func dialFramed(t *testing.T, addr string, query []byte) []byte {
	t.Helper()
	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		t.Fatalf("dial relay: %v", err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))

	var lp [2]byte
	binary.BigEndian.PutUint16(lp[:], uint16(len(query)))
	if _, err := conn.Write(append(lp[:], query...)); err != nil {
		t.Fatalf("write query: %v", err)
	}
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		t.Fatalf("read answer header: %v", err)
	}
	ans := make([]byte, binary.BigEndian.Uint16(hdr))
	if _, err := io.ReadFull(conn, ans); err != nil {
		t.Fatalf("read answer body: %v", err)
	}
	return ans
}

func TestRelayForwardsQueryAndAnswer(t *testing.T) {
	upstreamAddr, caPool, stop := startFakeDoTServer(t, "test.dot.local", func(query []byte) []byte {
		// Echo the query back with a marker byte appended, so we can prove
		// the exact bytes made a round trip through TLS and back.
		return append(append([]byte{}, query...), 0xAA)
	})
	defer stop()

	relayLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("relay listen: %v", err)
	}
	defer relayLn.Close()

	servers := []dotServer{{addr: upstreamAddr, sni: "test.dot.local"}}
	go func() {
		for {
			c, err := relayLn.Accept()
			if err != nil {
				return
			}
			go serveConn(c, servers, 3*time.Second, caPool)
		}
	}()

	query := []byte{0x12, 0x34, 0x01, 0x00} // fake DNS header bytes, content-agnostic
	got := dialFramed(t, relayLn.Addr().String(), query)

	want := append(append([]byte{}, query...), 0xAA)
	if len(got) != len(want) {
		t.Fatalf("answer length = %d, want %d", len(got), len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("answer mismatch at byte %d: got %x want %x", i, got[i], want[i])
		}
	}
}

func TestRelayHandlesMultipleQueriesOnOneConnection(t *testing.T) {
	callCount := 0
	upstreamAddr, caPool, stop := startFakeDoTServer(t, "test.dot.local", func(query []byte) []byte {
		callCount++
		return []byte{byte(callCount)}
	})
	defer stop()

	relayLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("relay listen: %v", err)
	}
	defer relayLn.Close()

	servers := []dotServer{{addr: upstreamAddr, sni: "test.dot.local"}}
	go func() {
		c, err := relayLn.Accept()
		if err != nil {
			return
		}
		serveConn(c, servers, 3*time.Second, caPool)
	}()

	conn, err := net.DialTimeout("tcp", relayLn.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatalf("dial relay: %v", err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))

	for i := 1; i <= 3; i++ {
		q := []byte{0x01}
		var lp [2]byte
		binary.BigEndian.PutUint16(lp[:], uint16(len(q)))
		conn.Write(append(lp[:], q...))

		hdr := make([]byte, 2)
		io.ReadFull(conn, hdr)
		ans := make([]byte, binary.BigEndian.Uint16(hdr))
		io.ReadFull(conn, ans)
		if len(ans) != 1 || ans[0] != byte(i) {
			t.Fatalf("query %d: got answer %v, want [%d]", i, ans, i)
		}
	}
}

func TestRelayFallsBackToSecondServerOnFirstFailure(t *testing.T) {
	// First server: nothing listening (connection refused).
	badLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	badAddr := badLn.Addr().String()
	badLn.Close() // closed immediately: nothing accepts on this port now

	goodAddr, caPool, stop := startFakeDoTServer(t, "good.dot.local", func(query []byte) []byte {
		return []byte{0x42}
	})
	defer stop()

	relayLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("relay listen: %v", err)
	}
	defer relayLn.Close()

	servers := []dotServer{
		{addr: badAddr, sni: "bad.dot.local"},
		{addr: goodAddr, sni: "good.dot.local"},
	}
	go func() {
		c, err := relayLn.Accept()
		if err != nil {
			return
		}
		serveConn(c, servers, 3*time.Second, caPool)
	}()

	got := dialFramed(t, relayLn.Addr().String(), []byte{0x01})
	if len(got) != 1 || got[0] != 0x42 {
		t.Fatalf("fallback answer = %v, want [0x42]", got)
	}
}

func TestParseDoTSpec(t *testing.T) {
	cases := []struct {
		spec string
		want []dotServer
	}{
		{"1.1.1.1@cloudflare-dns.com", []dotServer{{"1.1.1.1:853", "cloudflare-dns.com"}}},
		{"9.9.9.9:853@dns.quad9.net", []dotServer{{"9.9.9.9:853", "dns.quad9.net"}}},
		{"8.8.8.8", []dotServer{{"8.8.8.8:853", "8.8.8.8"}}},
		{
			"1.1.1.1@cloudflare-dns.com;9.9.9.9@dns.quad9.net",
			[]dotServer{{"1.1.1.1:853", "cloudflare-dns.com"}, {"9.9.9.9:853", "dns.quad9.net"}},
		},
	}
	for _, c := range cases {
		got, err := parseDoTSpec(c.spec)
		if err != nil {
			t.Fatalf("parseDoTSpec(%q): %v", c.spec, err)
		}
		if len(got) != len(c.want) {
			t.Fatalf("parseDoTSpec(%q) = %v, want %v", c.spec, got, c.want)
		}
		for i := range got {
			if got[i] != c.want[i] {
				t.Fatalf("parseDoTSpec(%q)[%d] = %v, want %v", c.spec, i, got[i], c.want[i])
			}
		}
	}

	if _, err := parseDoTSpec("  "); err == nil {
		t.Fatal("parseDoTSpec(blank) should error")
	}
}
