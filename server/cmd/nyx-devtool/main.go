// nyx-devtool — outil de développement Nyx : client minimal en ligne de commande.
//
// Il matérialise le principe « l'identité est une adresse onion » : une identité
// cliente est une paire ed25519 dont la clé publique encode l'adresse onion v3.
// Les événements qu'elle signe sont vérifiables par n'importe quel nœud sans annuaire.
//
// Sous-commandes :
//
//	keygen <fichier.json>            crée une identité cliente et la sauvegarde (0600)
//	show   --keys <fichier.json>     affiche l'adresse onion et la prochaine séquence
//	event  --keys <f.json> [opts]    émet un événement signé (JSON) sur stdout
//	verify <fichier.json|->          vérifie un événement (structure + signature + horloge)
//	turn-creds --key-id <id> [--ttl s]  credentials ICE éphémères Cloudflare (ADR-014 :
//	                                    token lu depuis NYX_TURN_API_TOKEN — jamais en argv)
//	turn-creds --key-id <id> [--ttl s]  credentials ICE éphémères Cloudflare (ADR-014 :
//	                                    token lu depuis NYX_TURN_API_TOKEN — jamais en argv)
//	turn-creds --key-id <id> [--ttl s]  credentials ICE éphémères Cloudflare (ADR-014 :
//	                                    token lu depuis NYX_TURN_API_TOKEN — jamais en argv)
//
// Exemples (voir GUIDE_DEMARRAGE.md) :
//
//	nyx-devtool keygen alice.json
//	nyx-devtool event --keys alice.json --body "salut" > ev.json
//	curl -X POST http://127.0.0.1:8081/client/event -d @ev.json
//	nyx-devtool verify ev.json
package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
	"github.com/alexisfrlopez-debug/nyx-messenger/server/internal/turn"
)

// keysFile — contenu du fichier d'identité cliente (même forme que identity.json
// du serveur, plus le compteur de séquence applicative).
type keysFile struct {
	Onion     string `json:"onion"`      // adresse onion v3 dérivée de la clé publique
	OnionPriv string `json:"onion_priv"` // base64, 64 octets ed25519
	NextSeq   uint64 `json:"next_seq"`   // prochaine séquence à utiliser (auto-incrémentée)
}

func die(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "nyx-devtool: "+format+"\n", args...)
	os.Exit(1)
}

func usage() {
	fmt.Fprint(os.Stderr, `nyx-devtool — client minimal de test pour Nyx

Usage :
  nyx-devtool keygen <fichier.json>
  nyx-devtool show   --keys <fichier.json>
  nyx-devtool event  --keys <fichier.json> [--type msg] [--body "texte"]
                     [--to <onion|public>] [--ttl 0] [--seq N]
  nyx-devtool verify <fichier.json|->
  nyx-devtool turn-creds --key-id <TOKEN_ID> [--ttl 86400]
  nyx-devtool turn-alloc --relay <url> --key-id <TOKEN_ID> [--ttl s]
                         [--socks host:port] [--peer ip:port]
                         allocation relais RFC 6062 (ADR-014 phase 2) :
                         affiche l'adresse relayée ; avec --peer, établit le
                         tunnel et relaie stdin/stdout (diagnostic)

Voir GUIDE_DEMARRAGE.md à la racine du projet pour le scénario complet.
`)
	os.Exit(2)
}

func loadKeys(path string) *keysFile {
	b, err := os.ReadFile(path)
	if err != nil {
		die("lecture %s: %v", path, err)
	}
	var k keysFile
	if err := json.Unmarshal(b, &k); err != nil {
		die("%s: JSON invalide: %v", path, err)
	}
	if k.Onion == "" || k.OnionPriv == "" {
		die("%s: fichier d'identité incomplet (champs onion/onion_priv requis)", path)
	}
	return &k
}

func saveKeys(path string, k *keysFile) {
	b, err := json.MarshalIndent(k, "", "  ")
	if err != nil {
		die("encodage identité: %v", err)
	}
	if dir := filepath.Dir(path); dir != "." {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			die("répertoire: %v", err)
		}
	}
	if err := os.WriteFile(path, append(b, '\n'), 0o600); err != nil {
		die("écriture %s: %v", path, err)
	}
}

func privKeyFrom(k *keysFile) (ed25519.PrivateKey, error) {
	b, err := base64.StdEncoding.DecodeString(k.OnionPriv)
	if err != nil {
		return nil, err
	}
	if len(b) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("%d octets ≠ 64", len(b))
	}
	return ed25519.PrivateKey(b), nil
}

// cmdKeygen — génère une identité cliente : ed25519 → adresse onion v3.
func cmdKeygen(args []string) {
	fs := flag.NewFlagSet("keygen", flag.ExitOnError)
	if err := fs.Parse(args); err != nil || fs.NArg() != 1 {
		usage()
	}
	path := fs.Arg(0)
	if _, err := os.Stat(path); err == nil {
		die("%s existe déjà — supprime-le pour régénérer (l'ancienne identité serait perdue)", path)
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		die("génération de clé: %v", err)
	}
	onion, err := journal.PubKeyToOnion(pub)
	if err != nil {
		die("encodage onion: %v", err)
	}
	k := &keysFile{
		Onion:     onion,
		OnionPriv: base64.StdEncoding.EncodeToString(priv),
		NextSeq:   1,
	}
	saveKeys(path, k)
	fmt.Printf("identité créée : %s\nfichier        : %s\n", onion, path)
}

// cmdShow — affiche l'identité.
func cmdShow(args []string) {
	fs := flag.NewFlagSet("show", flag.ExitOnError)
	keys := fs.String("keys", "", "fichier d'identité (requis)")
	_ = fs.Parse(args)
	if *keys == "" {
		usage()
	}
	k := loadKeys(*keys)
	fmt.Printf("onion    : %s\nnext_seq : %d\n", k.Onion, k.NextSeq)
}

// cmdEvent — construit, signe et imprime un événement ; incrémente next_seq.
func cmdEvent(args []string) {
	fs := flag.NewFlagSet("event", flag.ExitOnError)
	keys := fs.String("keys", "", "fichier d'identité (requis)")
	typ := fs.String("type", "msg", "type d'événement (msg|contact|prekey|meta)")
	body := fs.String("body", "", "corps en clair (encodé base64 automatiquement)")
	to := fs.String("to", "public", "destinataire : adresse onion ou « public »")
	ttl := fs.Uint64("ttl", 0, "TTL en secondes (0 = permanent)")
	seq := fs.Uint64("seq", 0, "séquence (0 = auto : next_seq du fichier)")
	_ = fs.Parse(args)
	if *keys == "" || *body == "" {
		usage()
	}

	k := loadKeys(*keys)
	priv, err := privKeyFrom(k)
	if err != nil {
		die("%s: clé privée invalide: %v", *keys, err)
	}
	// Vérifie que la clé du fichier correspond bien à l'adresse onion déclarée.
	pub, err := journal.OnionToPubKey(k.Onion)
	if err != nil {
		die("adresse onion invalide: %v", err)
	}
	if !pub.Equal(ed25519.PrivateKey(priv).Public().(ed25519.PublicKey)) {
		die("incohérence : la clé privée ne correspond pas à %s", k.Onion)
	}

	ev := &journal.Event{
		Type:   *typ,
		Author: k.Onion,
		Seq:    k.NextSeq,
		TS:     time.Now().Unix(),
		TTL:    uint32(*ttl),
		Body:   base64.StdEncoding.EncodeToString([]byte(*body)),
	}
	if *to != "" && *to != "public" {
		if _, err := journal.OnionToPubKey(*to); err != nil {
			die("destinataire --to invalide: %v", err)
		}
		ev.To = *to
	}
	if *seq != 0 {
		ev.Seq = *seq
	}
	ev.Sign(priv)

	out, err := ev.Encode()
	if err != nil {
		die("encodage événement: %v", err)
	}
	if *seq == 0 {
		k.NextSeq++
		saveKeys(*keys, k)
	}
	os.Stdout.Write(out)
	os.Stdout.Write([]byte("\n"))
}

// cmdVerify — vérifie un événement depuis un fichier ou stdin (« - »).
func cmdVerify(args []string) {
	fs := flag.NewFlagSet("verify", flag.ExitOnError)
	_ = fs.Parse(args)
	if fs.NArg() != 1 {
		usage()
	}
	var b []byte
	var err error
	if fs.Arg(0) == "-" {
		b, err = io.ReadAll(os.Stdin)
	} else {
		b, err = os.ReadFile(fs.Arg(0))
	}
	if err != nil {
		die("lecture: %v", err)
	}
	ev, err := journal.Decode(b)
	if err != nil {
		die("JSON invalide: %v", err)
	}
	if err := ev.Validate(time.Now().Unix()); err != nil {
		die("ÉVÉNEMENT INVALIDE : %v", err)
	}
	fmt.Printf("OK  id=%s  auteur=%s  seq=%d  to=%s\n", ev.ID(), ev.Author, ev.Seq, ev.To)
}

// cmdTurnCreds — credentials ICE éphémères (ADR-014, phase 1) : appel API
// Cloudflare avec le token lu depuis l'environnement NYX_TURN_API_TOKEN
// (jamais d'argument de ligne de commande — visible dans `ps` ; jamais de
// fichier dans le dépôt). Résultat : iceServers filtrés TCP-only sur stdout.
func cmdTurnCreds(args []string) {
	fs := flag.NewFlagSet("turn-creds", flag.ExitOnError)
	keyID := fs.String("key-id", "", "Token ID de la clé TURN Cloudflare (obligatoire)")
	ttl := fs.Int("ttl", 86400, "durée de vie des credentials en secondes (≤ 86400)")
	_ = fs.Parse(args)
	if *keyID == "" {
		die("turn-creds: --key-id requis")
	}
	token := os.Getenv("NYX_TURN_API_TOKEN")
	if token == "" {
		die("turn-creds: variable d'environnement NYX_TURN_API_TOKEN absente (jamais en argv)")
	}
	servers, err := turn.GenerateICEServers(*keyID, token, *ttl)
	if err != nil {
		die("turn-creds: %v", err)
	}
	out, err := json.MarshalIndent(turn.TCPOnly(servers), "", "  ")
	if err != nil {
		die("turn-creds: encodage: %v", err)
	}
	fmt.Println(string(out))
}

// cmdTurnAlloc — diagnostic du relais TURN (ADR-014 phase 2) : génère des
// credentials éphémères (token via NYX_TURN_API_TOKEN, jamais argv), alloue
// (RFC 6062 §4.2) et affiche l'adresse relayée. Avec --peer : CONNECTION-BIND
// complet puis relais stdin/stdout sur le tunnel (test manuel de bout en bout).
func cmdTurnAlloc(args []string) {
	fs := flag.NewFlagSet("turn-alloc", flag.ExitOnError)
	relay := fs.String("relay", "", "URL du relais (défaut : première URL TCP des iceServers)")
	keyID := fs.String("key-id", "", "Token ID de la clé TURN Cloudflare (obligatoire)")
	ttl := fs.Int("ttl", 3600, "durée de vie demandée en secondes (≤ 86400)")
	socks := fs.String("socks", "", "SOCKS5 pour la connexion de contrôle (ex. 127.0.0.1:9050)")
	peer := fs.String("peer", "", "ip:port du pair cible (CONNECT RFC 6062 §4.3)")
	_ = fs.Parse(args)
	if *keyID == "" {
		die("turn-alloc: --key-id requis")
	}
	token := os.Getenv("NYX_TURN_API_TOKEN")
	if token == "" {
		die("turn-alloc: variable d'environnement NYX_TURN_API_TOKEN absente (jamais en argv)")
	}
	ice, err := turn.GenerateICEServers(*keyID, token, *ttl)
	if err != nil {
		die("turn-alloc: %v", err)
	}
	if len(ice) == 0 {
		die("turn-alloc: aucune entrée iceServers")
	}
	url, user, cred := turn.PickTCPURL(ice)
	if url == "" {
		die("turn-alloc: aucune URL TCP dans iceServers (UDP exclu — Tor TCP only)")
	}
	if *relay != "" {
		url = *relay
	}
	fmt.Fprintf(os.Stderr, "[turn-alloc] contrôle : %s (socks=%q)\n", url, *socks)
	ctrl, err := turn.DialControl(url, *socks, 20*time.Second)
	if err != nil {
		die("turn-alloc: %v", err)
	}
	alloc, err := ctrl.Allocate(user, cred, time.Duration(*ttl)*time.Second)
	if err != nil {
		die("turn-alloc: %v", err)
	}
	fmt.Printf("adresse relayée : %s (TTL %s)\n", alloc.RelayAddr(), alloc.Lifetime)
	if *peer == "" {
		return
	}
	fmt.Fprintf(os.Stderr, "[turn-alloc] CONNECT %s — tunnel stdin/stdout (Ctrl-D pour finir)\n", *peer)
	conn, err := alloc.Connect(*peer, 30*time.Second)
	if err != nil {
		die("turn-alloc: connect: %v", err)
	}
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(conn, os.Stdin); done <- struct{}{} }()
	go func() { _, _ = io.Copy(os.Stdout, conn); done <- struct{}{} }()
	<-done
}

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	switch os.Args[1] {
	case "keygen":
		cmdKeygen(os.Args[2:])
	case "show":
		cmdShow(os.Args[2:])
	case "event":
		cmdEvent(os.Args[2:])
	case "verify":
		cmdVerify(os.Args[2:])
	case "turn-creds":
		cmdTurnCreds(os.Args[2:])
	case "turn-alloc":
		cmdTurnAlloc(os.Args[2:])
	case "-h", "--help", "help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "nyx-devtool: sous-commande inconnue %q\n\n", os.Args[1])
		usage()
	}
}
