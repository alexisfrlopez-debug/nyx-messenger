// Package tor — client minimal du control port Tor pour publier un onion service v3
// à partir d'une clé ed25519 donnée (identité du nœud).
//
// Implémentation volontairement minimale (~200 lignes) plutôt qu'une dépendance :
// seules les commandes AUTHENTICATE, ADD_ONION et DEL_ONION sont nécessaires.
package tor

import (
        "bufio"
        "encoding/hex"
        "errors"
        "fmt"
        "net"
        "os"
        "strconv"
        "strings"
)

// Control — connexion au control port Tor.
type Control struct {
        conn net.Conn
        rw   *bufio.ReadWriter
}

// Dial — ouvre la connexion TCP vers le control port.
func Dial(addr string) (*Control, error) {
        conn, err := net.Dial("tcp", addr)
        if err != nil {
                return nil, err
        }
        return &Control{
                conn: conn,
                rw:   bufio.NewReadWriter(bufio.NewReader(conn), bufio.NewWriter(conn)),
        }, nil
}

// Close — ferme la connexion.
func (c *Control) Close() error { return c.conn.Close() }

// Response — réponse multi-lignes du control port.
type Response struct {
        Code   int
        Lines  []string
        Values map[string]string // pour ADD_ONION : ServiceID=…, etc.
}

// cmd — envoie une commande et lit la réponse multi-lignes.
func (c *Control) cmd(line string) (*Response, error) {
        if _, err := c.rw.WriteString(line + "\r\n"); err != nil {
                return nil, err
        }
        if err := c.rw.Flush(); err != nil {
                return nil, err
        }
        resp := &Response{Values: map[string]string{}}
        for {
                l, err := c.rw.ReadString('\n')
                if err != nil {
                        return nil, err
                }
                l = strings.TrimRight(l, "\r\n")
                if len(l) < 4 {
                        return nil, fmt.Errorf("ligne de réponse invalide: %q", l)
                }
                code, err := strconv.Atoi(l[:3])
                if err != nil {
                        return nil, fmt.Errorf("code invalide: %q", l)
                }
                resp.Code = code
                rest := l[4:]
                resp.Lines = append(resp.Lines, rest)
                if strings.Contains(rest, "=") {
                        kv := strings.SplitN(rest, "=", 2)
                        resp.Values[kv[0]] = kv[1]
                }
                // ' ' = dernière ligne du bloc ; '-' = continuation
                if l[3] == ' ' {
                        break
                }
        }
        if resp.Code >= 400 {
                return resp, fmt.Errorf("erreur Tor %d: %s", resp.Code, strings.Join(resp.Lines, " / "))
        }
        return resp, nil
}

// findCookieFile — localise le fichier de cookie d'authentification.
func FindCookieFile(candidates []string) string {
        for _, p := range candidates {
                if _, err := os.Stat(p); err == nil {
                        return p
                }
        }
        return ""
}

// Authenticate — authentification par cookie (hex) ou par mot de passe.
func (c *Control) Authenticate(cookieFile, password string) error {
        if password != "" {
                _, err := c.cmd(fmt.Sprintf("AUTHENTICATE %q", password))
                return err
        }
        cookie, err := os.ReadFile(cookieFile)
        if err != nil {
                return fmt.Errorf("cookie introuvable (%s): %w — activer CookieAuthentication 1 dans torrc",
                        cookieFile, err)
        }
        _, err = c.cmd("AUTHENTICATE " + hex.EncodeToString(cookie))
        return err
}

// PublishOnion — publie un onion service v3 avec la clé privée ed25519 fournie
// (64 octets, encodage base64 standard). Port=80 (onion) → 127.0.0.1:targetPort.
// Retourne le ServiceID (adresse sans « .onion »).
func (c *Control) PublishOnion(ed25519PrivB64 string, targetPort int) (string, error) {
        return c.PublishOnionPorts(ed25519PrivB64, 80, targetPort)
}

// ErrOnionCollision — v0.2.37 : ADD_ONION a reçu « 550 Onion address
// collision », c'est-à-dire que le service porté par CETTE clé est DÉJÀ
// vivant dans ce démon tor (il a survécu à l'arrêt du process précédent
// grâce à Flags=Detach). L'appelant doit l'ADOPTER : ses points
// d'introduction et son descripteur HSDir sont TOUJOURS vivants — le
// re-créer (DEL+ADD) rouvrirait une fenêtre de descripteur orphelin.
var ErrOnionCollision = errors.New("collision onion 550 : service déjà vivant (Detach) — à adopter")

// PublishOnionPorts — variante à ports explicites : Port=<virtPort> (côté
// client Tor) → 127.0.0.1:<targetPort>. Utilisée pour l'onion d'ADMIN
// (9091 → 9091, distinct de l'onion client 80 → httpPort — APPLI_ADMIN.md §2).
//
// v0.2.37 — Flags=Detach + ADOPTION DE COLLISION. AVANT : ADD_ONION sans
// Detach → le service mourait INSTANTANÉMENT à la fermeture de la connexion
// contrôle, donc à CHAQUE « systemctl stop nyx-server » du déploiement 1-clic
// console (SshDeployer.updateNode). Orphelin pendant 1-3 min : l'ANCIEN
// descripteur restait servi par les HSDirs (points d'intro morts) → les
// clients Tor qui le fetchaient dans cette fenêtre le CACHAIENT jusqu'à
// ~24 h (hs_cache.c — cause racine terrain 23/10 19:0x : Blackview v0.2.44
// incapable de joindre le réplica après un déploiement 19:03:17 alors que
// le Xiaomi, cache vierge, le joignait en 227 ms). AVEC Detach : le service
// survit au stop/start du process, le nouveau ADD_ONION entre en collision
// 550 → [ErrOnionCollision] → l'appelant ADOPTE le service vivant (même
// clé, mêmes ports — la config n'a pas changé) : déploiement SANS interruption.
func (c *Control) PublishOnionPorts(ed25519PrivB64 string, virtPort, targetPort int) (string, error) {
        if strings.TrimSpace(ed25519PrivB64) == "" {
                return "", errors.New("clé privée onion vide")
        }
        cmd := fmt.Sprintf("ADD_ONION ED25519-V3:%s Flags=Detach,DiscardPK Port=%d,127.0.0.1:%d",
                ed25519PrivB64, virtPort, targetPort)
        resp, err := c.cmd(cmd)
        if err != nil {
                if resp != nil && resp.Code == 550 &&
                        strings.Contains(strings.ToLower(strings.Join(resp.Lines, " ")), "collision") {
                        return "", ErrOnionCollision
                }
                return "", err
        }
        sid := resp.Values["ServiceID"]
        if sid == "" {
                return "", fmt.Errorf("ServiceID absent de la réponse: %v", resp.Lines)
        }
        return sid, nil
}

// UnpublishOnion — retire le service onion (arrêt propre).
func (c *Control) UnpublishOnion(serviceID string) error {
        _, err := c.cmd("DEL_ONION " + serviceID)
        return err
}

// Watch — MAINTIENT la connexion de contrôle ouverte jusqu'à ce que tor la
// referme. Bug v0.2.0 n°2 : un service publié via ADD_ONION est ÉPHÉMÈRE —
// si la connexion contrôle qui l'a créé est fermée, tor détruit immédiatement
// le service (l'onion disparaissait quelques secondes après le démarrage).
// L'appelant publie puis reste dans Watch() ; au retour (coupure, tor
// redémarré), il re-publie en boucle. Les événements asynchrones éventuels
// (HS_DESC, NOTICE…) sont lus et ignorés.
func (c *Control) Watch() error {
        for {
                if _, err := c.rw.ReadString('\n'); err != nil {
                        return err
                }
        }
}
