// Package adminapi — API d'administration privilégiée du nœud (console nyx-admin).
//
// SPÉCIFICATION DE RÉFÉRENCE : docs/APPLI_ADMIN.md (§3 authentification, §4 API)
// — document qui vit dans le dépôt PRIVÉ nyx-admin (règle opérateur : les
// endpoints d'administration ne sont pas annoncés dans la doc publique ;
// l'implémentation serveur reste publique car le nœud est un logiciel ouvert —
// la sécurité repose sur la possession de clé, pas sur l'obscurité).
//
// DEUX BARRIÈRES indépendantes :
//
//  1. (réseau) le service onion d'admin est un onion DÉDIÉ, publié séparément
//     (port 9091), protégé par Tor ClientAuthorization côté démon tor — avant
//     même HTTP ;
//
//  2. (application) chaque commande est signée ed25519 par une clé admin
//     déclarée dans la config, sur une chaîne canonique figée :
//
//     nyx-admin-v1 \n <ts> \n <nonce b64> \n <METHOD> \n <path?query> \n <sha256(body) hex>
//
//     avec fenêtre temporelle ±120 s et cache de nonce (anti-replay).
package adminapi

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var b64 = base64.StdEncoding

// Constantes de la barrière applicative (miroir exact de AdminSigner.kt).
const (
	CanonicalPrefix = "nyx-admin-v1"
	TSWindowSec     = 120              // fenêtre de fraîcheur de l'horodatage
	NonceTTL        = 10 * time.Minute // marge large devant la fenêtre
)

// PeerStat — instantané de santé d'un pair (alimenté par le moteur gossip).
type PeerStat struct {
	Onion          string
	LastContact    time.Time
	LatencyMs      int
	BackoffSeconds int
	PushCursor     uint64
	PullCursor     uint64
}

// KeyStore — surface de gestion des clés admin fournie par config.Config
// (adaptée dans main.go pour découpler les packages).
type KeyStore interface {
	HasAdminKey(pubB64 string) bool
	AddAdminKey(k AdminKeyView) bool
	RemoveAdminKey(pubB64 string) (bool, error)
	AdminKeys() []AdminKeyView
	Save() error
}

// AdminKeyView — vue minimale d'une clé admin (découplage du package config).
type AdminKeyView struct {
	PublicKey string
	Label     string
	AddedAt   string
}

// JournalStore — surface du journal utilisée par l'admin (découplage).
type JournalStore interface {
	Count() int
	JournalBytes() int64
	MerkleRoot(before int) (string, error)
	ApplyCheckpoint(before int, expectedRoot string) (string, error)
}

// Server — dépendances de l'API admin. Toutes les capacités du nœud sont
// injectées par main.go sous forme de closures (pas d'import circulaire).
type Server struct {
	Version   string
	Onion     string
	GossipPub string
	Store     JournalStore
	Cfg       KeyStore
	StartedAt time.Time

	// Maintenance — partagé avec l'API client (main.go) : 0 = actif, 1 = maintenance.
	Maintenance *atomic.Int32

	// Actions déléguées au moteur gossip.
	PeerStats  func() []PeerStat
	AddPeer    func(onion, gossipPub string) error
	RemovePeer func(onion string) error
	ResyncPeer func(onion string) error

	// StopRequest — reçoit "shutdown" ou "restart" ; main.go orchestre l'arrêt
	// gracieux (flush config, fermeture, exit 0 — systemd Restart=always relance).
	StopRequest chan string

	// SignRoot — signature ed25519 de la racine de Merkle (clé onion du nœud).
	SignRoot func(rootHex string) (string, error)

	DataDir string

	nonceMu sync.Mutex
	nonces  map[string]time.Time // nonce (telle quelle) → vu à

	mu       sync.Mutex
	proposal *checkpointProposal
	prevCPU  cpuSample
	prevAt   time.Time
}

// checkpointProposal — proposition d'élagage en attente d'application (2 étapes).
type checkpointProposal struct {
	Before    int
	Root      string
	Signature string
	At        time.Time
}

const proposalTTL = 10 * time.Minute

// Handler — routeur admin complet, TOUTES les routes protégées.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	auth := func(h http.HandlerFunc) http.HandlerFunc { return s.authenticated(h) }

	mux.HandleFunc("/admin/status", auth(s.handleStatus))
	mux.HandleFunc("/admin/resources", auth(s.handleResources))
	mux.HandleFunc("/admin/peers/resync", auth(s.handlePeersResync))
	mux.HandleFunc("/admin/peers/", auth(s.handlePeerDelete)) // DELETE /admin/peers/{onion}
	mux.HandleFunc("/admin/peers", auth(s.handlePeers))       // GET liste / POST ajout
	mux.HandleFunc("/admin/log", auth(s.handleLog))
	mux.HandleFunc("/admin/maintenance", auth(s.handleMaintenance))
	mux.HandleFunc("/admin/shutdown", auth(s.handleShutdown))
	mux.HandleFunc("/admin/restart", auth(s.handleRestart))
	mux.HandleFunc("/admin/checkpoint/propose", auth(s.handleCheckpointPropose))
	mux.HandleFunc("/admin/checkpoint/apply", auth(s.handleCheckpointApply))
	mux.HandleFunc("/admin/identity", auth(s.handleIdentity))
	mux.HandleFunc("/admin/identity/rotate", auth(s.handleIdentityRotate))
	mux.HandleFunc("/admin/keys/", auth(s.handleKeyDelete)) // DELETE /admin/keys/{clé}
	mux.HandleFunc("/admin/keys", auth(s.handleKeys))       // GET liste / POST ajout
	return mux
}

// authenticated — barrière 2 : vérifie ts, nonce, clé autorisée, signature
// ed25519 sur la chaîne canonique, puis journalise (audit) et délègue.
func (s *Server) authenticated(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		pubB64 := r.Header.Get("X-Nyx-Admin")
		tsS := r.Header.Get("X-Nyx-Ts")
		nonceB64 := r.Header.Get("X-Nyx-Nonce")
		sigB64 := r.Header.Get("X-Nyx-Sig")
		code, err := s.verify(pubB64, tsS, nonceB64, sigB64, r)
		if err != nil {
			s.audit(pubB64, r, code, err.Error())
			writeJSON(w, code, map[string]string{"error": err.Error()})
			return
		}
		// Le nonce n'est consommé qu'après vérification complète : un échec de
		// signature ne doit pas brûler le nonce d'une requête corrigée ensuite.
		s.rememberNonce(nonceB64)
		rec := &statusRecorder{ResponseWriter: w, code: 200}
		next(rec, r)
		s.audit(pubB64, r, rec.code, "")
	}
}

// verify — les vérifications de la barrière 2 (APPLI_ADMIN.md §3), dans l'ordre.
// Retourne (code HTTP conseillé, erreur) — (200, nil) si tout est bon.
func (s *Server) verify(pubB64, tsS, nonceB64, sigB64 string, r *http.Request) (int, error) {
	if pubB64 == "" || tsS == "" || nonceB64 == "" || sigB64 == "" {
		return 401, fmt.Errorf("en-têtes d'authentification admin incomplets")
	}
	ts, err := parseTS(tsS)
	if err != nil {
		return 400, fmt.Errorf("horodatage invalide")
	}
	drift := time.Since(ts)
	if drift < 0 {
		drift = -drift
	}
	if drift > TSWindowSec*time.Second {
		return 401, fmt.Errorf("horodatage hors fenêtre (±%d s)", TSWindowSec)
	}
	nonce, err := b64.DecodeString(nonceB64)
	if err != nil || len(nonce) != 32 {
		return 400, fmt.Errorf("nonce invalide (32 octets attendus)")
	}
	if s.seenNonce(nonceB64) {
		return 401, fmt.Errorf("nonce déjà utilisé (rejeu suspecté)")
	}
	pub, err := b64.DecodeString(pubB64)
	if err != nil || len(pub) != ed25519.PublicKeySize {
		return 400, fmt.Errorf("clé admin invalide")
	}
	if !s.Cfg.HasAdminKey(pubB64) {
		return 403, fmt.Errorf("clé admin non autorisée")
	}
	sig, err := b64.DecodeString(sigB64)
	if err != nil || len(sig) != ed25519.SignatureSize {
		return 400, fmt.Errorf("signature invalide")
	}
	body := readBody(r)
	canonical := CanonicalString(ts.Unix(), nonceB64, r.Method, r.RequestURI, body)
	if !ed25519.Verify(ed25519.PublicKey(pub), []byte(canonical), sig) {
		return 401, fmt.Errorf("signature admin invalide")
	}
	return 200, nil
}

// readBody — lit le corps une fois et le remet à disposition des handlers
// (le hachage de la chaîne canonique et le décodage JSON l'exigent tous deux).
func readBody(r *http.Request) []byte {
	if r.Body == nil {
		return nil
	}
	b, err := io.ReadAll(r.Body)
	_ = r.Body.Close()
	if err != nil {
		b = nil
	}
	r.Body = io.NopCloser(strings.NewReader(string(b)))
	return b
}

func parseTS(s string) (time.Time, error) {
	var n int64
	if _, err := fmt.Sscanf(strings.TrimSpace(s), "%d", &n); err != nil {
		return time.Time{}, err
	}
	return time.Unix(n, 0), nil
}

// CanonicalString — CHAÎNE DE RÉFÉRENCE : doit rester identique à AdminSigner.kt
// (dépôt privé nyx-admin — spec APPLI_ADMIN.md §3). Toute divergence casse
// toutes les commandes : ne modifier qu'en révision de spec coordonnée.
func CanonicalString(ts int64, nonceB64, method, pathWithQuery string, body []byte) string {
	sum := sha256.Sum256(body)
	return strings.Join([]string{
		CanonicalPrefix,
		fmt.Sprintf("%d", ts),
		nonceB64,
		strings.ToUpper(method),
		pathWithQuery,
		fmt.Sprintf("%x", sum),
	}, "\n")
}

func (s *Server) seenNonce(n string) bool {
	s.nonceMu.Lock()
	defer s.nonceMu.Unlock()
	if s.nonces == nil {
		s.nonces = map[string]time.Time{}
	}
	s.gcNonces()
	_, ok := s.nonces[n]
	return ok
}

func (s *Server) rememberNonce(n string) {
	s.nonceMu.Lock()
	defer s.nonceMu.Unlock()
	if s.nonces == nil {
		s.nonces = map[string]time.Time{}
	}
	s.nonces[n] = time.Now()
}

func (s *Server) gcNonces() {
	now := time.Now()
	for k, at := range s.nonces {
		if now.Sub(at) > NonceTTL {
			delete(s.nonces, k)
		}
	}
}

// statusRecorder — capte le code de réponse pour le journal d'audit.
type statusRecorder struct {
	http.ResponseWriter
	code int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.code = code
	r.ResponseWriter.WriteHeader(code)
}

// audit — journal d'audit admin (JSONL local, relu par GET /admin/log).
// Empreinte de clé : 12 hex de sha256(clé) — corrélation appareil↔commandes
// sans dupliquer la clé dans le journal.
func (s *Server) audit(pubB64 string, r *http.Request, code int, errMsg string) {
	entry := map[string]any{
		"ts":     time.Now().Unix(),
		"key":    KeyFingerprint(pubB64),
		"method": r.Method,
		"path":   r.URL.Path,
		"code":   code,
	}
	if errMsg != "" {
		entry["error"] = errMsg
	}
	b, err := json.Marshal(entry)
	if err != nil {
		return
	}
	f, err := os.OpenFile(s.auditPath(), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		log.Printf("[admin] audit: %v", err)
		return
	}
	defer f.Close()
	_, _ = f.Write(append(b, '\n'))
}

func (s *Server) auditPath() string { return s.DataDir + "/admin_audit.jsonl" }

// KeyFingerprint — empreinte courte d'une clé admin (journal d'audit).
func KeyFingerprint(pubB64 string) string {
	if pubB64 == "" {
		return "-"
	}
	sum := sha256.Sum256([]byte(pubB64))
	return fmt.Sprintf("%x", sum[:6])
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
