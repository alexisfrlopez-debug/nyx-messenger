// Tests du type "profile" (wire-format.md §7) — validation, index, checkpoint.
package journal

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"os"
	"testing"
	"time"
)

// signEvent — fabrique un événement signé valide (helper de test).
func signEvent(t *testing.T, priv ed25519.PrivateKey, ev *Event) *Event {
	t.Helper()
	ev.Sign(priv)
	return ev
}

func b64Of(v any) string {
	b, _ := json.Marshal(v)
	return base64.StdEncoding.EncodeToString(b)
}

// TestProfileBroadcastValid — une carte de profil 100 % publique est acceptée
// et indexée (dernier gagne).
func TestProfileBroadcastValid(t *testing.T) {
	dir, _ := os.MkdirTemp("", "nyx-prof")
	defer os.RemoveAll(dir)
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := PubKeyToOnion(pub)

	ev := signEvent(t, priv, &Event{
		Type: "profile", Author: onion, Seq: 1, TS: nowOK(),
		Body: b64Of(map[string]any{
			"fields": map[string]any{
				"display_name": map[string]any{"value": "Anon", "public": true},
				"bio":          map[string]any{"value": "hello", "public": true},
			}}),
	})
	if _, err := store.Append(ev, nowOK()); err != nil {
		t.Fatalf("profil public valide refusé: %v", err)
	}
	cur := store.ResolveProfile(onion)
	if cur == nil || cur.Seq != 1 {
		t.Fatal("profil non indexé")
	}

	// Mise à jour (seq 2) : dernier gagne
	ev2 := signEvent(t, priv, &Event{
		Type: "profile", Author: onion, Seq: 2, TS: nowOK(),
		Body: b64Of(map[string]any{
			"fields": map[string]any{
				"display_name": map[string]any{"value": "Anon2", "public": true},
			}}),
	})
	if _, err := store.Append(ev2, nowOK()); err != nil {
		t.Fatalf("mise à jour refusée: %v", err)
	}
	if cur := store.ResolveProfile(onion); cur.Seq != 2 {
		t.Fatalf("profil courant = seq %d, attendu 2 (dernier gagne)", cur.Seq)
	}
}

// TestProfileBroadcastPrivateRejected — invariant §7.2 : un champ privé dans
// un profil DIFFUSÉ est rejeté (un champ privé ne doit jamais quitter l'appareil).
func TestProfileBroadcastPrivateRejected(t *testing.T) {
	dir, _ := os.MkdirTemp("", "nyx-prof")
	defer os.RemoveAll(dir)
	store, _ := OpenStore(dir)
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := PubKeyToOnion(pub)

	ev := signEvent(t, priv, &Event{
		Type: "profile", Author: onion, Seq: 1, TS: nowOK(),
		Body: b64Of(map[string]any{
			"fields": map[string]any{
				"location": map[string]any{"value": "Paris", "public": false},
			}}),
	})
	if _, err := store.Append(ev, nowOK()); err == nil {
		t.Fatal("champ privé en diffusion ACCEPTÉ — invariant §7.2 cassé")
	}
}

// TestProfileUnknownKeyAndOversizeRejected — clés fermées + limites de taille.
func TestProfileUnknownKeyAndOversizeRejected(t *testing.T) {
	dir, _ := os.MkdirTemp("", "nyx-prof")
	defer os.RemoveAll(dir)
	store, _ := OpenStore(dir)
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := PubKeyToOnion(pub)

	// clé inconnue
	ev := signEvent(t, priv, &Event{
		Type: "profile", Author: onion, Seq: 1, TS: nowOK(),
		Body: b64Of(map[string]any{
			"fields": map[string]any{"real_name": map[string]any{"value": "X", "public": true}}}),
	})
	if _, err := store.Append(ev, nowOK()); err == nil {
		t.Fatal("clé de champ inconnue ACCEPTÉE")
	}

	// bio trop longue
	long := make([]byte, 281)
	for i := range long {
		long[i] = 'a'
	}
	ev2 := signEvent(t, priv, &Event{
		Type: "profile", Author: onion, Seq: 1, TS: nowOK(),
		Body: b64Of(map[string]any{
			"fields": map[string]any{"bio": map[string]any{"value": string(long), "public": true}}}),
	})
	if _, err := store.Append(ev2, nowOK()); err == nil {
		t.Fatal("champ surdimensionné ACCEPTÉ")
	}
}

// TestProfileAddressedOpaque — un profil adressé (to != "") n'est pas validé
// sémantiquement (corps chiffré opaque — partage E2E futur, §7.2).
func TestProfileAddressedOpaque(t *testing.T) {
	dir, _ := os.MkdirTemp("", "nyx-prof")
	defer os.RemoveAll(dir)
	store, _ := OpenStore(dir)
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := PubKeyToOnion(pub)
	pub2, _, _ := ed25519.GenerateKey(rand.Reader)
	onion2, _ := PubKeyToOnion(pub2)

	// corps invalide sémantiquement (champ privé) mais ADRESSÉ : doit passer
	ev := signEvent(t, priv, &Event{
		Type: "profile", Author: onion, Seq: 1, TS: nowOK(), To: onion2,
		Body: b64Of(map[string]any{
			"fields": map[string]any{"location": map[string]any{"value": "Paris", "public": false}}}),
	})
	if _, err := store.Append(ev, nowOK()); err != nil {
		t.Fatalf("profil adressé rejeté alors qu'il doit rester opaque: %v", err)
	}
	// et ne doit PAS être indexé comme carte publique
	if store.ResolveProfile(onion) != nil {
		t.Fatal("profil adressé indexé comme carte publique — §7.3 violé")
	}
}

// TestMerkleRootDeterministic — la racine de Merkle du préfixe est déterministe
// et change si le contenu change (propriété d'engagement du checkpoint).
func TestMerkleRootDeterministic(t *testing.T) {
	dir, _ := os.MkdirTemp("", "nyx-prof")
	defer os.RemoveAll(dir)
	store, _ := OpenStore(dir)
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := PubKeyToOnion(pub)

	for seq := 1; seq <= 5; seq++ {
		ev := signEvent(t, priv, &Event{
			Type: "alias", Author: onion, Seq: uint64(seq), TS: nowOK(),
			Body: b64Of(map[string]any{"alias": "alias_" + string(rune('a'+seq)), "action": "claim"}),
		})
		if _, err := store.Append(ev, nowOK()); err != nil {
			t.Fatal(err)
		}
	}
	r1, err := store.MerkleRoot(5)
	if err != nil {
		t.Fatal(err)
	}
	r2, _ := store.MerkleRoot(5)
	if r1 != r2 {
		t.Fatal("racine non déterministe")
	}
	r3, _ := store.MerkleRoot(3)
	if r1 == r3 {
		t.Fatal("racines de préfixes différents identiques")
	}
	if _, err := store.MerkleRoot(6); err == nil {
		t.Fatal("préfixe hors bornes ACCEPTÉ")
	}
}

// TestApplyCheckpoint — élagage en 2 étapes : archive + troncature + index.
func TestApplyCheckpoint(t *testing.T) {
	dir, _ := os.MkdirTemp("", "nyx-prof")
	defer os.RemoveAll(dir)
	store, _ := OpenStore(dir)
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	onion, _ := PubKeyToOnion(pub)

	for seq := 1; seq <= 4; seq++ {
		ev := signEvent(t, priv, &Event{
			Type: "alias", Author: onion, Seq: uint64(seq), TS: nowOK(),
			Body: b64Of(map[string]any{"alias": "alias_" + string(rune('a'+seq)), "action": "claim"}),
		})
		if _, err := store.Append(ev, nowOK()); err != nil {
			t.Fatal(err)
		}
	}
	root, _ := store.MerkleRoot(3)

	// racine erronée → refus
	if _, err := store.ApplyCheckpoint(3, "deadbeef"); err == nil {
		t.Fatal("application avec racine erronée ACCEPTÉE")
	}
	archive, err := store.ApplyCheckpoint(3, root)
	if err != nil {
		t.Fatalf("application légitime refusée: %v", err)
	}
	if st, err := os.Stat(archive); err != nil || st.Size() == 0 {
		t.Fatalf("archive absente ou vide: %s", archive)
	}
	if store.Count() != 1 {
		t.Fatalf("journal = %d événements, attendu 1", store.Count())
	}
	// le seq de l'auteur survit à l'élagage (le 4e événement a seq 4)
	if seq := store.EventsByAuthor(onion, 0, 10); len(seq) != 1 || seq[0].Seq != 4 {
		t.Fatalf("suffixe corrompu par le checkpoint")
	}
	// nouveau store sur disque : le replay charge uniquement le suffixe
	store2, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if store2.Count() != 1 {
		t.Fatalf("replay post-checkpoint = %d événements, attendu 1", store2.Count())
	}
}

// nowOK — horodatage acceptable (±24 h du serveur).
func nowOK() int64 { return time.Now().Unix() }
