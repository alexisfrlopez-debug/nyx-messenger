// Package identity — identité du nœud : clé ed25519 (onion) + clé X25519 (canal gossip).
package identity

import (
        "crypto/ed25519"
        "crypto/rand"
        "crypto/sha512"
        "encoding/base64"
        "encoding/json"
        "fmt"
        "os"
        "path/filepath"

        "golang.org/x/crypto/curve25519"

        "github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

// Identity — persistée dans <data-dir>/identity.json (0600).
type Identity struct {
        OnionPriv  string `json:"onion_priv"`  // base64 (64 octets ed25519)
        GossipPriv string `json:"gossip_priv"` // base64 (32 octets X25519)
        Onion      string `json:"onion"`       // adresse "xxxx.onion" (calculée à la création)
        GossipPub  string `json:"gossip_pub"`  // base64 clé publique X25519
}

// LoadOrCreate — charge l'identité ou en génère une nouvelle.
// v0.2.42 : ne régénère QUE si le fichier n'existe pas. Toute autre erreur
// de lecture (EIO, permissions, montage en cours) est FATALE : régénérer
// écraserait l'identité — adresse onion du nœud, journal, pairs — de manière
// IRRÉVERSIBLE (retour d'audit session 63 : « clé perdue = identité perdue »).
func LoadOrCreate(dataDir string) (*Identity, error) {
        p := filepath.Join(dataDir, "identity.json")
        b, err := os.ReadFile(p)
        if err == nil {
                var id Identity
                if err := json.Unmarshal(b, &id); err != nil {
                        return nil, err
                }
                return &id, nil
        }
        if !os.IsNotExist(err) {
                return nil, fmt.Errorf("identity.json illisible — refus de régénérer (l'identité serait perdue): %w", err)
        }
        id, err := generate()
        if err != nil {
                return nil, err
        }
        if err := os.MkdirAll(dataDir, 0o700); err != nil {
                return nil, err
        }
        // v0.2.44 — FIX COMPILATION (bug du changeset v0.2.42 jamais détecté :
        // la flotte n'a pas été redéployée, le binaire v0.2.42 n'a jamais été
        // construit) : « b, err := » ici rédéclarait les variables de la portée
        // fonction (b ligne du ReadFile, err du generate) — « no new variables
        // on left side of := ». Réaffectation simple.
        b, err = json.MarshalIndent(id, "", "  ")
        if err != nil {
                return nil, err
        }
        if err := os.WriteFile(p, append(b, '\n'), 0o600); err != nil {
                return nil, err
        }
        return id, nil
}

func generate() (*Identity, error) {
        // Clé onion : ed25519 (Tor l'utilise telle quelle via ADD_ONION ED25519-V3)
        edPub, edPriv, err := ed25519.GenerateKey(rand.Reader)
        if err != nil {
                return nil, err
        }
        // Clé gossip : X25519 (échelle Diffie-Hellman du canal serveur↔serveur)
        gossipPriv := make([]byte, 32)
        if _, err := rand.Read(gossipPriv); err != nil {
                return nil, err
        }
        gossipPub, err := curve25519.X25519(gossipPriv, curve25519.Basepoint)
        if err != nil {
                return nil, err
        }
        onion, err := journal.PubKeyToOnion(edPub)
        if err != nil {
                return nil, err
        }
        b64 := base64.StdEncoding
        return &Identity{
                OnionPriv:  b64.EncodeToString(edPriv),
                GossipPriv: b64.EncodeToString(gossipPriv),
                Onion:      onion,
                GossipPub:  b64.EncodeToString(gossipPub),
        }, nil
}

// OnionPrivKey — décode la clé privée ed25519.
func (i *Identity) OnionPrivKey() (ed25519.PrivateKey, error) {
        b, err := base64.StdEncoding.DecodeString(i.OnionPriv)
        if err != nil {
                return nil, err
        }
        if len(b) != ed25519.PrivateKeySize {
                return nil, fmt.Errorf("clé onion: %d octets ≠ %d", len(b), ed25519.PrivateKeySize)
        }
        return ed25519.PrivateKey(b), nil
}

// GossipPrivKey — décode la clé privée X25519.
func (i *Identity) GossipPrivKey() ([]byte, error) {
        b, err := base64.StdEncoding.DecodeString(i.GossipPriv)
        if err != nil {
                return nil, err
        }
        if len(b) != 32 {
                return nil, fmt.Errorf("clé gossip: %d octets ≠ 32", len(b))
        }
        return b, nil
}

// ---- identité onion d'ADMIN (séparée de l'identité client — APPLI_ADMIN.md §2) ----

// AdminIdentity — paire ed25519 de l'onion d'administration, persistée dans
// <data-dir>/admin_identity.json (0600). Charger l'identité ADMIN avec la même
// clé après redémarrage est essentiel : la console configure son client-auth
// pour une adresse onion précise.
type AdminIdentity struct {
        OnionPriv string `json:"onion_priv"` // base64 (64 octets ed25519)
        Onion     string `json:"onion"`      // "xxxx.onion"
}

// LoadOrCreateAdmin — charge ou génère l'identité onion d'admin.
func LoadOrCreateAdmin(dataDir string) (*AdminIdentity, error) {
        p := filepath.Join(dataDir, "admin_identity.json")
        if b, err := os.ReadFile(p); err == nil {
                var a AdminIdentity
                if err := json.Unmarshal(b, &a); err != nil {
                        return nil, err
                }
                return &a, nil
        }
        pub, priv, err := ed25519.GenerateKey(rand.Reader)
        if err != nil {
                return nil, err
        }
        onion, err := journal.PubKeyToOnion(pub)
        if err != nil {
                return nil, err
        }
        a := &AdminIdentity{
                OnionPriv: base64.StdEncoding.EncodeToString(priv),
                Onion:     onion,
        }
        b, err := json.MarshalIndent(a, "", "  ")
        if err != nil {
                return nil, err
        }
        if err := os.WriteFile(p, append(b, '\n'), 0o600); err != nil {
                return nil, err
        }
        return a, nil
}

// AdminPrivKeyB64 — clé privée ed25519 base64 PRÊTE POUR ADD_ONION.
//
// Bug v0.2.0 n°1 (adresse admin fantôme hmt3k5t…) : tor interprète les 32
// premiers octets du blob ED25519-V3 comme un SCALAIRE BRUT — il ne fait AUCUNE
// expansion sha512(graine) lui-même. Le format Go (graine||pub, 64 octets)
// alimenté tel quel publie donc une adresse ≠ adresse déclarée (dérive).
// Contract tor (prouvé sur VM, blocs A/P) : envoyer SHA512(graine)[0..32]
// CLAMPÉ (RFC 8032 : h[0]&=248 ; h[31]&=127 ; h[31]|=64) sur les 64 octets du
// digeste → tor dérive exactement la clé publique déclarée.
func (a *AdminIdentity) AdminPrivKeyB64() string {
        b, err := base64.StdEncoding.DecodeString(a.OnionPriv)
        if err != nil || len(b) < 32 {
                // Blob illisible : on le rend tel quel (l'ADD_ONION échouera bruyamment
                // plutôt que de publier une adresse fantôme silencieusement).
                return a.OnionPriv
        }
        h := sha512.Sum512(b[:32])
        h[0] &= 248
        h[31] &= 127
        h[31] |= 64
        return base64.StdEncoding.EncodeToString(h[:])
}

// OnionScalarB64 — scalaire ed25519 « expandé + clampé » (RFC 8032) de la clé
// onion du NŒUD (identity.json), prêt pour ADD_ONION ED25519-V3.
// Même contrat tor qu'AdminPrivKeyB64 (bug n°1) — utilisé par la publication de
// l'ONION CLIENT (bug n°3) : l'app mobile joint /client/* via cette adresse.
func (i *Identity) OnionScalarB64() (string, error) {
        b, err := base64.StdEncoding.DecodeString(i.OnionPriv)
        if err != nil {
                return "", err
        }
        if len(b) < 32 {
                return "", fmt.Errorf("clé onion: %d octets < 32", len(b))
        }
        h := sha512.Sum512(b[:32])
        h[0] &= 248
        h[31] &= 127
        h[31] |= 64
        return base64.StdEncoding.EncodeToString(h[:]), nil
}
