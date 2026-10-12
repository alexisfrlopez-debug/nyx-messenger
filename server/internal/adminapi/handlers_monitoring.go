// Package adminapi — handlers de monitoring (read-only, APPLI_ADMIN.md §4).
package adminapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// GET /admin/status — version, uptime, état, journal, pairs vivants, seq par auteur.
// Champs consommés par NodeStatus.parse (console) : version, uptime_seconds,
// state, events, journal_bytes, peers_alive (+ extension : seqs, onion, gossip_pub).
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	state := "actif"
	if s.Maintenance != nil && s.Maintenance.Load() == 1 {
		state = "maintenance"
	}
	alive := 0
	for _, p := range s.peerStatsSafe() {
		if time.Since(p.LastContact) < 10*time.Minute {
			alive++
		}
	}
	resp := map[string]any{
		"version":        s.Version,
		"onion":          s.Onion,
		"gossip_pub":     s.GossipPub,
		"uptime_seconds": int64(time.Since(s.StartedAt).Seconds()),
		"state":          state,
		"events":         s.Store.Count(),
		"journal_bytes":  s.Store.JournalBytes(),
		"peers_alive":    alive,
	}
	if seqs := s.storeSeqs(); seqs != nil {
		resp["seqs"] = seqs
	}
	writeJSON(w, 200, resp)
}

// storeSeqs — derniers seq par auteur (vue « Journal » de la console).
func (s *Server) storeSeqs() map[string]uint64 {
	type seqser interface{ SeqsByAuthor() map[string]uint64 }
	if sq, ok := s.Store.(seqser); ok {
		return sq.SeqsByAuthor()
	}
	return nil
}

func (s *Server) peerStatsSafe() []PeerStat {
	if s.PeerStats == nil {
		return nil
	}
	return s.PeerStats()
}

// GET /admin/resources — CPU/RAM/disque (lecture /proc + statfs), taille data-dir.
func (s *Server) handleResources(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	resp := map[string]any{}

	if cpu, ok := s.cpuPercent(); ok {
		resp["cpu_percent"] = cpu
	}
	if used, total, ok := procMem(); ok {
		resp["ram_used_bytes"] = used
		resp["ram_total_bytes"] = total
	}
	if used, total, ok := diskUsage(s.DataDir); ok {
		resp["disk_used_bytes"] = used
		resp["disk_total_bytes"] = total
	}
	resp["data_dir_bytes"] = dirSize(s.DataDir)
	writeJSON(w, 200, resp)
}

// cpuPercent — delta (utime+stime)/écoulé depuis l'appel précédent, normalisé
// par le nombre de cœurs. Deux appels rapprochés donnent une mesure bruitée :
// la console interroge à la seconde — suffisant pour un indicateur.
func (s *Server) cpuPercent() (float64, bool) {
	b, err := os.ReadFile("/proc/self/stat")
	if err != nil {
		return 0, false
	}
	// champ 14 (utime) et 15 (stime) — après le PID et comm entre parenthèses
	parts := strings.Fields(string(b))
	if len(parts) < 15 {
		return 0, false
	}
	// comm peut contenir des espaces : repartir de la dernière ')'
	idx := strings.LastIndex(string(b), ")")
	if idx < 0 {
		return 0, false
	}
	fields := strings.Fields(string(b)[idx+1:])
	if len(fields) < 12 { // fields[10]=utime, fields[11]=stime après comm
		return 0, false
	}
	utime, e1 := strconv.ParseFloat(fields[10], 64)
	stime, e2 := strconv.ParseFloat(fields[11], 64)
	if e1 != nil || e2 != nil {
		return 0, false
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	ticks := float64(runtime.NumCPU()) * 100 // _SC_CLK_TCK = 100 sur Linux
	if s.prevAt.IsZero() {
		s.prevCPU, s.prevAt = cpuSample{utime + stime, now}, now
		return 0, false // première mesure : pas de delta exploitable
	}
	dCpu := (utime + stime) - s.prevCPU.ticks
	dSec := now.Sub(s.prevAt).Seconds()
	s.prevCPU, s.prevAt = cpuSample{utime + stime, now}, now
	if dSec <= 0 || ticks == 0 {
		return 0, false
	}
	return round2(dCpu / dSec * ticks), true
}

type cpuSample struct {
	ticks float64
	at    time.Time
}

func round2(f float64) float64 { return float64(int(f*100+0.5)) / 100 }

// procMem — RSS du processus nœud + MemTotal (indicateur opérateur).
func procMem() (used, total int64, ok bool) {
	if b, err := os.ReadFile("/proc/self/status"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if strings.HasPrefix(line, "VmRSS:") {
				fmt.Sscanf(line, "VmRSS: %d", &used)
				used *= 1024
			}
		}
	}
	if b, err := os.ReadFile("/proc/meminfo"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if strings.HasPrefix(line, "MemTotal:") {
				fmt.Sscanf(line, "MemTotal: %d", &total)
				total *= 1024
			}
		}
	}
	return used, total, used > 0 && total > 0
}

// diskUsage — espace du système de fichiers hébergeant dataDir.
func diskUsage(path string) (used, total int64, ok bool) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, 0, false
	}
	bsize := int64(st.Bsize)
	total = int64(st.Blocks) * bsize
	free := int64(st.Bavail) * bsize
	return total - free, total, true
}

// dirSize — taille récursive du répertoire (data-dir).
func dirSize(path string) int64 {
	var total int64
	_ = filepath.Walk(path, func(_ string, info os.FileInfo, err error) error {
		if err == nil && info.Mode().IsRegular() {
			total += info.Size()
		}
		return nil
	})
	return total
}

// GET /admin/peers — pairs : onion, dernier contact, curseurs, backoff, latence.
func (s *Server) handlePeers(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		out := []map[string]any{}
		for _, p := range s.peerStatsSafe() {
			e := map[string]any{"onion": p.Onion}
			if !p.LastContact.IsZero() {
				e["last_contact"] = p.LastContact.UTC().Format(time.RFC3339)
			} else {
				e["last_contact"] = "jamais"
			}
			e["push_cursor"] = p.PushCursor
			e["pull_cursor"] = p.PullCursor
			e["backoff_seconds"] = p.BackoffSeconds
			e["latency_ms"] = p.LatencyMs
			out = append(out, e)
		}
		writeJSON(w, 200, map[string]any{"peers": out})
	case http.MethodPost:
		var req struct {
			Onion     string `json:"onion"`
			GossipPub string `json:"gossip_pub"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, 400, map[string]string{"error": "JSON invalide"})
			return
		}
		if s.AddPeer == nil {
			writeJSON(w, 501, map[string]string{"error": "gestion des pairs indisponible"})
			return
		}
		if err := s.AddPeer(req.Onion, req.GossipPub); err != nil {
			writeJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true, "onion": req.Onion})
	default:
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
	}
}

// DELETE /admin/peers/{onion} — retire un pair (persisté).
func (s *Server) handlePeerDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	onion := strings.TrimPrefix(r.URL.Path, "/admin/peers/")
	if onion == "" {
		writeJSON(w, 400, map[string]string{"error": "adresse onion manquante"})
		return
	}
	if s.RemovePeer == nil {
		writeJSON(w, 501, map[string]string{"error": "gestion des pairs indisponible"})
		return
	}
	if err := s.RemovePeer(onion); err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "onion": onion})
}

// POST /admin/peers/resync {"onion":…} — remet le curseur du pair à zéro :
// le prochain pull ré-aspire tout le journal (dé-dup par id côté intégration).
func (s *Server) handlePeersResync(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	var req struct {
		Onion string `json:"onion"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Onion == "" {
		writeJSON(w, 400, map[string]string{"error": "champ onion requis"})
		return
	}
	if s.ResyncPeer == nil {
		writeJSON(w, 501, map[string]string{"error": "gestion des pairs indisponible"})
		return
	}
	if err := s.ResyncPeer(req.Onion); err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "onion": req.Onion, "action": "resync"})
}

// GET /admin/log?tail=n — journal d'audit des commandes admin (n défaut 200, max 1000).
func (s *Server) handleLog(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
		return
	}
	tail := 200
	if v := r.URL.Query().Get("tail"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			tail = n
		}
	}
	if tail > 1000 {
		tail = 1000
	}
	lines, err := tailLines(s.auditPath(), tail)
	if err != nil {
		writeJSON(w, 200, map[string]any{"entries": []string{}})
		return
	}
	writeJSON(w, 200, map[string]any{"entries": lines})
}

// tailLines — les n dernières lignes d'un fichier JSONL (scan inverse simple :
// le fichier est petit — une ligne par commande admin).
func tailLines(path string, n int) ([]string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var lines []string
	for _, l := range strings.Split(strings.TrimRight(string(b), "\n"), "\n") {
		if l != "" {
			lines = append(lines, l)
		}
	}
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines, nil
}
