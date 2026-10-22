// faux serveur XMPP pour les tests du client transportxmpp (processus en
// mémoire, un seul client) : stream, SASL PLAIN + SCRAM-SHA-1 vérifié
// mathématiquement, bind, MUC join, relais groupchat, iq MAM.
package transportxmpp

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/xml"
	"fmt"
	"net"
	"strings"
	"testing"

	"golang.org/x/crypto/pbkdf2"
)

// generateKeyPair — paire ed25519 de test.
func generateKeyPair(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return pub, priv
}

const (
	fakeScramSalt  = "selselselsel"
	fakeScramIters = 4096
)

// fakeServer — le strict nécessaire côté serveur.
type fakeServer struct {
	t        *testing.T
	ln       net.Listener
	addr     string
	mechs    string // contenu de <mechanisms>…</mechanisms>
	received []string

	saslDone        bool
	scrmClientFirst string // bare
	scrmServerFirst string
	scrmSalted      []byte
}

func newFakeServer(t *testing.T, mechs string) *fakeServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	fs := &fakeServer{t: t, ln: ln, addr: ln.Addr().String(), mechs: mechs}
	go fs.serve()
	t.Cleanup(func() { ln.Close() })
	return fs
}

func (fs *fakeServer) serve() {
	conn, err := fs.ln.Accept()
	if err != nil {
		return
	}
	defer conn.Close()
	dec := xml.NewDecoder(conn)
	for {
		tok, err := dec.Token()
		if err != nil {
			return
		}
		se, ok := tok.(xml.StartElement)
		if !ok {
			continue
		}
		switch {
		case se.Name.Local == "stream": // entête de stream (initial ou post-auth)
			features := "<mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>" + fs.mechs + "</mechanisms>"
			if fs.saslDone {
				features = "<bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'/>"
			}
			fmt.Fprintf(conn, "<?xml version='1.0'?>"+
				"<stream:stream xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams' from='%s'>"+
				"<stream:features>%s</stream:features>", testHost, features)
		case se.Name.Local == "auth":
			if !fs.handleAuth(conn, dec, se) {
				return
			}
		case se.Name.Local == "response": // client-final SCRAM
			if !fs.handleScramFinal(conn, dec) {
				return
			}
		case se.Name.Local == "iq":
			if !fs.handleIQ(conn, dec, se) {
				return
			}
		case se.Name.Local == "presence":
			consumeElement(dec)
			fmt.Fprintf(conn, "<presence from='%s/%s' to='%s/nyx'>"+
				"<x xmlns='http://jabber.org/protocol/muc#user'><status code='110'/></x></presence>",
				testMUC, testNick, testJID)
		case se.Name.Local == "message":
			body := extractBody(dec)
			fs.received = append(fs.received, body)
			// relais immédiat sous le nick d'un AUTRE occupant (écho self exclu)
			fmt.Fprintf(conn, "<message from='%s/autre-occupant' type='groupchat'><body>%s</body></message>",
				testMUC, xmlEscape(body))
		default:
			consumeElement(dec)
		}
	}
}

// handleAuth — PLAIN (vérifie le mot de passe) ou challenge SCRAM.
func (fs *fakeServer) handleAuth(conn net.Conn, dec *xml.Decoder, se xml.StartElement) bool {
	payload := readText(dec)
	switch attr(se, "mechanism") {
	case "PLAIN":
		raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(payload))
		if err != nil {
			fmt.Fprint(conn, "<failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
			return true
		}
		parts := strings.Split(string(raw), "\x00")
		if len(parts) != 3 || parts[1] != testJID || parts[2] != testPass {
			fmt.Fprint(conn, "<failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
			return true
		}
		fs.saslDone = true
		fmt.Fprint(conn, "<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
		return true
	case "SCRAM-SHA-1":
		raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(payload))
		if err != nil {
			fmt.Fprint(conn, "<failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
			return true
		}
		first := string(raw) // "n,,n=…,r=…"
		fs.scrmClientFirst = strings.TrimPrefix(first, "n,,")
		cnonce := first[strings.LastIndex(first, "r=")+2:]
		fs.scrmServerFirst = fmt.Sprintf("r=%sSERVEUR,s=%s,i=%d",
			cnonce, base64.StdEncoding.EncodeToString([]byte(fakeScramSalt)), fakeScramIters)
		fs.scrmSalted = pbkdf2.Key([]byte(testPass), []byte(fakeScramSalt), fakeScramIters, sha1.Size, sha1.New)
		fmt.Fprintf(conn, "<challenge xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>%s</challenge>",
			base64.StdEncoding.EncodeToString([]byte(fs.scrmServerFirst)))
		return true
	default:
		fmt.Fprint(conn, "<failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
		return true
	}
}

// handleScramFinal — vérifie la preuve du client, émet v=ServerSignature.
func (fs *fakeServer) handleScramFinal(conn net.Conn, dec *xml.Decoder) bool {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(readText(dec)))
	if err != nil {
		fmt.Fprint(conn, "<failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
		return true
	}
	final := string(raw) // "c=biws,r=<nonce>,p=<proof>"
	proofB64 := final[strings.LastIndex(final, ",p=")+3:]
	clientFinalNoProof := final[:strings.LastIndex(final, ",p=")]
	proof, err := base64.StdEncoding.DecodeString(proofB64)
	if err != nil {
		fmt.Fprint(conn, "<failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
		return true
	}
	clientKey := hmacSHA1(fs.scrmSalted, []byte("Client Key"))
	storedKey := sha1.Sum(clientKey)
	authMessage := fs.scrmClientFirst + "," + fs.scrmServerFirst + "," + clientFinalNoProof
	clientSig := hmacSHA1(storedKey[:], []byte(authMessage))
	recovered := make([]byte, len(proof))
	for i := range proof {
		recovered[i] = proof[i] ^ clientSig[i]
	}
	if !hmac.Equal(recovered, clientKey) {
		fmt.Fprint(conn, "<failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
		return true
	}
	serverSig := hmacSHA1(hmacSHA1(fs.scrmSalted, []byte("Server Key")), []byte(authMessage))
	fs.saslDone = true
	// server-final-message = "v=" + base64(ServerSignature) — le tout encodé
	// base64 par le cadrage SASL (RFC 5802 §7).
	serverFinal := "v=" + base64.StdEncoding.EncodeToString(serverSig)
	fmt.Fprintf(conn, "<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>%s</success>",
		base64.StdEncoding.EncodeToString([]byte(serverFinal)))
	return true
}

// handleIQ — bind → jid ; MAM → simple résultat.
func (fs *fakeServer) handleIQ(conn net.Conn, dec *xml.Decoder, se xml.StartElement) bool {
	id := attr(se, "id")
	isBind := false
	depth := 1
	for depth > 0 { // consomme l'iQ en repérant <bind>
		tok, err := dec.Token()
		if err != nil {
			return false
		}
		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			if t.Name.Local == "bind" {
				isBind = true
			}
		case xml.EndElement:
			depth--
		}
	}
	switch {
	case isBind:
		fmt.Fprintf(conn, "<iq id='%s' type='result'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'>"+
			"<jid>%s/nyx</jid></bind></iq>", id, testJID)
	default: // requête MAM : accusé de réception simple
		fmt.Fprintf(conn, "<iq id='%s' type='result'/>", id)
	}
	return true
}

// ---- petits utilitaires de lecture côté faux serveur ----

// readText — consomme l'élément ouvert, renvoie son texte.
func readText(d *xml.Decoder) string {
	var sb strings.Builder
	depth := 1
	for depth > 0 {
		tok, err := d.Token()
		if err != nil {
			return sb.String()
		}
		switch t := tok.(type) {
		case xml.CharData:
			sb.Write(t)
		case xml.StartElement:
			depth++
		case xml.EndElement:
			depth--
		}
	}
	return sb.String()
}

// consumeElement — saute l'élément ouvert.
func consumeElement(d *xml.Decoder) {
	depth := 1
	for depth > 0 {
		tok, err := d.Token()
		if err != nil {
			return
		}
		switch tok.(type) {
		case xml.StartElement:
			depth++
		case xml.EndElement:
			depth--
		}
	}
}

// extractBody — consomme un <message>, renvoie le texte du premier <body>.
func extractBody(d *xml.Decoder) string {
	depth := 1
	var body strings.Builder
	inBody := false
	for depth > 0 {
		tok, err := d.Token()
		if err != nil {
			return body.String()
		}
		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			if t.Name.Local == "body" {
				inBody = true
			}
		case xml.CharData:
			if inBody {
				body.Write(t)
			}
		case xml.EndElement:
			if t.Name.Local == "body" {
				inBody = false
			}
			depth--
		}
	}
	return strings.TrimSpace(body.String())
}

func xmlEscape(s string) string {
	r := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", "'", "&#39;")
	return r.Replace(s)
}
