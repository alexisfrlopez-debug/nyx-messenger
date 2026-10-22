// Tests du transport XMPP (ADR-014) : enveloppes nyx1 + fragmentation,
// SCRAM-SHA-1 (client↔serveur math réel), handshake complet contre un faux
// serveur XMPP (PLAIN et SCRAM), MUC join, envoi/réception groupchat, MAM,
// et intégration journal (handleBody).
package transportxmpp

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/pbkdf2"

	"github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

// ---- 1. enveloppes nyx1 ----

func TestEnvelopeSingleRoundTrip(t *testing.T) {
	payload := []byte(`{"type":"msg","author":"a.onion","seq":1,"body":"Y2Fybw=="}`)
	bodies, err := EncodeBodies("a.onion", 1, payload)
	if err != nil {
		t.Fatal(err)
	}
	if len(bodies) != 1 {
		t.Fatalf("1 corps attendu, %d", len(bodies))
	}
	if !strings.HasPrefix(bodies[0], Prefix) {
		t.Fatalf("corps sans préfixe nyx1 : %.20s", bodies[0])
	}
	env, err := ParseBody(bodies[0])
	if err != nil {
		t.Fatal(err)
	}
	if env.Frag != (Frag{I: 1, N: 1}) {
		t.Fatalf("fragment inattendu %d/%d", env.Frag.I, env.Frag.N)
	}
	data, err := base64.RawURLEncoding.DecodeString(env.Data)
	if err != nil || string(data) != string(payload) {
		t.Fatalf("aller-retour data échoué")
	}
}

func TestEnvelopeFragmentedRoundTrip(t *testing.T) {
	payload := make([]byte, 35000) // base64 ≈ 46 668 > maxSingle → 2 fragments
	for i := range payload {
		payload[i] = byte(i % 251)
	}
	bodies, err := EncodeBodies("ab…onion", 7, payload)
	if err != nil {
		t.Fatal(err)
	}
	if len(bodies) < 2 {
		t.Fatalf("fragmentation attendue, %d corps", len(bodies))
	}
	for _, b := range bodies {
		if len(b) > MaxBodySize {
			t.Fatalf("corps %d octets > MaxBodySize %d", len(b), MaxBodySize)
		}
	}
	asm := NewAssembler()
	var got [][]byte
	for _, b := range bodies {
		env, err := ParseBody(b)
		if err != nil {
			t.Fatal(err)
		}
		out, err := asm.Add(env)
		if err != nil {
			t.Fatal(err)
		}
		got = append(got, out...)
	}
	if len(got) != 1 || string(got[0]) != string(payload) {
		t.Fatalf("réassemblage incorrect (%d charges)", len(got))
	}
}

func TestEnvelopeOversizeRejected(t *testing.T) {
	if _, err := EncodeBodies("a.onion", 1, make([]byte, maxPayloadSize()+1)); err == nil {
		t.Fatal("payload surdimensionné accepté")
	}
}

func TestParseBodyForeign(t *testing.T) {
	if _, err := ParseBody("bonjour le salon"); err != ErrNotNyx {
		t.Fatalf("ErrNotNyx attendu, obtenu %v", err)
	}
	if _, err := ParseBody(Prefix + "!!!pas-du-base64!!!"); err == nil {
		t.Fatal("base64 invalide accepté")
	}
	if _, err := ParseBody(`nyx1 eyJ2Ijo5fQ`); err == nil { // {"v":9}
		t.Fatal("version d'enveloppe inconnue acceptée")
	}
}

// ---- 2. SCRAM-SHA-1 : math client ↔ serveur ----

func TestSCRAMRoundTripAndForgery(t *testing.T) {
	// dérivés « serveur » depuis le mot de passe (formules RFC 5802)
	const password = "s3crèt-du-jid"
	salt := []byte("selselselsel")
	iters := 4096
	salted := pbkdf2.Key([]byte(password), salt, iters, sha1.Size, sha1.New)
	clientKey := hmacSHA1(salted, []byte("Client Key"))
	storedKey := sha1.Sum(clientKey)
	serverKey := hmacSHA1(salted, []byte("Server Key"))

	c, err := newScram("nyx-abcd@hookipa.net", password)
	if err != nil {
		t.Fatal(err)
	}
	first := c.clientFirst()
	if !strings.HasPrefix(first, "n,,n=") {
		t.Fatalf("client-first inattendu : %s", first)
	}
	nonce := first[strings.LastIndex(first, "r=")+2:]
	serverFirst := "r=" + nonce + "SERVERPART,s=" + base64.StdEncoding.EncodeToString(salt) + ",i=4096"
	final, err := c.handleServerFirst(serverFirst)
	if err != nil {
		t.Fatal(err)
	}
	// côté serveur : vérifier la preuve p=
	parts := map[string]string{}
	for _, p := range strings.Split(final, ",") {
		if len(p) > 2 && p[1] == '=' {
			parts[p[:1]] = p[2:]
		}
	}
	if parts["c"] != "biws" {
		t.Fatalf("gs2 final inattendu : %s", parts["c"])
	}
	proof, err := base64.StdEncoding.DecodeString(parts["p"])
	if err != nil {
		t.Fatal(err)
	}
	authMessage := first[len("n,,"):] + "," + serverFirst + "," + "c=biws,r=" + nonce + "SERVERPART"
	clientSig := hmacSHA1(storedKey[:], []byte(authMessage))
	recovered := make([]byte, len(proof))
	for i := range proof {
		recovered[i] = proof[i] ^ clientSig[i]
	}
	if !hmac.Equal(recovered, clientKey) {
		t.Fatal("preuve SCRAM invalide (le serveur rejetterait)")
	}
	// serveur-final : v=ServerSignature ; le client doit l'accepter
	serverSig := hmacSHA1(serverKey, []byte(authMessage))
	if err := c.verifyServerFinal("v=" + base64.StdEncoding.EncodeToString(serverSig)); err != nil {
		t.Fatalf("server-final légitime rejeté: %v", err)
	}
	// un imposteur sans le dérivé échoue
	if err := c.verifyServerFinal("v=" + base64.StdEncoding.EncodeToString(hmacSHA1([]byte("fausse clé"), []byte(authMessage)))); err == nil {
		t.Fatal("server-final falsifié accepté")
	}
	// nonce serveur sans notre nonce : rejet anti-MITM
	c2, _ := newScram("u@h", "p")
	if _, err := c2.handleServerFirst("r=INCONNU,s=" + base64.StdEncoding.EncodeToString(salt) + ",i=4096"); err == nil {
		t.Fatal("nonce serveur sans notre nonce accepté")
	}
}

// ---- 3. faux serveur XMPP ----

const (
	testHost = "xmpp.test"
	testJID  = "nyx-0123456789abcdef@" + testHost
	testPass = "mot-de-passe-de-test"
	testMUC  = "nyx-bus@conference." + testHost
	testNick = "nyx-0123456789abcdef"
)

// ---- 4. handshake complet + messages ----

func TestClientEndToEndPLAIN(t *testing.T) {
	fs := newFakeServer(t, "<mechanism>PLAIN</mechanism>")
	opt := Options{Host: testHost, Addr: fs.addr, JID: testJID, Password: testPass,
		MUC: testMUC, Nick: testNick, TLSEnforce: true, AllowNoTLS: true, Timeout: 5 * time.Second}
	cli, err := Dial(opt)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer cli.Close()
	if err := cli.JoinMUC(); err != nil {
		t.Fatalf("join: %v", err)
	}
	if _, err := cli.QueryMAM(10); err != nil {
		t.Fatalf("mam: %v", err)
	}
	cli.ReadPump()
	if err := cli.SendGroupChat("nyx1 ping"); err != nil {
		t.Fatalf("send: %v", err)
	}
	select {
	case body := <-cli.Out():
		if body != "nyx1 ping" {
			t.Fatalf("corps relais inattendu : %q", body)
		}
	case err := <-cli.ErrC():
		t.Fatalf("erreur lecture: %v", err)
	case <-time.After(5 * time.Second):
		t.Fatal("délai dépassé (relais)")
	}
}

func TestClientEndToEndSCRAM(t *testing.T) {
	fs := newFakeServer(t, "<mechanism>SCRAM-SHA-1</mechanism>")
	opt := Options{Host: testHost, Addr: fs.addr, JID: testJID, Password: testPass,
		MUC: testMUC, Nick: testNick, TLSEnforce: false, Timeout: 5 * time.Second}
	cli, err := Dial(opt)
	if err != nil {
		t.Fatalf("dial SCRAM: %v", err)
	}
	defer cli.Close()
	if err := cli.JoinMUC(); err != nil {
		t.Fatalf("join: %v", err)
	}
}

func TestClientBadPasswordRejected(t *testing.T) {
	fs := newFakeServer(t, "<mechanism>PLAIN</mechanism>")
	opt := Options{Host: testHost, Addr: fs.addr, JID: testJID, Password: "MAUVAIS",
		MUC: testMUC, Nick: testNick, TLSEnforce: false, Timeout: 5 * time.Second}
	if _, err := Dial(opt); err != ErrAuthFailed {
		t.Fatalf("ErrAuthFailed attendu, obtenu %v", err)
	}
}

func TestClientTLSRequired(t *testing.T) {
	fs := newFakeServer(t, "<mechanism>PLAIN</mechanism>") // pas de starttls offert
	opt := Options{Host: testHost, Addr: fs.addr, JID: testJID, Password: testPass,
		MUC: testMUC, Nick: testNick, TLSEnforce: true, Timeout: 5 * time.Second}
	if _, err := Dial(opt); err != ErrTLSRequired {
		t.Fatalf("ErrTLSRequired attendu, obtenu %v", err)
	}
}

// ---- 5. intégration journal via handleBody ----

func TestPumpHandleBodyIntegratesSignedEvent(t *testing.T) {
	dir := t.TempDir()
	store, err := journal.OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	pub, priv := generateKeyPair(t)
	author, err := journal.PubKeyToOnion(pub)
	if err != nil {
		t.Fatal(err)
	}
	ev := &journal.Event{Type: "msg", Author: author, Seq: 1,
		TS: time.Now().Unix(), To: "dest.onion",
		Body: base64.StdEncoding.EncodeToString([]byte("message chiffré"))}
	ev.Sign(priv)
	data, err := ev.Encode()
	if err != nil {
		t.Fatal(err)
	}
	bodies, err := EncodeBodies(author, ev.Seq, data)
	if err != nil {
		t.Fatal(err)
	}
	kick := make(chan struct{}, 1)
	p := NewPump(store, "autre.onion", kick, Options{}, time.Second)
	for _, b := range bodies {
		p.handleBody(b)
	}
	if store.Count() != 1 {
		t.Fatalf("événement non intégré (count=%d)", store.Count())
	}
	if store.LastSeq(author) != 1 {
		t.Fatalf("LastSeq=%d, attendu 1", store.LastSeq(author))
	}

	// rejeu (écho MAM) : idempotent, pas d'erreur
	for _, b := range bodies {
		p.handleBody(b)
	}
	if store.Count() != 1 {
		t.Fatalf("rejeu dupliqué (count=%d)", store.Count())
	}

	// bruit tiers : ignoré silencieusement
	p.handleBody("discussion publique du salon")
	if store.Count() != 1 {
		t.Fatal("bruit tiers intégré")
	}
}

func TestPumpRejectsForgedEvent(t *testing.T) {
	dir := t.TempDir()
	store, _ := journal.OpenStore(dir)
	pub, _ := generateKeyPair(t)
	author, _ := journal.PubKeyToOnion(pub)
	_, forged := generateKeyPair(t) // autre clé : signature invalide
	ev := &journal.Event{Type: "msg", Author: author, Seq: 1,
		TS: time.Now().Unix(), To: "dest.onion", Body: "eHg="}
	ev.Sign(forged)
	data, _ := ev.Encode()
	bodies, err := EncodeBodies(author, 1, data)
	if err != nil {
		t.Fatal(err)
	}
	p := NewPump(store, "autre.onion", nil, Options{}, time.Second)
	p.handleBody(bodies[0])
	if store.Count() != 0 {
		t.Fatal("événement falsifié intégré — la signature doit faire foi")
	}
}
