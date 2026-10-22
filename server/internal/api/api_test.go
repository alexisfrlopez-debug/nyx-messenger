package api

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/alexisfrlopez-debug/nyx-messenger/server/internal/config"
	"github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

// TestAPIEndToEnd — dépôt d'un événement signé via POST /client/event,
// récupération via GET /client/inbox : le circuit client complet.
func TestAPIEndToEnd(t *testing.T) {
	dir, _ := os.MkdirTemp("", "nyx-api")
	defer os.RemoveAll(dir)

	store, err := journal.OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := journal.PubKeyToOnion(pub)

	peers := []config.Peer{}
	s := &Server{
		Version:    "test",
		Onion:      "nœud.onion",
		GossipPub:  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		GossipPriv: make([]byte, 32),
		Store:      store,
		Peers:      func() []config.Peer { return peers },
		AddPeer:    func(p config.Peer) {},
		Kick:       make(chan struct{}, 1),
	}
	ts := httptest.NewServer(s.Handler())
	defer ts.Close()

	// POST /client/event
	ev := &journal.Event{Type: "msg", Author: onion, Seq: 1, TS: time.Now().Unix(), To: "bob.onion", Body: "Ym9uam91cg=="} // "bonjour"
	ev.Sign(priv)
	b, _ := json.Marshal(ev)
	resp, err := http.Post(ts.URL+"/client/event", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("POST /client/event: HTTP %d", resp.StatusCode)
	}

	// GET /client/inbox
	resp, err = http.Get(ts.URL + "/client/inbox?to=bob.onion")
	if err != nil {
		t.Fatal(err)
	}
	var out struct {
		Events []*journal.Event `json:"events"`
		Cursor int64            `json:"cursor"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if len(out.Events) != 1 || out.Events[0].Body != "Ym9uam91cg==" {
		t.Fatalf("inbox invalide: %+v", out.Events)
	}

	// Événement non signé → 400
	bad := &journal.Event{Type: "msg", Author: onion, Seq: 2, TS: time.Now().Unix(), To: "bob.onion", Body: "eA=="}
	b, _ = json.Marshal(bad)
	resp, err = http.Post(ts.URL+"/client/event", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 400 {
		t.Fatalf("événement invalide accepté: HTTP %d", resp.StatusCode)
	}
}
