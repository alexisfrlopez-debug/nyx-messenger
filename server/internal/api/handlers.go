// Package api — routes HTTP du nœud (clients + gossip), servies derrière Tor.
package api

import (
        "crypto/rand"
        "encoding/base64"
        "encoding/json"
        "fmt"
        "log"
        "net/http"
        "strconv"
        "strings"
        "time"

        "golang.org/x/crypto/curve25519"
        "golang.org/x/crypto/nacl/secretbox"

        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/config"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

var b64 = base64.StdEncoding

// v0.2.42 — BORNAGE DES CORPS HTTP (retour d'audit session 63) : sans
// MaxBytesReader, un POST de plusieurs Go était intégralement décodé en
// mémoire AVANT que la validation ne le rejette (DoS mémoire trivial à
// travers un onion). Limites alignées sur la spec (protocol/specs/api.md) :
// événement client ≤ 256 Ko, enveloppe gossip (lot ≤ 5 Mo + base64) ≤ 7 Mo.
const maxClientBody = 256 << 10
const maxGossipBody = 7 << 20

// limitedBody — remplace le corps par une version bornée ; les handlers
// décodent ensuite normalement, le décodeur échoue proprement au-delà.
func limitedBody(w http.ResponseWriter, r *http.Request, n int64) *http.Request {
        r.Body = http.MaxBytesReader(w, r.Body, n)
        return r
}

// Server — dépendances des handlers.
type Server struct {
        Version    string
        Onion      string // "xxxx.onion"
        GossipPub  string // base64
        GossipPriv []byte
        Store      *journal.Store
        Peers      func() []config.Peer // liste courante des pairs
        AddPeer    func(p config.Peer)  // enregistrement d'un pair découvert
        Kick       chan struct{}        // notification « nouvel événement local »
        // Maintenance — vrai si le nœud a été placé en maintenance par l'opérateur
        // (API admin, dépôt privé APPLI_ADMIN.md §4) : les routes client répondent
        // 503, le gossip inter-réplicas continue (drain). Nil-safe (défaut : actif).
        Maintenance func() bool
        // Rendezvous signé (ADR-016) — closures de lecture servies à /rendezvous.
        // RendezvousRead renvoie (json, sig, ok) ; ok=false → 404 (non configuré).
        RendezvousRead func() (json []byte, sig []byte, ok bool)
}

// inMaintenance — état de maintenance (nil = jamais en maintenance).
func (s *Server) inMaintenance() bool { return s.Maintenance != nil && s.Maintenance() }

// rejectIfMaintenance — 503 pour les routes client si le nœud est en maintenance.
func (s *Server) rejectIfMaintenance(w http.ResponseWriter) bool {
        if s.inMaintenance() {
                writeJSON(w, 503, map[string]string{"error": "nœud en maintenance — réessayez plus tard"})
                return true
        }
        return false
}

// Handler — construit le routeur complet.
func (s *Server) Handler() http.Handler {
        mux := http.NewServeMux()
        mux.HandleFunc("/health", s.handleHealth)
        mux.HandleFunc("/identity", s.handleIdentity)
        mux.HandleFunc("/client/event", s.handleClientEvent)
        mux.HandleFunc("/client/inbox", s.handleInbox)
        mux.HandleFunc("/client/events", s.handleEventsByAuthor)
        mux.HandleFunc("/client/alias", s.handleAliasResolve)
        mux.HandleFunc("/client/profile", s.handleProfileGet)
        mux.HandleFunc("/gossip/peers", s.handleGossipPeers)
        mux.HandleFunc("/gossip/push", s.handleGossipPush)
        mux.HandleFunc("/gossip/pull", s.handleGossipPull)
        mux.HandleFunc("/rendezvous", s.handleRendezvous)
        // v0.2.36 — relais média d'appel (repli « décrochage ») : appariement
        // par cid + pipe d'octets (cf. relay.go). Réfusé en maintenance.
        mux.HandleFunc("/call/relay", s.handleCallRelay)
        // v0.2.38 — NYX-LINK, lien inversé (ADR-018) : le téléphone tire ses
        // requêtes par long-poll, les demandeurs déposent/attendent (cf. link.go).
        mux.HandleFunc("/link/send", s.handleLinkSend)
        mux.HandleFunc("/link/pull", s.handleLinkPull)
        mux.HandleFunc("/link/reply", s.handleLinkReply)
        mux.HandleFunc("/link/wait", s.handleLinkWait)
        return mux
}

func writeJSON(w http.ResponseWriter, code int, v any) {
        w.Header().Set("Content-Type", "application/json")
        w.WriteHeader(code)
        _ = json.NewEncoder(w).Encode(v)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
        writeJSON(w, 200, map[string]any{
                "ok":      true,
                "version": s.Version,
                "onion":   s.Onion,
                "events":  s.Store.Count(),
        })
}

// GET /rendezvous — liste de rendezvous signée (ADR-009 §3, ADR-016).
// Envelope {"json_b64", "sig_b64"} : le client vérifie la signature ed25519
// détachée sur b"nyx-rendezvous-v1|" + octets du JSON (clé publique de release
// embarquée dans l'APK) — la route est NON AUTHENTIFIÉE car la liste est
// publique et auto-vérifiable ; elle reste servie en maintenance (statique).
// 404 si le nœud n'a pas de liste configurée (comportement v0.2.22).
func (s *Server) handleRendezvous(w http.ResponseWriter, r *http.Request) {
        if r.Method != http.MethodGet {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        if s.RendezvousRead == nil {
                writeJSON(w, 404, map[string]string{"error": "rendezvous non configuré"})
                return
        }
        raw, sig, ok := s.RendezvousRead()
        if !ok {
                writeJSON(w, 404, map[string]string{"error": "rendezvous non configuré"})
                return
        }
        writeJSON(w, 200, map[string]string{
                "json_b64": b64.EncodeToString(raw),
                "sig_b64":  b64.EncodeToString(sig),
        })
}

func (s *Server) handleIdentity(w http.ResponseWriter, r *http.Request) {
        writeJSON(w, 200, map[string]string{
                "onion":      s.Onion,
                "gossip_pub": s.GossipPub,
        })
}

// POST /client/event — dépôt d'un événement signé par un client.
func (s *Server) handleClientEvent(w http.ResponseWriter, r *http.Request) {
        if r.Method != http.MethodPost {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        if s.rejectIfMaintenance(w) {
                return
        }
        // v0.2.42 — corps borné AVANT décodage (256 Ko, cf. constante).
        r = limitedBody(w, r, maxClientBody)
        var ev journal.Event
        if err := json.NewDecoder(r.Body).Decode(&ev); err != nil {
                writeJSON(w, 400, map[string]string{"error": "JSON invalide: " + err.Error()})
                return
        }
        dup, err := s.Store.Append(&ev, time.Now().Unix())
        if err != nil {
                // en attente de seq (futur) ou invalide : distinguer via message
                if dup {
                        writeJSON(w, 409, map[string]string{"error": "déjà connu", "id": ev.ID()})
                        return
                }
                writeJSON(w, 400, map[string]string{"error": err.Error()})
                return
        }
        // Re-propager immédiatement vers les pairs
        select {
        case s.Kick <- struct{}{}:
        default:
        }
        writeJSON(w, 200, map[string]any{"accepted": true, "id": ev.ID()})
}

// GET /client/alias?name=<alias> — résolution d'un pseudonyme unique
// (wire-format.md §6, api.md « /client/alias »). Pas de listing global (anti-énumération).
func (s *Server) handleAliasResolve(w http.ResponseWriter, r *http.Request) {
        if r.Method != http.MethodGet {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        if s.rejectIfMaintenance(w) {
                return
        }
        name := r.URL.Query().Get("name")
        if !journal.AliasRE.MatchString(name) {
                writeJSON(w, 400, map[string]string{"error": "paramètre name requis (format ^[a-z0-9_]{3,24}$)"})
                return
        }
        author, taken := s.Store.ResolveAlias(name)
        resp := map[string]any{"alias": name, "taken": taken}
        if taken {
                resp["by"] = author
        }
        writeJSON(w, 200, resp)
}

// GET /client/inbox?to=<onion>&after=<curseur global>&limit=
func (s *Server) handleInbox(w http.ResponseWriter, r *http.Request) {
        if r.Method != http.MethodGet {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        if s.rejectIfMaintenance(w) {
                return
        }
        to := r.URL.Query().Get("to")
        if to == "" {
                writeJSON(w, 400, map[string]string{"error": "paramètre to requis"})
                return
        }
        after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
        limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
        events, cursor := s.Store.Inbox(to, after, limit)
        if events == nil {
                events = []*journal.Event{}
        }
        writeJSON(w, 200, map[string]any{"events": events, "cursor": cursor})
}

// GET /client/events?author=<onion>&since=<seq>&limit=
func (s *Server) handleEventsByAuthor(w http.ResponseWriter, r *http.Request) {
        if s.rejectIfMaintenance(w) {
                return
        }
        author := r.URL.Query().Get("author")
        if author == "" {
                writeJSON(w, 400, map[string]string{"error": "paramètre author requis"})
                return
        }
        since, _ := strconv.ParseUint(r.URL.Query().Get("since"), 10, 64)
        limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
        events := s.Store.EventsByAuthor(author, since, limit)
        if events == nil {
                events = []*journal.Event{}
        }
        writeJSON(w, 200, map[string]any{"events": events})
}

// GET /client/profile?author=<onion> — carte de profil publique courante d'un
// auteur (type "profile" en diffusion — wire-format.md §7, api.md v1.2).
// 404 si l'auteur n'a jamais publié de profil public.
func (s *Server) handleProfileGet(w http.ResponseWriter, r *http.Request) {
        if r.Method != http.MethodGet {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        if s.rejectIfMaintenance(w) {
                return
        }
        author := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("author")))
        if author == "" {
                writeJSON(w, 400, map[string]string{"error": "paramètre author requis"})
                return
        }
        if _, err := journal.OnionToPubKey(author); err != nil {
                writeJSON(w, 400, map[string]string{"error": "adresse onion invalide"})
                return
        }
        ev := s.Store.ResolveProfile(author)
        if ev == nil {
                writeJSON(w, 404, map[string]string{"error": "aucun profil public pour cet auteur"})
                return
        }
        // La carte a été validée à l'insertion ; on renvoie les champs décodés.
        fields, err := journal.ProfileFromEvent(ev)
        if err != nil {
                writeJSON(w, 500, map[string]string{"error": "profil indexé illisible"})
                return
        }
        writeJSON(w, 200, map[string]any{
                "author": ev.Author,
                "seq":    ev.Seq,
                "ts":     ev.TS,
                "fields": fields,
        })
}

// ---- gossip ----

func (s *Server) handleGossipPeers(w http.ResponseWriter, r *http.Request) {
        peers := s.Peers()
        out := make([]map[string]string, 0, len(peers))
        for _, p := range peers {
                if p.Onion == s.Onion {
                        continue
                }
                out = append(out, map[string]string{"onion": p.Onion, "gossip_pub": p.GossipPub})
        }
        writeJSON(w, 200, map[string]any{"peers": out})
}

// decryptEnvelope — authentifie l'expéditeur (connu) et déchiffre son payload.
func (s *Server) decryptEnvelope(r *http.Request) ([]byte, string, error) {
        // v0.2.42 — enveloppe gossip bornée AVANT décodage (lot ≤ 5 Mo en
        // base64 + marge, cf. constante) : un corps géant ne peut plus être lu
        // en mémoire avant l'échec de déchiffrement.
        r.Body = http.MaxBytesReader(nil, r.Body, maxGossipBody)
        var env struct {
                Sender  string `json:"sender"`
                Nonce   string `json:"nonce"`
                Payload string `json:"payload"`
        }
        if err := json.NewDecoder(r.Body).Decode(&env); err != nil {
                return nil, "", fmt.Errorf("enveloppe invalide")
        }
        var peerPub string
        for _, p := range s.Peers() {
                if p.Onion == env.Sender {
                        peerPub = p.GossipPub
                        break
                }
        }
        if peerPub == "" {
                return nil, "", fmt.Errorf("expéditeur inconnu: %s", env.Sender)
        }
        pub, err := b64.DecodeString(peerPub)
        if err != nil || len(pub) != 32 {
                return nil, "", fmt.Errorf("clé gossip du pair invalide")
        }
        shared, err := curve25519.X25519(s.GossipPriv, pub)
        if err != nil {
                return nil, "", err
        }
        nonce, err := b64.DecodeString(env.Nonce)
        if err != nil || len(nonce) != 24 {
                return nil, "", fmt.Errorf("nonce invalide")
        }
        sealed, err := b64.DecodeString(env.Payload)
        if err != nil {
                return nil, "", fmt.Errorf("payload invalide")
        }
        clear, ok := secretbox.Open(nil, sealed, (*[24]byte)(nonce), (*[32]byte)(shared))
        if !ok {
                return nil, "", fmt.Errorf("déchiffrement impossible")
        }
        return clear, env.Sender, nil
}

func (s *Server) encryptEnvelope(peerOnion, peerPubB64 string, clear []byte) (map[string]string, error) {
        var peerPub []byte
        var err error
        if peerPub, err = b64.DecodeString(peerPubB64); err != nil || len(peerPub) != 32 {
                return nil, fmt.Errorf("clé gossip du pair invalide")
        }
        shared, err := curve25519.X25519(s.GossipPriv, peerPub)
        if err != nil {
                return nil, err
        }
        nonce := make([]byte, 24)
        if _, err := rand.Read(nonce); err != nil {
                return nil, err
        }
        sealed := secretbox.Seal(nil, clear, (*[24]byte)(nonce), (*[32]byte)(shared))
        return map[string]string{
                "sender":  s.Onion,
                "nonce":   b64.EncodeToString(nonce),
                "payload": b64.EncodeToString(sealed),
        }, nil
}

// POST /gossip/push — lot d'événements d'un pair.
func (s *Server) handleGossipPush(w http.ResponseWriter, r *http.Request) {
        clear, sender, err := s.decryptEnvelope(r)
        if err != nil {
                writeJSON(w, 401, map[string]string{"error": err.Error()})
                return
        }
        var req struct {
                Events []*journal.Event `json:"events"`
        }
        if err := json.Unmarshal(clear, &req); err != nil {
                writeJSON(w, 400, map[string]string{"error": "payload push invalide"})
                return
        }
        accepted, rejected := 0, 0
        now := time.Now().Unix()
        for _, ev := range req.Events {
                dup, err := s.Store.Append(ev, now)
                switch {
                case err == nil:
                        accepted++
                case dup:
                        // déjà connu : ni accepté ni rejeté (idempotent) — compté comme accepté
                        accepted++
                default:
                        rejected++
                        log.Printf("[api] push %s: rejet seq %d de %s: %v", sender, ev.Seq, ev.Author, err)
                }
        }
        writeJSON(w, 200, map[string]int{"accepted": accepted, "rejected": rejected})
}

// POST /gossip/pull — lot d'événements demandé par un pair.
func (s *Server) handleGossipPull(w http.ResponseWriter, r *http.Request) {
        clear, sender, err := s.decryptEnvelope(r)
        if err != nil {
                writeJSON(w, 401, map[string]string{"error": err.Error()})
                return
        }
        var req struct {
                Want  map[string]uint64 `json:"want"`
                Limit int               `json:"limit"`
        }
        if err := json.Unmarshal(clear, &req); err != nil {
                writeJSON(w, 400, map[string]string{"error": "payload pull invalide"})
                return
        }
        events, more := s.Store.EventsFor(req.Want, req.Limit)
        if events == nil {
                events = []*journal.Event{}
        }
        var peerPub string
        for _, p := range s.Peers() {
                if p.Onion == sender {
                        peerPub = p.GossipPub
                        break
                }
        }
        resp, err := json.Marshal(map[string]any{"events": events, "more": more})
        if err != nil {
                writeJSON(w, 500, map[string]string{"error": "encodage réponse"})
                return
        }
        env, err := s.encryptEnvelope(sender, peerPub, resp)
        if err != nil {
                writeJSON(w, 500, map[string]string{"error": err.Error()})
                return
        }
        writeJSON(w, 200, env)
}
