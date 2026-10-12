package api

import (
        "bytes"
        "crypto/ed25519"
        "crypto/rand"
        "encoding/base64"
        "encoding/json"
        "net/http"
        "net/http/httptest"
        "os"
        "testing"
        "time"

        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/config"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

// TestAliasEndToEnd — claim d'un pseudonyme via POST /client/event (type alias),
// résolution via GET /client/alias : le circuit complet (wire-format.md §6).
func TestAliasEndToEnd(t *testing.T) {
        dir, _ := os.MkdirTemp("", "nyx-alias")
        defer os.RemoveAll(dir)

        store, err := journal.OpenStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        pub, priv, _ := ed25519.GenerateKey(rand.Reader)
        onion, _ := journal.PubKeyToOnion(pub)

        s := &Server{
                Version:    "test",
                Onion:      "nœud.onion",
                GossipPub:  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                GossipPriv: make([]byte, 32),
                Store:      store,
                Peers:      func() []config.Peer { return []config.Peer{} },
                AddPeer:    func(p config.Peer) {},
                Kick:       make(chan struct{}, 1),
        }
        ts := httptest.NewServer(s.Handler())
        defer ts.Close()

        // Alias libre avant tout claim
        resp, err := http.Get(ts.URL + "/client/alias?name=pseudo_1")
        if err != nil {
                t.Fatal(err)
        }
        var res struct {
                Alias string `json:"alias"`
                Taken bool   `json:"taken"`
                By    string `json:"by"`
        }
        json.NewDecoder(resp.Body).Decode(&res)
        if res.Taken {
                t.Fatalf("alias %q devrait être libre", res.Alias)
        }

        // Claim signé
        body := base64.StdEncoding.EncodeToString([]byte(`{"alias":"pseudo_1","action":"claim"}`))
        ev := &journal.Event{Type: "alias", Author: onion, Seq: 1, TS: time.Now().Unix(), Body: body}
        ev.Sign(priv)
        b, _ := json.Marshal(ev)
        resp, err = http.Post(ts.URL+"/client/event", "application/json", bytes.NewReader(b))
        if err != nil {
                t.Fatal(err)
        }
        if resp.StatusCode != 200 {
                t.Fatalf("claim alias rejeté: HTTP %d", resp.StatusCode)
        }

        // Résolution : pris, détenteur = auteur
        resp, err = http.Get(ts.URL + "/client/alias?name=pseudo_1")
        if err != nil {
                t.Fatal(err)
        }
        if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
                t.Fatal(err)
        }
        if !res.Taken || res.By != onion {
                t.Fatalf("résolution invalide: %+v (attendu by=%s)", res, onion)
        }

        // Format invalide → 400
        for _, bad := range []string{"AB", "avec-espace", "-x", "", "un_alias_beaucoup_trop_long_pour_le_format"} {
                resp, err := http.Get(ts.URL + "/client/alias?name=" + bad)
                if err != nil {
                        t.Fatal(err)
                }
                if resp.StatusCode != 400 {
                        t.Fatalf("alias invalide %q accepté: HTTP %d", bad, resp.StatusCode)
                }
        }

        // Claim au body invalide → 400 (rejeté à la validation)
        badBody := base64.StdEncoding.EncodeToString([]byte(`{"alias":"X!","action":"claim"}`))
        bad := &journal.Event{Type: "alias", Author: onion, Seq: 2, TS: time.Now().Unix(), Body: badBody}
        bad.Sign(priv)
        b, _ = json.Marshal(bad)
        resp, err = http.Post(ts.URL+"/client/event", "application/json", bytes.NewReader(b))
        if err != nil {
                t.Fatal(err)
        }
        if resp.StatusCode != 400 {
                t.Fatalf("claim avec alias invalide accepté: HTTP %d", resp.StatusCode)
        }
}
