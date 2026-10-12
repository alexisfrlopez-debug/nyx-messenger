// Package identity — identité du nœud : clé ed25519 (onion) + clé X25519 (canal gossip).
package identity

import (
	"crypto/ed25519"
	"crypto/rand"
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
func LoadOrCreate(dataDir string) (*Identity, error) {
	p := filepath.Join(dataDir, "identity.json")
	if b, err := os.ReadFile(p); err == nil {
		var id Identity
		if err := json.Unmarshal(b, &id); err != nil {
			return nil, err
		}
		return &id, nil
	}
	id, err := generate()
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	b, err := json.MarshalIndent(id, "", "  ")
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

// AdminPrivKeyB64 — clé privée ed25519 base64 prête pour ADD_ONION.
func (a *AdminIdentity) AdminPrivKeyB64() string { return a.OnionPriv }
