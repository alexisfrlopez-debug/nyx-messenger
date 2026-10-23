package api

import (
        "io"
        "log"
        "net"
        "net/http"
        "strings"
        "sync"
        "time"
)

// v0.2.36 — RELAIS MÉDIA D'APPEL (repli « décrochage », session 65).
//
// Terrain (sessions 41 → 65) : le média direct onion→onion des téléphones
// est chroniquement impossible quand l'onion du pair est injoignable
// (points d'introduction morts / descripteur non propagé — « SOCKS server
// general failure », « Connect timed out ») alors que le chemin téléphone ↔
// réplica fonctionne TOUJOURS (sync journal, /health 375 ms terrain). Ce
// relais fait transiter le média d'appel par le réplica : les DEUX pairs
// se connectent au réplica (chemin prouvé), le serveur apparie les deux
// sockets par cid et pipe les octets à l'aveugle.
//
// Sécurité / anonymat :
//   - le média d'appel est chiffré E2E par CallAudio/CallVideo (csd) — le
//     relais ne voit QUE du ciphertext, exactement comme un serveur TURN ;
//   - l'accès est gated par le client-auth Tor de l'onion (comme TOUTES
//     les routes de ce mux) — seuls les membres de la flotte s'y branchent ;
//   - le cid (16 octets hex aléatoires, échangé E2E dans l'offre) est le
//     seul secret d'appariement — invisible pour le réplica (corps chiffré) ;
//   - bornage : relayMaxConcurrent sockets au total, TTL 240 s pour une
//     moitié jamais appariée, durcité 3 h par appel apparié (cap client
//     v0.2.42 = 2 h + marge), UNE socket par rôle et par cid.
const (
        relayMaxConcurrent = 64
        relayMatchTTL      = 240 * time.Second
        relayCallHardCap   = 3 * time.Hour
)

// relayHalf — une moitié d'appel en attente de sa contrepartie.
type relayHalf struct {
        role  string
        conn  net.Conn
        rd    io.Reader // bufio du hijack (les octets précoces y vivent)
        timer *time.Timer
}

type relayMatcher struct {
        mu      sync.Mutex
        pending map[string]*relayHalf
        pairing map[string]bool
        serving int
}

var relay = &relayMatcher{
        pending: map[string]*relayHalf{},
        pairing: map[string]bool{},
}

// tryRegister — enregistre la socket (cid, role).
// Renvoie (moitié adverse, true) si l'appariement est complet,
// (nil, true) si la socket est enregistrée et patiente,
// (nil, false) si REFUSÉ (plein / doublon de rôle / cid déjà apparié).
func (m *relayMatcher) tryRegister(cid, role string, conn net.Conn, rd io.Reader) (*relayHalf, bool) {
        m.mu.Lock()
        defer m.mu.Unlock()
        if m.serving >= relayMaxConcurrent {
                return nil, false
        }
        if m.pairing[cid] {
                return nil, false
        }
        if half, ok := m.pending[cid]; ok {
                if half.role == role {
                        return nil, false // même rôle deux fois : l'ancienne patiente
                }
                // Appariement complet — le timer TTL de la moitié patiente est
                // STOPPÉ (sinon il tuerait l'appel en cours 240 s plus tard).
                if half.timer != nil {
                        half.timer.Stop()
                }
                delete(m.pending, cid)
                m.pairing[cid] = true
                m.serving++ // la nouvelle socket rejoint le décompte
                return half, true
        }
        m.pending[cid] = &relayHalf{role: role, conn: conn, rd: rd}
        m.serving++
        return nil, true
}

// sweepExpired — rappel du TTL : ferme la moitié SI elle patiente encore.
func (m *relayMatcher) sweepExpired(cid string) {
        m.mu.Lock()
        half, ok := m.pending[cid]
        if ok {
                delete(m.pending, cid)
                m.serving--
        }
        m.mu.Unlock()
        if ok {
                _ = half.conn.Close()
        }
}

// endCall — l'appel apparié est terminé : libérer cid + décompte.
func (m *relayMatcher) endCall(cid string) {
        m.mu.Lock()
        defer m.mu.Unlock()
        if m.pairing[cid] {
                delete(m.pairing, cid)
                m.serving -= 2
        }
}

// handleCallRelay — GET /call/relay?cid=<hex>&role=caller|callee avec
// « Upgrade: nyx-pipe » → 101 → les octets sont pipés tels quels entre les
// deux moitiés (le protocole d'appel Nyx — trames binaires signées — circule
// inchangé, le relais est transparent).
func (s *Server) handleCallRelay(w http.ResponseWriter, r *http.Request) {
        if s.rejectIfMaintenance(w) {
                return
        }
        if r.Method != http.MethodGet {
                writeJSON(w, 405, map[string]string{"error": "méthode interdite"})
                return
        }
        if !strings.EqualFold(r.Header.Get("Upgrade"), "nyx-pipe") {
                writeJSON(w, 400, map[string]string{"error": "Upgrade: nyx-pipe requis"})
                return
        }
        cid := r.URL.Query().Get("cid")
        role := r.URL.Query().Get("role")
        if !validRelayCid(cid) || (role != "caller" && role != "callee") {
                writeJSON(w, 400, map[string]string{"error": "cid/role invalide"})
                return
        }

        hj, ok := w.(http.Hijacker)
        if !ok {
                writeJSON(w, 500, map[string]string{"error": "hijack indisponible"})
                return
        }
        conn, buf, err := hj.Hijack()
        if err != nil {
                return
        }
        // 101 puis plus AUCUN octet HTTP : tuyau brut (les délais http.Server
        // ne s'appliquent plus après hijack — c'est le nœud qui gouverne).
        if _, err := conn.Write([]byte("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: nyx-pipe\r\nConnection: Upgrade\r\n\r\n")); err != nil {
                _ = conn.Close()
                return
        }
        var rd io.Reader = conn
        if buf != nil && buf.Reader != nil {
                rd = buf.Reader // octets précoces du client (improbables mais sûrs)
        }

        half, registered := relay.tryRegister(cid, role, conn, rd)
        if !registered {
                _ = conn.Close()
                return
        }
        if half == nil {
                // Seule : patienter jusqu'à la contrepartie ou le TTL.
                time.AfterFunc(relayMatchTTL, func() { relay.sweepExpired(cid) })
                log.Printf("relais appel %s… : %s en attente", cidShort(cid), role)
                return
        }
        log.Printf("relais appel %s… : %s apparié, média pipé", cidShort(cid), role)
        go pipeRelay(cid,
                relaySide{conn: conn, rd: rd},
                relaySide{conn: half.conn, rd: half.rd})
}

// relaySide — une extrémité d'un appel apparié.
type relaySide struct {
        conn net.Conn
        rd   io.Reader
}

// pipeRelay — copie brutale dans les deux sens ; à la première extrémité
// fermée, les deux sont fermées (le client interprète l'EOF et raccroche).
func pipeRelay(cid string, a, b relaySide) {
        done := make(chan struct{}, 2)
        copyOne := func(from io.Reader, to net.Conn) {
                _, _ = io.Copy(to, from)
                done <- struct{}{}
        }
        go copyOne(a.rd, b.conn)
        go copyOne(b.rd, a.conn)
        time.AfterFunc(relayCallHardCap, func() {
                _ = a.conn.Close()
                _ = b.conn.Close()
        })
        <-done
        _ = a.conn.Close()
        _ = b.conn.Close()
        <-done
        relay.endCall(cid)
        log.Printf("relais appel %s… : terminé", cidShort(cid))
}

func validRelayCid(cid string) bool {
        if len(cid) < 16 || len(cid) > 64 {
                return false
        }
        for _, c := range cid {
                if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
                        return false
                }
        }
        return true
}

func cidShort(cid string) string {
        if len(cid) > 8 {
                return cid[:8]
        }
        return cid
}
