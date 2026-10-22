// client.go — client XMPP minimal (RFC 6120/6121 + XEP-0045 + XEP-0313) :
// XML streams, STARTTLS/TLS direct, SASL SCRAM-SHA-1 (repli PLAIN sous TLS),
// bind, join MUC, messages groupchat, requête MAM, dial via SOCKS5 (Tor).
// Écrit dans le dépôt — amendement ADR-005 (stdlib + x/crypto uniquement).
package transportxmpp

import (
	"crypto/tls"
	"encoding/base64"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/proxy"
)

// Options — paramètres de connexion (transport-xmpp.md §3/§4).
type Options struct {
	Host       string        // domaine XMPP, ex. « hookipa.net »
	Addr       string        // host:port TCP ; vide = host:5222
	JID        string        // compte pseudonyme dédié au réseau
	Password   string        // secret — data_dir/config.json uniquement
	Resource   string        // bind ; vide = « nyx »
	MUC        string        // salon bus, ex. « nyx-bus@conference.hookipa.net »
	Nick       string        // occupant dans le salon ; vide = partie locale du JID
	TorSOCKS   string        // « 127.0.0.1:9050 » = toutes connexions via Tor ; vide = direct
	TLSEnforce bool          // STARTTLS (ou TLS direct) OBLIGATOIRE avant authentification
	TLSDirect  bool          // TLS dès la connexion (port 5223), pas de STARTTLS
	Timeout    time.Duration // dial + chaque étape ; 0 = 30 s
	AllowNoTLS bool          // TESTS uniquement : SASL en clair toléré malgré TLSEnforce
}

// Client — connexion XMPP établie (post-bind).
type Client struct {
	opt   Options
	conn  net.Conn
	dec   *xml.Decoder
	wmu   sync.Mutex
	jid   string // JID full après bind
	selfM string // JID self dans le MUC (muc/nick) — échos à ignorer

	out    chan string // corps de messages groupchat reçus
	errc   chan error
	closed chan struct{}
	once   sync.Once
}

// erreurs de session.
var (
	ErrAuthFailed  = errors.New("transportxmpp: authentification XMPP refusée")
	ErrTLSRequired = errors.New("transportxmpp: TLS indisponible côté serveur (tls_enforce=true)")
	ErrMUCJoin     = errors.New("transportxmpp: join MUC refusé")
)

// Dial — connexion complète : TCP/SOCKS5 → TLS → SASL → bind. Ne joint PAS
// encore le MUC (JoinMUC), pour laisser les tests vérifier étape par étape.
func Dial(opt Options) (*Client, error) {
	if opt.Host == "" || opt.JID == "" || opt.MUC == "" {
		return nil, fmt.Errorf("transportxmpp: host, jid et muc requis")
	}
	if opt.Timeout <= 0 {
		opt.Timeout = 30 * time.Second
	}
	if opt.Resource == "" {
		opt.Resource = "nyx"
	}
	if opt.Nick == "" {
		at := strings.IndexByte(opt.JID, '@')
		if at <= 0 {
			return nil, fmt.Errorf("transportxmpp: jid invalide (sans partie locale) : %q", opt.JID)
		}
		opt.Nick = opt.JID[:at]
	}
	addr := opt.Addr
	if addr == "" {
		addr = net.JoinHostPort(opt.Host, "5222")
	}
	conn, err := dialConn(opt, addr)
	if err != nil {
		return nil, err
	}
	c := &Client{
		opt: opt, conn: conn,
		dec: xml.NewDecoder(conn),
		out: make(chan string, 256), errc: make(chan error, 8),
		closed: make(chan struct{}),
		selfM:  opt.MUC + "/" + opt.Nick,
	}
	if opt.TLSDirect {
		if err := c.upgradeTLS(); err != nil {
			c.Close()
			return nil, err
		}
	}
	if err := c.session(); err != nil {
		c.Close()
		return nil, err
	}
	return c, nil
}

// dialConn — TCP direct ou via SOCKS5 de Tor (ADR-014 : Tor = canal d'accès).
func dialConn(opt Options, addr string) (net.Conn, error) {
	if opt.TorSOCKS == "" {
		return net.DialTimeout("tcp", addr, opt.Timeout)
	}
	d, err := proxy.SOCKS5("tcp", opt.TorSOCKS, nil, &net.Dialer{Timeout: opt.Timeout})
	if err != nil {
		return nil, fmt.Errorf("transportxmpp: SOCKS5 %s: %w", opt.TorSOCKS, err)
	}
	cd, ok := d.(proxy.ContextDialer)
	if !ok {
		return nil, fmt.Errorf("transportxmpp: dialer SOCKS5 inattendu")
	}
	return cd.DialContext(nil, "tcp", addr)
}

// upgradeTLS — TLS direct (port 5223) ou STARTTLS (même socket).
func (c *Client) upgradeTLS() error {
	_ = c.conn.SetDeadline(time.Now().Add(c.opt.Timeout))
	tconn := tls.Client(c.conn, &tls.Config{ServerName: c.opt.Host, MinVersion: tls.VersionTLS12})
	if err := tconn.Handshake(); err != nil {
		return fmt.Errorf("transportxmpp: handshake TLS: %w", err)
	}
	c.conn = tconn
	c.dec = xml.NewDecoder(tconn)
	return nil
}

// session — stream → features → (STARTTLS) → SASL → stream → bind.
func (c *Client) session() error {
	starttls, mechs, err := c.openStreamReadFeatures()
	if err != nil {
		return err
	}
	if starttls {
		if err := c.startTLS(); err != nil {
			return err
		}
		if starttls, mechs, err = c.openStreamReadFeatures(); err != nil {
			return err
		}
		if starttls {
			return fmt.Errorf("transportxmpp: serveur ré-offre STARTTLS après TLS (incohérent)")
		}
	} else if c.opt.TLSEnforce && !c.opt.AllowNoTLS {
		return ErrTLSRequired
	}
	if err := c.authenticate(mechs); err != nil {
		return err
	}
	if _, _, err := c.openStreamReadFeatures(); err != nil {
		return err
	}
	return c.bind()
}

// openStreamReadFeatures — envoie l'entête de stream, consomme l'entête
// serveur, retourne les features : STARTTLS offert ? mécanismes SASL.
func (c *Client) openStreamReadFeatures() (starttls bool, mechs []string, err error) {
	_ = c.conn.SetDeadline(time.Now().Add(c.opt.Timeout))
	hdr := "<?xml version='1.0'?>" +
		"<stream:stream xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams' " +
		"to='" + escapeXML(c.opt.Host) + "' version='1.0'>"
	if _, err = io.WriteString(c.conn, hdr); err != nil {
		return false, nil, err
	}
	// consomme <?xml …?> puis <stream:stream …>
	for {
		tok, err := c.dec.Token()
		if err != nil {
			return false, nil, fmt.Errorf("transportxmpp: entête de stream: %w", err)
		}
		if se, ok := tok.(xml.StartElement); ok && se.Name.Local == "stream" {
			break
		}
	}
	if _, err := c.nextStart("features"); err != nil {
		return false, nil, err
	}
	// parcours des features : les enfants intéressants sont consommés finement,
	// le reste est sauté ; la fin est la fermeture explicite de </features>
	// (les conteneurs transparents comme <mechanisms> se ferment avant).
	for {
		tok, err := c.dec.Token()
		if err != nil {
			return false, nil, err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch t.Name.Local {
			case "starttls":
				starttls = true
				if err := skipElement(c.dec, t); err != nil {
					return false, nil, err
				}
			case "mechanisms":
				// transparent : les <mechanism> enfants sont lus individuellement
			case "mechanism":
				txt, err := elementText(c.dec, t)
				if err != nil {
					return false, nil, err
				}
				mechs = append(mechs, strings.TrimSpace(txt))
			default:
				if err := skipElement(c.dec, t); err != nil {
					return false, nil, err
				}
			}
		case xml.EndElement:
			if t.Name.Local == "features" {
				return starttls, mechs, nil // fin de <features>
			}
			// fermeture d'un conteneur transparent (mechanisms) — continuer
		}
	}
}

// startTLS — demande puis applique STARTTLS.
func (c *Client) startTLS() error {
	if _, err := io.WriteString(c.conn,
		"<starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>"); err != nil {
		return err
	}
	for {
		tok, err := c.dec.Token()
		if err != nil {
			return err
		}
		if se, ok := tok.(xml.StartElement); ok {
			switch se.Name.Local {
			case "proceed":
				return c.upgradeTLS()
			case "failure":
				return fmt.Errorf("transportxmpp: STARTTLS refusé par le serveur")
			}
		}
	}
}

// authenticate — SCRAM-SHA-1 prioritaire, PLAIN en repli (sous TLS).
func (c *Client) authenticate(mechs []string) error {
	has := func(m string) bool {
		for _, x := range mechs {
			if strings.EqualFold(x, m) {
				return true
			}
		}
		return false
	}
	switch {
	case has("SCRAM-SHA-1"):
		return c.authSCRAM()
	case has("PLAIN"):
		return c.authPlain()
	default:
		return fmt.Errorf("transportxmpp: aucun mécanisme SASL supporté (%v)", mechs)
	}
}

func (c *Client) authPlain() error {
	payload := base64.StdEncoding.EncodeToString(
		[]byte("\x00" + c.opt.JID + "\x00" + c.opt.Password))
	if _, err := io.WriteString(c.conn,
		"<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='PLAIN'>"+payload+"</auth>"); err != nil {
		return err
	}
	return c.waitSASLResult()
}

// waitSASLResult — PLAIN : success/failure immédiats (pas de challenge).
func (c *Client) waitSASLResult() error {
	for {
		tok, err := c.dec.Token()
		if err != nil {
			return err
		}
		se, ok := tok.(xml.StartElement)
		if !ok {
			continue
		}
		switch se.Name.Local {
		case "success":
			_, _ = elementText(c.dec, se) // vide pour PLAIN
			return nil
		case "failure":
			return ErrAuthFailed
		case "challenge":
			return fmt.Errorf("transportxmpp: challenge inattendu pour PLAIN")
		}
	}
}

func (c *Client) authSCRAM() error {
	sc, err := newScram(c.opt.JID, c.opt.Password)
	if err != nil {
		return err
	}
	first := base64.StdEncoding.EncodeToString([]byte(sc.clientFirst()))
	if _, err := io.WriteString(c.conn,
		"<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='SCRAM-SHA-1'>"+first+"</auth>"); err != nil {
		return err
	}
	for {
		tok, err := c.dec.Token()
		if err != nil {
			return err
		}
		se, ok := tok.(xml.StartElement)
		if !ok {
			continue
		}
		switch se.Name.Local {
		case "challenge":
			txt, err := elementText(c.dec, se)
			if err != nil {
				return err
			}
			challenge, err := base64.StdEncoding.DecodeString(strings.TrimSpace(txt))
			if err != nil {
				return fmt.Errorf("transportxmpp: challenge SCRAM illisible")
			}
			final, err := sc.handleServerFirst(string(challenge))
			if err != nil {
				return err
			}
			if _, err := io.WriteString(c.conn,
				"<response xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>"+
					base64.StdEncoding.EncodeToString([]byte(final))+"</response>"); err != nil {
				return err
			}
		case "success":
			txt, _ := elementText(c.dec, se)
			sf, err := base64.StdEncoding.DecodeString(strings.TrimSpace(txt))
			if err != nil {
				return fmt.Errorf("transportxmpp: server-final illisible")
			}
			if err := sc.verifyServerFinal(string(sf)); err != nil {
				return err
			}
			return nil
		case "failure":
			return ErrAuthFailed
		default:
			if err := skipElement(c.dec, se); err != nil {
				return err
			}
		}
	}
}

// bind — resource bind (RFC 6120 §7) ; retient le JID full.
func (c *Client) bind() error {
	id := "nx-bind-1"
	if _, err := io.WriteString(c.conn,
		"<iq type='set' id='"+id+"'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'>"+
			"<resource>"+escapeXML(c.opt.Resource)+"</resource></bind></iq>"); err != nil {
		return err
	}
	for {
		se, err := c.nextStart("iq")
		if err != nil {
			return err
		}
		if attr(se, "id") != id {
			if err := skipElement(c.dec, se); err != nil {
				return err
			}
			continue
		}
		if attr(se, "type") == "error" {
			return fmt.Errorf("transportxmpp: bind refusé")
		}
		jid, err := c.consumeIQExtractJID(se)
		if err != nil {
			return err
		}
		if jid == "" {
			return fmt.Errorf("transportxmpp: bind sans jid")
		}
		c.jid = jid
		return nil
	}
}

// consumeIQExtractJID — consomme un <iq> complet, extrait le texte de <jid>.
func (c *Client) consumeIQExtractJID(open xml.StartElement) (string, error) {
	depth := 1
	var jid strings.Builder
	var inJID bool
	for depth > 0 {
		tok, err := c.dec.Token()
		if err != nil {
			return "", err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			if t.Name.Local == "jid" {
				inJID = true
			}
		case xml.CharData:
			if inJID {
				jid.Write(t)
			}
		case xml.EndElement:
			if t.Name.Local == "jid" {
				inJID = false
			}
			depth--
		}
	}
	_ = open
	return strings.TrimSpace(jid.String()), nil
}

// JoinMUC — présence XEP-0045 + attente de la présence self (code 110).
func (c *Client) JoinMUC() error {
	if _, err := io.WriteString(c.conn,
		"<presence to='"+escapeXML(c.opt.MUC+"/"+c.opt.Nick)+"'>"+
			"<x xmlns='http://jabber.org/protocol/muc'/></presence>"); err != nil {
		return err
	}
	_ = c.conn.SetDeadline(time.Now().Add(c.opt.Timeout))
	defer func() { _ = c.conn.SetDeadline(time.Time{}) }()
	for {
		se, err := c.nextStart("presence")
		if err != nil {
			return err
		}
		if attr(se, "type") == "error" {
			return fmt.Errorf("%w (presence error)", ErrMUCJoin)
		}
		joined, err := c.scanPresenceSelf(se)
		if err != nil {
			return err
		}
		if joined {
			return nil
		}
	}
}

// scanPresenceSelf — consomme une <presence> complète ; true si code=110.
func (c *Client) scanPresenceSelf(open xml.StartElement) (bool, error) {
	depth := 1
	for depth > 0 {
		tok, err := c.dec.Token()
		if err != nil {
			return false, err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			if t.Name.Local == "status" && attr(t, "code") == "110" {
				if err := skipElement(c.dec, t); err != nil {
					return false, err
				}
				depth--
				return true, nil
			}
		case xml.EndElement:
			depth--
		}
	}
	_ = open
	return false, nil
}

// QueryMAM — requête de rattrapage XEP-0313 (résultats traités par ReadPump).
func (c *Client) QueryMAM(max int) (string, error) {
	if max <= 0 || max > 100 {
		max = 50
	}
	qid := "nx-mam-1"
	iq := "<iq type='set' id='nx-mam-q1'><query xmlns='urn:xmpp:mam:2' queryid='" + qid + "'>" +
		"<set xmlns='http://jabber.org/protocol/rsm'><max>" + strconv.Itoa(max) + "</max></set>" +
		"</query></iq>"
	if _, err := io.WriteString(c.conn, iq); err != nil {
		return "", err
	}
	return qid, nil
}

// SendGroupChat — publie un corps (déjà `nyx1 …`) dans le salon.
func (c *Client) SendGroupChat(body string) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	_, err := io.WriteString(c.conn,
		"<message to='"+escapeXML(c.opt.MUC)+"' type='groupchat'><body>"+
			escapeXML(body)+"</body></message>")
	return err
}

// ReadPump — lance la boucle de lecture : pousse les corps groupchat sur Out,
// signale les erreurs de flux sur ErrC. À appeler UNE fois après JoinMUC.
func (c *Client) ReadPump() {
	go func() {
		defer close(c.out)
		defer func() { c.errc <- io.EOF }()
		_ = c.conn.SetDeadline(time.Time{})
		for {
			select {
			case <-c.closed:
				return
			default:
			}
			tok, err := c.dec.Token()
			if err != nil {
				c.errc <- err
				return
			}
			se, ok := tok.(xml.StartElement)
			if !ok {
				continue
			}
			switch se.Name.Local {
			case "message":
				body, from, err := c.readMessageBody(se)
				if err != nil {
					c.errc <- err
					return
				}
				if body != "" && from != c.selfM {
					select {
					case c.out <- body:
					case <-c.closed:
						return
					}
				}
			case "error": // erreur de stream
				txt, _ := elementText(c.dec, se)
				c.errc <- fmt.Errorf("transportxmpp: stream error: %s", strings.TrimSpace(txt))
				return
			default:
				if err := skipElement(c.dec, se); err != nil {
					c.errc <- err
					return
				}
			}
		}
	}()
}

// readMessageBody — consomme un <message> complet (forwarded MAM inclus) ;
// renvoie le texte du premier <body> et l'attribut from.
func (c *Client) readMessageBody(open xml.StartElement) (string, string, error) {
	from := attr(open, "from")
	depth := 1
	var body strings.Builder
	var inBody bool
	for depth > 0 {
		tok, err := c.dec.Token()
		if err != nil {
			return "", "", err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			if t.Name.Local == "body" {
				inBody = true
			}
		case xml.CharData:
			if inBody {
				body.Write(t)
			}
		case xml.EndElement:
			if t.Name.Local == "body" {
				inBody = false
			}
			depth--
		}
	}
	_ = open
	return strings.TrimSpace(body.String()), from, nil
}

// Out — canal des corps reçus (fermé à l'arrêt).
func (c *Client) Out() <-chan string { return c.out }

// ErrC — canal des erreurs de lecture.
func (c *Client) ErrC() <-chan error { return c.errc }

// JID — JID full post-bind.
func (c *Client) JID() string { return c.jid }

// Close — ferme la connexion (idempotent).
func (c *Client) Close() error {
	c.once.Do(func() {
		close(c.closed)
		_ = c.conn.Close()
	})
	return nil
}

// ---- petits utilitaires XML ----

// nextStart — prochain StartElement de nom local donné (skip du reste).
func (c *Client) nextStart(local string) (xml.StartElement, error) {
	for {
		tok, err := c.dec.Token()
		if err != nil {
			return xml.StartElement{}, err
		}
		if se, ok := tok.(xml.StartElement); ok && se.Name.Local == local {
			return se, nil
		}
	}
}

// elementText — consomme l'élément ouvert, renvoie son texte.
func elementText(d *xml.Decoder, _ xml.StartElement) (string, error) {
	var sb strings.Builder
	depth := 1
	for depth > 0 {
		tok, err := d.Token()
		if err != nil {
			return "", err
		}
		switch t := tok.(type) {
		case xml.CharData:
			sb.Write(t)
		case xml.StartElement:
			depth++
		case xml.EndElement:
			depth--
		}
	}
	return sb.String(), nil
}

// skipElement — consomme l'élément ouvert jusqu'à sa fin (ignore le contenu).
func skipElement(d *xml.Decoder, _ xml.StartElement) error {
	depth := 1
	for depth > 0 {
		tok, err := d.Token()
		if err != nil {
			return err
		}
		switch tok.(type) {
		case xml.StartElement:
			depth++
		case xml.EndElement:
			depth--
		}
	}
	return nil
}

func attr(se xml.StartElement, name string) string {
	for _, a := range se.Attr {
		if a.Name.Local == name {
			return a.Value
		}
	}
	return ""
}

// escapeXML — échappement pour textes et valeurs d'attributs.
func escapeXML(s string) string {
	r := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;",
		"'", "&#39;", "\"", "&quot;")
	return r.Replace(s)
}
