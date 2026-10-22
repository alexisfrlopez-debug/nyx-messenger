// Rendezvous signé — mise à jour opérée par la console (ADR-009 §3, ADR-016).
//
// POST /admin/rendezvous  {"json_b64": "...", "sig_b64": "..."}
//
//	→ le serveur VÉRIFIE la signature ed25519 détachée
//	  b"nyx-rendezvous-v1|" + octets du JSON avec la clé publique de release
//	  configurée (config.rendezvous.pubkey) AVANT d'écrire — un fichier non
//	  signé par la clé de release est refusé (409) ; le nœud ne peut donc
//	  diffuser qu'une liste approuvée par l'opérateur (clé privée hors nœud).
//	→ écriture atomique (temp + rename) de rendezvous.json et rendezvous.json.sig.
//	→ sans pubkey configurée : 400 (route en lecture seule).
package adminapi

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// rendezvousDomain — même domaine de séparation que l'outil Python
// (scripts/gen_rendezvous.py) et le client (RendezvousList.kt DOMAIN).
const rendezvousDomain = "nyx-rendezvous-v1|"

// RendezvousUpdate — charge utile du POST /admin/rendezvous.
type RendezvousUpdate struct {
	JSONB64 string `json:"json_b64"` // octets exacts du rendezvous.json
	SigB64  string `json:"sig_b64"`  // signature ed25519 détachée (base64)
}

// rendezvousPaths — fichier (json) et signature (.sig) — relatif à DataDir
// si non absolu ; défaut : rendezvous.json dans le data_dir.
func (s *Server) rendezvousPaths() (string, string) {
	file := strings.TrimSpace(s.RendezvousFile)
	if file == "" {
		file = "rendezvous.json"
	}
	if !filepath.IsAbs(file) && s.DataDir != "" {
		file = filepath.Join(s.DataDir, file)
	}
	return file, file + ".sig"
}

// writeAtomicFile — écriture atomique (fichier temporaire + rename).
func writeAtomicFile(path string, data []byte, mode os.FileMode) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, mode); err != nil {
		return fmt.Errorf("écriture %s : %w", tmp, err)
	}
	return os.Rename(tmp, path)
}

// POST /admin/rendezvous — remplace la liste servie (signature vérifiée).
func (s *Server) handleRendezvousPost(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "méthode interdite"})
		return
	}
	pubB64 := strings.TrimSpace(s.RendezvousPubKey)
	if pubB64 == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "rendezvous.pubkey non configurée — route en lecture seule"})
		return
	}
	var up RendezvousUpdate
	if err := json.Unmarshal(readBody(r), &up); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "corps JSON invalide"})
		return
	}
	raw, err := base64.StdEncoding.DecodeString(up.JSONB64)
	if err != nil || len(raw) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "json_b64 invalide"})
		return
	}
	sig, err := base64.StdEncoding.DecodeString(up.SigB64)
	if err != nil || len(sig) != ed25519.SignatureSize {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "sig_b64 invalide (64 octets attendus)"})
		return
	}
	pub, err := base64.StdEncoding.DecodeString(pubB64)
	if err != nil || len(pub) != ed25519.PublicKeySize {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "rendezvous.pubkey mal configurée"})
		return
	}
	// Vérification ed25519 sur domaine + octets EXACTS (aucune canonicalisation).
	msg := append([]byte(rendezvousDomain), raw...)
	if !ed25519.Verify(ed25519.PublicKey(pub), msg, sig) {
		log.Printf("[admin] rendezvous : signature INVALIDE — refus (%d o json)", len(raw))
		writeJSON(w, http.StatusConflict, map[string]string{"error": "signature invalide — liste refusée"})
		return
	}
	jsonPath, sigPath := s.rendezvousPaths()
	if err := writeAtomicFile(jsonPath, raw, 0o644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	if err := writeAtomicFile(sigPath, sig, 0o644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	log.Printf("[admin] rendezvous mis à jour (%d o json, %d o sig)", len(raw), len(sig))
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "bytes": len(raw)})
}

// RendezvousServe — closure de lecture pour l'API client (GET /rendezvous),
// câblée dans main.go après création de l'API admin (fichiers partagés).
// Renvoie (json, sig, true) si les deux fichiers sont présents et non vides.
func (s *Server) RendezvousServe() ([]byte, []byte, bool) {
	jsonPath, sigPath := s.rendezvousPaths()
	raw, err := os.ReadFile(jsonPath)
	if err != nil || len(raw) == 0 {
		return nil, nil, false
	}
	sig, err := os.ReadFile(sigPath)
	if err != nil || len(sig) == 0 {
		return nil, nil, false
	}
	return raw, sig, true
}
