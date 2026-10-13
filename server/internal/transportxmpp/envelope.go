// Package transportxmpp — transport alternatif « commodity » : un salon MUC
// XMPP sert de bus de diffusion aux enveloppes Nyx (ADR-014, opt-in ;
// protocol/specs/transport-xmpp.md). Client XMPP minimal écrit dans le dépôt
// (stdlib + x/crypto uniquement — amendement ADR-005 ; AUCUNE dépendance
// mellium/pion). Le serveur XMPP ne voit que des charges chiffrées signées.
package transportxmpp

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

// Prefix — marqueur de corps de message Nyx (transport-xmpp.md §5).
const Prefix = "nyx1 "

// Limites de fragmentation (transport-xmpp.md §5) : max_stanza_size usuel
// ≈ 64 Ko sur les serveurs publics → corps de message ≤ 48 Ko. Un Event court
// part en un seul corps ; au-delà, découpage en fragments de 40 KiB de base64.
const (
	MaxBodySize = 48 * 1024
	// ⚠️ double encodage : Data (déjà base64) est intégré dans le JSON de
	// l'enveloppe, PUIS le JSON est encodé base64 dans le corps → expansion 4/3
	// à chaque niveau. Bornes calibrées pour des corps finis < 48 KiB.
	maxDataChunk = 33792 // 33 KiB de data b64 par fragment (multiple de 4)
	maxSingle    = 32256 // 31,5 KiB monolithique (multiple de 4)
	EnvVersion   = 1
	// maxEventPayload — borne d'un Event à l'émission (256 Ko = MaxBodySize du
	// wire-format) : un gros avatar partira en ~9 fragments de 40 KiB.
	maxEventPayload = 256 * 1024
)

// ErrNotNyx — le corps n'est pas une enveloppe Nyx (bruit tiers du salon).
var ErrNotNyx = errors.New("transportxmpp: corps non nyx1")

// Frag — fragment i sur n (1-indexé).
type Frag struct {
	I int `json:"i"`
	N int `json:"n"`
}

// Envelope — enveloppe de transport (DISTINCTE de l'Event du journal, qui
// transite encodé dans Data). Les champs From/Seq ne sont qu'indicatifs :
// l'authenticité vient de la signature ed25519 de l'Event (wire-format.md).
type Envelope struct {
	V    int    `json:"v"`
	ID   string `json:"id"`   // identifiant logique du message (regroupement des fragments)
	From string `json:"from"` // onion de l'expéditeur (indicatif)
	Seq  uint64 `json:"seq"`  // seq de l'Event (indicatif)
	Frag Frag   `json:"frag"`
	Data string `json:"data"` // Event JSON, base64url
}

// EncodeBodies — découpe un Event encodé (JSON du journal) en corps de
// messages `nyx1` prêts à publier dans le MUC. Un Event court donne un corps
// unique (frag 1/1) ; au-delà de la réserve, fragmentation multiple de 4.
func EncodeBodies(from string, seq uint64, data []byte) ([]string, error) {
	if len(data) == 0 {
		return nil, fmt.Errorf("transportxmpp: data vide")
	}
	if len(data) > maxEventPayload {
		return nil, fmt.Errorf("transportxmpp: événement trop grand (%d octets > %d)", len(data), maxEventPayload)
	}
	b64 := base64.RawURLEncoding.EncodeToString(data)
	if len(b64) <= maxSingle {
		env := Envelope{V: EnvVersion, From: from, Seq: seq, Frag: Frag{I: 1, N: 1}, Data: b64}
		b, err := json.Marshal(env)
		if err != nil {
			return nil, err
		}
		return []string{Prefix + base64.RawURLEncoding.EncodeToString(b)}, nil
	}
	// découpage : Data est coupée en tranches multiples de 4 ; la concaténation
	// reconstitue exactement la chaîne base64url d'origine.
	id, err := newMsgID()
	if err != nil {
		return nil, err
	}
	n := (len(b64) + maxDataChunk - 1) / maxDataChunk
	out := make([]string, 0, n)
	for i := 0; i < n; i++ {
		lo, hi := i*maxDataChunk, min((i+1)*maxDataChunk, len(b64))
		frag := Envelope{V: EnvVersion, ID: id, From: from, Seq: seq,
			Frag: Frag{I: i + 1, N: n}, Data: b64[lo:hi]}
		b, err := json.Marshal(frag)
		if err != nil {
			return nil, err
		}
		out = append(out, Prefix+base64.RawURLEncoding.EncodeToString(b))
	}
	return out, nil
}

// maxPayloadSize — borne d'un Event accepté à l'émission (mémoire : la
// constante du wire-format ; enveloppes fragmentées au-delà de maxSingle).
func maxPayloadSize() int { return maxEventPayload }

// newMsgID — identifiant logique aléatoire des messages fragmentés.
func newMsgID() (string, error) {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

// ParseBody — analyse un corps de message groupchat : renvoie ErrNotNyx si le
// corps n'est pas marqué `nyx1 `, une erreur de décodage sinon.
func ParseBody(body string) (*Envelope, error) {
	if !strings.HasPrefix(body, Prefix) {
		return nil, ErrNotNyx
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(body[len(Prefix):]))
	if err != nil {
		return nil, fmt.Errorf("transportxmpp: enveloppe base64 invalide: %w", err)
	}
	var env Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, fmt.Errorf("transportxmpp: enveloppe JSON invalide: %w", err)
	}
	if env.V != EnvVersion {
		return nil, fmt.Errorf("transportxmpp: version d'enveloppe inconnue: %d", env.V)
	}
	if env.Data == "" {
		return nil, fmt.Errorf("transportxmpp: enveloppe sans data")
	}
	if env.Frag.I < 1 || env.Frag.N < 1 || env.Frag.I > env.Frag.N {
		return nil, fmt.Errorf("transportxmpp: fragment invalide %d/%d", env.Frag.I, env.Frag.N)
	}
	return &env, nil
}

// ---- réassemblage des fragments (transport-xmpp.md §5) ----

const (
	assemblerMax = 128              // messages partiels retenus max (éviction FIFO)
	assemblerTTL = 10 * time.Minute // durée de vie d'un partiel
)

// partial — message fragmenté en cours de réception.
type partial struct {
	env     map[int]string // fragment i → data
	n       int
	touched time.Time
}

// Assembler — reconstitue les charges utiles depuis les enveloppes reçues.
// Fil-safe. Un message non fragmenté est renvoyé immédiatement.
type Assembler struct {
	mu    sync.Mutex
	parts map[string]*partial // clé : ID (identifiant logique du message)
	order []string            // ordre d'arrivée (éviction FIFO)
}

// NewAssembler — assembleur prêt à l'emploi.
func NewAssembler() *Assembler {
	return &Assembler{parts: map[string]*partial{}}
}

// Add — enregistre une enveloppe ; renvoie la/les charge(s) utile(s)
// complétée(s) par cet apport (0 ou 1). Un fragment déjà vu renvoie (nil, nil)
// — les échos MAM ne sont pas des erreurs.
func (a *Assembler) Add(env *Envelope) ([][]byte, error) {
	data, err := base64.RawURLEncoding.DecodeString(env.Data)
	if err != nil {
		return nil, fmt.Errorf("transportxmpp: data base64 invalide: %w", err)
	}
	if env.Frag.N == 1 {
		return [][]byte{data}, nil
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.evictLocked()
	key := env.ID
	if key == "" {
		return nil, fmt.Errorf("transportxmpp: fragment sans id")
	}
	p := a.parts[key]
	if p == nil {
		if len(a.parts) >= assemblerMax {
			delete(a.parts, a.order[0])
			a.order = a.order[1:]
		}
		p = &partial{env: map[int]string{}, n: env.Frag.N, touched: time.Now()}
		a.parts[key] = p
		a.order = append(a.order, key)
	}
	if _, seen := p.env[env.Frag.I]; seen {
		return nil, nil // fragment déjà vu (écho MAM)
	}
	p.env[env.Frag.I] = env.Data
	p.touched = time.Now()
	if len(p.env) < p.n {
		return nil, nil
	}
	// complet : concaténer dans l'ordre, libérer
	full := make([]byte, 0, p.n*maxDataChunk)
	for i := 1; i <= p.n; i++ {
		d, err := base64.RawURLEncoding.DecodeString(p.env[i])
		if err != nil {
			delete(a.parts, key)
			return nil, fmt.Errorf("transportxmpp: fragment %d/%s illisible: %w", i, key, err)
		}
		full = append(full, d...)
	}
	delete(a.parts, key)
	return [][]byte{full}, nil
}

// evictLocked — purge des partiels expirés (TTL) — mutex posé.
func (a *Assembler) evictLocked() {
	now := time.Now()
	kept := a.order[:0]
	for _, k := range a.order {
		if p := a.parts[k]; p != nil && now.Sub(p.touched) > assemblerTTL {
			delete(a.parts, k)
			continue
		}
		kept = append(kept, k)
	}
	a.order = kept
}
