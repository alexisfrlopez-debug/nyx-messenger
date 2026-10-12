// Package adminapi — handlers d'action : maintenance, arrêt, élagage, identité, clés.
package adminapi

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"os"
	"strings"
	"time"
)

// POST /admin/maintenance {"on":true} — cesse d'accepter les clients
// (routes client → 503), le gossip inter-réplicas continue (drain).
func (s *Server) handleMaintenance(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	if s.Maintenance == nil {
		writeJSON(w, 501, map[string]string{"error": "maintenance indisponible"})
		return
	}
	var req struct {
		On bool `json:"on"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, map[string]string{"error": "JSON invalide (attendu {\"on\":bool})"})
		return
	}
	if req.On {
		s.Maintenance.Store(1)
	} else {
		s.Maintenance.Store(0)
	}
	state := "actif"
	if req.On {
		state = "maintenance"
	}
	writeJSON(w, 200, map[string]any{"ok": true, "state": state})
}

// requestStop — répond d'abord, PUIS demande l'arrêt à main.go (délai pour
// laisser la réponse TCP partir proprement vers le client via Tor).
func (s *Server) requestStop(w http.ResponseWriter, action string) {
	writeJSON(w, 200, map[string]any{"ok": true, "action": action,
		"note": "arrêt gracieux — systemd Restart=always relancera le nœud"})
	go func() {
		time.Sleep(500 * time.Millisecond)
		select {
		case s.StopRequest <- action:
		default:
		}
	}()
}

// POST /admin/shutdown — arrêt gracieux : flush journal/config, exit 0.
func (s *Server) handleShutdown(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	s.requestStop(w, "shutdown")
}

// POST /admin/restart — arrêt gracieux ; systemd relance (ou l'opérateur).
func (s *Server) handleRestart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	s.requestStop(w, "restart")
}

// POST /admin/checkpoint/propose {"before":n} — construit la racine de Merkle
// (RFC 6962) du préfixe [0,n) du journal, la signe avec la clé onion du nœud
// et la met en attente d'application (2 étapes — confirmation obligatoire).
// NB : le paramètre est un NOMBRE D'ÉVÉNEMENTS du préfixe (position globale),
// pas un seq applicatif — la console passe typiquement events (status).
func (s *Server) handleCheckpointPropose(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	var req struct {
		Before int64 `json:"before"`
	}
	// compat console : accepte aussi "before_seq" (historique de nommage)
	var alt struct {
		BeforeSeq int64 `json:"before_seq"`
	}
	body := readBody(r)
	if err := json.Unmarshal(body, &req); err != nil {
		if err2 := json.Unmarshal(body, &alt); err2 == nil && alt.BeforeSeq > 0 {
			req.Before = alt.BeforeSeq
		} else {
			writeJSON(w, 400, map[string]string{"error": "JSON invalide (attendu {\"before\":n})"})
			return
		}
	}
	if req.Before <= 0 {
		writeJSON(w, 400, map[string]string{"error": "préfixe invalide (before > 0 requis)"})
		return
	}
	before := int(req.Before)
	root, err := s.Store.MerkleRoot(before)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	sig := ""
	if s.SignRoot != nil {
		sig, err = s.SignRoot(root)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "signature de la racine: " + err.Error()})
			return
		}
	}
	s.mu.Lock()
	s.proposal = &checkpointProposal{Before: before, Root: root, Signature: sig, At: time.Now()}
	s.mu.Unlock()
	writeJSON(w, 200, map[string]any{
		"ok":         true,
		"before":     before,
		"root":       root,
		"signature":  sig,
		"algorithm":  "sha256-rfc6962",
		"expires_in": int(proposalTTL.Seconds()),
		"status":     "proposed — appliquer avec POST /admin/checkpoint/apply",
	})
}

// POST /admin/checkpoint/apply — DEUXIÈME étape (corps vide) : applique la
// dernière proposition (TTL 10 min). Archive le préfixe hors ligne, tronque
// le journal, reconstruit les index. Refus si la racine recalculée diffère
// (des événements sont arrivés entre propose et apply → re-proposer).
func (s *Server) handleCheckpointApply(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	s.mu.Lock()
	p := s.proposal
	s.mu.Unlock()
	if p == nil || time.Since(p.At) > proposalTTL {
		writeJSON(w, 409, map[string]string{"error": "aucune proposition valide — appeler /admin/checkpoint/propose d'abord"})
		return
	}
	archive, err := s.Store.ApplyCheckpoint(p.Before, p.Root)
	if err != nil {
		writeJSON(w, 409, map[string]string{"error": "application refusée: " + err.Error()})
		return
	}
	s.mu.Lock()
	s.proposal = nil // one-shot : l'archivage a consommé le préfixe
	s.mu.Unlock()
	writeJSON(w, 200, map[string]any{
		"ok":      true,
		"root":    p.Root,
		"before":  p.Before,
		"archive": archive,
		"events":  s.Store.Count(),
	})
}

// GET /admin/identity — onion, clé gossip publique, date de création.
func (s *Server) handleIdentity(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	createdAt := "?"
	if st, err := os.Stat(s.DataDir + "/identity.json"); err == nil {
		createdAt = st.ModTime().UTC().Format(time.RFC3339)
	}
	writeJSON(w, 200, map[string]any{
		"onion":      s.Onion,
		"public_key": s.GossipPub,
		"created_at": createdAt,
	})
}

// POST /admin/identity/rotate — rotation de l'identité onion : PRÉVUE en v0.4
// (roadmap APPLI_ADMIN.md §7 — double publication, annonce aux pairs). Réponse
// honnête 501 tant que le protocole de bascule n'est pas spécifié.
func (s *Server) handleIdentityRotate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	writeJSON(w, 501, map[string]string{"error": "rotation d'identité : prévue en v0.4 (roadmap admin — bascule à double publication)"})
}

// GET /admin/keys — clés admin autorisées.
func (s *Server) handleKeys(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		keys := []map[string]string{}
		for _, k := range s.Cfg.AdminKeys() {
			keys = append(keys, map[string]string{
				"public_key": k.PublicKey,
				"label":      k.Label,
				"added_at":   k.AddedAt,
			})
		}
		writeJSON(w, 200, map[string]any{"keys": keys})
	case http.MethodPost:
		var req struct {
			PublicKey string `json:"public_key"`
			Label     string `json:"label"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, 400, map[string]string{"error": "JSON invalide"})
			return
		}
		pub, err := base64.StdEncoding.DecodeString(strings.TrimSpace(req.PublicKey))
		if err != nil || len(pub) != ed25519.PublicKeySize {
			writeJSON(w, 400, map[string]string{"error": "public_key invalide (base64, 32 octets ed25519)"})
			return
		}
		added := s.Cfg.AddAdminKey(AdminKeyView{
			PublicKey: strings.TrimSpace(req.PublicKey),
			Label:     req.Label,
			AddedAt:   time.Now().UTC().Format(time.RFC3339),
		})
		if !added {
			writeJSON(w, 200, map[string]any{"ok": true, "note": "clé déjà autorisée"})
			return
		}
		if err := s.Cfg.Save(); err != nil {
			writeJSON(w, 500, map[string]string{"error": "persistance: " + err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true, "label": req.Label})
	default:
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
	}
}

// DELETE /admin/keys/{clé} — révoque un appareil (perte de téléphone/PC).
// Garde-fou : refuse de retirer la DERNIÈRE clé (anti-verrouillage).
func (s *Server) handleKeyDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	key := strings.TrimPrefix(r.URL.Path, "/admin/keys/")
	if key == "" {
		writeJSON(w, 400, map[string]string{"error": "clé manquante"})
		return
	}
	removed, err := s.Cfg.RemoveAdminKey(key)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	if !removed {
		writeJSON(w, 404, map[string]string{"error": "clé inconnue"})
		return
	}
	if err := s.Cfg.Save(); err != nil {
		writeJSON(w, 500, map[string]string{"error": "persistance: " + err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "revoked": key})
}
