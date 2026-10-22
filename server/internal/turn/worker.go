package turn

// Worker de maintien de l'allocation relais (ADR-014 phase 2, spec
// transport-turn.md §6.5) : régénère les credentials éphémères (§2), alloue
// (RFC 6062 §4.2), rafraîchit avant expiration et ré-alloue après rupture —
// backoff 30 s → 15 min (même politique que le transport XMPP).
//
// Le réplica est le BACKEND DE CONFIANCE de ses propres credentials (spec §2) :
// l'API Token Cloudflare vit dans data_dir/config.json (secret, jamais dans le
// dépôt). Chaque (re)allocation déclenche onRelay(RelayInfo) — le branchement
// « publication au rendezvous signé » (spec §4) se fait dans ce callback.

import (
	"context"
	"log"
	"strings"
	"time"
)

// RelayParams — paramètres du worker (issus de la section "turn" de la config).
type RelayParams struct {
	RelayURL string        // ex. "turns://turn.cloudflare.com:5349?transport=tcp"
	SOCKS    string        // SOCKS5 pour la connexion de contrôle ("" = direct)
	KeyID    string        // Token ID Cloudflare
	APIToken string        // SECRET — jamais logué ni inclus dans une erreur
	Lifetime time.Duration // TTL demandé (≤ 86400 s) ; 0 = 3600 s
}

// RelayInfo — adresse relayée à publier (rendezvous signé, spec §4).
type RelayInfo struct {
	RelayAddr string        // "ip:port" relayée
	Lifetime  time.Duration // TTL effectif renvoyé par le relais
}

const (
	turnMaxLifetime  = 86400 * time.Second
	turnDefLifetime  = 3600 * time.Second
	turnBackoffStart = 30 * time.Second
	turnBackoffMax   = 15 * time.Minute
)

// MaintainRelay — boucle de vie de l'allocation : jamais de retour silencieux,
// chaque échec est logué ; retourne quand ctx est annulé.
func MaintainRelay(ctx context.Context, p RelayParams, onRelay func(RelayInfo)) {
	if p.Lifetime <= 0 {
		p.Lifetime = turnDefLifetime
	}
	if p.Lifetime > turnMaxLifetime {
		p.Lifetime = turnMaxLifetime
	}
	backoff := turnBackoffStart
	for {
		if ctx.Err() != nil {
			return
		}
		alloc, err := allocateOnce(p)
		if err != nil {
			log.Printf("[turn] allocation impossible (%v) — nouvel essai dans %v", err, backoff)
			if !sleepCtx(ctx, backoff) {
				return
			}
			backoff *= 2
			if backoff > turnBackoffMax {
				backoff = turnBackoffMax
			}
			continue
		}
		backoff = turnBackoffStart
		onRelay(RelayInfo{RelayAddr: alloc.RelayAddr(), Lifetime: alloc.Lifetime})

		// Refresh aux 2/3 du TTL effectif (marge : la moitié des 2/3).
		refreshIn := alloc.Lifetime * 2 / 3
		if refreshIn > 30*time.Second {
			refreshIn -= 15 * time.Second
		}
		if !refreshLoop(ctx, alloc, p.Lifetime, refreshIn) {
			return
		}
		// Allocation perdue (erreur refresh) : petite pause puis ré-allocation.
		if !sleepCtx(ctx, time.Second) {
			return
		}
	}
}

// iceGen — génération de credentials (§2) ; variable de surcharge pour les
// tests (même pattern que apiBase dans turncreds.go).
var iceGen = GenerateICEServers

// allocateOnce — credentials éphémères (§2) puis allocation (§6.2).
func allocateOnce(p RelayParams) (*Allocation, error) {
	ice, err := iceGen(p.KeyID, p.APIToken, int(p.Lifetime/time.Second))
	if err != nil {
		return nil, err
	}
	if len(ice) == 0 {
		return nil, ErrNoInbound // improbable ; message explicite ci-dessous
	}
	url, user, cred := PickTCPURL(ice)
	if url == "" {
		return nil, ErrNoInbound
	}
	ctrl, err := DialControl(url, p.SOCKS, 20*time.Second)
	if err != nil {
		return nil, err
	}
	alloc, err := ctrl.Allocate(user, cred, p.Lifetime)
	if err != nil {
		ctrl.conn.Close()
		return nil, err
	}
	return alloc, nil
}

// PickTCPURL — choisit l'URL d'allocation : turns (TLS) prioritaire, turn/TCP
// en repli ; UDP exclu (Tor = TCP only, spec §3).
func PickTCPURL(ice []ICEServer) (url, username, credential string) {
	var fallback string
	for _, s := range ice {
		for _, u := range s.URLs {
			if hasTCPTransport(u) {
				if url == "" && isTLSTurn(u) {
					url = u
				} else if fallback == "" && !isTLSTurn(u) {
					fallback = u
				}
			}
		}
		if url == "" {
			url = fallback
		}
		if url != "" {
			return url, s.Username, s.Credential
		}
	}
	return fallback, ice[0].Username, ice[0].Credential
}

func isTLSTurn(u string) bool { return strings.HasPrefix(u, "turns:") }

func hasTCPTransport(u string) bool {
	for i := 0; i+len("?transport=tcp") <= len(u); i++ {
		if u[i:i+14] == "?transport=tcp" || u[i:i+14] == "&transport=tcp" {
			return true
		}
	}
	return false
}

// refreshLoop — rafraîchit l'allocation jusqu'à erreur ou annulation.
func refreshLoop(ctx context.Context, a *Allocation, lifetime time.Duration, every time.Duration) bool {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			_ = a.Refresh(0) // suppression propre best effort
			return false
		case <-t.C:
			if err := a.Refresh(lifetime); err != nil {
				log.Printf("[turn] refresh échoué (%v) — ré-allocation", err)
				closeQuietly(a)
				return true
			}
		}
	}
}

func closeQuietly(a *Allocation) {
	closeListener(a.ctrl.listener)
	_ = a.ctrl.conn.Close()
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}
