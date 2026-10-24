package api

import (
        "bytes"
        "crypto/ed25519"
        "crypto/sha256"
        "encoding/json"
        "fmt"
        "net/http"
        "net/http/httptest"
        "net/url"
        "testing"
        "time"

        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

// linkIdent — une identité de test (clé ed25519 + onion dérivé réel via
// journal.PubKeyToOnion — les signatures du protocole sont vérifiées par la
// MÊME dérivation que la production).
type linkIdent struct {
        pub  ed25519.PublicKey
        priv ed25519.PrivateKey
        addr string // "xxx.onion" complet
}

func newLinkIdent(t *testing.T) linkIdent {
        t.Helper()
        pub, priv, err := ed25519.GenerateKey(nil)
        if err != nil {
                t.Fatal(err)
        }
        addr, err := journal.PubKeyToOnion(pub)
        if err != nil {
                t.Fatal(err)
        }
        // PubKeyToOnion retourne DÉJÀ « …onion » (62 car.).
        return linkIdent{pub: pub, priv: priv, addr: addr}
}

// linkPullAuth — auth « ts:sig » du canon PULL (miroir de LinkChannel).
func (id linkIdent) linkPullAuth() string {
        ts := time.Now().Unix()
        canon := "NYX-LINK-PULL\n" + id.addr + "\n" + fmt.Sprintf("%d", ts)
        sig := ed25519.Sign(id.priv, []byte(canon))
        return fmt.Sprintf("%d:%s", ts, b64.EncodeToString(sig))
}

// linkReplyAuth — auth du canon REPLY : id + from + status + sha256(body).
func (id linkIdent) linkReplyAuth(reqID string, status int, bodyB64 string) string {
        sum := sha256.Sum256([]byte(bodyB64))
        ts := time.Now().Unix()
        canon := "NYX-LINK-REPLY\n" + id.addr + "\n" + fmt.Sprintf("%d", ts) + "\n" +
                reqID + "\n" + fmt.Sprintf("%d", status) + "\n" + fmt.Sprintf("%x", sum)
        sig := ed25519.Sign(id.priv, []byte(canon))
        return fmt.Sprintf("%d:%s", ts, b64.EncodeToString(sig))
}

// linkSend — dépose une requête (helper ; ferme le corps, renvoie le code).
func linkSend(t *testing.T, ts *httptest.Server, to, id, method, path, bodyB64 string) int {
        t.Helper()
        payload := map[string]string{"to": to, "id": id, "method": method, "path": path, "body_b64": bodyB64}
        raw, _ := json.Marshal(payload)
        resp, err := http.Post(ts.URL+"/link/send", "application/json", bytes.NewReader(raw))
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        return resp.StatusCode
}

// linkPull — tire le lot du téléphone (doit répondre immédiatement si des
// requêtes fraîches attendent), renvoie (code, nb requêtes). La signature
// base64 est URL-ENCODÉE (le « + » d'une signature brute deviendrait un
// espace dans le query — miroir du client Kotlin, URLEncoder.encode).
func linkPull(t *testing.T, ts *httptest.Server, id linkIdent) (int, []linkRequest) {
        t.Helper()
        client := &http.Client{Timeout: 10 * time.Second}
        resp, err := client.Get(ts.URL + "/link/pull?me=" + id.addr + "&auth=" + url.QueryEscape(id.linkPullAuth()))
        if err != nil {
                t.Fatal(err)
        }
        defer resp.Body.Close()
        var out struct {
                Reqs []linkRequest `json:"reqs"`
        }
        if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
                t.Fatal(err)
        }
        return resp.StatusCode, out.Reqs
}

// TestLinkRoundTrip — le voyage COMPLET : dépôt par un demandeur → le
// téléphone (PULL authentifié) reçoit SA requête immédiatement → il répond
// (REPLY signé) → le demandeur (WAIT) reçoit la réponse.
func TestLinkRoundTrip(t *testing.T) {
        srv := newRelayTestServer(t)
        phone := newLinkIdent(t)
        reqID := "a1b2c3d4e5f60718293a4b5c6d7e8f90"

        // 1. Le demandeur dépose GET /health pour le téléphone.
        if code := linkSend(t, srv, phone.addr, reqID, "GET", "/health", ""); code != 202 {
                t.Fatalf("send : attendu 202, reçu %d", code)
        }

        // 2. Le téléphone tire — la requête l'attend, réponse immédiate.
        code, reqs := linkPull(t, srv, phone)
        if code != 200 {
                t.Fatalf("pull : attendu 200, reçu %d", code)
        }
        if len(reqs) != 1 || reqs[0].ID != reqID || reqs[0].Path != "/health" || reqs[0].Method != "GET" {
                t.Fatalf("pull : lot inattendu %+v", reqs)
        }

        // 3. Le téléphone exécute localement (simulé) et répond (signé).
        body := "eyJvayI6dHJ1ZX0=" // {"ok":true}
        payload := map[string]any{
                "id": reqID, "from": phone.addr, "status": 200,
                "body_b64": body, "auth": phone.linkReplyAuth(reqID, 200, body),
        }
        raw, _ := json.Marshal(payload)
        resp, err := http.Post(srv.URL+"/link/reply", "application/json", bytes.NewReader(raw))
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        if resp.StatusCode != 202 {
                t.Fatalf("reply : attendu 202, reçu %d", resp.StatusCode)
        }

        // 4. Le demandeur reçoit la réponse (déjà présente — 200 immédiat).
        client := &http.Client{Timeout: 10 * time.Second}
        resp3, err := client.Get(srv.URL + "/link/wait?id=" + reqID)
        if err != nil {
                t.Fatal(err)
        }
        defer resp3.Body.Close()
        if resp3.StatusCode != 200 {
                t.Fatalf("wait : attendu 200, reçu %d", resp3.StatusCode)
        }
        var waitOut struct {
                Status  int    `json:"status"`
                BodyB64 string `json:"body_b64"`
        }
        if err := json.NewDecoder(resp3.Body).Decode(&waitOut); err != nil {
                t.Fatal(err)
        }
        if waitOut.Status != 200 || waitOut.BodyB64 != body {
                t.Fatalf("wait : réponse inattendue %+v", waitOut)
        }
}

// TestLinkPullAuth — sans signature, avec la signature d'une AUTRE
// identité, ou sur une adresse invalide : refus (seul le détenteur tire).
func TestLinkPullAuth(t *testing.T) {
        srv := newRelayTestServer(t)
        phone := newLinkIdent(t)
        other := newLinkIdent(t)
        client := &http.Client{Timeout: 10 * time.Second}

        // Pas d'auth du tout.
        resp, err := client.Get(srv.URL + "/link/pull?me=" + phone.addr)
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        if resp.StatusCode != 401 {
                t.Fatalf("sans auth : attendu 401, reçu %d", resp.StatusCode)
        }
        // Signature d'une autre identité sur l'adresse du téléphone.
        resp, err = client.Get(srv.URL + "/link/pull?me=" + phone.addr + "&auth=" + other.linkPullAuth())
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        if resp.StatusCode != 401 {
                t.Fatalf("signature étrangère : attendu 401, reçu %d", resp.StatusCode)
        }
        // Adresse invalide.
        resp, err = client.Get(srv.URL + "/link/pull?me=pasunonion&auth=" + phone.linkPullAuth())
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        if resp.StatusCode != 400 {
                t.Fatalf("adresse invalide : attendu 400, reçu %d", resp.StatusCode)
        }
}

// TestLinkSendValidation — le lien n'est PAS un proxy ouvert : paths
// whitelistés, méthodes bornées, ids bien formés, doublon d'id en vol.
func TestLinkSendValidation(t *testing.T) {
        srv := newRelayTestServer(t)
        phone := newLinkIdent(t)
        cases := []struct {
                name   string
                id     string
                method string
                path   string
                want   int
        }{
                {"admin interdit", "b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1", "GET", "/admin/status", 400},
                {"gossip interdit", "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2", "POST", "/gossip/push", 400},
                {"racine interdite", "b3b3b3b3b3b3b3b3b3b3b3b3b3b3b3b3", "GET", "/", 400},
                {"méthode inconnue", "b4b4b4b4b4b4b4b4b4b4b4b4b4b4b4b4", "DELETE", "/health", 400},
                {"inbox autorisé (query ok)", "b5b5b5b5b5b5b5b5b5b5b5b5b5b5b5b5", "GET", "/client/inbox?to=x&after=0", 202},
                {"id trop court", "ffff", "GET", "/health", 400},
                {"id non hex", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", "GET", "/health", 400},
        }
        for _, c := range cases {
                if code := linkSend(t, srv, phone.addr, c.id, c.method, c.path, ""); code != c.want {
                        t.Fatalf("%s : attendu %d, reçu %d", c.name, c.want, code)
                }
        }
        // Doublon d'id en vol → 409.
        if code := linkSend(t, srv, phone.addr, "b5b5b5b5b5b5b5b5b5b5b5b5b5b5b5b5", "GET", "/client/alias?name=x", ""); code != 409 {
                t.Fatalf("id en vol : attendu 409, reçu %d", code)
        }
}

// TestLinkQueueBoundAndLot — la file du destinataire est bornée (8 → 429)
// et le lot PULL est borné (4 requêtes max, FIFO).
func TestLinkQueueBoundAndLot(t *testing.T) {
        srv := newRelayTestServer(t)
        phone := newLinkIdent(t)
        for i := 0; i < 8; i++ {
                id := fmt.Sprintf("c%032d", i)
                if code := linkSend(t, srv, phone.addr, id, "GET", "/health", ""); code != 202 {
                        t.Fatalf("send %d : attendu 202, reçu %d", i, code)
                }
        }
        // 9e : file pleine.
        if code := linkSend(t, srv, phone.addr, "c1111111111111111111111111111111", "GET", "/health", ""); code != 429 {
                t.Fatalf("file pleine : attendu 429, reçu %d", code)
        }
        // Le PULL reçoit AU PLUS 4 requêtes (FIFO : 0,1,2,3).
        code, reqs := linkPull(t, srv, phone)
        if code != 200 {
                t.Fatalf("pull : attendu 200, reçu %d", code)
        }
        if len(reqs) != 4 {
                t.Fatalf("lot : attendu 4 requêtes, reçu %d", len(reqs))
        }
        for i, r := range reqs {
                want := fmt.Sprintf("c%032d", i)
                if r.ID != want {
                        t.Fatalf("FIFO brisé : position %d = %s (attendu %s)", i, r.ID, want)
                }
        }
}

// TestLinkRedelivery — at-least-once : une requête délivrée mais jamais
// exécutée (téléphone mort en vol) est RE-LIVRÉE après la fenêtre de grâce.
func TestLinkRedelivery(t *testing.T) {
        srv := newRelayTestServer(t)
        phone := newLinkIdent(t)
        reqID := "d1d2d3d4d5d6d7d8d9d0d1d2d3d4d5d6d7d8"
        if code := linkSend(t, srv, phone.addr, reqID, "GET", "/health", ""); code != 202 {
                t.Fatalf("send : reçu %d", code)
        }
        // 1re livraison : la requête part au téléphone.
        if _, reqs := linkPull(t, srv, phone); len(reqs) != 1 {
                t.Fatalf("1re livraison : attendu 1, reçu %d", len(reqs))
        }
        // On simule le téléphone mort en vol depuis 22 s (la requête livrée
        // n'a jamais été exécutée — état interne, sans attendre 20 s réels).
        link.mu.Lock()
        if e := link.reqs[reqID]; e != nil {
                e.deliveredAt = time.Now().Add(-linkRedeliver - 2*time.Second)
        }
        link.mu.Unlock()
        // Ré-livraison : la requête repart.
        if _, reqs := linkPull(t, srv, phone); len(reqs) != 1 {
                t.Fatalf("ré-livraison : attendu 1, reçu %d", len(reqs))
        }
}

// TestLinkReplyGuards — seul le DESTINATAIRE peut répondre, une seule fois,
// et le WAIT reçoit la réponse légitime.
func TestLinkReplyGuards(t *testing.T) {
        srv := newRelayTestServer(t)
        phone := newLinkIdent(t)
        other := newLinkIdent(t)
        reqID := "e1e2e3e4e5e6e7e8e9e0e1e2e3e4e5e6e7e8"
        if code := linkSend(t, srv, phone.addr, reqID, "GET", "/health", ""); code != 202 {
                t.Fatalf("send : reçu %d", code)
        }
        doReply := func(from linkIdent) int {
                payload := map[string]any{
                        "id": reqID, "from": from.addr, "status": 200, "body_b64": "",
                        "auth": from.linkReplyAuth(reqID, 200, ""),
                }
                raw, _ := json.Marshal(payload)
                resp, err := http.Post(srv.URL+"/link/reply", "application/json", bytes.NewReader(raw))
                if err != nil {
                        t.Fatal(err)
                }
                resp.Body.Close()
                return resp.StatusCode
        }
        // Un tiers répond → 403.
        if code := doReply(other); code != 403 {
                t.Fatalf("tiers répondant : attendu 403, reçu %d", code)
        }
        // Le destinataire répond → 202.
        if code := doReply(phone); code != 202 {
                t.Fatalf("destinataire : attendu 202, reçu %d", code)
        }
        // Double réponse → 404 (déjà répondue).
        if code := doReply(phone); code != 404 {
                t.Fatalf("double réponse : attendu 404, reçu %d", code)
        }
        // WAIT reçoit la réponse du destinataire.
        client := &http.Client{Timeout: 10 * time.Second}
        resp, err := client.Get(srv.URL + "/link/wait?id=" + reqID)
        if err != nil {
                t.Fatal(err)
        }
        defer resp.Body.Close()
        if resp.StatusCode != 200 {
                t.Fatalf("wait : attendu 200, reçu %d", resp.StatusCode)
        }
}

// TestLinkWaitUnknown — le WAIT d'un id inconnu ne fait pas patienter 75 s :
// 404 immédiat.
func TestLinkWaitUnknown(t *testing.T) {
        srv := newRelayTestServer(t)
        client := &http.Client{Timeout: 10 * time.Second}
        start := time.Now()
        resp, err := client.Get(srv.URL + "/link/wait?id=abababababababababababababababab")
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        if resp.StatusCode != 404 {
                t.Fatalf("attendu 404, reçu %d", resp.StatusCode)
        }
        if time.Since(start) > 2*time.Second {
                t.Fatalf("404 trop lent : %v", time.Since(start))
        }
}

// TestLinkWaitNotified — le demandeur en attente est RÉVEILLÉ par la
// réponse (chemin notify du long-poll, pas seulement la consultation tardive).
func TestLinkWaitNotified(t *testing.T) {
        srv := newRelayTestServer(t)
        phone := newLinkIdent(t)
        reqID := "f1f2f3f4f5f6f7f8f9f0f1f2f3f4f5f6f7f8"
        if code := linkSend(t, srv, phone.addr, reqID, "GET", "/health", ""); code != 202 {
                t.Fatalf("send : reçu %d", code)
        }
        // Le téléphone prend la livraison (sinon la REPLY est refusée).
        if _, reqs := linkPull(t, srv, phone); len(reqs) != 1 {
                t.Fatalf("livraison : reçu %d", len(reqs))
        }

        got := make(chan int, 1)
        client := &http.Client{Timeout: 10 * time.Second}
        go func() {
                resp, err := client.Get(srv.URL + "/link/wait?id=" + reqID)
                if err != nil {
                        got <- 0
                        return
                }
                resp.Body.Close()
                got <- resp.StatusCode
        }()
        // Laisser le WAIT s'installer dans son select, PUIS répondre.
        time.Sleep(300 * time.Millisecond)
        payload := map[string]any{
                "id": reqID, "from": phone.addr, "status": 200, "body_b64": "",
                "auth": phone.linkReplyAuth(reqID, 200, ""),
        }
        raw, _ := json.Marshal(payload)
        resp, err := http.Post(srv.URL+"/link/reply", "application/json", bytes.NewReader(raw))
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        if resp.StatusCode != 202 {
                t.Fatalf("reply : reçu %d", resp.StatusCode)
        }
        select {
        case code := <-got:
                if code != 200 {
                        t.Fatalf("wait réveillé : attendu 200, reçu %d", code)
                }
        case <-time.After(5 * time.Second):
                t.Fatal("le WAIT n'a pas été réveillé par la réponse")
        }
}

// TestLinkMaintenance — toutes les routes lien refusent en maintenance
// (cohérent avec /call/relay et les routes client).
func TestLinkMaintenance(t *testing.T) {
        phone := newLinkIdent(t)

        // Serveur dédié EN maintenance.
        dir := t.TempDir()
        store, err := journal.OpenStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        maint := &Server{
                Version: "test", Onion: "nœud.onion",
                GossipPub: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", GossipPriv: make([]byte, 32),
                Store: store, Kick: make(chan struct{}, 1),
                Maintenance: func() bool { return true },
        }
        ts := httptest.NewServer(maint.Handler())
        defer ts.Close()

        payload := map[string]string{"to": phone.addr, "id": "9f1f2f3f4f5f6f7f8f9f0f1f2f3f4f5f6", "method": "GET", "path": "/health"}
        raw, _ := json.Marshal(payload)
        resp, err := http.Post(ts.URL+"/link/send", "application/json", bytes.NewReader(raw))
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        if resp.StatusCode != 503 {
                t.Fatalf("send en maintenance : attendu 503, reçu %d", resp.StatusCode)
        }
        client := &http.Client{Timeout: 10 * time.Second}
        resp, err = client.Get(ts.URL + "/link/pull?me=" + phone.addr + "&auth=" + phone.linkPullAuth())
        if err != nil {
                t.Fatal(err)
        }
        resp.Body.Close()
        if resp.StatusCode != 503 {
                t.Fatalf("pull en maintenance : attendu 503, reçu %d", resp.StatusCode)
        }
}
