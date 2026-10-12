// Package config — configuration JSON du nœud Nyx.
package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// Peer — un réplica distant connu.
type Peer struct {
	Onion     string `json:"onion"`      // adresse onion v3, ex. "abcd….onion"
	GossipPub string `json:"gossip_pub"` // clé publique X25519 du canal gossip (base64)
}

// Config — configuration du nœud.
type Config struct {
	DataDir     string `json:"data_dir"`
	HTTPPort    int    `json:"http_port"`     // port local servi derrière Tor (Port=80,127.0.0.1:<http>)
	ControlPort int    `json:"control_port"`  // control port Tor
	SOCKSPort   int    `json:"socks_port"`    // SOCKS5 Tor (sortie gossip)
	Peers       []Peer `json:"peers"`
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
