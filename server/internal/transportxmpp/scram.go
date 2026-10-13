// scram.go — client SASL SCRAM-SHA-1 (RFC 5802), mécanisme prioritaire du
// transport XMPP (le mot de passe ne circule jamais en clair sur le fil).
// x/crypto/pbkdf2 uniquement — aucune dépendance externe (ADR-005).
package transportxmpp

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"

	"golang.org/x/crypto/pbkdf2"
)

const scramNonceLen = 18 // → 24 caractères base64

// scramClient — état d'une négociation SCRAM-SHA-1.
type scramClient struct {
	username, password string

	clientNonce     string
	clientFirstBare string

	saltedPassword []byte // Hi(password, salt, i)
	authMessage    string
}

// newScram — prépare la négociation (nonce frais à chaque tentative).
func newScram(username, password string) (*scramClient, error) {
	raw := make([]byte, scramNonceLen)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	nonce := base64.StdEncoding.EncodeToString(raw)
	c := &scramClient{username: saslPrep(username), password: password}
	c.clientFirstBare = "n=" + escapeName(c.username) + ",r=" + nonce
	return c, nil
}

// clientFirst — « gs2-header,bare » : ici canal TLS → canal standard "n,,".
func (c *scramClient) clientFirst() string { return "n,," + c.clientFirstBare }

// handleServerFirst — traite server-first-message, produit
// client-final-message-without-proof + preuve (renvoyés concaténés).
func (c *scramClient) handleServerFirst(serverFirst string) (string, error) {
	var serverNonce, saltB64 string
	var iters int
	for _, part := range strings.Split(serverFirst, ",") {
		switch {
		case strings.HasPrefix(part, "r="):
			serverNonce = part[2:]
		case strings.HasPrefix(part, "s="):
			saltB64 = part[2:]
		case strings.HasPrefix(part, "i="):
			n, err := strconv.Atoi(part[2:])
			if err != nil || n < 1 || n > 1<<20 {
				return "", fmt.Errorf("scram: itérations invalides")
			}
			iters = n
		}
	}
	if serverNonce == "" || saltB64 == "" || iters == 0 {
		return "", fmt.Errorf("scram: server-first incomplet")
	}
	if !strings.HasPrefix(serverNonce, c.clientFirstBare[strings.LastIndex(c.clientFirstBare, ",r=")+3:]) {
		return "", fmt.Errorf("scram: nonce serveur sans notre nonce (possible MITM)")
	}
	salt, err := base64.StdEncoding.DecodeString(saltB64)
	if err != nil {
		return "", fmt.Errorf("scram: sel invalide")
	}
	c.saltedPassword = pbkdf2.Key([]byte(c.password), salt, iters, sha1.Size, sha1.New)
	clientKey := hmacSHA1(c.saltedPassword, []byte("Client Key"))
	storedKey := sha1.Sum(clientKey)
	clientFinalNoProof := "c=biws,r=" + serverNonce
	c.authMessage = c.clientFirstBare + "," + serverFirst + "," + clientFinalNoProof
	clientSig := hmacSHA1(storedKey[:], []byte(c.authMessage))
	proof := make([]byte, len(clientKey))
	for i := range clientKey {
		proof[i] = clientKey[i] ^ clientSig[i]
	}
	return clientFinalNoProof + ",p=" + base64.StdEncoding.EncodeToString(proof), nil
}

// verifyServerFinal — vérifie la signature serveur (v=…) : preuve que le
// serveur connaît le dérivé du mot de passe (anti-phishing gratuit).
func (c *scramClient) verifyServerFinal(serverFinal string) error {
	var vB64 string
	for _, part := range strings.Split(serverFinal, ",") {
		if strings.HasPrefix(part, "v=") {
			vB64 = part[2:]
		}
	}
	if vB64 == "" {
		return fmt.Errorf("scram: server-final sans v=")
	}
	got, err := base64.StdEncoding.DecodeString(vB64)
	if err != nil {
		return fmt.Errorf("scram: v= invalide")
	}
	serverKey := hmacSHA1(c.saltedPassword, []byte("Server Key"))
	serverSig := hmacSHA1(serverKey, []byte(c.authMessage))
	if !hmac.Equal(serverSig, got) {
		return fmt.Errorf("scram: signature serveur invalide")
	}
	return nil
}

func hmacSHA1(key, msg []byte) []byte {
	m := hmac.New(sha1.New, key)
	m.Write(msg)
	return m.Sum(nil)
}

// escapeName — protège « = » et « , » dans le nom d'utilisateur (RFC 5802 §5.1).
func escapeName(s string) string {
	s = strings.ReplaceAll(s, "=", "=3D")
	return strings.ReplaceAll(s, ",", "=2C")
}

// saslPrep — préparation minimale (pas de caractères spéciaux attendus dans
// les JIDs Nyx :Trim espaces suffit pour notre usage pseudonyme).
func saslPrep(s string) string { return strings.TrimSpace(s) }
