package api

import (
	"bufio"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/alexisfrlopez-debug/nyx-messenger/server/internal/config"
	"github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

// newRelayTestServer — mux de test avec le relais actif (store temporaire).
func newRelayTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	dir := t.TempDir()
	store, err := journal.OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{
		Version:    "test",
		Onion:      "nœud.onion",
		GossipPub:  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		GossipPriv: make([]byte, 32),
		Store:      store,
		Peers:      func() []config.Peer { return nil },
		AddPeer:    func(p config.Peer) {},
		Kick:       make(chan struct{}, 1),
	}
	ts := httptest.NewServer(s.Handler())
	t.Cleanup(ts.Close)
	return ts
}

// relayDial — ouvre une moitié de relais et attend le 101 (client nu TCP).
func relayDial(t *testing.T, ts *httptest.Server, cid, role string) net.Conn {
	t.Helper()
	conn, err := net.DialTimeout("tcp", strings.TrimPrefix(ts.URL, "http://"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	req := "GET /call/relay?cid=" + cid + "&role=" + role +
		" HTTP/1.1\r\nHost: relais\r\nUpgrade: nyx-pipe\r\nConnection: Upgrade\r\n\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		t.Fatal(err)
	}
	br := bufio.NewReader(conn)
	line, err := br.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(line, "101") {
		t.Fatalf("attendu 101, reçu : %q", line)
	}
	for {
		h, err := br.ReadString('\n')
		if err != nil {
			t.Fatal(err)
		}
		if h == "\r\n" {
			break
		}
	}
	return conn
}

// TestRelayPipe — l'appelant ET l'appelé s'y retrouvent : les octets de
// l'un arrivent à l'autre, dans les deux sens (média bidirectionnel).
func TestRelayPipe(t *testing.T) {
	ts := newRelayTestServer(t)
	cid := "0123456789abcdef0123456789abcdef"

	caller := relayDial(t, ts, cid, "caller")
	defer caller.Close()
	// L'appelant patiente (aucune contrepartie) : il ne doit rien recevoir.
	caller.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	buf := make([]byte, 16)
	if n, err := caller.Read(buf); n > 0 || err == nil {
		t.Fatalf("l'appelant seul ne doit rien recevoir (n=%d err=%v)", n, err)
	}
	caller.SetReadDeadline(time.Time{})

	callee := relayDial(t, ts, cid, "callee")
	defer callee.Close()

	// Appelant → appelé, puis appelé → appelant.
	caller.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := caller.Write([]byte("HELLO_APPELANT")); err != nil {
		t.Fatal(err)
	}
	callee.SetReadDeadline(time.Now().Add(5 * time.Second))
	got := make([]byte, 64)
	n, err := callee.Read(got)
	if err != nil || string(got[:n]) != "HELLO_APPELANT" {
		t.Fatalf("appelé a reçu %q (err=%v)", got[:n], err)
	}
	callee.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := callee.Write([]byte("CALL_ACCEPT")); err != nil {
		t.Fatal(err)
	}
	caller.SetReadDeadline(time.Now().Add(5 * time.Second))
	n, err = caller.Read(got)
	if err != nil || string(got[:n]) != "CALL_ACCEPT" {
		t.Fatalf("appelant a reçu %q (err=%v)", got[:n], err)
	}
}

// TestRelayRolesUniques — un DOUBLON de rôle sur le même cid est refusé et
// l'appariement d'origine reste intact.
func TestRelayRolesUniques(t *testing.T) {
	ts := newRelayTestServer(t)
	cid := "ffffffffffffffffffffffffffffffff"

	c1 := relayDial(t, ts, cid, "caller")
	defer c1.Close()
	c2 := relayDial(t, ts, cid, "caller") // doublon — attendu fermé par le serveur
	defer c2.Close()
	c2.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := c2.Read(make([]byte, 16)); err == nil {
		t.Fatal("le doublon de rôle doit être fermé")
	}
	// L'appelé apparié au PREMIER appelant fonctionne toujours.
	c3 := relayDial(t, ts, cid, "callee")
	defer c3.Close()
	c1.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := c1.Write([]byte("X")); err != nil {
		t.Fatal(err)
	}
	c3.SetReadDeadline(time.Now().Add(5 * time.Second))
	if n, err := c3.Read(make([]byte, 16)); n != 1 || err != nil {
		t.Fatalf("appariement d'origine cassé (n=%d err=%v)", n, err)
	}
}

// TestRelayValidations — requêtes malformées rejetées en HTTP (jamais hijack).
func TestRelayValidations(t *testing.T) {
	ts := newRelayTestServer(t)

	// Mauvaise méthode / pas d'Upgrade / cid court / rôle inconnu : 4xx-405.
	checks := []struct {
		path string
		code int
		upg  bool
		meth string
	}{
		{"/call/relay?cid=0123456789abcdef&role=caller", 400, false, "GET"},
		{"/call/relay?cid=short&role=caller", 400, true, "GET"},
		{"/call/relay?cid=0123456789abcdef&role=hacker", 400, true, "GET"},
		{"/call/relay?cid=0123456789abcdef&role=caller", 405, true, "POST"},
	}
	for _, c := range checks {
		resp, err := postOrGet(ts, c.meth, c.path, c.upg)
		if err != nil {
			t.Fatal(err)
		}
		if resp.StatusCode != c.code {
			t.Fatalf("%s %s : attendu %d, reçu %d", c.meth, c.path, c.code, resp.StatusCode)
		}
		resp.Body.Close()
	}
}

// postOrGet — requête HTTP simple (avec ou sans en-tête Upgrade).
func postOrGet(ts *httptest.Server, method, path string, upgrade bool) (*http.Response, error) {
	var body io.Reader
	if method == "POST" {
		body = strings.NewReader("")
	}
	req, err := http.NewRequest(method, ts.URL+path, body)
	if err != nil {
		return nil, err
	}
	if upgrade {
		req.Header.Set("Upgrade", "nyx-pipe")
		req.Header.Set("Connection", "Upgrade")
	}
	return http.DefaultClient.Do(req)
}

// TestRelayMaintenance — le relais est fermé quand le nœud est en maintenance.
func TestRelayMaintenance(t *testing.T) {
	dir := t.TempDir()
	store, _ := journal.OpenStore(dir)
	s := &Server{
		Version: "test", Onion: "n.onion",
		GossipPub:   "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		GossipPriv:  make([]byte, 32),
		Store:       store,
		Peers:       func() []config.Peer { return nil },
		AddPeer:     func(p config.Peer) {},
		Kick:        make(chan struct{}, 1),
		Maintenance: func() bool { return true },
	}
	ts := httptest.NewServer(s.Handler())
	defer ts.Close()

	resp, err := postOrGet(ts, "GET", "/call/relay?cid=0123456789abcdef&role=caller", true)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 503 {
		t.Fatalf("maintenance : attendu 503, reçu %d", resp.StatusCode)
	}
}
