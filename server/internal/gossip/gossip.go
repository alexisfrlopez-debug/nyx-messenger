// Package gossip — réplication pair-à-pair chiffrée (NaCl) entre réplicas, via Tor.
//
// Chaque pair est joint via le SOCKS5 de Tor vers son adresse onion. Les requêtes
// push/pull sont encapsulées dans une secretbox dont la clé est dérivée du secret
// partagé X25519 entre les clés gossip des deux nœuds.
package gossip

import (
        "bytes"
        "crypto/rand"
        "encoding/base64"
        "encoding/json"
        "fmt"
        "log"
        "net/http"
        "sync"
        "time"

        "golang.org/x/crypto/curve25519"
        "golang.org/x/crypto/nacl/secretbox"
        "golang.org/x/net/proxy"

        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/config"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

var b64 = base64.StdEncoding

// Node — moteur de réplication.
type Node struct {
        SelfOnion   string
        GossipPriv  []byte // X25519
        Store       *journal.Store
        HTTP        *http.Client // client forcé SOCKS5 (Tor)
        Peers       []config.Peer
        Kick        chan struct{} // signal « nouvel événement : synchroniser maintenant »
        intervalSec int
        stop        chan struct{}

        mu      sync.Mutex
        cursors map[string]map[string]uint64 // par pair (onion) : dernier seq envoyé, par auteur
}

// NewNode — construit le moteur avec un client HTTP SOCKS5.
func NewNode(selfOnion string, gossipPriv []byte, store *journal.Store, socksAddr string, peers []config.Peer, intervalSec int) (*Node, error) {
        dialer, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
        if err != nil {
                return nil, err
        }
        ctxDialer := dialer.(proxy.ContextDialer)
        transport := &http.Transport{
                DialContext: ctxDialer.DialContext,
        }
        if intervalSec <= 0 {
                intervalSec = 60
        }
        return &Node{
                SelfOnion:   selfOnion,
                GossipPriv:  gossipPriv,
                Store:       store,
                HTTP:        &http.Client{Transport: transport, Timeout: 120 * time.Second},
                Peers:       peers,
                Kick:        make(chan struct{}, 1),
                intervalSec: intervalSec,
                stop:        make(chan struct{}),
                cursors:     map[string]map[string]uint64{},
        }, nil
}

// AddPeer — enregistre un pair découvert dynamiquement.
func (n *Node) AddPeer(p config.Peer) {
        for _, q := range n.Peers {
                if q.Onion == p.Onion {
                        return
                }
        }
        n.Peers = append(n.Peers, p)
        log.Printf("[gossip] pair ajouté: %s", p.Onion)
        select { case n.Kick <- struct{}{}: default: }
}

// Run — boucle de réplication (périodique + kick immédiat).
func (n *Node) Run() {
        t := time.NewTicker(time.Duration(n.intervalSec) * time.Second)
        defer t.Stop()
        for {
                select {
                case <-n.stop:
                        return
                case <-t.C:
                        n.SyncAll()
                case <-n.Kick:
                        time.Sleep(500 * time.Millisecond) // laisse le POST répondre d'abord
                        n.SyncAll()
                }
        }
}

// Stop — arrête la boucle.
func (n *Node) Stop() { close(n.stop) }

// SyncAll — push puis pull vers chaque pair.
func (n *Node) SyncAll() {
        for _, p := range n.Peers {
                if p.Onion == n.SelfOnion {
                        continue
                }
                n.pushPeer(p)
                n.pullPeer(p)
        }
}

// ---- enveloppes chiffrées ----

type envelope struct {
        Sender  string `json:"sender"`  // onion de l'expéditeur
        Nonce   string `json:"nonce"`   // base64 (24 octets)
        Payload string `json:"payload"` // base64 (secretbox)
}

func seal(key, plaintext []byte) (nonce []byte, sealed []byte, err error) {
        nonce = make([]byte, 24)
        if _, err = rand.Read(nonce); err != nil {
                return nil, nil, err
        }
        return nonce, secretbox.Seal(nil, plaintext, (*[24]byte)(nonce), (*[32]byte)(key)), nil
}

func sharedKey(gossipPriv []byte, peerPubB64 string) ([]byte, error) {
        pub, err := b64.DecodeString(peerPubB64)
        if err != nil || len(pub) != 32 {
                return nil, fmt.Errorf("clé gossip publique du pair invalide")
        }
        shared, err := curve25519.X25519(gossipPriv, pub)
        if err != nil {
                return nil, err
        }
        return shared, nil
}

func (n *Node) post(p config.Peer, path string, payload []byte) ([]byte, error) {
        key, err := sharedKey(n.GossipPriv, p.GossipPub)
        if err != nil {
                return nil, err
        }
        nonce, sealed, err := seal(key, payload)
        if err != nil {
                return nil, err
        }
        body, _ := json.Marshal(envelope{Sender: n.SelfOnion, Nonce: b64.EncodeToString(nonce), Payload: b64.EncodeToString(sealed)})
        resp, err := n.HTTP.Post("http://"+p.Onion+path, "application/json", bytes.NewReader(body))
        if err != nil {
                return nil, err
        }
        defer resp.Body.Close()
        if resp.StatusCode != 200 {
                return nil, fmt.Errorf("%s → HTTP %d", path, resp.StatusCode)
        }
        var env envelope
        if err := json.NewDecoder(resp.Body).Decode(&env); err != nil {
                return nil, err
        }
        if env.Sender != p.Onion {
                return nil, fmt.Errorf("réponse d'un pair inattendu: %s", env.Sender)
        }
        nonce, err = b64.DecodeString(env.Nonce)
        if err != nil || len(nonce) != 24 {
                return nil, fmt.Errorf("nonce invalide")
        }
        sealed, err = b64.DecodeString(env.Payload)
        if err != nil {
                return nil, err
        }
        clear, ok := secretbox.Open(nil, sealed, (*[24]byte)(nonce), (*[32]byte)(key))
        if !ok {
                return nil, fmt.Errorf("déchiffrement de la réponse impossible")
        }
        return clear, nil
}

// ---- push ----

type pushReq struct {
        Sender string           `json:"sender"`
        Cursor map[string]uint64 `json:"cursor"`
        Events []*journal.Event  `json:"events"`
}

type pushResp struct {
        Accepted int `json:"accepted"`
        Rejected int `json:"rejected"`
}

func (n *Node) pushPeer(p config.Peer) {
        n.mu.Lock()
        cursor := n.cursors[p.Onion]
        if cursor == nil {
                cursor = map[string]uint64{}
        }
        n.mu.Unlock()

        events, _ := n.Store.EventsFor(cursor, 500)
        if len(events) == 0 {
                return
        }
        req := pushReq{Sender: n.SelfOnion, Cursor: cursor, Events: events}
        b, _ := json.Marshal(req)
        respB, err := n.post(p, "/gossip/push", b)
        if err != nil {
                log.Printf("[gossip] push %s: %v", p.Onion, err)
                return
        }
        var r pushResp
        if err := json.Unmarshal(respB, &r); err != nil {
                log.Printf("[gossip] push %s: réponse invalide: %v", p.Onion, err)
                return
        }
        // Avancer le curseur du pair au max seq envoyé par auteur (dé-dup par id côté pair).
        n.mu.Lock()
        for _, ev := range events {
                if ev.Seq > n.cursors[p.Onion][ev.Author] {
                        if n.cursors[p.Onion] == nil {
                                n.cursors[p.Onion] = map[string]uint64{}
                        }
                        n.cursors[p.Onion][ev.Author] = ev.Seq
                }
        }
        n.mu.Unlock()
        log.Printf("[gossip] push %s: %d acceptés, %d rejetés", p.Onion, r.Accepted, r.Rejected)
}

// ---- pull ----

type pullReq struct {
        Sender string           `json:"sender"`
        Want   map[string]uint64 `json:"want"`  // par auteur : dernier seq que nous avons
        Limit  int              `json:"limit"`
}

type pullResp struct {
        Events []*journal.Event `json:"events"`
        More   bool             `json:"more"`
}

func (n *Node) pullPeer(p config.Peer) {
        for round := 0; round < 20; round++ { // limite de sécurité : 20 lots × 500
                req := pullReq{Sender: n.SelfOnion, Want: n.Store.SinceMap(), Limit: 500}
                b, _ := json.Marshal(req)
                respB, err := n.post(p, "/gossip/pull", b)
                if err != nil {
                        log.Printf("[gossip] pull %s: %v", p.Onion, err)
                        return
                }
                var r pullResp
                if err := json.Unmarshal(respB, &r); err != nil {
                        log.Printf("[gossip] pull %s: réponse invalide: %v", p.Onion, err)
                        return
                }
                if len(r.Events) == 0 {
                        return
                }
                now := time.Now().Unix()
                accepted := 0
                for _, ev := range r.Events {
                        if _, err := n.Store.Append(ev, now); err == nil {
                                accepted++
                        }
                }
                log.Printf("[gossip] pull %s: %d/%d événements intégrés", p.Onion, accepted, len(r.Events))
                if accepted > 0 {
                        select { case n.Kick <- struct{}{}: default: } // re-propager au reste du réseau
                }
                if !r.More {
                        return
                }
        }
}
