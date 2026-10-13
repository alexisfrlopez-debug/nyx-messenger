// Tests du helper TURN (ADR-014 phase 1) : appel API Cloudflare (httptest),
// erreurs explicites, filtre TCP-only.
package turn

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGenerateICEServersOK(t *testing.T) {
	var gotAuth, gotCT, gotBody, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotCT = r.Header.Get("Content-Type")
		gotPath = r.URL.Path
		b := make([]byte, 256)
		n, _ := r.Body.Read(b)
		gotBody = string(b[:n])
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478",
			"turn:turn.cloudflare.com:3478?transport=udp","turn:turn.cloudflare.com:3478?transport=tcp",
			"turns:turn.cloudflare.com:5349?transport=tcp"],"username":"u-éphémère","credential":"c-éphémère"}]}`))
	}))
	defer srv.Close()
	apiBase = srv.URL

	out, err := GenerateICEServers("KEY123", "token-secret", 3600)
	if err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer token-secret" {
		t.Fatalf("Authorization incorrect : %q", gotAuth)
	}
	if gotCT != "application/json" {
		t.Fatalf("Content-Type incorrect : %q", gotCT)
	}
	if gotBody != `{"ttl":3600}` {
		t.Fatalf("corps inattendu : %q", gotBody)
	}
	want := "/v1/turn/keys/KEY123/credentials/generate-ice-servers"
	if gotPath != want {
		t.Fatalf("chemin %q ≠ %q", gotPath, want)
	}
	if len(out) != 1 || len(out[0].URLs) != 4 || out[0].Username != "u-éphémère" {
		t.Fatalf("iceServers mal décodés : %+v", out)
	}
}

func TestGenerateICEServersErrors(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"code":"40100","message":"invalid token"}}`))
	}))
	defer srv.Close()
	apiBase = srv.URL

	if _, err := GenerateICEServers("K", "mauvais-token", 0); err == nil {
		t.Fatal("401 accepté sans erreur")
	} else if !strings.Contains(err.Error(), "401") {
		t.Fatalf("message sans code HTTP : %v", err)
	}
	if _, err := GenerateICEServers("", "", 0); err == nil {
		t.Fatal("paramètres vides acceptés")
	}
	// ttl au-delà de la borne éphémère
	if _, err := GenerateICEServers("K", "t", 90000); err == nil {
		t.Fatal("ttl > 86400 accepté")
	}
	// le token ne doit JAMAIS apparaître dans un message d'erreur
	if _, err := GenerateICEServers("K", "token-que-jaime-pas-montrer", 0); err != nil {
		if strings.Contains(err.Error(), "token-que-jaime-pas-montrer") {
			t.Fatal("token divulgué dans le message d'erreur")
		}
	}
}

func TestTCPOnly(t *testing.T) {
	in := []ICEServer{{
		URLs: []string{"stun:stun.cloudflare.com:3478",
			"turn:turn.cloudflare.com:3478?transport=udp",
			"turn:turn.cloudflare.com:3478?transport=tcp",
			"turns:turn.cloudflare.com:5349?transport=tcp"},
		Username: "u", Credential: "c",
	}}
	out := TCPOnly(in)
	if len(out) != 1 || len(out[0].URLs) != 2 {
		t.Fatalf("filtre TCP incorrect : %+v", out)
	}
	for _, u := range out[0].URLs {
		if strings.Contains(u, "udp") || strings.HasPrefix(u, "stun:") {
			t.Fatalf("URL non TCP conservée : %s", u)
		}
	}
}
