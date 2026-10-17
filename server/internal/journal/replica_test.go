// v0.2.25 — champ « replica » des profils publics : garde-fou d'identité
// (adresse == auteur) + agrégation Store.ReplicaAnnouncements (/admin/replicas).
package journal

import (
        "crypto/ed25519"
        "crypto/rand"
        "os"
        "testing"
        "time"
)

// replicaCard — corps de profil b64 avec (ou sans) champ « replica ».
func replicaCard(value string, withReplica bool) string {
        fields := map[string]any{}
        if withReplica {
                fields["replica"] = map[string]any{"value": value, "public": true}
        } else {
                fields["display_name"] = map[string]any{"value": "Anon", "public": true}
        }
        return b64Of(map[string]any{"fields": fields})
}

// TestProfileReplicaSelfOK — un auteur peut déclarer SON propre réplica.
func TestProfileReplicaSelfOK(t *testing.T) {
        dir, _ := os.MkdirTemp("", "nyx-repl")
        defer os.RemoveAll(dir)
        store, err := OpenStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        pub, priv, _ := ed25519.GenerateKey(rand.Reader)
        onion, _ := PubKeyToOnion(pub)

        ev := signEvent(t, priv, &Event{
                Type: "profile", Author: onion, Seq: 1, TS: nowOK(),
                Body: replicaCard(onion+":80", true),
        })
        if _, err := store.Append(ev, nowOK()); err != nil {
                t.Fatalf("auto-déclaration réplica valide refusée: %v", err)
        }
        list := store.ReplicaAnnouncements(time.Now().Unix(), 7*24*3600)
        if len(list) != 1 || list[0].Addr != onion+":80" || list[0].Author != onion {
                t.Fatalf("agrégation attendue 1 entrée (%s:80), obtenue %+v", onion[:16], list)
        }
}

// TestProfileReplicaThirdPartyRejected — annoncer l'adresse d'un TIERS est
// rejeté (anti-relais d'adresses arbitraires).
func TestProfileReplicaThirdPartyRejected(t *testing.T) {
        dir, _ := os.MkdirTemp("", "nyx-repl2")
        defer os.RemoveAll(dir)
        store, err := OpenStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        pub, priv, _ := ed25519.GenerateKey(rand.Reader)
        onion, _ := PubKeyToOnion(pub)
        thirdPub, _, _ := ed25519.GenerateKey(rand.Reader)
        thirdOnion, _ := PubKeyToOnion(thirdPub)

        ev := signEvent(t, priv, &Event{
                Type: "profile", Author: onion, Seq: 1, TS: nowOK(),
                Body: replicaCard(thirdOnion+":80", true),
        })
        if _, err := store.Append(ev, nowOK()); err == nil {
                t.Fatal("annonce d'une adresse tierce ACCEPTÉE — garde-fou cassé")
        }
}

// TestProfileReplicaBadPort — port invalide rejeté (0, >65535, non numérique).
func TestProfileReplicaBadPort(t *testing.T) {
        for _, bad := range []string{":0", ":99999", ":abc"} {
                dir, _ := os.MkdirTemp("", "nyx-repl3")
                store, err := OpenStore(dir)
                if err != nil {
                        t.Fatal(err)
                }
                pub, priv, _ := ed25519.GenerateKey(rand.Reader)
                onion, _ := PubKeyToOnion(pub)
                ev := signEvent(t, priv, &Event{
                        Type: "profile", Author: onion, Seq: 1, TS: nowOK(),
                        Body: replicaCard(onion+bad, true),
                })
                if _, err := store.Append(ev, nowOK()); err == nil {
                        t.Fatalf("port invalide %q accepté", bad)
                }
                os.RemoveAll(dir)
        }
}

// TestReplicaAnnouncementsTTL — une annonce périmée (> maxAgeSec) disparaît.
func TestReplicaAnnouncementsTTL(t *testing.T) {
        dir, _ := os.MkdirTemp("", "nyx-repl4")
        defer os.RemoveAll(dir)
        store, err := OpenStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        pub, priv, _ := ed25519.GenerateKey(rand.Reader)
        onion, _ := PubKeyToOnion(pub)
        old := nowOK() - 8*24*3600 // 8 jours
        ev := signEvent(t, priv, &Event{
                Type: "profile", Author: onion, Seq: 1, TS: old,
                Body: replicaCard(onion+":80", true),
        })
        if _, err := store.Append(ev, old); err != nil {
                t.Fatalf("insertion ancienne carte refusée: %v", err)
        }
        if list := store.ReplicaAnnouncements(time.Now().Unix(), 7*24*3600); len(list) != 0 {
                t.Fatalf("annonce périmée encore listée: %+v", list)
        }
}

// TestReplicaAnnouncementsFreshness — la ré-annonce rafraîchit (dernier seq
// gagne) et la liste reste triée.
func TestReplicaAnnouncementsFreshness(t *testing.T) {
        dir, _ := os.MkdirTemp("", "nyx-repl5")
        defer os.RemoveAll(dir)
        store, err := OpenStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        pub, priv, _ := ed25519.GenerateKey(rand.Reader)
        onion, _ := PubKeyToOnion(pub)
        stale := nowOK() - 2*3600
        ev1 := signEvent(t, priv, &Event{
                Type: "profile", Author: onion, Seq: 1, TS: stale,
                Body: replicaCard(onion+":80", true),
        })
        if _, err := store.Append(ev1, stale); err != nil {
                t.Fatal(err)
        }
        fresh := nowOK() - 60
        ev2 := signEvent(t, priv, &Event{
                Type: "profile", Author: onion, Seq: 2, TS: fresh,
                Body: replicaCard(onion+":8080", true),
        })
        if _, err := store.Append(ev2, fresh); err != nil {
                t.Fatal(err)
        }
        list := store.ReplicaAnnouncements(time.Now().Unix(), 7*24*3600)
        if len(list) != 1 || list[0].Addr != onion+":8080" {
                t.Fatalf("dernier seq attendu (:8080), obtenu %+v", list)
        }
}
