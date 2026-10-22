package turn

// Tests de l'allocation RFC 6062 contre un FAUX relais TURN TCP local —
// même démarche que fakexmpp_test.go (transportxmpp) : le dialecte est
// validé de bout en bout sur le loopback, sans réseau réel.

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"hash/crc32"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

// fakeRelay — relais TURN TCP minimal : ALLOCATE/CONNECT signés (short-term),
// ConnectionAttempt + connexion peer entrante DEPUIS l'adresse relayée
// (groupe SO_REUSEPORT symétrique au client), CONNECTION-BIND vérifié,
// relais d'octets peer↔client.
type fakeRelay struct {
	cred      string       // clé attendue sur les requêtes (short-term)
	respKey   string       // si non vide : clé de signature des RÉPONSES (test anti-MITM)
	relayed   net.Listener // « adresse relayée » simulée
	relayTune func(*net.Dialer)
	ctrl      net.Conn // connexion de contrôle du client
	pendingTx [12]byte // txid de la dernière ind. ConnectionAttempt envoyée
	t         *testing.T
}

func newFakeRelay(t *testing.T, cred string) *fakeRelay {
	t.Helper()
	ln, tune, err := newCoupledListener()
	if err != nil {
		t.Skipf("couplage inbound indisponible ici : %v", err)
	}
	return &fakeRelay{cred: cred, relayed: ln, relayTune: tune, t: t}
}

// addrCtrl — lance l'écoute de contrôle et retourne l'URL turn:// à passer
// à DialControl (TLS écarté : le dialecte est le test, le TLS est stdlib).
func (r *fakeRelay) addrCtrl(t *testing.T) string {
	t.Helper()
	ctrl, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ctrl.Close() })
	go func() {
		for {
			c, err := ctrl.Accept()
			if err != nil {
				return
			}
			go r.serveControl(c)
		}
	}()
	return "turn://" + ctrl.Addr().String() + "?transport=tcp"
}

func (r *fakeRelay) respond(m *Message, class Class, key string, attrs ...Attr) {
	signKey := key
	if r.respKey != "" {
		signKey = r.respKey
	}
	resp := &Message{Method: m.Method, Class: class, TxID: m.TxID, Attrs: attrs}
	raw, err := resp.Encode(signKey, signKey != "")
	if err != nil {
		r.t.Errorf("relais encodage réponse : %v", err)
		return
	}
	if _, err := r.ctrl.Write(raw); err != nil {
		r.t.Errorf("relais écriture réponse : %v", err)
	}
}

func (r *fakeRelay) serveControl(c net.Conn) {
	r.ctrl = c
	defer c.Close()
	for {
		b, err := readFrame(c)
		if err != nil {
			return
		}
		m, err := Decode(b)
		if err != nil {
			return
		}
		if m.Class != ClassRequest {
			continue
		}
		switch m.Method {
		case MethodAllocate:
			if !VerifyIntegrity(m, b, r.cred) {
				// 401 sans intégrité : le client doit afficher l'erreur.
				e := &Message{Method: m.Method, Class: ClassErrorResp, TxID: m.TxID}
				e.Attrs = append(e.Attrs, Attr{Type: AttrErrorCode, Value: []byte{0, 0, 0x04, 0x01, 'u', 'n', 'a', 'u', 't', 'h'}})
				raw, _ := e.Encode("", false)
				c.Write(raw)
				continue
			}
			lv := m.Get(AttrLifetime)
			if len(lv) != 4 {
				lv = []byte{0, 0, 0x02, 0x58} // 600 s par défaut
			}
			// 127.0.0.1 = adresse réellement atteignable du listener relayé (loopback) —
			// un relais réel publie son IP publique ; To4() de 0.0.0.0 est nil (IPv4z).
			xa, _ := EncodeXorAddr(net.ParseIP("127.0.0.1"), r.relayed.Addr().(*net.TCPAddr).Port, m.TxID)
			r.respond(m, ClassSuccessResp, r.cred,
				Attr{Type: AttrXorRelayedAddress, Value: xa},
				Attr{Type: AttrLifetime, Value: lv})
		case MethodConnect:
			r.respond(m, ClassSuccessResp, r.cred)
		case MethodRefresh:
			lv := m.Get(AttrLifetime)
			if len(lv) != 4 {
				lv = []byte{0, 0, 0, 0}
			}
			r.respond(m, ClassSuccessResp, r.cred, Attr{Type: AttrLifetime, Value: lv})
		default:
			r.respond(m, ClassErrorResp, r.cred, Attr{Type: AttrErrorCode, Value: []byte{0, 0, 0x04, 0x00}})
		}
	}
}

// acceptPeer — le « pair » du test s'est connecté à l'adresse relayée :
// indication ConnectionAttempt, puis connexion ENTRANTE vers le client
// DEPUIS l'adresse relayée, CONNECTION-BIND vérifié, puis relais d'octets.
func (r *fakeRelay) acceptPeer(peerAddr net.IP, peerPort int) {
	peer, err := r.relayed.Accept()
	if err != nil {
		return
	}
	defer peer.Close()
	ind := &Message{Method: MethodConnectionAttempt, Class: ClassIndication}
	if _, err := rand.Read(ind.TxID[:]); err != nil {
		r.t.Errorf("txid indication : %v", err)
		return
	}
	r.pendingTx = ind.TxID
	xa, _ := EncodeXorAddr(peerAddr, peerPort, ind.TxID)
	ind.Attrs = append(ind.Attrs, Attr{Type: AttrXorPeerAddress, Value: xa})
	raw, _ := ind.Encode("", false)
	if _, err := r.ctrl.Write(raw); err != nil {
		r.t.Errorf("ConnectionAttempt : %v", err)
		return
	}
	// Connexion ENTRANTE vers le client, source = adresse relayée (§4.4).
	d := net.Dialer{Timeout: 5 * time.Second}
	r.relayTune(&d)
	out, err := d.Dial("tcp", r.ctrl.RemoteAddr().String())
	if err != nil {
		r.t.Errorf("dial entrant vers le client : %v", err)
		return
	}
	defer out.Close()
	// CONNECTION-BIND attendu : même txid que l'indication, signé.
	b, err := readFrame(out)
	if err != nil {
		r.t.Errorf("lecture CONNECTION-BIND : %v", err)
		return
	}
	bm, err := Decode(b)
	if err != nil {
		r.t.Errorf("décode CONNECTION-BIND : %v", err)
		return
	}
	if bm.TxID != r.pendingTx {
		r.t.Errorf("CONNECTION-BIND txid %v != indication %v", bm.TxID, r.pendingTx)
		return
	}
	if !VerifyIntegrity(bm, b, r.cred) {
		r.t.Errorf("CONNECTION-BIND sans intégrité valide")
		return
	}
	r.respondBind(out, bm)
	go io.Copy(out, peer) // relais d'octets peer→client
	io.Copy(peer, out)    // relais d'octets client→peer
}

func (r *fakeRelay) respondBind(out net.Conn, req *Message) {
	resp := &Message{Method: MethodConnectionBind, Class: ClassSuccessResp, TxID: req.TxID}
	raw, _ := resp.Encode(r.cred, true)
	out.Write(raw)
}

// ————— test de bout en bout —————

func TestTurnAllocateConnectRelayEndToEnd(t *testing.T) {
	const cred = "ephemeral-credential-0123456789"
	relay := newFakeRelay(t, cred)
	url := relay.addrCtrl(t)

	c, err := DialControl(url, "", 5*time.Second)
	if err != nil {
		t.Fatalf("DialControl : %v", err)
	}
	alloc, err := c.Allocate("nyx-user-1", cred, 600*time.Second)
	if err != nil {
		t.Fatalf("Allocate : %v", err)
	}
	if alloc.Lifetime != 600*time.Second {
		t.Errorf("lifetime = %v, attendu 600s", alloc.Lifetime)
	}
	// L'adresse relayée doit être exactement celle du listener simulé.
	ra := relay.relayed.Addr().(*net.TCPAddr)
	if alloc.RelayAddr() != net.JoinHostPort("127.0.0.1", strconv.Itoa(ra.Port)) {
		t.Errorf("RelayAddr = %s, attendu %v", alloc.RelayAddr(), relay.relayed.Addr())
	}

	// Le pair cible (fictif) — le faux relais l'encode dans l'indication.
	go relay.acceptPeer(net.ParseIP("198.51.100.7"), 4444)

	// Le « peer » réel du test se connecte à l'adresse relayée et attend
	// l'écho : le canal client↔relais est établi par Connect() ci-dessous.
	peerDone := make(chan error, 1)
	go func() {
		p, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(ra.Port)))
		if err != nil {
			peerDone <- err
			return
		}
		defer p.Close()
		if _, err := p.Write([]byte("hello-from-peer")); err != nil {
			peerDone <- err
			return
		}
		// Le réplica répond par le canal relais (le client renvoie le pong).
		buf := make([]byte, len("pong-from-replica"))
		if _, err := io.ReadFull(p, buf); err != nil {
			peerDone <- err
			return
		}
		if string(buf) != "pong-from-replica" {
			peerDone <- io.ErrUnexpectedEOF
			return
		}
		peerDone <- nil
	}()

	app, err := alloc.Connect("198.51.100.7:4444", 10*time.Second)
	if err != nil {
		t.Fatalf("Connect : %v", err)
	}
	defer app.Close()
	buf := make([]byte, len("hello-from-peer"))
	app.SetReadDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.ReadFull(app, buf); err != nil {
		t.Fatalf("lecture canal app : %v", err)
	}
	if string(buf) != "hello-from-peer" {
		t.Errorf("canal app : %q", buf)
	}
	// Sens retour : le relais copie client→peer.
	if _, err := app.Write([]byte("pong-from-replica")); err != nil {
		t.Fatalf("écriture canal app : %v", err)
	}
	select {
	case err := <-peerDone:
		if err != nil && err != io.EOF {
			t.Errorf("côté peer : %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("peer sans réponse (délai dépassé)")
	}
	_ = alloc.Refresh(300 * time.Second) // doit renvoyer LIFETIME 300
	if alloc.Lifetime != 300*time.Second {
		t.Errorf("refresh lifetime = %v", alloc.Lifetime)
	}
}

// ————— erreurs explicites —————

func TestTurnAllocateBadCredentialRejected(t *testing.T) {
	// Le réplica signe avec un credential DIFFÉRENT de celui attendu par le
	// relais → 401 sans intégrité, code affiché tel quel (aucun repli).
	relay := newFakeRelay(t, "relay-expects-this")
	url := relay.addrCtrl(t)
	c, err := DialControl(url, "", 5*time.Second)
	if err != nil {
		t.Fatalf("DialControl : %v", err)
	}
	if _, err := c.Allocate("u", "client-uses-that", 0); err == nil {
		t.Fatal("ALLOCATE accepté avec un credential invalide")
	} else if !strings.Contains(err.Error(), "401") {
		t.Errorf("erreur = %v, attendu code 401 explicite", err)
	}
}

func TestTurnResponseBadIntegrity(t *testing.T) {
	// Le relais VÉRIFIE la requête puis signe ses réponses avec une AUTRE clé
	// (simulation MITM / relais compromis) → le client doit rejeter.
	relay := newFakeRelay(t, "shared-credential")
	relay.respKey = "evil-key"
	url := relay.addrCtrl(t)
	c, err := DialControl(url, "", 5*time.Second)
	if err != nil {
		t.Fatalf("DialControl : %v", err)
	}
	if _, err := c.Allocate("u", "shared-credential", 0); err == nil {
		t.Fatal("réponse mal signée acceptée")
	} else if !strings.Contains(err.Error(), "intégrité") {
		t.Errorf("erreur = %v, attendu intégrité invalide", err)
	}
}

func TestTurnConnectWithoutInboundCapability(t *testing.T) {
	// Plateformes sans SO_REUSEPORT : Connect échoue explicitement (ErrNoInbound).
	relay := newFakeRelay(t, "cred")
	url := relay.addrCtrl(t)
	c, err := DialControl(url, "", 5*time.Second)
	if err != nil {
		t.Fatalf("DialControl : %v", err)
	}
	alloc, err := c.Allocate("u", "cred", 0)
	if err != nil {
		t.Fatalf("Allocate : %v", err)
	}
	saved := alloc.ctrl.listener
	alloc.ctrl.listener = nil // simule l'absence de couplage
	if _, err := alloc.Connect("198.51.100.7:4444", time.Second); err == nil {
		t.Fatal("Connect accepté sans capacité inbound")
	}
	alloc.ctrl.listener = saved
}

// ————— parsing d'URL —————

func TestParseRelayURL(t *testing.T) {
	cases := []struct {
		url       string
		host      string
		port      int
		tls       bool
		wantError bool
	}{
		{"turns://turn.cloudflare.com:5349?transport=tcp", "turn.cloudflare.com", 5349, true, false},
		{"turn://turn.cloudflare.com:3478?transport=tcp", "turn.cloudflare.com", 3478, false, false},
		{"turns://turn.cloudflare.com?transport=tcp", "turn.cloudflare.com", 5349, true, false},
		{"turn://turn.cloudflare.com?transport=tcp", "turn.cloudflare.com", 3478, false, false},
		{"turns://[2606:4700::1]:5349?transport=tcp", "2606:4700::1", 5349, true, false},
		{"turn://turn.cloudflare.com:3478?transport=udp", "", 0, false, true}, // UDP exclu
		{"turn://turn.cloudflare.com:3478", "", 0, false, true},               // transport absent
		{"htt" + "p://turn.cloudflare.com:5349", "", 0, false, true},          // scheme
		{"turns://[bad:5349?transport=tcp", "", 0, false, true},               // IPv6
	}
	for _, c := range cases {
		host, port, tls, err := parseRelayURL(c.url)
		if c.wantError {
			if err == nil {
				t.Errorf("%q : accepté (attendu erreur)", c.url)
			}
			continue
		}
		if err != nil || host != c.host || port != c.port || tls != c.tls {
			t.Errorf("%q → (%q,%d,%v,%v), attendu (%q,%d,%v,nil)", c.url, host, port, tls, err, c.host, c.port, c.tls)
		}
	}
}

// ————— unitaires STUN —————

func TestStunEncodeDecodeRoundTrip(t *testing.T) {
	m := &Message{Method: MethodAllocate, Class: ClassRequest}
	rand.Read(m.TxID[:])
	m.Attrs = append(m.Attrs,
		Attr{Type: AttrRequestedTransport, Value: []byte{ProtoTCP, 0, 0, 0}},
		Attr{Type: AttrUsername, Value: []byte("nyx-user-1")},
	)
	raw, err := m.Encode("secret", true)
	if err != nil {
		t.Fatal(err)
	}
	d, err := Decode(raw)
	if err != nil {
		t.Fatal(err)
	}
	if d.Method != MethodAllocate || d.Class != ClassRequest || d.TxID != m.TxID {
		t.Fatalf("en-tête : %+v", d)
	}
	if u := d.Get(AttrUsername); string(u) != "nyx-user-1" {
		t.Errorf("username = %q", u)
	}
	if rt := d.Get(AttrRequestedTransport); len(rt) != 4 || rt[0] != ProtoTCP {
		t.Errorf("requested-transport = %v", rt)
	}
	if !VerifyIntegrity(d, raw, "secret") {
		t.Error("MESSAGE-INTEGRITY invalide avec la bonne clé")
	}
	if VerifyIntegrity(d, raw, "wrong") {
		t.Error("MESSAGE-INTEGRITY valide avec une mauvaise clé")
	}
}

func TestStunTypeCoding(t *testing.T) {
	// RFC 5389 §5 : ALLOCATE request = 0x0003, success = 0x0103 ; CONNECT
	// request = 0x000A ; ConnectionAttempt indication = 0x001C.
	cases := []struct {
		m Method
		c Class
		w uint16
	}{
		{MethodAllocate, ClassRequest, 0x0003},
		{MethodAllocate, ClassSuccessResp, 0x0103},
		{MethodRefresh, ClassRequest, 0x0004},
		{MethodConnect, ClassRequest, 0x000A},
		{MethodConnectionBind, ClassRequest, 0x000B},
		{MethodConnectionAttempt, ClassIndication, 0x001C},
	}
	for _, c := range cases {
		if got := encodeType(c.m, c.c); got != c.w {
			t.Errorf("encodeType(%v,%v) = 0x%04x, attendu 0x%04x", c.m, c.c, got, c.w)
			continue
		}
		if m, cl := decodeType(c.w); m != c.m || cl != c.c {
			t.Errorf("decodeType(0x%04x) = (%v,%v)", c.w, m, cl)
		}
	}
}

func TestStunXorAddrRoundTrip(t *testing.T) {
	var tx [12]byte
	rand.Read(tx[:])
	for _, s := range []string{"203.0.113.7:54321", "127.0.0.1:80", "198.51.100.255:65535"} {
		a, _ := net.ResolveTCPAddr("tcp4", s)
		v, err := EncodeXorAddr(a.IP, a.Port, tx)
		if err != nil {
			t.Fatal(err)
		}
		ip, port, err := DecodeXorAddr(v, tx)
		if err != nil {
			t.Fatal(err)
		}
		if !ip.Equal(a.IP) || port != a.Port {
			t.Errorf("%s → %v:%d", s, ip, port)
		}
	}
	// IPv6.
	ip6 := net.ParseIP("2606:4700::1111")
	v, err := EncodeXorAddr(ip6, 5349, tx)
	if err != nil {
		t.Fatal(err)
	}
	if len(v) != 20 {
		t.Fatalf("IPv6 : %d octets", len(v))
	}
	ip, port, err := DecodeXorAddr(v, tx)
	if err != nil || !ip.Equal(ip6) || port != 5349 {
		t.Errorf("IPv6 → %v:%d (%v)", ip, port, err)
	}
}

func TestStunErrorCode(t *testing.T) {
	// Classe 4 + numéro 01 → 401.
	v := []byte{0, 0, 0x04, 0x01}
	num, reason, err := ParseErrorCode(append(v, []byte("unauthorized")...))
	if err != nil || num != 401 || reason != "unauthorized" {
		t.Errorf("= (%d,%q,%v)", num, reason, err)
	}
}

func TestStunTruncatedFramesRejected(t *testing.T) {
	m := &Message{Method: MethodConnect, Class: ClassRequest}
	rand.Read(m.TxID[:])
	m.Attrs = append(m.Attrs, Attr{Type: AttrUsername, Value: []byte("abcd")})
	raw, _ := m.Encode("", false)
	for _, bad := range [][]byte{raw[:10], raw[:19], append(raw, 0xFF, 0xFF, 0xFF, 0xFF)} {
		if _, err := Decode(bad); err == nil && len(bad) < len(raw) {
			t.Errorf("trame tronquée %d octets acceptée", len(bad))
		}
	}
	// Cookie magique invalide.
	bad := append([]byte(nil), raw...)
	bad[4] = 0
	if _, err := Decode(bad); err == nil {
		t.Error("cookie invalide accepté")
	}
}

func TestStunFingerprintRoundTrip(t *testing.T) {
	m := &Message{Method: MethodAllocate, Class: ClassRequest}
	rand.Read(m.TxID[:])
	raw, _ := m.Encode("k", true)
	d, err := Decode(raw)
	if err != nil {
		t.Fatal(err)
	}
	if d.Get(AttrFingerprint) == nil {
		t.Fatal("FINGERPRINT absent")
	}
	// Recalcul : CRC32 du message jusqu'à FINGERPRINT exclus, XOR « STUN ».
	if !bytes.Equal(d.Get(AttrFingerprint), nil) {
		if !verifyFingerprint(d, raw) {
			t.Error("FINGERPRINT invalide")
		}
	}
}

// verifyFingerprint — recalcule et compare (test-only, hors API publique).
func verifyFingerprint(m *Message, raw []byte) bool {
	want := m.Get(AttrFingerprint)
	if len(want) != 4 {
		return false
	}
	crc := crc32.ChecksumIEEE(raw[:len(raw)-8]) ^ 0x5354554E
	return binary.BigEndian.Uint32(want) == crc
}

// ————— worker de maintien (ADR-014 phase 2, §6.5) —————

func TestPickTCPURL(t *testing.T) {
	ice := []ICEServer{
		{URLs: []string{
			"stun:stun.cloudflare.com:3478",
			"turn:turn.cloudflare.com:3478?transport=udp",
			"turn:turn.cloudflare.com:3478?transport=tcp",
			"turns:turn.cloudflare.com:5349?transport=tcp",
		}, Username: "u", Credential: "c"},
	}
	url, user, cred := PickTCPURL(ice)
	if url != "turns:turn.cloudflare.com:5349?transport=tcp" || user != "u" || cred != "c" {
		t.Errorf("URL choisie = %q (attendu turns:5349 tcp)", url)
	}
	// Repli : pas de turns → turn/TCP.
	ice[0].URLs = []string{"turn:turn.cloudflare.com:3478?transport=udp", "turn:turn.cloudflare.com:3478?transport=tcp"}
	if url, _, _ = PickTCPURL(ice); url != "turn:turn.cloudflare.com:3478?transport=tcp" {
		t.Errorf("repli = %q (attendu turn:3478 tcp)", url)
	}
	// UDP seul → vide (Tor TCP only).
	ice[0].URLs = []string{"turn:turn.cloudflare.com:3478?transport=udp"}
	if url, _, _ = PickTCPURL(ice); url != "" {
		t.Errorf("UDP accepté : %q", url)
	}
}

func TestWorkerMaintainRelay(t *testing.T) {
	relay := newFakeRelay(t, "worker-cred")
	url := relay.addrCtrl(t)
	// L'API Cloudflare est court-circuitée (hook iceGen) : le faux relais
	// sert de relais TURN ; on valide allocation → onRelay → refresh → annulation.
	done := make(chan RelayInfo, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	saved := iceGen
	iceGen = func(keyID, token string, ttl int) ([]ICEServer, error) {
		return []ICEServer{{URLs: []string{url}, Username: "u", Credential: "worker-cred"}}, nil
	}
	defer func() { iceGen = saved }()
	go MaintainRelay(ctx, RelayParams{RelayURL: url, Lifetime: 2 * time.Second},
		func(info RelayInfo) {
			select {
			case done <- info:
			default:
			}
		})
	select {
	case info := <-done:
		if info.RelayAddr == "" || info.Lifetime != 2*time.Second {
			t.Errorf("RelayInfo = %+v", info)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("onRelay jamais appelé")
	}
	cancel()
}
