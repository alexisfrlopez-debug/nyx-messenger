// Tests de POST /admin/rendezvous — signature ed25519 vérifiée avant écriture (ADR-016).
package adminapi

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const rendezvousTestDomain = "nyx-rendezvous-v1|"

const rendezvousTestJSON = `{"format":"nyx-rendezvous","version":1,"generatedAt":1760000000,"replicas":[{"onion":"5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80","note":"test"}]}`

// TestRendezvousPostValid — signature valide par la clé de release → 200,
// fichiers écrits, RendezvousServe les restitue à l'identique.
func TestRendezvousPostValid(t *testing.T) {
	h := newTestServer(t)
	pubRel, privRel, _ := ed25519.GenerateKey(rand.Reader)
	h.srv.RendezvousPubKey = base64.StdEncoding.EncodeToString(pubRel)

	sig := ed25519.Sign(privRel, append([]byte(rendezvousTestDomain), []byte(rendezvousTestJSON)...))
	body, _ := json.Marshal(RendezvousUpdate{
		JSONB64: base64.StdEncoding.EncodeToString([]byte(rendezvousTestJSON)),
		SigB64:  base64.StdEncoding.EncodeToString(sig),
	})
	code, resp := h.signedReq(t, "POST", "/admin/rendezvous", body)
	if code != 200 {
		t.Fatalf("200 attendu, obtenu %d : %s", code, resp)
	}
	raw, sigOut, ok := h.srv.RendezvousServe()
	if !ok || string(raw) != rendezvousTestJSON || string(sigOut) != string(sig) {
		t.Fatalf("fichiers non restitués à l'identique (ok=%v)", ok)
	}
}

// TestRendezvousPostBadSignature — un octet altéré → 409, RIEN n'est écrit.
func TestRendezvousPostBadSignature(t *testing.T) {
	h := newTestServer(t)
	pubRel, privRel, _ := ed25519.GenerateKey(rand.Reader)
	h.srv.RendezvousPubKey = base64.StdEncoding.EncodeToString(pubRel)

	sig := ed25519.Sign(privRel, append([]byte(rendezvousTestDomain), []byte(rendezvousTestJSON)...))
	sig[3] ^= 0x40
	body, _ := json.Marshal(RendezvousUpdate{
		JSONB64: base64.StdEncoding.EncodeToString([]byte(rendezvousTestJSON)),
		SigB64:  base64.StdEncoding.EncodeToString(sig),
	})
	code, _ := h.signedReq(t, "POST", "/admin/rendezvous", body)
	if code != 409 {
		t.Fatalf("409 attendu (signature invalide), obtenu %d", code)
	}
	if _, _, ok := h.srv.RendezvousServe(); ok {
		t.Fatal("aucun fichier ne doit être écrit sur signature invalide")
	}
}

// TestRendezvousPostWrongKey — signée par une clé ÉTRANGÈRE → 409.
func TestRendezvousPostWrongKey(t *testing.T) {
	h := newTestServer(t)
	pubRel, _, _ := ed25519.GenerateKey(rand.Reader)
	_, privForeign, _ := ed25519.GenerateKey(rand.Reader)
	h.srv.RendezvousPubKey = base64.StdEncoding.EncodeToString(pubRel)

	sig := ed25519.Sign(privForeign, append([]byte(rendezvousTestDomain), []byte(rendezvousTestJSON)...))
	body, _ := json.Marshal(RendezvousUpdate{
		JSONB64: base64.StdEncoding.EncodeToString([]byte(rendezvousTestJSON)),
		SigB64:  base64.StdEncoding.EncodeToString(sig),
	})
	code, _ := h.signedReq(t, "POST", "/admin/rendezvous", body)
	if code != 409 {
		t.Fatalf("409 attendu (clé étrangère), obtenu %d", code)
	}
}

// TestRendezvousPostNoPubkey — route en lecture seule sans config → 400.
func TestRendezvousPostNoPubkey(t *testing.T) {
	h := newTestServer(t)
	h.srv.RendezvousPubKey = ""
	body, _ := json.Marshal(RendezvousUpdate{
		JSONB64: base64.StdEncoding.EncodeToString([]byte(rendezvousTestJSON)),
		SigB64:  strings.Repeat("A", 86) + "=",
	})
	code, _ := h.signedReq(t, "POST", "/admin/rendezvous", body)
	if code != 400 {
		t.Fatalf("400 attendu (pubkey non configurée), obtenu %d", code)
	}
}

// TestRendezvousGet404 — sans fichiers → ok=false (404 côté client).
func TestRendezvousGet404(t *testing.T) {
	h := newTestServer(t)
	if _, _, ok := h.srv.RendezvousServe(); ok {
		t.Fatal("ok=true inattendu sans fichiers")
	}
}

// TestRendezvousPathsRelative — chemin relatif résolu sous DataDir.
func TestRendezvousPathsRelative(t *testing.T) {
	h := newTestServer(t)
	h.srv.RendezvousFile = "rnd.json"
	jp, sp := h.srv.rendezvousPaths()
	if !filepath.IsAbs(jp) || filepath.Base(jp) != "rnd.json" || sp != jp+".sig" {
		t.Fatalf("chemins inattendus : %s / %s", jp, sp)
	}
	_ = os.Remove(jp) // rien de spécial : sanity sur la manipulation du chemin
}
