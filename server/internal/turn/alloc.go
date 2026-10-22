package turn

// Client d'allocation relais TCP (RFC 6062) — PHASE 2 d'ADR-014
// (protocol/specs/transport-turn.md §6) : le réplica Nyx, privé de port
// entrant, obtient auprès d'un relais TURN une ADRESSE RELAYÉE qu'il publie
// (rendezvous signé, spec §4) ; les connexions peer entrantes lui sont
// retournées par le relais sur des TCP dédiées (source = adresse relayée,
// §4.4 RFC 6062) qu'il authentifie par CONNECTION-BIND.
//
// outbound-only strict : tout part du réplica (connexion de contrôle TLS,
// éventuellement via SOCKS5/Tor) — le relais réutilise le chemin NAT déjà
// traversé pour ramener la connexion peer. stdlib (+ x/net pour SOCKS5).
//
// Ordre d'ouverture (RFC 6062 §4.4) : le peer se connecte au relais, le
// relais ouvre une TCP ENTRANTE vers le client DEPUIS l'adresse relayée vers
// le port source de la connexion de contrôle — le client doit donc écouter
// ce port : le listener est créé AVANT le dial (SO_REUSEPORT, cf.
// reuseport_linux.go) et le dial part du même ip:port.

import (
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/proxy"
)

// ErrNoInbound — la plateforme ne permet pas le ré-écoute du port source
// (SO_REUSEPORT requis pour accepter la connexion peer du relais, RFC 6062
// §4.4) : Allocate reste possible, Connect échouera explicitement.
var ErrNoInbound = errors.New("turn: acceptation inbound non supportée sur cette plateforme (SO_REUSEPORT requis)")

// Control — connexion de contrôle vers le relais + écouteur d'acceptation
// couplé (même ip:port) ; listener == nil sur les plateformes sans SO_REUSEPORT.
type Control struct {
	conn     net.Conn
	listener net.Listener
	// Indications ConnectionAttempt intercalées entre les transactions :
	// transaction() les met en file ici, Connect les consomme (quel que soit
	// l'ordre relais : indication avant ou après la réponse CONNECT).
	attemptCh chan [12]byte
}

// DialControl — établit la connexion de contrôle vers le relais.
//
//	relayURL : "turns://turn.cloudflare.com:5349?transport=tcp" (TLS, voie
//	           principale — ressemble à HTTPS et passe le SOCKS5 de Tor) ou
//	           "turn://…:3478?transport=tcp" (repli clair, déconseillé).
//	socks5   : adresse d'un proxy SOCKS5 (Tor : 127.0.0.1:9050) ou "" pour un
//	           dial direct. UDP exclu : seule la variante TCP est acceptée.
//
// Le TLS vérifie le certificat du relais (ServerName = hôte de l'URL).
func DialControl(relayURL, socks5 string, timeout time.Duration) (*Control, error) {
	host, port, tlsOn, err := parseRelayURL(relayURL)
	if err != nil {
		return nil, err
	}
	addr := net.JoinHostPort(host, strconv.Itoa(port))

	// 1. Écouteur couplé (port source réservé, SO_REUSEPORT) — AVANT le dial.
	var listener net.Listener
	var dialTune func(*net.Dialer)
	listener, dialTune, err = newCoupledListener()
	if err != nil && !errors.Is(err, ErrNoInbound) {
		return nil, err
	}

	// 2. Dial (direct ou SOCKS5) — depuis le port réservé quand possible.
	deadline := time.Now().Add(timeout)
	var raw net.Conn
	if socks5 != "" {
		d, derr := proxy.SOCKS5("tcp", socks5, nil, proxy.Direct)
		if derr != nil {
			closeListener(listener)
			return nil, fmt.Errorf("turn: SOCKS5 %s : %w", socks5, derr)
		}
		// NB (documenté) : le dial SOCKS5 ne peut pas partir du port réservé —
		// la source vue du relais ne sera pas l'ip:port du listener couplé, et
		// Tor n'achemine de toute façon pas de TCP entrant : l'acceptation
		// RFC 6062 §4.4 (Connect) exige un dial DIRECT. SOCKS5 = contrôle seul.
		raw, derr = d.Dial("tcp", addr)
		if derr != nil {
			closeListener(listener)
			return nil, fmt.Errorf("turn: dial via SOCKS5 %s vers %s : %w", socks5, addr, derr)
		}
	} else {
		d := net.Dialer{Deadline: deadline}
		if dialTune != nil {
			dialTune(&d)
		}
		var derr error
		raw, derr = d.Dial("tcp", addr)
		if derr != nil {
			closeListener(listener)
			return nil, fmt.Errorf("turn: dial %s : %w", addr, derr)
		}
	}
	raw.SetDeadline(deadline)

	c := &Control{listener: listener, attemptCh: make(chan [12]byte, 8)}
	if tlsOn {
		tlsConn := tls.Client(raw, &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12})
		if derr := tlsConn.Handshake(); derr != nil {
			raw.Close()
			closeListener(listener)
			return nil, fmt.Errorf("turn: handshake TLS %s : %w", host, derr)
		}
		c.conn = tlsConn
	} else {
		c.conn = raw
	}
	// Après ouverture : plus de deadline globale — chaque transaction impose
	// la sienne (deadlines par appel).
	c.conn.SetDeadline(time.Time{})
	return c, nil
}

// closeListener — fermeture sûre (listener nil sur plateformes sans REUSEPORT).
func closeListener(l net.Listener) {
	if l != nil {
		l.Close()
	}
}

// parseRelayURL — turns[://]host:port?transport=tcp | turn[://]… (les URLs
// iceServers sont de la forme « turns:host:port?transport=tcp », sans //).
// UDP (absence de ?transport=tcp ou transport=udp) : refusé (Tor = TCP only).
func parseRelayURL(u string) (host string, port int, tlsOn bool, err error) {
	var s string
	switch {
	case strings.HasPrefix(u, "turns://"):
		tlsOn, s = true, u[8:]
	case strings.HasPrefix(u, "turns:"):
		tlsOn, s = true, u[6:]
	case strings.HasPrefix(u, "turn://"):
		tlsOn, s = false, u[7:]
	case strings.HasPrefix(u, "turn:"):
		tlsOn, s = false, u[5:]
	default:
		return "", 0, false, fmt.Errorf("turn: URL %q : scheme turns|turn requis", u)
	}
	// ?transport=tcp obligatoire.
	qi := strings.IndexByte(s, '?')
	q := ""
	if qi >= 0 {
		q, s = s[qi+1:], s[:qi]
	}
	if q == "" || !strings.Contains(q, "transport=tcp") {
		return "", 0, false, fmt.Errorf("turn: URL %q : ?transport=tcp requis (UDP exclu, Tor TCP only)", u)
	}
	// hôte[:port] — gérer les littéraux IPv6 [h]:p.
	if strings.HasPrefix(s, "[") {
		end := strings.IndexByte(s, ']')
		if end < 0 {
			return "", 0, false, fmt.Errorf("turn: URL %q : IPv6 mal formée", u)
		}
		host = s[1:end]
		rest := s[end+1:]
		if rest == "" {
			port = 5349
			if !tlsOn {
				port = 3478
			}
			return host, port, tlsOn, nil
		}
		p, aerr := strconv.Atoi(strings.TrimPrefix(rest, ":"))
		if aerr != nil || p <= 0 || p > 65535 {
			return "", 0, false, fmt.Errorf("turn: URL %q : port invalide", u)
		}
		return host, p, tlsOn, nil
	}
	if !strings.Contains(s, ":") {
		port = 5349
		if !tlsOn {
			port = 3478
		}
		return s, port, tlsOn, nil
	}
	h, p, perr := net.SplitHostPort(s)
	if perr != nil {
		return "", 0, false, fmt.Errorf("turn: URL %q : hôte:port attendu", u)
	}
	port, aerr := strconv.Atoi(p)
	if aerr != nil || port <= 0 || port > 65535 {
		return "", 0, false, fmt.Errorf("turn: URL %q : port %q invalide", u, p)
	}
	return h, port, tlsOn, nil
}

// Allocation — une allocation TCP active chez le relais (RFC 6062 §4.2).
type Allocation struct {
	ctrl        *Control
	key         string
	relayedIP   net.IP
	relayedPort int
	Lifetime    time.Duration
}

// defaultLifetime — si le relais ne répond pas de LIFETIME (improbable : la
// RFC 5766 impose le champ), 10 min prudentes avec refresh aux 2/3.
const defaultLifetime = 10 * time.Minute

// RelayAddr — « ip:port » relayée à publier dans le rendezvous signé
// (spec transport-turn.md §4, champ transports[].addr).
func (a *Allocation) RelayAddr() string {
	return net.JoinHostPort(a.relayedIP.String(), strconv.Itoa(a.relayedPort))
}

// Allocate — ALLOCATE REQUESTED-TRANSPORT=TCP avec credentials short-term
// (RFC 5389 §10.1 : clé = credential ; RFC 6062 §4.2).
func (c *Control) Allocate(username, credential string, lifetime time.Duration) (*Allocation, error) {
	if username == "" || credential == "" {
		return nil, errors.New("turn: username/credential éphémères requis")
	}
	req := &Message{Method: MethodAllocate, Class: ClassRequest}
	if err := freshTxID(&req.TxID); err != nil {
		return nil, err
	}
	req.Attrs = append(req.Attrs,
		Attr{Type: AttrRequestedTransport, Value: []byte{ProtoTCP, 0, 0, 0}},
		Attr{Type: AttrUsername, Value: []byte(username)},
	)
	if lifetime > 0 {
		v := make([]byte, 4)
		binary.BigEndian.PutUint32(v, uint32(lifetime/time.Second))
		req.Attrs = append(req.Attrs, Attr{Type: AttrLifetime, Value: v})
	}
	resp, err := c.transaction(req, credential, 15*time.Second)
	if err != nil {
		return nil, err
	}
	if resp.Class == ClassErrorResp {
		return nil, errorResp("ALLOCATE", resp)
	}
	a := &Allocation{ctrl: c, key: credential}
	ra := resp.Get(AttrXorRelayedAddress)
	if ra == nil {
		return nil, errors.New("turn: ALLOCATE sans XOR-RELAYED-ADDRESS")
	}
	ip, port, err := DecodeXorAddr(ra, resp.TxID)
	if err != nil {
		return nil, err
	}
	a.relayedIP, a.relayedPort = ip, port
	a.Lifetime = defaultLifetime
	if lv := resp.Get(AttrLifetime); len(lv) == 4 {
		a.Lifetime = time.Duration(binary.BigEndian.Uint32(lv)) * time.Second
	}
	return a, nil
}

// freshTxID — identifiant de transaction 96 bits (crypto/rand).
func freshTxID(tx *[12]byte) error {
	if _, err := rand.Read(tx[:]); err != nil {
		return fmt.Errorf("turn: entropie transaction : %w", err)
	}
	return nil
}

// Refresh — renouvelle l'allocation (RFC 5766 §7) ; lifetime 0 = suppression.
func (a *Allocation) Refresh(lifetime time.Duration) error {
	req := &Message{Method: MethodRefresh, Class: ClassRequest}
	if err := freshTxID(&req.TxID); err != nil {
		return err
	}
	v := make([]byte, 4)
	binary.BigEndian.PutUint32(v, uint32(lifetime/time.Second))
	req.Attrs = append(req.Attrs, Attr{Type: AttrLifetime, Value: v})
	resp, err := a.ctrl.transaction(req, a.key, 15*time.Second)
	if err != nil {
		return err
	}
	if resp.Class == ClassErrorResp {
		return errorResp("REFRESH", resp)
	}
	if lifetime > 0 {
		a.Lifetime = lifetime
		if lv := resp.Get(AttrLifetime); len(lv) == 4 {
			a.Lifetime = time.Duration(binary.BigEndian.Uint32(lv)) * time.Second
		}
	}
	return nil
}

// Connect — demande un tunnel vers peer ("ip:port") (RFC 6062 §4.3) puis
// accepte la connexion peer retournée par le relais (§4.4) : TCP entrante
// dont la source = l'adresse RELAYÉE (vérifiée), authentifiée par
// CONNECTION-BIND (CONNECTION-ID porté par l'indication ConnectionAttempt).
// Le net.Conn retourné est le canal applicatif peer↔réplica.
func (a *Allocation) Connect(peer string, timeout time.Duration) (net.Conn, error) {
	ln, ok := a.ctrl.listener.(*net.TCPListener)
	if !ok || a.ctrl.listener == nil {
		return nil, ErrNoInbound
	}
	pip, pport, err := net.SplitHostPort(peer)
	if err != nil {
		return nil, fmt.Errorf("turn: peer %q : %w", peer, err)
	}
	pIP := net.ParseIP(pip)
	pN, aErr := strconv.Atoi(pport)
	if pIP == nil || aErr != nil || pN <= 0 {
		return nil, fmt.Errorf("turn: peer %q invalide", peer)
	}
	req := &Message{Method: MethodConnect, Class: ClassRequest}
	if err := freshTxID(&req.TxID); err != nil {
		return nil, err
	}
	xa, err := EncodeXorAddr(pIP, pN, req.TxID)
	if err != nil {
		return nil, err
	}
	req.Attrs = append(req.Attrs, Attr{Type: AttrXorPeerAddress, Value: xa})

	resp, err := a.ctrl.transaction(req, a.key, 15*time.Second)
	if err != nil {
		return nil, err
	}
	if resp.Class == ClassErrorResp {
		return nil, errorResp("CONNECT", resp)
	}

	// La connexion peer arrive sur le port source de la connexion de contrôle.
	// Vérification stricte de la source (RFC 6062 §4.4 : « the client MUST
	// verify that the source address is the relayed address »).
	ln.SetDeadline(time.Now().Add(timeout))
	in, err := ln.Accept()
	if err != nil {
		return nil, fmt.Errorf("turn: connexion peer entrante : %w", err)
	}
	ra, ok := in.RemoteAddr().(*net.TCPAddr)
	if !ok || !ra.IP.Equal(a.relayedIP) || ra.Port != a.relayedPort {
		in.Close()
		return nil, fmt.Errorf("turn: source entrante %v != relais %s (rejetée)", in.RemoteAddr(), a.RelayAddr())
	}

	// CONNECTION-BIND sur la nouvelle connexion — TRANSACTION-ID = celui de
	// l'indication ConnectionAttempt reçue sur la connexion de contrôle.
	txid, err := a.readConnectionAttempt(30 * time.Second)
	if err != nil {
		in.Close()
		return nil, err
	}
	bind := &Message{Method: MethodConnectionBind, Class: ClassRequest, TxID: txid}
	if err := freshTxIDPlaceholder(&bind.TxID, txid); err != nil {
		in.Close()
		return nil, err
	}
	bind.Attrs = append(bind.Attrs, Attr{Type: AttrUsername, Value: []byte("nyx")})
	in.SetDeadline(time.Now().Add(15 * time.Second))
	raw, _ := bind.Encode(a.key, true)
	if _, err = in.Write(raw); err != nil {
		in.Close()
		return nil, fmt.Errorf("turn: CONNECTION-BIND : %w", err)
	}
	bresp, err := readFrame(in)
	if err != nil {
		in.Close()
		return nil, fmt.Errorf("turn: réponse CONNECTION-BIND : %w", err)
	}
	bm, err := Decode(bresp)
	if err != nil {
		in.Close()
		return nil, err
	}
	if bm.Class == ClassErrorResp {
		in.Close()
		return nil, errorResp("CONNECTION-BIND", bm)
	}
	if !VerifyIntegrity(bm, bresp, a.key) {
		in.Close()
		return nil, errors.New("turn: CONNECTION-BIND sans intégrité valide")
	}
	in.SetDeadline(time.Time{})
	return in, nil
}

// freshTxIDPlaceholder — CONNECTION-BIND réutilise le TRANSACTION-ID de
// l'indication ConnectionAttempt (RFC 6062 §4.4) : pas d'entropie ici,
// helper présent pour l'explicitation du flux.
func freshTxIDPlaceholder(dst *[12]byte, src [12]byte) error {
	*dst = src
	return nil
}

// readConnectionAttempt — lit sur la connexion de contrôle l'indication
// ConnectionAttempt (RFC 6062 §4.4) et retourne son TRANSACTION-ID.
// Les autres messages sont ignorés : en usage Nyx, aucune transaction ne
// chevauche un Connect (le CONNECT a déjà obtenu sa réponse).
func (a *Allocation) readConnectionAttempt(timeout time.Duration) ([12]byte, error) {
	var zero [12]byte
	// 1. Indication déjà reçue et mise en file par une transaction antérieure.
	select {
	case tx := <-a.ctrl.attemptCh:
		return tx, nil
	default:
	}
	// 2. Sinon, lecture bloquante de la connexion de contrôle.
	a.ctrl.conn.SetDeadline(time.Now().Add(timeout))
	defer a.ctrl.conn.SetDeadline(time.Time{})
	for {
		raw, err := readFrame(a.ctrl.conn)
		if err != nil {
			return zero, fmt.Errorf("turn: ConnectionAttempt : %w", err)
		}
		m, err := Decode(raw)
		if err != nil {
			return zero, err
		}
		if m.Method == MethodConnectionAttempt && m.Class == ClassIndication {
			return m.TxID, nil
		}
		// Tout autre message sur la connexion de contrôle à ce stade est
		// inattendu mais non fatal : on continue d'attendre l'indication.
	}
}

// transaction — écrit la requête, lit la réponse correspondante (même TxID).
// Réponses de succès SANS MESSAGE-INTEGRITY (short-term) : rejetées ; erreurs
// sans intégrité : tolérées (le code d'erreur est affiché tel quel).
func (c *Control) transaction(req *Message, key string, timeout time.Duration) (*Message, error) {
	raw, err := req.Encode(key, true)
	if err != nil {
		return nil, err
	}
	c.conn.SetDeadline(time.Now().Add(timeout))
	defer c.conn.SetDeadline(time.Time{})
	if _, err := c.conn.Write(raw); err != nil {
		return nil, fmt.Errorf("turn: écriture %v : %w", req.Method, err)
	}
	for {
		b, err := readFrame(c.conn)
		if err != nil {
			return nil, fmt.Errorf("turn: lecture %v : %w", req.Method, err)
		}
		m, err := Decode(b)
		if err != nil {
			return nil, err
		}
		if m.Class == ClassRequest || m.Class == ClassIndication {
			if m.Method == MethodConnectionAttempt && m.Class == ClassIndication {
				select {
				case c.attemptCh <- m.TxID:
				default: // file pleine : indication perdue (usage Nyx : jamais)
				}
			}
			continue // indication intercalée : ignorée
		}
		if m.TxID != req.TxID {
			continue // réponse d'une transaction antérieure : ignorée
		}
		if m.Get(AttrMessageIntegrity) != nil {
			if key == "" || !VerifyIntegrity(m, b, key) {
				return nil, fmt.Errorf("turn: %v — intégrité invalide (MITM ou credential éphémère expiré)", req.Method)
			}
		} else if m.Class == ClassSuccessResp {
			return nil, fmt.Errorf("turn: %v succès sans MESSAGE-INTEGRITY (rejeté)", req.Method)
		}
		return m, nil
	}
}

// errorResp — erreur STUN explicite (401 creds éphémères expirées, 429 rate
// limit…). Le message n'inclut JAMAIS de secret.
func errorResp(op string, m *Message) error {
	num, reason, err := ParseErrorCode(m.Get(AttrErrorCode))
	if err != nil {
		return fmt.Errorf("turn: %v rejeté (réponse d'erreur illisible)", op)
	}
	return fmt.Errorf("turn: %v rejeté — %d %s", op, num, reason)
}

// readFrame — lit une trame STUN complète sur un flux TCP (en-tête 20 octets,
// corps msgLen). io.EOF propre sur fermeture en tête de trame.
func readFrame(r io.Reader) ([]byte, error) {
	hdr := make([]byte, headerSize)
	if _, err := io.ReadFull(r, hdr); err != nil {
		if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
			return nil, io.EOF
		}
		return nil, err
	}
	n := int(binary.BigEndian.Uint16(hdr[2:4]))
	buf := make([]byte, headerSize+n)
	copy(buf, hdr)
	if _, err := io.ReadFull(r, buf[headerSize:]); err != nil {
		return nil, fmt.Errorf("stun: corps tronqué : %w", err)
	}
	return buf, nil
}

// Close — ferme connexion de contrôle et écouteur ; l'allocation expire
// naturellement (TTL ≤ 86400 s) — un REFRESH 0 explicite est tenté d'abord.
func (a *Allocation) Close() error {
	_ = a.Refresh(0) // best effort ; l'expiration TTL reste le filet
	closeListener(a.ctrl.listener)
	return a.ctrl.conn.Close()
}
