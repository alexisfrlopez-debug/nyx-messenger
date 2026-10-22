// Package journal — événements signés ed25519 et représentation canonique.
//
// L'identité d'un auteur est son adresse onion v3 : elle encode sa clé publique
// ed25519. Un événement signé par « abcd….onion » est vérifiable sans annuaire.
package journal

import (
        "crypto/ed25519"
        "crypto/sha256"
        "encoding/base32"
        "encoding/base64"
        "encoding/json"
        "fmt"
        "regexp"
        "strings"

        "golang.org/x/crypto/sha3"
)

// Event — unité atomique du journal (voir protocol/specs/wire-format.md).
type Event struct {
        Type   string `json:"type"`
        Author string `json:"author"` // "xxxx…onion" (56 chars base32 + ".onion")
        Seq    uint64 `json:"seq"`
        TS     int64  `json:"ts"`
        TTL    uint32 `json:"ttl"`          // 0 = permanent
        To     string `json:"to,omitempty"` // destinataire ("" = public)
        Body   string `json:"body"`         // base64, opaque au serveur
        Sig    string `json:"sig"`          // base64 ed25519 sur canonique
}

// Types d'événements connus (v0.1 + alias v1.1 + profile v1.2).
var KnownTypes = map[string]bool{
        "msg":     true,
        "contact": true,
        "prekey":  true,
        "meta":    true,
        "alias":   true,
        "profile": true,
}

// Limites v0.1.
const (
        MaxBodySize   = 256 * 1024 // 256 Ko
        MaxTSDriftSec = 24 * 3600  // tolérance horloge (latence Tor)
)

// AliasRE — format d'un pseudonyme unique (wire-format.md §6.2) : minuscules,
// chiffres, underscore, 3 à 24 caractères.
var AliasRE = regexp.MustCompile(`^[a-z0-9_]{3,24}$`)

// aliasBody — corps d'un événement type "alias" (JSON, non chiffré).
type aliasBody struct {
        Alias  string `json:"alias"`
        Action string `json:"action"`
}

// ---- profil (wire-format.md §7, v1.2) ----

// profileField — un champ de carte de profil.
type profileField struct {
        Value  string `json:"value"`
        Public bool   `json:"public"`
}

// profileBody — corps d'un événement type "profile" en diffusion.
type profileBody struct {
        Fields map[string]profileField `json:"fields"`
}

// ProfileKeys — clés de champs fermées + limites de valeur (wire-format.md §7.2).
var ProfileKeys = map[string]int{
        "display_name": 64,
        "bio":          280,
        "website":      128,
        "location":     64,
        "avatar":       65536, // base64 image ≈ 48 Ko binaires
        // v0.2.25 : auto-déclaration du réplica temporaire embarqué — valeur
        // « <author.onion>:<port> » (garde-fou ci-dessous), agrégée par
        // GET /admin/replicas pour l'auto-ajout à la console nyx-admin.
        "replica":      128,
}

// ProfileFromEvent — extrait et valide la carte de profil d'un événement
// type "profile" EN DIFFUSION (to == ""). Invariant serveur (§7.2) : tout champ
// diffusé est public — un champ "public":false dans le journal public est un
// rejet immédiat (un champ privé ne doit jamais quitter l'appareil).
// Un événement adressé (to != "") n'est PAS validé ici (corps opaque, chiffré).
func ProfileFromEvent(ev *Event) (map[string]profileField, error) {
        if ev.Type != "profile" {
                return nil, fmt.Errorf("type %q ≠ profile", ev.Type)
        }
        if ev.To != "" {
                return nil, fmt.Errorf("profil adressé : corps opaque, pas de validation serveur")
        }
        raw, err := b64.DecodeString(ev.Body)
        if err != nil {
                return nil, fmt.Errorf("body non base64: %w", err)
        }
        var pb profileBody
        if err := json.Unmarshal(raw, &pb); err != nil {
                return nil, fmt.Errorf("body profile non JSON: %w", err)
        }
        if pb.Fields == nil {
                return nil, fmt.Errorf("clé \"fields\" requise (wire-format.md §7.2)")
        }
        if len(pb.Fields) > len(ProfileKeys) {
                return nil, fmt.Errorf("trop de champs (%d)", len(pb.Fields))
        }
        for k, f := range pb.Fields {
                max, ok := ProfileKeys[k]
                if !ok {
                        return nil, fmt.Errorf("champ de profil inconnu: %q", k)
                }
                if len(f.Value) > max {
                        return nil, fmt.Errorf("champ %q: %d caractères > limite %d", k, len(f.Value), max)
                }
                if !f.Public {
                        return nil, fmt.Errorf("champ %q privé dans un profil DIFFUSÉ — interdit (§7.2)", k)
                }
        }
        // v0.2.25 — GARDE-FOU du champ « replica » : la valeur DOIT être l'adresse
        // onion DE L'AUTEUR (optionnellement portée « :port », 1-65535). Un pair
        // ne peut annoncer que SON PROPRE réplica temporaire — aucune diffusion
        // d'adresses tierces (anti-relais d'adresses arbitraires).
        if rf, ok := pb.Fields["replica"]; ok {
                addr := rf.Value
                host := addr
                portPart := ""
                if i := strings.LastIndex(addr, ":"); i >= 0 {
                        host, portPart = addr[:i], addr[i+1:]
                }
                if host != ev.Author {
                        return nil, fmt.Errorf("champ replica: adresse %q ≠ auteur %q — interdit", host, ev.Author)
                }
                if portPart != "" {
                        p := 0
                        for _, c := range portPart {
                                if c < '0' || c > '9' {
                                        return nil, fmt.Errorf("champ replica: port invalide %q", portPart)
                                }
                                p = p*10 + int(c-'0')
                                if p > 65535 {
                                        return nil, fmt.Errorf("champ replica: port invalide %q", portPart)
                                }
                        }
                        if p < 1 {
                                return nil, fmt.Errorf("champ replica: port invalide %q", portPart)
                        }
                }
        }
        return pb.Fields, nil
}

// AliasFromEvent — extrait et valide l'alias d'un événement type "alias".
// Retourne une erreur si le body n'est pas un claim valide.
func AliasFromEvent(ev *Event) (string, error) {
        if ev.Type != "alias" {
                return "", fmt.Errorf("type %q ≠ alias", ev.Type)
        }
        raw, err := b64.DecodeString(ev.Body)
        if err != nil {
                return "", fmt.Errorf("body non base64: %w", err)
        }
        var ab aliasBody
        if err := json.Unmarshal(raw, &ab); err != nil {
                return "", fmt.Errorf("body alias non JSON: %w", err)
        }
        if ab.Action != "claim" {
                return "", fmt.Errorf("action alias %q inconnue (attendu claim)", ab.Action)
        }
        if !AliasRE.MatchString(ab.Alias) {
                return "", fmt.Errorf("format d'alias invalide: %q (attendu ^[a-z0-9_]{3,24}$)", ab.Alias)
        }
        return ab.Alias, nil
}

var b64 = base64.StdEncoding

// Canonical — représentation canonique signée (déterministe).
func (e *Event) Canonical() string {
        bodyHash := sha256.Sum256([]byte(e.Body))
        return fmt.Sprintf("NYX1\n%s\n%s\n%d\n%d\n%d\n%s\n%s",
                e.Type, e.Author, e.Seq, e.TS, e.TTL, e.To, fmt.Sprintf("%x", bodyHash))
}

// ID — adresse du contenu : sha256 de la représentation canonique (hex).
func (e *Event) ID() string {
        h := sha256.Sum256([]byte(e.Canonical()))
        return fmt.Sprintf("%x", h)
}

// Sign — calcule la signature avec la clé privée ed25519 de l'auteur.
func (e *Event) Sign(priv ed25519.PrivateKey) {
        e.Sig = b64.EncodeToString(ed25519.Sign(priv, []byte(e.Canonical())))
}

// OnionToPubKey — décode une adresse onion v3 en clé publique ed25519
// (base32(pubkey || checksum || 0x03), checksum = sha3-256(".onion checksum" || pubkey || 0x03)[:2]).
func OnionToPubKey(onion string) (ed25519.PublicKey, error) {
        // Normalisation
        onion = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(onion), ".onion"))
        if len(onion) != 56 {
                return nil, fmt.Errorf("adresse onion invalide (longueur %d ≠ 56)", len(onion))
        }
        raw, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(strings.ToUpper(onion))
        if err != nil {
                return nil, fmt.Errorf("base32 invalide: %w", err)
        }
        if len(raw) != 35 {
                return nil, fmt.Errorf("décodage onion: longueur %d ≠ 35", len(raw))
        }
        pub := raw[:32]
        version := raw[34]
        if version != 0x03 {
                return nil, fmt.Errorf("version onion %d ≠ 3", version)
        }
        checksum := onionChecksum(pub)
        if checksum[0] != raw[32] || checksum[1] != raw[33] {
                return nil, fmt.Errorf("checksum onion invalide")
        }
        return ed25519.PublicKey(pub), nil
}

func onionChecksum(pub []byte) []byte {
        // sha3-256(".onion checksum" || pubkey || 0x03) — voir spéc onion v3
        h := sha3.New256()
        h.Write([]byte(".onion checksum"))
        h.Write(pub)
        h.Write([]byte{0x03})
        return h.Sum(nil)[:2]
}

// PubKeyToOnion — encode une clé publique ed25519 en adresse onion v3
// (opération inverse de OnionToPubKey ; utilisée à la création d'identité).
func PubKeyToOnion(pub ed25519.PublicKey) (string, error) {
        if len(pub) != 32 {
                return "", fmt.Errorf("clé publique: %d octets ≠ 32", len(pub))
        }
        cs := onionChecksum(pub)
        raw := make([]byte, 0, 35)
        raw = append(raw, pub...)
        raw = append(raw, cs...)
        raw = append(raw, 0x03)
        addr := base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(raw)
        return strings.ToLower(addr) + ".onion", nil
}

// Validate — vérifie structure, signature, séquence applicative hors store.
func (e *Event) Validate(now int64) error {
        if !KnownTypes[e.Type] {
                return fmt.Errorf("type inconnu %q", e.Type)
        }
        if len(e.Body) == 0 || len(e.Body) > MaxBodySize {
                return fmt.Errorf("taille de body invalide (%d)", len(e.Body))
        }
        if _, err := b64.DecodeString(e.Body); err != nil {
                return fmt.Errorf("body non base64: %w", err)
        }
        pub, err := OnionToPubKey(e.Author)
        if err != nil {
                return err
        }
        sig, err := b64.DecodeString(e.Sig)
        if err != nil {
                return fmt.Errorf("sig non base64: %w", err)
        }
        if !ed25519.Verify(pub, []byte(e.Canonical()), sig) {
                return fmt.Errorf("signature invalide pour %s", e.Author)
        }
        drift := now - e.TS
        if drift < 0 {
                drift = -drift
        }
        if drift > MaxTSDriftSec {
                return fmt.Errorf("horodatage hors tolérance (%ds)", drift)
        }
        // Validation sémantique du corps alias (wire-format.md §6)
        if e.Type == "alias" {
                if _, err := AliasFromEvent(e); err != nil {
                        return err
                }
        }
        // Validation sémantique du profil EN DIFFUSION (wire-format.md §7) —
        // les profils adressés (partage E2E futur) restent opaques.
        if e.Type == "profile" && e.To == "" {
                if _, err := ProfileFromEvent(e); err != nil {
                        return err
                }
        }
        return nil
}

// Encode/Decode — JSON filaire.
func (e *Event) Encode() ([]byte, error) { return json.Marshal(e) }

func Decode(b []byte) (*Event, error) {
        var e Event
        if err := json.Unmarshal(b, &e); err != nil {
                return nil, err
        }
        return &e, nil
}
