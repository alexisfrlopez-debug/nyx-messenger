// nyx-server — nœud serveur Nyx : onion service v3 + journal signé + réplication gossip.
//
// Prérequis : un démon Tor avec ControlPort et CookieAuthentication activés.
//
//      torrc minimal :
//        ControlPort 9051
//        CookieAuthentication 1
//        SocksPort 9050
//
// v0.2.0 (session 12) :
//   - type "profile" (carte publique à visibilité par champ — wire-format.md §7) ;
//   - API ADMIN complète (/admin/*) sur un onion DÉDIÉ (port 9091), activée dès
//     qu'une clé admin est configurée dans config.json (console nyx-admin,
//     dépôt privé APPLI_ADMIN.md) : signature ed25519 + nonce anti-replay ;
//   - arrêt gracieux orchestré (shutdown/restart admin), maintenance (503 client).
package main

import (
        "context"
        "crypto/ed25519"
        "encoding/base64"
        "flag"
        "fmt"
        "log"
        "net/http"
        "os"
        "os/signal"
        "path/filepath"
        "sync/atomic"
        "syscall"
        "time"

        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/adminapi"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/api"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/config"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/gossip"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/identity"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/tor"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/transportxmpp"
        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/turn"
)

const version = "0.2.0"

var defaultCookiePaths = []string{
        "/run/tor/control.authcookie",
        "/var/run/tor/control.authcookie",
}

const adminPort = 9091 // onion d'admin dédié (APPLI_ADMIN.md §2/§4)

// adminKeyAdapter — adapte *config.Config à adminapi.KeyStore (découplage).
type adminKeyAdapter struct{ c *config.Config }

func (a adminKeyAdapter) HasAdminKey(p string) bool { return a.c.HasAdminKey(p) }
func (a adminKeyAdapter) AddAdminKey(k adminapi.AdminKeyView) bool {
        return a.c.AddAdminKey(config.AdminKey{PublicKey: k.PublicKey, Label: k.Label, AddedAt: k.AddedAt})
}
func (a adminKeyAdapter) RemoveAdminKey(p string) (bool, error) { return a.c.RemoveAdminKey(p) }
func (a adminKeyAdapter) AdminKeys() []adminapi.AdminKeyView {
        ks := a.c.AdminKeys()
        out := make([]adminapi.AdminKeyView, 0, len(ks))
        for _, k := range ks {
                out = append(out, adminapi.AdminKeyView{PublicKey: k.PublicKey, Label: k.Label, AddedAt: k.AddedAt})
        }
        return out
}
func (a adminKeyAdapter) Save() error { return a.c.Save() }

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

        // 3. État partagé : maintenance (API admin → API client), arrêt demandé.
        maintenance := &atomic.Int32{}
        stopRequest := make(chan string, 1)

        // 4. Moteur gossip (créé AVANT l'API : les closures d'admin y accèdent).
        kick := make(chan struct{}, 1)
        peersPtr := cfg.Peers
        gn, err := gossip.NewNode(id.Onion, gossipPriv, store,
                fmt.Sprintf("127.0.0.1:%d", cfg.SOCKSPort), peersPtr, *interval)
        if err != nil {
                log.Fatalf("gossip: %v", err)
        }

        // addPeerShared — enregistrement d'un pair (API découverte + commande admin) :
        // met à jour la config, la liste locale ET le moteur gossip (sinon le pair
        // n'est réellement joint qu'au redémarrage).
        addPeerShared := func(p config.Peer) error {
                for _, q := range peersPtr {
                        if q.Onion == p.Onion {
                                return fmt.Errorf("pair déjà connu: %s", p.Onion)
                        }
                }
                if _, err := journal.OnionToPubKey(p.Onion); err != nil {
                        return fmt.Errorf("adresse onion invalide")
                }
                if gp, err := base64.StdEncoding.DecodeString(p.GossipPub); err != nil || len(gp) != 32 {
                        return fmt.Errorf("gossip_pub invalide (base64, 32 octets)")
                }
                peersPtr = append(peersPtr, p)
                cfg.Peers = peersPtr
                if err := cfg.Save(); err != nil {
                        return err
                }
                gn.AddPeer(p)
                return nil
        }
        removePeerShared := func(onion string) error {
                out := peersPtr[:0]
                found := false
                for _, q := range peersPtr {
                        if q.Onion == onion {
                                found = true
                                continue
                        }
                        out = append(out, q)
                }
                peersPtr = out
                cfg.Peers = peersPtr
                if err := cfg.Save(); err != nil {
                        return err
                }
                gn.RemovePeer(onion)
                if !found {
                        return fmt.Errorf("pair inconnu: %s", onion)
                }
                return nil
        }

        // 5. API client + gossip.
        // rendezvousRead — closure câblée APRÈS création de l'API admin (ADR-016 :
        // mêmes fichiers rendezvous.json/.sig que POST /admin/rendezvous) ; nil
        // avant → /rendezvous répond 404 (aucune liste).
        var rendezvousRead func() ([]byte, []byte, bool)
        srv := &api.Server{
                Version:    version,
                Onion:      id.Onion,
                GossipPub:  id.GossipPub,
                GossipPriv: gossipPriv,
                Store:      store,
                Peers:      func() []config.Peer { return peersPtr },
                AddPeer: func(p config.Peer) {
                        _ = addPeerShared(p) // découverte silencieuse (erreurs déjà journalisées)
                },
                Maintenance: func() bool { return maintenance.Load() == 1 },
                Kick:        kick,
                // Rendezvous signé (ADR-016) : la closure est câblée APRÈS création de
                // l'API admin (mêmes fichiers) — nil-safe avant (404 tant que non câblé).
                RendezvousRead: func() ([]byte, []byte, bool) {
                        if rendezvousRead == nil {
                                return nil, nil, false
                        }
                        return rendezvousRead()
                },
        }

        // partage des signaux entre API, moteur gossip et transport XMPP (le cas échéant)
        var xkick chan struct{}
        go func() {
                for range kick {
                        select {
                        case gn.Kick <- struct{}{}:
                        default:
                        }
                        if xkick != nil {
                                select {
                                case xkick <- struct{}{}:
                                default:
                                }
                        }
                }
        }()
        go gn.Run()

        // 5bis. Transport XMPP MUC (ADR-014, opt-in — ADR-009 reste le défaut) :
        // activé uniquement si config.json contient une section "xmpp" enabled.
        if cfg.XMPP.Enabled {
                port := cfg.XMPP.Port
                if port == 0 {
                        port = 5222
                }
                opt := transportxmpp.Options{
                        Host:       cfg.XMPP.Host,
                        Addr:       fmt.Sprintf("%s:%d", cfg.XMPP.Host, port),
                        JID:        cfg.XMPP.JID,
                        Password:   cfg.XMPP.Password,
                        MUC:        cfg.XMPP.MUC,
                        Nick:       cfg.XMPP.Nick,
                        TorSOCKS:   cfg.XMPP.TorSOCKS,
                        TLSEnforce: cfg.XMPP.TLSEnforced(),
                }
                xkick = make(chan struct{}, 1)
                pump := transportxmpp.NewPump(store, id.Onion, xkick, opt, 10*time.Second)
                pump.OnNew = func() {
                        select {
                        case kick <- struct{}{}:
                        default:
                        }
                }
                go pump.Run()
                defer pump.Stop()
                log.Printf("[xmpp] transport MUC activé : %s (tor_socks=%q, tls_enforce=%v)",
                        cfg.XMPP.MUC, cfg.XMPP.TorSOCKS, cfg.XMPP.TLSEnforced())
        }

        // 5ter. Relais TURN (ADR-014 phase 2, opt-in — ADR-009 reste le défaut) :
        // adresse relayée RFC 6062 obtenue outbound-only et maintenue ; publication
        // au rendezvous signé = spec §4 (branchement à venir). Voie opt-in : la
        // section "turn" est absente par défaut. SOCKS (Tor) = contrôle seul :
        // l'acceptation des connexions peer exige un chemin TCP direct (§4.4).
        if cfg.TURN.Enabled {
                turnCtx, cancelTurn := context.WithCancel(context.Background())
                defer cancelTurn()
                go turn.MaintainRelay(turnCtx, turn.RelayParams{
                        RelayURL: cfg.TURN.RelayURL,
                        SOCKS:    cfg.TURN.SOCKS,
                        KeyID:    cfg.TURN.KeyID,
                        APIToken: cfg.TURN.APIToken,
                        Lifetime: time.Duration(cfg.TURN.Lifetime) * time.Second,
                }, func(info turn.RelayInfo) {
                        // Publication rendezvous signé (spec §4) à brancher ici.
                        log.Printf("[turn] adresse relayée active : %s (TTL %s) — publication rendezvous à brancher (spec §4)",
                                info.RelayAddr, info.Lifetime)
                })
                log.Printf("[turn] relais activé : %s (socks=%q)", cfg.TURN.RelayURL, cfg.TURN.SOCKS)
        }

        addr := fmt.Sprintf("127.0.0.1:%d", cfg.HTTPPort)
        log.Printf("✔ API en écoute sur %s (onion:80 → ici)", addr)
        go func() {
                if err := http.ListenAndServe(addr, srv.Handler()); err != nil {
                        log.Fatalf("http: %v", err)
                }
        }()

        // 5bis. Publication de l'ONION CLIENT (data) : Port=80 → 127.0.0.1:httpPort.
        // Bug v0.2.0 n°3 : seul l'onion d'ADMIN était publié — l'API /client/* que
        // l'app mobile doit joindre (alias, inbox, events…) restait sans onion.
        // Même contrat éphémère que l'admin (bug n°2) : connexion contrôle tenue
        // ouverte via Watch() + re-publication automatique si tor redémarre.
        go func() {
                scalar, err := id.OnionScalarB64()
                if err != nil {
                        log.Printf("[tor] client: clé onion illisible: %v", err)
                        return
                }
                cookie := *cookieFile
                if cookie == "" && *password == "" {
                        cookie = tor.FindCookieFile(defaultCookiePaths)
                }
                for {
                        ctrl, err := tor.Dial(fmt.Sprintf("127.0.0.1:%d", cfg.ControlPort))
                        if err != nil {
                                log.Printf("[tor] client: control port injoignable (%v) — nouvel essai dans 5 s", err)
                                time.Sleep(5 * time.Second)
                                continue
                        }
                        if err := ctrl.Authenticate(cookie, *password); err != nil {
                                log.Printf("[tor] client: authentification: %v — nouvel essai dans 10 s", err)
                                ctrl.Close()
                                time.Sleep(10 * time.Second)
                                continue
                        }
                        sid, err := ctrl.PublishOnion(scalar, cfg.HTTPPort)
                        if err != nil {
                                log.Printf("[tor] client: publication onion: %v — nouvel essai dans 10 s", err)
                                ctrl.Close()
                                time.Sleep(10 * time.Second)
                                continue
                        }
                        log.Printf("✔ ONION CLIENT publié : %s (attendu %s) → 127.0.0.1:%d", sid, id.Onion, cfg.HTTPPort)
                        // Connexion contrôle MAINTENUE : tor détruirait le service sinon (bug n°2).
                        ctrl.Watch()
                        log.Printf("[tor] client: connexion contrôle fermée — re-publication dans 5 s")
                        time.Sleep(5 * time.Second)
                }
        }()

        // 6. API ADMIN — activée uniquement si une clé admin est configurée
        // (config.json → admin.authorized_keys). Sinon : aucun onion d'admin
        // publié, aucune surface privilégiée (défaut sûr).
        if cfg.AdminEnabled() {
                adminIdentity, err := identity.LoadOrCreateAdmin(*dataDir)
                if err != nil {
                        log.Fatalf("identité admin: %v", err)
                }
                adminSrv := &adminapi.Server{
                        Version:     version,
                        Onion:       adminIdentity.Onion,
                        GossipPub:   id.GossipPub,
                        Store:       store,
                        Cfg:         adminKeyAdapter{cfg},
                        StartedAt:   time.Now(),
                        Maintenance: maintenance,
                        StopRequest: stopRequest,
                        DataDir:     *dataDir,
                        // Rendezvous signé (ADR-016) : fichier servi + clé publique qui
                        // valide POST /admin/rendezvous (vide = lecture seule).
                        RendezvousFile:   cfg.Rendezvous.File,
                        RendezvousPubKey: cfg.Rendezvous.PubKey,
                        // v0.2.25 : réplicas temporaires auto-déclarés (champ « replica »
                        // des profils publics) → GET /admin/replicas, auto-ajout console.
                        Replicas: func(now int64, maxAgeSec int64) []adminapi.ReplicaView {
                                src := store.ReplicaAnnouncements(now, maxAgeSec)
                                out := make([]adminapi.ReplicaView, 0, len(src))
                                for _, r := range src {
                                        out = append(out, adminapi.ReplicaView{Author: r.Author, Addr: r.Addr, TS: r.TS})
                                }
                                return out
                        },
                        PeerStats: func() []adminapi.PeerStat {
                                src := gn.Stats()
                                out := make([]adminapi.PeerStat, 0, len(src))
                                for _, p := range src {
                                        out = append(out, adminapi.PeerStat{
                                                Onion:          p.Onion,
                                                LastContact:    p.LastContact,
                                                LatencyMs:      p.LatencyMs,
                                                BackoffSeconds: p.BackoffSeconds,
                                                PushCursor:     p.PushCursor,
                                                PullCursor:     p.PullCursor,
                                        })
                                }
                                return out
                        },
                        AddPeer: func(onion, gossipPub string) error {
                                return addPeerShared(config.Peer{Onion: onion, GossipPub: gossipPub})
                        },
                        RemovePeer: removePeerShared,
                        ResyncPeer: func(onion string) error {
                                if !gn.ResetPeerCursor(onion) {
                                        return fmt.Errorf("pair inconnu: %s", onion)
                                }
                                return nil
                        },
                        SignRoot: func(rootHex string) (string, error) {
                                // Signature de la racine de Merkle avec la clé onion du nœud
                                // (premier 32 octets de la clé privée ed25519 = seed).
                                sig := ed25519.Sign(onionPriv, []byte(rootHex))
                                return base64.StdEncoding.EncodeToString(sig), nil
                        },
                }
                rendezvousRead = adminSrv.RendezvousServe // câblage API client (ADR-016)
                adminAddr := fmt.Sprintf("127.0.0.1:%d", adminPort)
                go func() {
                        log.Printf("✔ API admin en écoute sur %s (onion admin:9091 → ici)", adminAddr)
                        if err := http.ListenAndServe(adminAddr, adminSrv.Handler()); err != nil {
                                log.Fatalf("http admin: %v", err)
                        }
                }()
                // Publication de l'ONION DÉDIÉ d'admin (distinct de l'onion client).
                // Bug v0.2.0 n°2 : le service ADD_ONION est ÉPHÉMÈRE — tor le détruit
                // dès la fermeture de la connexion contrôle qui l'a créé (comportement
                // observé : carte console rouge après quelques secondes). On ne ferme
                // PLUS la connexion après publication : Watch() la maintient ouverte,
                // et si elle casse (redémarrage tor, coupure), on re-publie en boucle.
                go func() {
                        cookie := *cookieFile
                        if cookie == "" && *password == "" {
                                cookie = tor.FindCookieFile(defaultCookiePaths)
                        }
                        for {
                                ctrl, err := tor.Dial(fmt.Sprintf("127.0.0.1:%d", cfg.ControlPort))
                                if err != nil {
                                        log.Printf("[tor] admin: control port injoignable (%v) — nouvel essai dans 5 s", err)
                                        time.Sleep(5 * time.Second)
                                        continue
                                }
                                if err := ctrl.Authenticate(cookie, *password); err != nil {
                                        log.Printf("[tor] admin: authentification: %v — nouvel essai dans 10 s", err)
                                        ctrl.Close()
                                        time.Sleep(10 * time.Second)
                                        continue
                                }
                                sid, err := ctrl.PublishOnionPorts(adminIdentity.AdminPrivKeyB64(), adminPort, adminPort)
                                if err != nil {
                                        log.Printf("[tor] admin: publication onion: %v — nouvel essai dans 10 s", err)
                                        ctrl.Close()
                                        time.Sleep(10 * time.Second)
                                        continue
                                }
                                log.Printf("✔ ONION ADMIN publié : %s.onion (→ 127.0.0.1:%d) — à déclarer dans la console nyx-admin", sid, adminPort)
                                // Connexion contrôle MAINTENUE (bug n°2) : Watch() bloque jusqu'à
                                // la coupure, puis la boucle re-publie après 5 s.
                                ctrl.Watch()
                                log.Printf("[tor] admin: connexion contrôle fermée — re-publication dans 5 s")
                                time.Sleep(5 * time.Second)
                        }
                }()
        } else {
                log.Printf("API admin désactivée (aucune clé dans admin.authorized_keys)")
        }

        fmt.Println("Nyx est prêt. Adresse onion du nœud : " + id.Onion)

        // 7. Arrêts : signal OS (SIGINT/SIGTERM) ou commande admin (shutdown/restart).
        sig := make(chan os.Signal, 1)
        signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
        select {
        case <-sig:
                log.Printf("arrêt (signal)…")
        case action := <-stopRequest:
                log.Printf("arrêt gracieux demandé par la console admin : %s", action)
        }
        gn.Stop()
        _ = cfg.Save()
        log.Printf("au revoir")
}
