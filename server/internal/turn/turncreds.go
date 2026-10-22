// Package turn — helper TURN « commodity » (ADR-014, protocol/specs/transport-turn.md) :
// génération de credentials ICE éphémères auprès de l'API Cloudflare
// (PHASE 1 — l'allocation relais RFC 6062 côté réplica est PHASE 2, spec seule).
// stdlib uniquement. Les credentials éphémères sont générés par un BACKEND de
// confiance (réplica ou console nyx-admin) — JAMAIS embarqués dans l'APK ; le
// API Token est un secret (environnement / data_dir/config.json — jamais git).
package turn

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// apiBase — point d'entrée de l'API RTC Cloudflare (surchargé par les tests).
var apiBase = "https://rtc.live.cloudflare.com"

// ICEServer — une entrée iceServers (modèle RFC 8445 / WebRTC, tel que rendu
// par l'API). Nyx n'utilise effectivement que les URLs `turns:…:5349` (TCP via
// Tor) et `turn:…:3478?transport=tcp` en repli — UDP exclu (Tor = TCP only).
type ICEServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
}

// generateResp — corps de réponse de l'API.
type generateResp struct {
	IceServers []ICEServer `json:"iceServers"`
	ErrorCode  *struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

// GenerateICEServers — appelle l'endpoint de génération de credentials
// éphémères :
//
//	POST {apiBase}/v1/turn/keys/{keyID}/credentials/generate-ice-servers
//	Authorization: Bearer <apiToken>   Content-Type: application/json
//	{"ttl": <ttlSeconds>}
//
// Retourne les iceServers ; erreur explicite sur 401/403/429/5xx (aucun repli
// silencieux). Le token n'apparaît JAMAIS dans un message d'erreur.
func GenerateICEServers(keyID, apiToken string, ttlSeconds int) ([]ICEServer, error) {
	if keyID == "" || apiToken == "" {
		return nil, fmt.Errorf("turn: key_id et api token requis")
	}
	if ttlSeconds <= 0 {
		ttlSeconds = 86400
	}
	if ttlSeconds > 86400 {
		return nil, fmt.Errorf("turn: ttl > 86400 s refusé (credentials éphémères)")
	}
	body := fmt.Sprintf(`{"ttl":%d}`, ttlSeconds)
	req, err := http.NewRequest(http.MethodPost,
		apiBase+"/v1/turn/keys/"+keyID+"/credentials/generate-ice-servers",
		strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+apiToken)
	req.Header.Set("Content-Type", "application/json")
	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return nil, fmt.Errorf("turn: API injoignable: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("turn: lecture réponse: %w", err)
	}
	var out generateResp
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("turn: réponse invalide (HTTP %d)", resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		msg := ""
		if out.ErrorCode != nil {
			msg = " (" + out.ErrorCode.Code + ")"
		}
		return nil, fmt.Errorf("turn: génération refusée HTTP %d%s", resp.StatusCode, msg)
	}
	if len(out.IceServers) == 0 {
		return nil, fmt.Errorf("turn: réponse sans iceServers")
	}
	return out.IceServers, nil
}

// TCPOnly — filtre les iceServers : ne conserve que les URLs TCP utilisables
// par Nyx (turns:5349 via SOCKS5 Tor, ou turn:3478 transport=tcp). STUN et UDP
// sont retirés (sans objet hors WebRTC ; UDP impossible via Tor).
func TCPOnly(in []ICEServer) []ICEServer {
	out := make([]ICEServer, 0, len(in))
	for _, s := range in {
		keep := make([]string, 0, len(s.URLs))
		for _, u := range s.URLs {
			if strings.Contains(u, "transport=tcp") || strings.HasPrefix(u, "turns:") {
				keep = append(keep, u)
			}
		}
		if len(keep) > 0 {
			s.URLs = keep
			out = append(out, s)
		}
	}
	return out
}
