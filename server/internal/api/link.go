package api

import (
        "crypto/ed25519"
        "crypto/sha256"
        "encoding/hex"
        "encoding/json"
        "log"
        "net/http"
        "strconv"
        "strings"
        "sync"
        "time"

        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

// v0.2.38 — NYX-LINK : LIEN INVERSÉ (ADR-018, session 68).
//
// Terrain (sessions 41 → 67, preuves 23/10) : les connexions SORTANTES du
// téléphone (vers les réplicas de flotte) fonctionnent dans 100 % des
// observations alors que son onion ENTRANT est chroniquement injoignable
// (descripteur non propagé, points d'introduction morts, cache client
// périmé jusqu'à 24 h) — structurel sur Android/5G, pas un bug. NYX-LINK
// inverse la connexion : le téléphone tire ses requêtes par un long-poll
// permanent, tout demandeur dépose et attend sa réponse ici.
//
// Sécurité / anonymat :
//   - mux gated client-auth Tor (comme TOUTES les routes client) ;
//   - PULL/REPLY authentifiés par signature ed25519 (détenteur de l'adresse
//     prouvé via journal.OnionToPubKey — le routeur ne connaît rien du
//     membre) ; SEND/WAIT anonymes mais bornés (comme le dépôt d'événement) ;
//   - le lien n'est PAS un proxy ouvert : paths whitelistés (routes réplica
//     uniquement), méthodes GET/POST, corps ≤ 320 Ko ;
//   - at-least-once : une requête délivrée non exécutée est RE-LIVRÉE après
//     20 s (un téléphone mort emporte son lot) — SÛR car le protocole
//     applicatif est idempotent par construction (dépôt = dédup eventId
//     sha256, inbox/alias/profile = lectures pures).
//
// Bornage : file ≤ 8 requêtes/destinataire (429 au-delà), TTL requête 90 s,
// TTL réponse 210 s (90 de vol + 120 de consultation), lot PULL ≤ 4
// requêtes / 512 Ko, UN canal PULL utile par destinataire.
const (
        linkMaxQueued  = 8
        linkReqTTL     = 90 * time.Second
        linkReplyTTL   = 120 * time.Second
        linkRedeliver  = 20 * time.Second
        linkPullWait   = 45 * time.Second
        linkWaitFor    = 75 * time.Second
        linkMaxBodyB64 = 320 << 10
        linkMaxLot     = 4
        linkMaxLotB    = 512 << 10
        linkAuthWinS   = 300
)

// linkRequest — une requête en transit vers le téléphone (spec link.md).
type linkRequest struct {
        ID      string `json:"id"`
        Method  string `json:"method"`
        Path    string `json:"path"`
        BodyB64 string `json:"body_b64"`
}

type linkReqEntry struct {
        req         linkRequest
        to56        string
        deliveredAt time.Time // zéro = jamais délivrée
        expiresAt   time.Time
        answered    bool // REPLY reçue — retrait logique
}

// linkWaiter — la « case réponse » d'un id, créée au SEND (le canal
// d'attente du demandeur doit exister AVANT l'arrivée de la réponse).
type linkWaiter struct {
        answered  bool
        status    int
        bodyB64   string
        expiresAt time.Time
        notify    chan struct{}
}

type linkPeer struct {
        order  []string // ids FIFO (compacté par le sweep)
        notify chan struct{}
}

var link = &linkHub{
        peers:   map[string]*linkPeer{},
        reqs:    map[string]*linkReqEntry{},
        waiters: map[string]*linkWaiter{},
}

type linkHub struct {
        mu      sync.Mutex
        peers   map[string]*linkPeer
        reqs    map[string]*linkReqEntry
        waiters map[string]*linkWaiter
}

// ---------- validation ----------

func linkOnion56(raw string) (string, bool) {
        s := strings.ToLower(strings.TrimSpace(raw))
        if s == "" {
                return "", false
        }
        if _, err := journal.OnionToPubKey(s); err != nil {
                return "", false
        }
        return strings.TrimSuffix(s, ".onion"), true
}

func linkValidID(id string) bool {
        if len(id) < 16 || len(id) > 64 {
                return false
        }
        for _, c := range id {
                if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
                        return false
                }
        }
        return true
}

// linkAllowedPath — le lien n'achemine QUE les routes du réplica (spec
// link.md) : jamais de proxy ouvert vers /admin, /gossip ou l'inconnu.
func linkAllowedPath(pathWithQuery string) bool {
        p := pathWithQuery
        if i := strings.IndexByte(p, '?'); i >= 0 {
                p = p[:i]
        }
        switch p {
        case "/health", "/client/event", "/client/inbox", "/client/profile", "/client/alias":
                return true
        }
        return false
}

// linkVerifySig — auth « ts:sigB64 » sur un canon exact (spec link.md).
// La chaîne d'adresse est celle ENVOYÉE par le client (avec ou sans suffixe
// .onion) : le canon signe ce qui transite, la clé est dérivée de l'adresse.
func linkVerifySig(addr, auth, prefix string, extra ...string) bool {
        parts := strings.SplitN(auth, ":", 2)
        if len(parts) != 2 {
                return false
        }
        ts, err := strconv.ParseInt(parts[0], 10, 64)
        if err != nil {
                return false
        }
        now := time.Now().Unix()
        if ts > now+linkAuthWinS || ts < now-linkAuthWinS {
                return false
        }
        sig, err := b64.DecodeString(parts[1])
        if err != nil {
                return false
        }
        pub, err := journal.OnionToPubKey(addr)
        if err != nil {
                return false
        }
        var sb strings.Builder
        sb.WriteString(prefix)
        sb.WriteString("\n")
        sb.WriteString(addr)
        sb.WriteString("\n")
        sb.WriteString(parts[0])
        for _, e := range extra {
                sb.WriteString("\n")
                sb.WriteString(e)
        }
        return ed25519.Verify(pub, []byte(sb.String()), sig)
}

// ---------- hub ----------

func (h *linkHub) notifyPeer(to56 string) {
        p := h.peers[to56]
        if p == nil {
                return
        }
        select {
        case p.notify <- struct{}{}:
        default:
        }
}

// sweepLocked — purge paresseuse (appelée sous mutex par chaque handler).
func (h *linkHub) sweepLocked(now time.Time) {
        for id, e := range h.reqs {
                if now.After(e.expiresAt) {
                        delete(h.reqs, id)
                }
        }
        for id, wt := range h.waiters {
                if now.After(wt.expiresAt) {
                        delete(h.waiters, id)
                }
        }
        for to, p := range h.peers {
                kept := p.order[:0]
                for _, id := range p.order {
                        if e, ok := h.reqs[id]; ok && !e.answered {
                                kept = append(kept, id)
                        }
                }
                if len(kept) == 0 {
                        delete(h.peers, to)
                } else {
                        p.order = kept
                }
        }
}

// ---------- handlers ----------

// handleLinkSend — POST /link/send : dépôt d'une requête pour un téléphone.
// 202 accepté · 400 validation · 409 id en vol · 429 file pleine · 503 maintenance.
func (s *Server) handleLinkSend(w http.ResponseWriter, r *http.Request) {
        if s.rejectIfMaintenance(w) {
                return
        }
        if r.Method != http.MethodPost {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        r = limitedBody(w, r, linkMaxBodyB64+(16<<10))
        var in struct {
                To      string `json:"to"`
                ID      string `json:"id"`
                Method  string `json:"method"`
                Path    string `json:"path"`
                BodyB64 string `json:"body_b64"`
        }
        if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
                writeJSON(w, 400, map[string]string{"error": "JSON invalide: " + err.Error()})
                return
        }
        to56, ok := linkOnion56(in.To)
        if !ok {
                writeJSON(w, 400, map[string]string{"error": "adresse onion invalide"})
                return
        }
        in.Method = strings.ToUpper(strings.TrimSpace(in.Method))
        if in.Method != "GET" && in.Method != "POST" {
                writeJSON(w, 400, map[string]string{"error": "method GET/POST requis"})
                return
        }
        if !linkAllowedPath(in.Path) {
                writeJSON(w, 400, map[string]string{"error": "path non autorisé par le lien (routes réplica uniquement)"})
                return
        }
        if len(in.BodyB64) > linkMaxBodyB64 {
                writeJSON(w, 400, map[string]string{"error": "corps trop volumineux"})
                return
        }
        if !linkValidID(in.ID) {
                writeJSON(w, 400, map[string]string{"error": "id invalide (hex 16-64)"})
                return
        }

        h := link
        h.mu.Lock()
        now := time.Now()
        h.sweepLocked(now)
        if e, busy := h.reqs[in.ID]; busy && now.Before(e.expiresAt) && !e.answered {
                h.mu.Unlock()
                writeJSON(w, 409, map[string]string{"error": "id déjà en vol"})
                return
        }
        p := h.peers[to56]
        if p == nil {
                p = &linkPeer{notify: make(chan struct{}, 1)}
                h.peers[to56] = p
        }
        queued := 0
        for _, id := range p.order {
                if e := h.reqs[id]; e != nil && !e.answered {
                        queued++
                }
        }
        if queued >= linkMaxQueued {
                h.mu.Unlock()
                writeJSON(w, 429, map[string]string{"error": "file du destinataire pleine — réessayez"})
                return
        }
        h.reqs[in.ID] = &linkReqEntry{
                req:       linkRequest{ID: in.ID, Method: in.Method, Path: in.Path, BodyB64: in.BodyB64},
                to56:      to56,
                expiresAt: now.Add(linkReqTTL),
        }
        p.order = append(p.order, in.ID)
        // La « case réponse » vit le temps du vol + de la consultation : le
        // demandeur peut re-WAIT (la réponse reste lisible jusqu'à expiration).
        h.waiters[in.ID] = &linkWaiter{
                expiresAt: now.Add(linkReqTTL + linkReplyTTL),
                notify:    make(chan struct{}, 1),
        }
        h.notifyPeer(to56)
        h.mu.Unlock()
        writeJSON(w, 202, map[string]string{"id": in.ID, "queued": "true"})
}

// handleLinkPull — GET /link/pull?me=&auth=ts:sig — long-poll 45 s du
// téléphone. Lot ≤ 4 requêtes / 512 Ko, at-least-once (ré-livraison 20 s).
func (s *Server) handleLinkPull(w http.ResponseWriter, r *http.Request) {
        if s.rejectIfMaintenance(w) {
                return
        }
        if r.Method != http.MethodGet {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        me := strings.TrimSpace(r.URL.Query().Get("me"))
        auth := r.URL.Query().Get("auth")
        me56, ok := linkOnion56(me)
        if !ok {
                writeJSON(w, 400, map[string]string{"error": "adresse onion invalide"})
                return
        }
        if !linkVerifySig(me, auth, "NYX-LINK-PULL") {
                writeJSON(w, 401, map[string]string{"error": "authentification lien invalide (ts:sig)"})
                return
        }

        h := link
        h.mu.Lock()
        p := h.peers[me56]
        if p == nil {
                p = &linkPeer{notify: make(chan struct{}, 1)}
                h.peers[me56] = p
        }
        h.mu.Unlock()

        deadline := time.Now().Add(linkPullWait)
        for {
                h.mu.Lock()
                now := time.Now()
                h.sweepLocked(now)
                var lot []linkRequest
                var bytes int
                for _, id := range p.order {
                        e, ok := h.reqs[id]
                        if !ok || e.answered || now.After(e.expiresAt) {
                                continue
                        }
                        fresh := e.deliveredAt.IsZero() || now.Sub(e.deliveredAt) > linkRedeliver
                        if !fresh {
                                continue
                        }
                        if len(lot) >= linkMaxLot {
                                break
                        }
                        if len(lot) > 0 && bytes+len(e.req.BodyB64) > linkMaxLotB {
                                break
                        }
                        e.deliveredAt = now
                        lot = append(lot, e.req)
                        bytes += len(e.req.BodyB64)
                }
                h.mu.Unlock()
                if lot != nil || time.Now().After(deadline) {
                        if lot == nil {
                                lot = []linkRequest{}
                        }
                        writeJSON(w, 200, map[string]any{"reqs": lot})
                        return
                }
                select {
                case <-p.notify:
                case <-time.After(time.Until(deadline)):
                        writeJSON(w, 200, map[string]any{"reqs": []linkRequest{}})
                        return
                case <-r.Context().Done():
                        return
                }
        }
}

// handleLinkReply — POST /link/reply : réponse signée du téléphone.
// 202 · 400 validation · 401 auth · 403 répondant ≠ destinataire ·
// 404 requête inconnue/expirée · 409 déjà répondu.
func (s *Server) handleLinkReply(w http.ResponseWriter, r *http.Request) {
        if s.rejectIfMaintenance(w) {
                return
        }
        if r.Method != http.MethodPost {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        r = limitedBody(w, r, linkMaxBodyB64+(16<<10))
        var in struct {
                ID      string `json:"id"`
                From    string `json:"from"`
                Status  int    `json:"status"`
                BodyB64 string `json:"body_b64"`
                Auth    string `json:"auth"`
        }
        if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
                writeJSON(w, 400, map[string]string{"error": "JSON invalide: " + err.Error()})
                return
        }
        from56, ok := linkOnion56(in.From)
        if !ok || !linkValidID(in.ID) || in.Status < 200 || in.Status > 599 {
                writeJSON(w, 400, map[string]string{"error": "id/from/status invalide"})
                return
        }
        if len(in.BodyB64) > linkMaxBodyB64 {
                writeJSON(w, 400, map[string]string{"error": "corps trop volumineux"})
                return
        }
        sum := sha256.Sum256([]byte(in.BodyB64))
        if !linkVerifySig(in.From, in.Auth, "NYX-LINK-REPLY", in.ID, strconv.Itoa(in.Status), hex.EncodeToString(sum[:])) {
                writeJSON(w, 401, map[string]string{"error": "authentification lien invalide (ts:sig)"})
                return
        }

        h := link
        h.mu.Lock()
        h.sweepLocked(time.Now())
        e, ok := h.reqs[in.ID]
        if !ok || e.answered {
                h.mu.Unlock()
                writeJSON(w, 404, map[string]string{"error": "requête inconnue, expirée ou déjà répondue"})
                return
        }
        if e.to56 != from56 {
                h.mu.Unlock()
                writeJSON(w, 403, map[string]string{"error": "seul le destinataire peut répondre"})
                return
        }
        wt, ok := h.waiters[in.ID]
        if !ok {
                h.mu.Unlock()
                writeJSON(w, 404, map[string]string{"error": "requête inconnue ou expirée"})
                return
        }
        if wt.answered {
                h.mu.Unlock()
                writeJSON(w, 409, map[string]string{"error": "déjà répondu"})
                return
        }
        e.answered = true
        wt.answered = true
        wt.status = in.Status
        wt.bodyB64 = in.BodyB64
        select {
        case wt.notify <- struct{}{}:
        default:
        }
        h.mu.Unlock()
        log.Printf("lien inversé %s… : réponse %d de %s…", cidShort(in.ID), in.Status, cidShort(from56))
        writeJSON(w, 202, map[string]string{"id": in.ID})
}

// handleLinkWait — GET /link/wait?id= — long-poll 75 s du demandeur.
// 200 réponse · 400 id invalide · 408 délai dépassé.
func (s *Server) handleLinkWait(w http.ResponseWriter, r *http.Request) {
        if s.rejectIfMaintenance(w) {
                return
        }
        if r.Method != http.MethodGet {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        id := strings.TrimSpace(r.URL.Query().Get("id"))
        if !linkValidID(id) {
                writeJSON(w, 400, map[string]string{"error": "id invalide (hex 16-64)"})
                return
        }

        h := link
        h.mu.Lock()
        h.sweepLocked(time.Now())
        wt, ok := h.waiters[id]
        h.mu.Unlock()
        if ok && wt.answered {
                writeJSON(w, 200, map[string]any{"status": wt.status, "body_b64": wt.bodyB64})
                return
        }
        if !ok {
                writeJSON(w, 404, map[string]string{"error": "requête inconnue ou expirée"})
                return
        }

        deadline := time.Now().Add(linkWaitFor)
        for {
                select {
                case <-wt.notify:
                case <-time.After(time.Until(deadline)):
                        writeJSON(w, 408, map[string]string{"error": "délai dépassé — téléphone sans canal ou injoignable"})
                        return
                case <-r.Context().Done():
                        return
                }
                h.mu.Lock()
                h.sweepLocked(time.Now())
                wt, ok = h.waiters[id]
                h.mu.Unlock()
                if ok && wt.answered {
                        writeJSON(w, 200, map[string]any{"status": wt.status, "body_b64": wt.bodyB64})
                        return
                }
                if !ok {
                        writeJSON(w, 408, map[string]string{"error": "délai dépassé — téléphone sans canal ou injoignable"})
                        return
                }
        }
}
