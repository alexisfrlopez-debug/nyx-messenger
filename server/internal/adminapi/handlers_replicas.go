// GET /admin/replicas — réplicas temporaires auto-déclarés (v0.2.25).
//
// Les clients « test 2 » déclarent leur réplica temporaire embarqué DANS
// leur carte de profil publique (champ « replica », whitelisté côté serveur
// avec garde-fou d'identité : adresse == auteur — cf. journal.ProfileFromEvent).
// Le serveur agrège ces champs (journal.Store.ReplicaAnnouncements) et les
// expose ici : la console nyx-admin alimente son onglet « Répl. embarqués »
// avec cette liste (AUTO-AJOUT, demande opérateur) en complément de sa liste
// manuelle — plus aucune inscription à la main.
//
// Réponse : {"replicas":[{"author":"…onion","addr":"…onion:80","ts":unix}]}
// triée par annonce la plus récente d'abord. Une annonce de plus de 7 jours
// (téléphone ne servant plus) est retirée par le serveur lui-même.
package adminapi

import (
	"net/http"
	"time"
)

// ReplicaView — vue d'un réplica temporaire auto-déclaré.
type ReplicaView struct {
	Author string `json:"author"`
	Addr   string `json:"addr"`
	TS     int64  `json:"ts"`
}

// handleReplicas — GET : liste triée des réplicas temporaires actifs.
func (s *Server) handleReplicas(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "méthode interdite"})
		return
	}
	provider := s.Replicas
	if provider == nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"replicas": []ReplicaView{}})
		return
	}
	const maxAge = 7 * 24 * 3600 // 7 jours sans ré-annonce = adresse retirée
	list := provider(time.Now().Unix(), maxAge)
	if list == nil {
		list = []ReplicaView{}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"replicas": list})
}
