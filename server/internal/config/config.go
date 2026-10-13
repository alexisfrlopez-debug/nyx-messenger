// Package config — configuration JSON du nœud Nyx.
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// Peer — un réplica distant connu.
type Peer struct {
	Onion     string `json:"onion"`      // adresse onion v3, ex. "abcd….onion"
	GossipPub string `json:"gossip_pub"` // clé publique X25519 du canal gossip (base64)
}

// AdminKey — clé publique ed25519 d'un appareil d'administration autorisé
// (console nyx-admin — dépôt privé APPLI_ADMIN.md §3).
type AdminKey struct {
	PublicKey string `json:"public_key"` // base64 (32 octets)
	Label     string `json:"label"`
	AddedAt   string `json:"added_at"` // RFC3339
}

// Admin — section administration de la config. Clés vides = API admin désactivée
// (aucun onion d'admin publié).
type Admin struct {
	AuthorizedKeys []AdminKey `json:"authorized_keys"`
}

// XMPP — transport alternatif opt-in (ADR-014, protocol/specs/transport-xmpp.md) :
// un salon MUC d'un serveur XMPP tiers sert de bus aux enveloppes Nyx signées.
// Vide (défaut) = transport DÉSACTIVÉ — l'onion v3 reste le transport premier
// (ADR-009). Le mot de passe est un SECRET : data_dir/config.json uniquement,
// jamais dans config.example.json (valeur factice) ni dans le dépôt.
type XMPP struct {
	Enabled    bool   `json:"enabled"`
	Host       string `json:"host"`        // domaine XMPP, ex. "hookipa.net"
	Port       int    `json:"port"`        // 5222 client / 5223 TLS direct ; 0 = 5222
	JID        string `json:"jid"`         // compte pseudonyme dédié au réseau
	Password   string `json:"password"`    // SECRET (jamais dans le dépôt)
	MUC        string `json:"muc"`         // salon bus, ex. "nyx-bus@conference.hookipa.net"
	Nick       string `json:"nick"`        // occupant ; vide = partie locale du JID
	TorSOCKS   string `json:"tor_socks"`   // ex. "127.0.0.1:9050" ; vide = direct
	TLSEnforce *bool  `json:"tls_enforce"` // défaut true (STARTTLS/TLS obligatoire)
}

// TLSEnforced — valeur effective de tls_enforce (défaut true).
func (x XMPP) TLSEnforced() bool { return x.TLSEnforce == nil || *x.TLSEnforce }

// TURN — relais anti-blocage opt-in (ADR-014 phase 2, protocol/specs/
// transport-turn.md) : le réplica obtient une adresse relayée (RFC 6062,
// outbound-only) qu'il publie au rendezvous signé ; les métadonnées sont
// visibles du relais (Cloudflare) → voie OPT-IN, jamais par défaut (ADR-009).
// api_token = SECRET : data_dir/config.json uniquement, jamais dans
// config.example.json (valeur factice) ni dans le dépôt ni dans les logs.
// NB : l'acceptation des connexions peer (RFC 6062 §4.4) exige un chemin TCP
// direct — socks ne sert qu'à la connexion de contrôle (génération/refresh).
type TURN struct {
	Enabled  bool   `json:"enabled"`
	RelayURL string `json:"relay_url"` // ex. "turns://turn.cloudflare.com:5349?transport=tcp"
	KeyID    string `json:"key_id"`    // Token ID Cloudflare (non secret)
	APIToken string `json:"api_token"` // SECRET (jamais dans le dépôt)
	SOCKS    string `json:"socks"`     // ex. "127.0.0.1:9050" ; vide = direct
	Lifetime int    `json:"lifetime"`  // secondes (≤ 86400) ; 0 = 3600
}

// Config — configuration du nœud.
type Config struct {
	DataDir     string `json:"data_dir"`
	HTTPPort    int    `json:"http_port"`    // port local servi derrière Tor (Port=80,127.0.0.1:<http>)
	ControlPort int    `json:"control_port"` // control port Tor
	SOCKSPort   int    `json:"socks_port"`   // SOCKS5 Tor (sortie gossip)
	Peers       []Peer `json:"peers"`
	Admin       Admin  `json:"admin"` // clés admin autorisées (console nyx-admin)
	XMPP        XMPP   `json:"xmpp"`  // transport opt-in (ADR-014) ; vide = désactivé
	TURN        TURN   `json:"turn"`  // relais opt-in (ADR-014 phase 2) ; vide = désactivé
}

// AdminEnabled — vrai si au moins une clé admin est configurée.
func (c *Config) AdminEnabled() bool { return len(c.Admin.AuthorizedKeys) > 0 }

// AdminKeys — liste des clés autorisées (copie défensive).
func (c *Config) AdminKeys() []AdminKey {
	out := make([]AdminKey, len(c.Admin.AuthorizedKeys))
	copy(out, c.Admin.AuthorizedKeys)
	return out
}

// HasAdminKey — clé publique (base64) déjà autorisée ?
func (c *Config) HasAdminKey(pubB64 string) bool {
	for _, k := range c.Admin.AuthorizedKeys {
		if k.PublicKey == pubB64 {
			return true
		}
	}
	return false
}

// AddAdminKey — ajoute une clé admin (doublon → no-op).
func (c *Config) AddAdminKey(k AdminKey) bool {
	if c.HasAdminKey(k.PublicKey) {
		return false
	}
	c.Admin.AuthorizedKeys = append(c.Admin.AuthorizedKeys, k)
	return true
}

// RemoveAdminKey — retire une clé admin. Refuse de retirer la DERNIÈRE clé
// (anti-verrouillage : il doit toujours rester un moyen d'entrer).
func (c *Config) RemoveAdminKey(pubB64 string) (bool, error) {
	if len(c.Admin.AuthorizedKeys) <= 1 {
		return false, fmt.Errorf("impossible de retirer la dernière clé admin (retrait physique de la config requis)")
	}
	out := c.Admin.AuthorizedKeys[:0]
	removed := false
	for _, k := range c.Admin.AuthorizedKeys {
		if k.PublicKey == pubB64 {
			removed = true
			continue
		}
		out = append(out, k)
	}
	c.Admin.AuthorizedKeys = out
	return removed, nil
}

// Default — valeurs par défaut.
func Default(dataDir string) *Config {
	return &Config{
		DataDir:     dataDir,
		HTTPPort:    8080,
		ControlPort: 9051,
		SOCKSPort:   9050,
		Peers:       []Peer{},
	}
}

// Path — chemin du fichier de configuration dans le répertoire de données.
func (c *Config) Path() string { return filepath.Join(c.DataDir, "config.json") }

// Load — charge la configuration depuis un fichier, avec défauts.
func Load(path, dataDir string) (*Config, error) {
	c := Default(dataDir)
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return c, nil // pas de fichier : défauts, pas d'erreur
		}
		return nil, err
	}
	if err := json.Unmarshal(b, c); err != nil {
		return nil, err
	}
	if c.DataDir == "" {
		c.DataDir = dataDir
	}
	return c, nil
}

// Save — persiste la configuration (notamment les pairs découverts).
func (c *Config) Save() error {
	if err := os.MkdirAll(c.DataDir, 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(c.Path(), append(b, '\n'), 0o600)
}
