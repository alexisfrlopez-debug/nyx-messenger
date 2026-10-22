// Tests du rendezvous signé côté API client (ADR-016).
package api

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRendezvousNotConfigured(t *testing.T) {
	s := &Server{Version: "test", Onion: "x.onion"} // RendezvousRead nil
	resp := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/rendezvous", nil)
	s.Handler().ServeHTTP(resp, req)
	if resp.Code != http.StatusNotFound {
		t.Fatalf("404 attendu sans closure, obtenu %d", resp.Code)
	}
}

func TestRendezvousEnvelope(t *testing.T) {
	raw := []byte(`{"format":"nyx-rendezvous","version":1,"replicas":[{"onion":"abc…onion"}]}`)
	sig := []byte("signature-detest-64-octets-0000000000000000000000000000000000000000000000000000000")
	s := &Server{
		Version: "test",
		RendezvousRead: func() ([]byte, []byte, bool) {
			return raw, sig, true
		},
	}
	resp := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/rendezvous", nil)
	s.Handler().ServeHTTP(resp, req)
	if resp.Code != http.StatusOK {
		t.Fatalf("200 attendu, obtenu %d", resp.Code)
	}
	var env struct {
		JSONB64 string `json:"json_b64"`
		SigB64  string `json:"sig_b64"`
	}
	if err := json.Unmarshal(resp.Body.Bytes(), &env); err != nil {
		t.Fatalf("envelope invalide : %v", err)
	}
	got, err := base64.StdEncoding.DecodeString(env.JSONB64)
	if err != nil || string(got) != string(raw) {
		t.Fatalf("round-trip json_b64 cassé : %q (%v)", got, err)
	}
	gotSig, err := base64.StdEncoding.DecodeString(env.SigB64)
	if err != nil || string(gotSig) != string(sig) {
		t.Fatalf("round-trip sig_b64 cassé : %q (%v)", gotSig, err)
	}
}

func TestRendezvousMethodNotAllowed(t *testing.T) {
	s := &Server{Version: "test"}
	resp := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/rendezvous", strings.NewReader("{}"))
	s.Handler().ServeHTTP(resp, req)
	if resp.Code != http.StatusMethodNotAllowed {
		t.Fatalf("405 attendu, obtenu %d", resp.Code)
	}
}
