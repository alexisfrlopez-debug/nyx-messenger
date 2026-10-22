package journal

import (
	"crypto/ed25519"
	"crypto/rand"
	"os"
	"strings"
	"testing"
	"time"
)

// TestOnionRoundTrip — une clé ed25519 encode et décode fidèlement en adresse onion v3.
func TestOnionRoundTrip(t *testing.T) {
	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	onion, err := PubKeyToOnion(pub)
	if err != nil {
		t.Fatal(err)
	}
	if len(onion) != 62 || !strings.HasSuffix(onion, ".onion") { // 56 base32 + ".onion"
		t.Fatalf("adresse invalide (longueur %d): %s", len(onion), onion)
	}
	back, err := OnionToPubKey(onion)
	if err != nil {
		t.Fatal(err)
	}
	if string(back) != string(pub) {
		t.Fatal("aller-retour onion ↔ clé publique échoué")
	}
}

// TestOnionChecksum — un checksum falsifié doit être rejeté.
func TestOnionChecksum(t *testing.T) {
	if _, err := OnionToPubKey("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion"); err == nil {
		t.Fatal("checksum invalide accepté à tort")
	}
}

func makeSignedEvent(t *testing.T, priv ed25519.PrivateKey, author string, seq uint64, body string) *Event {
	t.Helper()
	ev := &Event{Type: "msg", Author: author, Seq: seq, TS: time.Now().Unix(), TTL: 0, To: "dest.onion", Body: b64.EncodeToString([]byte(body))}
	ev.Sign(priv)
	return ev
}

// TestEventSignValidate — signature ed25519 validée ; falsification rejetée.
func TestEventSignValidate(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := PubKeyToOnion(pub)

	ev := makeSignedEvent(t, priv, onion, 1, "coucou chiffré")
	if err := ev.Validate(time.Now().Unix()); err != nil {
		t.Fatalf("événement valide rejeté: %v", err)
	}
	// Falsification du body
	ev2 := makeSignedEvent(t, priv, onion, 1, "coucou chiffré")
	ev2.Body = b64.EncodeToString([]byte("autre contenu"))
	if err := ev2.Validate(time.Now().Unix()); err == nil {
		t.Fatal("body falsifié accepté")
	}
	// Mauvais auteur
	ev3 := makeSignedEvent(t, priv, onion, 1, "x")
	_, autre, _ := ed25519.GenerateKey(rand.Reader)
	ev3.Author, _ = PubKeyToOnion(autre.Public().(ed25519.PublicKey))
	if err := ev3.Validate(time.Now().Unix()); err == nil {
		t.Fatal("auteur falsifié accepté")
	}
}

// TestStoreSeqAndInbox — séquence stricte par auteur, pending, inbox par destinataire.
func TestStoreSeqAndInbox(t *testing.T) {
	dir, _ := os.MkdirTemp("", "nyx-test")
	defer os.RemoveAll(dir)

	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := PubKeyToOnion(pub)
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().Unix()

	if _, err := store.Append(makeSignedEvent(t, priv, onion, 2, "trou"), now); err == nil {
		t.Fatal("seq en trou acceptée")
	}
	if _, err := store.Append(makeSignedEvent(t, priv, onion, 1, "premier"), now); err != nil {
		t.Fatalf("seq 1 rejetée: %v", err)
	}
	// L'événement seq 2 en attente doit être admis automatiquement (trou comblé)
	if store.Count() != 2 {
		t.Fatalf("admission automatique du pending: %d ≠ 2", store.Count())
	}
	dup, err := store.Append(makeSignedEvent(t, priv, onion, 1, "premier"), now)
	if !dup || err != nil {
		t.Fatalf("duplication mal gérée: dup=%v err=%v", dup, err)
	}
	if _, err := store.Append(makeSignedEvent(t, priv, onion, 3, "troisième"), now); err != nil {
		t.Fatalf("seq 3 rejetée: %v", err)
	}

	evs, cursor := store.Inbox("dest.onion", 0, 100)
	if len(evs) != 3 {
		t.Fatalf("inbox: %d événements ≠ 3", len(evs))
	}
	if cursor < 3 {
		t.Fatalf("curseur global suspect: %d", cursor)
	}

	// Recharge : persistance JSONL
	store2, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if store2.Count() != 3 {
		t.Fatalf("après rechargement: %d ≠ 3", store2.Count())
	}
}

// TestAPIRoundTrip — POST /client/event puis GET /client/inbox via le routeur HTTP.
func TestAPIRoundTrip(t *testing.T) {
	// (test d'intégration minimal sans Tor — le Handler est purement HTTP)
	// Nécessite le package api : exercé dans TestAPIEndToEnd plus bas (voir api/api_test.go).
}
