// Tests de l'API admin (APPLI_ADMIN.md §3/§4, spec privée) : barrière de
// signature, anti-replay, fenêtre temporelle, endpoints, clés, maintenance,
// checkpoints. Miroir exact du comportement attendu par AdminSigner.kt.
package adminapi

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// fakeStore — journal minimal pour les tests admin.
type fakeStore struct{ n int }

func (f *fakeStore) Count() int                                     { return f.n }
func (f *fakeStore) JournalBytes() int64                            { return 1234 }
func (f *fakeStore) MerkleRoot(before int) (string, error)          { return "abc123", nil }
func (f *fakeStore) ApplyCheckpoint(int, string) (string, error)    { return "/tmp/archive.jsonl", nil }

// fakeCfg — config de clés minimale.
type fakeCfg struct {
	keys map[string]bool
}

func (f *fakeCfg) HasAdminKey(p string) bool { return f.keys[p] }
func (f *fakeCfg) AddAdminKey(k AdminKeyView) bool {
	if f.keys[k.PublicKey] {
		return false
	}
	f.keys[k.PublicKey] = true
	return true
}
func (f *fakeCfg) RemoveAdminKey(p string) (bool, error) {
	if len(f.keys) <= 1 {
		return false, fmt.Errorf("dernière clé")
	}
	if !f.keys[p] {
		return false, nil
	}
	delete(f.keys, p)
	return true, nil
}
func (f *fakeCfg) AdminKeys() []AdminKeyView {
	out := []AdminKeyView{}
	for k := range f.keys {
		out = append(out, AdminKeyView{PublicKey: k, Label: "test"})
	}
	return out
}
func (f *fakeCfg) Save() error { return nil }

// testServer — nœud admin de test avec une clé admin générée.
type testServer struct {
	srv    *Server
	pub    ed25519.PublicKey
	priv   ed25519.PrivateKey
	ts     *httptest.Server
	maint  *atomic.Int32
	stopped chan string
}

func newTestServer(t *testing.T) *testServer {
	t.Helper()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pubB64 := base64.StdEncoding.EncodeToString(pub)
	maint := &atomic.Int32{}
	stopped := make(chan string, 1)
	s := &Server{
		Version:     "test",
		Onion:       "admin.onion",
		GossipPub:   "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		Store:       &fakeStore{n: 7},
		Cfg:         &fakeCfg{keys: map[string]bool{pubB64: true}},
		StartedAt:   time.Now().Add(-90 * time.Second),
		Maintenance: maint,
		StopRequest: stopped,
		DataDir:     t.TempDir(),
	}
	ts := httptest.NewServer(s.Handler())
	t.Cleanup(ts.Close)
	return &testServer{srv: s, pub: pub, priv: priv, ts: ts, maint: maint, stopped: stopped}
}

// signedReq — requête signée exactement comme AdminSigner.kt (chaîne §3).
// Retourne (code HTTP, corps).
func (h *testServer) signedReq(t *testing.T, method, pathWithQuery string, body []byte) (int, string) {
	t.Helper()
	tsUnix := time.Now().Unix()
	nonce := make([]byte, 32)
	_, _ = rand.Read(nonce)
	nonceB64 := base64.StdEncoding.EncodeToString(nonce)
	sum := sha256.Sum256(body)
	canonical := strings.Join([]string{
		CanonicalPrefix,
		fmt.Sprintf("%d", tsUnix),
		nonceB64,
		strings.ToUpper(method),
		pathWithQuery,
		fmt.Sprintf("%x", sum),
	}, "\n")
	sig := ed25519.Sign(h.priv, []byte(canonical))
	req, _ := http.NewRequest(method, h.ts.URL+pathWithQuery, strings.NewReader(string(body)))
	req.Header.Set("X-Nyx-Admin", base64.StdEncoding.EncodeToString(h.pub))
	req.Header.Set("X-Nyx-Ts", fmt.Sprintf("%d", tsUnix))
	req.Header.Set("X-Nyx-Nonce", nonceB64)
	req.Header.Set("X-Nyx-Sig", base64.StdEncoding.EncodeToString(sig))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	buf := make([]byte, 8192)
	n, _ := resp.Body.Read(buf)
	return resp.StatusCode, string(buf[:n])
}

func (h *testServer) get(t *testing.T, path string) (int, string) {
	return h.signedReq(t, "GET", path, nil)
}

// TestAuthSignatureRequired — sans en-têtes → 401 ; avec signature invalide → 401.
func TestAuthSignatureRequired(t *testing.T) {
	h := newTestServer(t)
	resp, err := http.Get(h.ts.URL + "/admin/status")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 401 {
		t.Fatalf("sans en-têtes: %d ≠ 401", resp.StatusCode)
	}

	// signature bidon
	req, _ := http.NewRequest("GET", h.ts.URL+"/admin/status", nil)
	req.Header.Set("X-Nyx-Admin", base64.StdEncoding.EncodeToString(h.pub))
	req.Header.Set("X-Nyx-Ts", fmt.Sprintf("%d", time.Now().Unix()))
	req.Header.Set("X-Nyx-Nonce", base64.StdEncoding.EncodeToString(make([]byte, 32)))
	req.Header.Set("X-Nyx-Sig", base64.StdEncoding.EncodeToString(make([]byte, 64)))
	resp2, _ := http.DefaultClient.Do(req)
	resp2.Body.Close()
	if resp2.StatusCode != 401 {
		t.Fatalf("signature invalide: %d ≠ 401", resp2.StatusCode)
	}
}

// TestAuthUnauthorizedKey — une clé NON déclarée est refusée (403) même avec
// une signature valide.
func TestAuthUnauthorizedKey(t *testing.T) {
	h := newTestServer(t)
	_, _, priv2 := [32]byte{}, [32]byte{}, func() ed25519.PrivateKey {
		_, p, _ := ed25519.GenerateKey(rand.Reader)
		return p
	}()
	_ = priv2
	// signer avec une autre paire
	otherPub, otherPriv, _ := ed25519.GenerateKey(rand.Reader)
	body := []byte(nil)
	ts := time.Now().Unix()
	nonceB64 := base64.StdEncoding.EncodeToString(make([]byte, 32))
	sum := sha256.Sum256(body)
	canonical := CanonicalString(ts, nonceB64, "GET", "/admin/status", sum[:])
	sig := ed25519.Sign(otherPriv, []byte(canonical))
	req, _ := http.NewRequest("GET", h.ts.URL+"/admin/status", nil)
	req.Header.Set("X-Nyx-Admin", base64.StdEncoding.EncodeToString(otherPub))
	req.Header.Set("X-Nyx-Ts", fmt.Sprintf("%d", ts))
	req.Header.Set("X-Nyx-Nonce", nonceB64)
	req.Header.Set("X-Nyx-Sig", base64.StdEncoding.EncodeToString(sig))
	resp, _ := http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.StatusCode != 403 {
		t.Fatalf("clé inconnue: %d ≠ 403", resp.StatusCode)
	}
}

// TestAuthReplayRejected — rejouer LA MÊME requête signée échoue (nonce).
func TestAuthReplayRejected(t *testing.T) {
	h := newTestServer(t)
	// 1re fois : OK
	code, _ := h.get(t, "/admin/status")
	if code != 200 {
		t.Fatalf("première requête: %d ≠ 200", code)
	}
	// la requête suivante (autres nonce) : OK aussi
	code2, _ := h.get(t, "/admin/status")
	if code2 != 200 {
		t.Fatalf("requête suivante: %d ≠ 200", code2)
	}
	// rejeu STRICT : reconstruire une requête avec un nonce DÉJÀ utilisé
	// (corps vide — la chaîne canonique couvre sha256("") exactement comme le serveur)
	tsUnix := time.Now().Unix()
	nonceB64 := base64.StdEncoding.EncodeToString(make([]byte, 32))
	canonical := CanonicalString(tsUnix, nonceB64, "GET", "/admin/identity", nil)
	sig := ed25519.Sign(h.priv, []byte(canonical))
	mk := func() *http.Request {
		req, _ := http.NewRequest("GET", h.ts.URL+"/admin/identity", nil)
		req.Header.Set("X-Nyx-Admin", base64.StdEncoding.EncodeToString(h.pub))
		req.Header.Set("X-Nyx-Ts", fmt.Sprintf("%d", tsUnix))
		req.Header.Set("X-Nyx-Nonce", nonceB64)
		req.Header.Set("X-Nyx-Sig", base64.StdEncoding.EncodeToString(sig))
		return req
	}
	resp1, _ := http.DefaultClient.Do(mk())
	b1 := make([]byte, 256)
	resp1.Body.Read(b1)
	resp1.Body.Close()
	if resp1.StatusCode != 200 {
		t.Fatalf("première émission du nonce: %d ≠ 200", resp1.StatusCode)
	}
	resp2, _ := http.DefaultClient.Do(mk())
	b2 := make([]byte, 256)
	resp2.Body.Read(b2)
	resp2.Body.Close()
	if resp2.StatusCode != 401 {
		t.Fatalf("rejeu: %d ≠ 401", resp2.StatusCode)
	}
}

// TestAuthTimestampWindow — horodatage trop vieux → 401.
func TestAuthTimestampWindow(t *testing.T) {
	h := newTestServer(t)
	tsUnix := time.Now().Add(-10 * time.Minute).Unix()
	nonceB64 := base64.StdEncoding.EncodeToString(make([]byte, 32))
	sum := sha256.Sum256([]byte(nil))
	canonical := CanonicalString(tsUnix, nonceB64, "GET", "/admin/status", sum[:])
	sig := ed25519.Sign(h.priv, []byte(canonical))
	req, _ := http.NewRequest("GET", h.ts.URL+"/admin/status", nil)
	req.Header.Set("X-Nyx-Admin", base64.StdEncoding.EncodeToString(h.pub))
	req.Header.Set("X-Nyx-Ts", fmt.Sprintf("%d", tsUnix))
	req.Header.Set("X-Nyx-Nonce", nonceB64)
	req.Header.Set("X-Nyx-Sig", base64.StdEncoding.EncodeToString(sig))
	resp, _ := http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.StatusCode != 401 {
		t.Fatalf("horodatage vieux de 10 min: %d ≠ 401", resp.StatusCode)
	}
}

// TestMonitoringEndpoints — status, resources, peers, identity, log.
func TestMonitoringEndpoints(t *testing.T) {
	h := newTestServer(t)

	code, body := h.get(t, "/admin/status")
	if code != 200 {
		t.Fatalf("status: %d", code)
	}
	var st map[string]any
	json.Unmarshal([]byte(body), &st)
	if st["events"].(float64) != 7 {
		t.Fatalf("events = %v ≠ 7", st["events"])
	}
	if st["state"] != "actif" {
		t.Fatalf("state = %v", st["state"])
	}

	code, _ = h.get(t, "/admin/resources")
	if code != 200 {
		t.Fatalf("resources: %d", code)
	}

	code, body = h.get(t, "/admin/peers")
	if code != 200 || !strings.Contains(body, "\"peers\"") {
		t.Fatalf("peers: %d %s", code, body)
	}

	code, body = h.get(t, "/admin/identity")
	if code != 200 || !strings.Contains(body, "admin.onion") {
		t.Fatalf("identity: %d %s", code, body)
	}

	// audit : les appels ci-dessus doivent y figurer
	code, body = h.get(t, "/admin/log?tail=50")
	if code != 200 || !strings.Contains(body, "/admin/status") {
		t.Fatalf("log sans trace d'audit: %d %s", code, body)
	}
}

// TestMaintenanceToggle — bascule maintenance et effet sur l'état déclaré.
func TestMaintenanceToggle(t *testing.T) {
	h := newTestServer(t)
	code, body := h.signedReq(t, "POST", "/admin/maintenance", []byte(`{"on":true}`))
	if code != 200 || !strings.Contains(body, "maintenance") {
		t.Fatalf("maintenance on: %d %s", code, body)
	}
	if h.maint.Load() != 1 {
		t.Fatal("flag maintenance non positionné")
	}
	_, body = h.get(t, "/admin/status")
	if !strings.Contains(body, "maintenance") {
		t.Fatalf("status ne reflète pas la maintenance: %s", body)
	}
	h.signedReq(t, "POST", "/admin/maintenance", []byte(`{"on":false}`))
	if h.maint.Load() != 0 {
		t.Fatal("flag maintenance non retiré")
	}
}

// TestKeysLifecycle — ajout, listing, révocation, garde de dernière clé.
func TestKeysLifecycle(t *testing.T) {
	h := newTestServer(t)
	otherPub, _, _ := ed25519.GenerateKey(rand.Reader)
	otherB64 := base64.StdEncoding.EncodeToString(otherPub)

	code, body := h.signedReq(t, "POST", "/admin/keys",
		[]byte(fmt.Sprintf(`{"public_key":%q,"label":"téléphone 2"}`, otherB64)))
	if code != 200 {
		t.Fatalf("ajout clé: %d %s", code, body)
	}
	code, body = h.get(t, "/admin/keys")
	if !strings.Contains(body, otherB64) {
		t.Fatalf("clé absente du listing: %s", body)
	}
	// révocation de la 2e clé (signée avec NOTRE clé, toujours autorisée)
	code, body = h.signedReq(t, "DELETE", "/admin/keys/"+otherB64, nil)
	if code != 200 {
		t.Fatalf("révocation: %d %s", code, body)
	}
	// il ne reste que NOTRE clé : nouvelle tentative de retrait → garde-fou
	code, body = h.signedReq(t, "DELETE", "/admin/keys/"+base64.StdEncoding.EncodeToString(h.pub), nil)
	if code != 400 || !strings.Contains(body, "dernière") {
		t.Fatalf("garde dernière clé: %d %s", code, body)
	}
}

// TestCheckpointTwoSteps — propose signe une racine ; apply consomme la
// proposition ; un apply sans propose est refusé.
func TestCheckpointTwoSteps(t *testing.T) {
	h := newTestServer(t)
	code, body := h.signedReq(t, "POST", "/admin/checkpoint/apply", nil)
	if code != 409 {
		t.Fatalf("apply sans propose: %d ≠ 409", code)
	}
	code, body = h.signedReq(t, "POST", "/admin/checkpoint/propose", []byte(`{"before":5}`))
	if code != 200 || !strings.Contains(body, "abc123") {
		t.Fatalf("propose: %d %s", code, body)
	}
	code, body = h.signedReq(t, "POST", "/admin/checkpoint/apply", nil)
	if code != 200 || !strings.Contains(body, "archive") {
		t.Fatalf("apply: %d %s", code, body)
	}
}

// TestIdentityRotateHonest — la rotation est annoncée non implémentée (501).
func TestIdentityRotateHonest(t *testing.T) {
	h := newTestServer(t)
	code, _ := h.signedReq(t, "POST", "/admin/identity/rotate", nil)
	if code != 501 {
		t.Fatalf("rotate: %d ≠ 501", code)
	}
}

// TestShutdownRequest — shutdown répond 200 puis pousse la demande d'arrêt.
func TestShutdownRequest(t *testing.T) {
	h := newTestServer(t)
	code, body := h.signedReq(t, "POST", "/admin/shutdown", nil)
	if code != 200 || !strings.Contains(body, "shutdown") {
		t.Fatalf("shutdown: %d %s", code, body)
	}
	select {
	case action := <-h.stopped:
		if action != "shutdown" {
			t.Fatalf("action = %s", action)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("aucune demande d'arrêt reçue")
	}
}
