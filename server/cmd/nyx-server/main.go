// nyx-server — nœud serveur Nyx : onion service v3 + journal signé + réplication gossip.
//
// Prérequis : un démon Tor avec ControlPort et CookieAuthentication activés.
//   torrc minimal :
//     ControlPort 9051
//     CookieAuthentication 1
//     SocksPort 9050
package main

import (
        "encoding/base64"
        "flag"
        "fmt"
        "log"
        "net/http"
        "os"
        "os/signal"
        "path/filepath"
        "syscall"
        "time"

        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/api"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/config"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/gossip"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/identity"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/tor"
)

const version = "0.1.0"

var defaultCookiePaths = []string{
        "/run/tor/control.authcookie",
        "/var/run/tor/control.authcookie",
}

func main() {
        dataDir := flag.String("data-dir", "./data", "répertoire de données du nœud")
        httpPort := flag.Int("http-port", 8080, "port HTTP local (derrière Tor : Port=80)")
        controlPort := flag.Int("control-port", 9051, "control port Tor")
        socksPort := flag.Int("socks-port", 9050, "SOCKS5 Tor")
        cookieFile := flag.String("cookie-file", "", "fichier cookie control port (auto-détecté si vide)")
        password := flag.String("control-password", "", "mot de passe control port (sinon cookie)")
        configFile := flag.String("config", "", "fichier config.json (défaut : <data-dir>/config.json)")
        interval := flag.Int("gossip-interval", 60, "période de réplication en secondes")
        flag.Parse()

        log.SetFlags(log.LstdFlags | log.LUTC)
        log.Printf("Nyx server %s — démarrage (data: %s)", version, *dataDir)

        if err := os.MkdirAll(*dataDir, 0o700); err != nil {
                log.Fatalf("répertoire de données: %v", err)
        }

        cfgPath := *configFile
        if cfgPath == "" {
                cfgPath = filepath.Join(*dataDir, "config.json")
        }
        cfg, err := config.Load(cfgPath, *dataDir)
        if err != nil {
                log.Fatalf("config: %v", err)
        }
        cfg.HTTPPort = *httpPort
        cfg.ControlPort = *controlPort
        cfg.SOCKSPort = *socksPort

        // 1. Identité (clé onion ed25519 + clé gossip X25519)
        id, err := identity.LoadOrCreate(*dataDir)
        if err != nil {
                log.Fatalf("identité: %v", err)
        }
        onionPriv, err := id.OnionPrivKey()
        if err != nil {
                log.Fatalf("clé onion: %v", err)
        }
        gossipPriv, err := id.GossipPrivKey()
        if err != nil {
                log.Fatalf("clé gossip: %v", err)
        }
        log.Printf("identité: onion=%s gossip_pub=%s…", id.Onion, id.GossipPub[:16])

        // 2. Journal
        store, err := journal.OpenStore(*dataDir)
        if err != nil {
                log.Fatalf("journal: %v", err)
        }
        log.Printf("journal: %d événements chargés", store.Count())

        // 3. Publication onion service (via control port)
        onionAddr := id.Onion
        go func() {
                cookie := *cookieFile
                if cookie == "" && *password == "" {
                        cookie = tor.FindCookieFile(defaultCookiePaths)
                }
                for {
                        ctrl, err := tor.Dial(fmt.Sprintf("127.0.0.1:%d", cfg.ControlPort))
                        if err != nil {
                                log.Printf("[tor] control port injoignable (%v) — nouvel essai dans 5 s", err)
                                time.Sleep(5 * time.Second)
                                continue
                        }
                        if err := ctrl.Authenticate(cookie, *password); err != nil {
                                log.Fatalf("[tor] authentification: %v", err)
                        }
                        b64Priv := base64.StdEncoding.EncodeToString(onionPriv)
                        sid, err := ctrl.PublishOnion(b64Priv, cfg.HTTPPort)
                        if err != nil {
                                log.Printf("[tor] publication onion: %v — nouvel essai dans 10 s", err)
                                ctrl.Close()
                                time.Sleep(10 * time.Second)
                                continue
                        }
                        ctrl.Close()
                        if sid+".onion" != onionAddr {
                                log.Printf("[tor] AVERTISSEMENT: ServiceID tor (%s.onion) ≠ identité calculée (%s)",
                                        sid, onionAddr)
                                onionAddr = sid + ".onion"
                        }
                        log.Printf("✔ onion service publié : %s (→ 127.0.0.1:%d)", onionAddr, cfg.HTTPPort)
                        return
                }
        }()

        // 4. API + gossip
        kick := make(chan struct{}, 1)
        peersPtr := cfg.Peers
        srv := &api.Server{
                Version:    version,
                Onion:      onionAddr,
                GossipPub:  id.GossipPub,
                GossipPriv: gossipPriv,
                Store:      store,
                Peers:      func() []config.Peer { return peersPtr },
                AddPeer: func(p config.Peer) {
                        for _, q := range peersPtr {
                                if q.Onion == p.Onion {
                                        return
                                }
                        }
                        peersPtr = append(peersPtr, p)
                        _ = cfg.Save() // persister les pairs découverts
                },
                Kick: kick,
        }

        gn, err := gossip.NewNode(onionAddr, gossipPriv, store,
                fmt.Sprintf("127.0.0.1:%d", cfg.SOCKSPort), peersPtr, *interval)
        if err != nil {
                log.Fatalf("gossip: %v", err)
        }
        // partage des signaux entre API et moteur gossip
        go func() {
                for range kick {
                        select { case gn.Kick <- struct{}{}: default: }
                }
        }()
        go gn.Run()

        addr := fmt.Sprintf("127.0.0.1:%d", cfg.HTTPPort)
        log.Printf("✔ API en écoute sur %s (onion:80 → ici)", addr)
        go func() {
                if err := http.ListenAndServe(addr, srv.Handler()); err != nil {
                        log.Fatalf("http: %v", err)
                }
        }()

        fmt.Println("Nyx est prêt. Adresse onion du nœud : " + onionAddr)

        // 5. Arrêt propre
        sig := make(chan os.Signal, 1)
        signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
        <-sig
        log.Printf("arrêt…")
        gn.Stop()
        _ = cfg.Save()
        log.Printf("au revoir")
}
