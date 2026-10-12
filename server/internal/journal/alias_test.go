package journal

import (
        "crypto/ed25519"
        "crypto/rand"
        "encoding/base64"
        "os"
        "testing"
        "time"
)

// helper — crée un événement alias signé prêt pour Append.
func signedAlias(t *testing.T, priv ed25519.PrivateKey, onion string, seq uint64, alias string) *Event {
        t.Helper()
        body := base64.StdEncoding.EncodeToString([]byte(`{"alias":"` + alias + `","action":"claim"}`))
        ev := &Event{Type: "alias", Author: onion, Seq: seq, TS: time.Now().Unix(), Body: body}
        ev.Sign(priv)
        return ev
}

// TestAliasConvergenceMinID — deux auteurs claiment le même alias : le gagnant
// est le claim au plus petit id, indépendamment de l'ordre d'arrivée
// (règle déterministe, wire-format.md §6.3), et le replay reconstruit le même gagnant.
func TestAliasConvergenceMinID(t *testing.T) {
        dir, _ := os.MkdirTemp("", "nyx-alias-conv")
        defer os.RemoveAll(dir)

        store, err := OpenStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        pub1, priv1, _ := ed25519.GenerateKey(rand.Reader)
        pub2, priv2, _ := ed25519.GenerateKey(rand.Reader)
        onion1, _ := PubKeyToOnion(pub1)
        onion2, _ := PubKeyToOnion(pub2)

        for _, ev := range []*Event{
                signedAlias(t, priv1, onion1, 1, "meme_alias"),
                signedAlias(t, priv2, onion2, 1, "meme_alias"),
        } {
                if _, err := store.Append(ev, time.Now().Unix()); err != nil {
                        t.Fatal(err)
                }
        }
        winner := store.byAlias["meme_alias"]
        if winner == nil {
                t.Fatal("alias non indexé")
        }
        // Le gagnant doit être l'événement au plus petit id
        if winner.Author != onion1 && winner.Author != onion2 {
                t.Fatalf("gagnant inattendu: %s", winner.Author)
        }
        other := onion2
        if winner.Author == onion2 {
                other = onion1
        }
        ev1 := store.EventsByAuthor(onion1, 0, 10)
        ev2 := store.EventsByAuthor(onion2, 0, 10)
        if len(ev1) != 1 || len(ev2) != 1 {
                t.Fatalf("journaux incomplets: %d / %d", len(ev1), len(ev2))
        }
        want := ev1[0].ID()
        if ev2[0].ID() < want {
                want = ev2[0].ID()
        }
        if winner.ID() != want {
                t.Fatalf("gagnant %s ≠ min-id %s", winner.ID()[:12], want[:12])
        }
        otherIsWinner := winner.Author == other
        _ = otherIsWinner

        // Replay : OpenStore doit reconstruire exactement le même gagnant
        store2, err := OpenStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        author, taken := store2.ResolveAlias("meme_alias")
        if !taken || author != winner.Author {
                t.Fatalf("replay divergent: %s/%v ≠ %s", author, taken, winner.Author)
        }
}
