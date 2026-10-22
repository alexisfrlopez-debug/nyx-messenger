package turn

// Framing STUN minimal (RFC 5389) requis pour l'allocation relais TCP
// (RFC 6062) — PHASE 2 d'ADR-014 (protocol/specs/transport-turn.md §6).
// stdlib uniquement : binary, crc32, hmac, sha1.
//
// Périmètre volontairement restreint au dialecte TURN/TCP utilisé par Nyx :
// ALLOCATE / REFRESH / CONNECT / CONNECTION-BIND / CONNECTION-ATTEMPT,
// credentials SHORT-TERM (RFC 5389 §10.1), MESSAGE-INTEGRITY + FINGERPRINT,
// adresses XOR IPv4/IPv6. Ni UDP, ni ICE, ni long-term credentials.

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"net"
)

const (
	magicCookie    = uint32(0x2112A442)
	headerSize     = 20                 // type(2)+len(2)+cookie(4)+txid(12)
	fingerprintXor = uint32(0x5354554E) // "STUN"
	miSize         = 20                 // HMAC-SHA1
	fpSize         = 4                  // CRC32
)

// Classes STUN (bits C1..C0 du champ type — RFC 5389 §5).
type Class uint8

const (
	ClassRequest     Class = 0b00
	ClassIndication  Class = 0b01
	ClassSuccessResp Class = 0b10
	ClassErrorResp   Class = 0b11
)

// Méthodes du dialecte TURN/TCP de Nyx.
type Method uint16

const (
	MethodAllocate          Method = 0x003
	MethodRefresh           Method = 0x004
	MethodConnect           Method = 0x00A
	MethodConnectionBind    Method = 0x00B
	MethodConnectionAttempt Method = 0x00C
)

// Types d'attributs utilisés (RFC 5389/6062/5766).
const (
	AttrUsername           uint16 = 0x0006
	AttrMessageIntegrity   uint16 = 0x0008
	AttrErrorCode          uint16 = 0x0009
	AttrLifetime           uint16 = 0x000D
	AttrXorPeerAddress     uint16 = 0x0012
	AttrXorRelayedAddress  uint16 = 0x0016
	AttrRequestedTransport uint16 = 0x0019
	AttrConnectionID       uint16 = 0x001A
	AttrXorMappedAddress   uint16 = 0x0020
	AttrFingerprint        uint16 = 0x8028
)

// ProtoTCP — valeur REQUESTED-TRANSPORT pour une allocation TCP (RFC 6062 §4.2).
const ProtoTCP = 6

// Attr — un attribut TLV (valeur brute, padding inclus à l'encodage).
type Attr struct {
	Type  uint16
	Value []byte
}

// Message — une trame STUN décodée (ou prête à encoder).
type Message struct {
	Method Method
	Class  Class
	TxID   [12]byte
	Attrs  []Attr
}

// Get — première valeur de l'attribut type, ou nil.
func (m *Message) Get(t uint16) []byte {
	for i := range m.Attrs {
		if m.Attrs[i].Type == t {
			return m.Attrs[i].Value
		}
	}
	return nil
}

// encodeType — compose le champ type : bits de méthode m11..m7 aux positions
// 11..7, m6..m4 aux 6..4, m3..m0 aux 3..0 ; classe C1 sur le bit 8, C2 sur le
// bit 4 (RFC 5389 §5).
func encodeType(m Method, c Class) uint16 {
	mm := uint16(m)
	t := (mm & 0x0F80) << 2
	t |= (mm & 0x0070) << 1
	t |= (mm & 0x000F)
	if c&0b10 != 0 {
		t |= 0x0100
	}
	if c&0b01 != 0 {
		t |= 0x0010
	}
	return t
}

// decodeType — opération inverse de encodeType.
func decodeType(t uint16) (Method, Class) {
	m := ((t >> 2) & 0x0F80) | ((t >> 1) & 0x0070) | (t & 0x000F)
	var c Class
	if t&0x0100 != 0 {
		c |= 0b10
	}
	if t&0x0010 != 0 {
		c |= 0b01
	}
	return Method(m), c
}

// Encode — sérialise le message ; si key != "", ajoute MESSAGE-INTEGRITY
// (short-term : clé = credential, RFC 5389 §15.4) et, si fingerprint, un
// FINGERPRINT (CRC32 XOR « STUN ») après l'intégrité.
func (m *Message) Encode(key string, fingerprint bool) ([]byte, error) {
	body := make([]byte, 0, 128)
	for _, a := range m.Attrs {
		v := a.Value
		pad := (4 - len(v)%4) % 4
		body = binary.BigEndian.AppendUint16(body, a.Type)
		body = binary.BigEndian.AppendUint16(body, uint16(len(v)))
		body = append(body, v...)
		body = append(body, make([]byte, pad)...)
	}
	// Longueur d'en-tête FINALE : attributs + MESSAGE-INTEGRITY + FINGERPRINT.
	hdrLen := len(body)
	if key != "" {
		hdrLen += 4 + miSize
		if fingerprint {
			hdrLen += fpTotalSize()
		}
	}
	if hdrLen > 0xFFFF {
		return nil, errors.New("stun: message trop long")
	}
	out := make([]byte, 0, headerSize+hdrLen)
	out = binary.BigEndian.AppendUint16(out, encodeType(m.Method, m.Class))
	out = binary.BigEndian.AppendUint16(out, uint16(hdrLen))
	out = binary.BigEndian.AppendUint32(out, magicCookie)
	out = append(out, m.TxID[:]...)
	out = append(out, body...)

	if key != "" {
		// HMAC-SHA1 sur l'en-tête avec longueur ajustée (MI inclus, FINGERPRINT
		// exclu) + attributs jusqu'à MI exclus (RFC 5389 §15.4).
		lengthMAC := len(body) + 4 + miSize
		hdr := append([]byte(nil), out[:headerSize]...)
		binary.BigEndian.PutUint16(hdr[2:4], uint16(lengthMAC))
		mac := hmac.New(sha1.New, []byte(key))
		mac.Write(append(hdr, out[headerSize:]...))
		out = binary.BigEndian.AppendUint16(out, AttrMessageIntegrity)
		out = binary.BigEndian.AppendUint16(out, miSize)
		out = mac.Sum(out)
	}
	if fingerprint {
		// CRC32 sur tout le message jusqu'à FINGERPRINT exclus, XOR 0x5354554E.
		crc := crc32.ChecksumIEEE(out) ^ fingerprintXor
		out = binary.BigEndian.AppendUint16(out, AttrFingerprint)
		out = binary.BigEndian.AppendUint16(out, fpSize)
		out = binary.BigEndian.AppendUint32(out, crc)
	}
	return out, nil
}

// fpTotalSize — taille totale (type+longueur+valeur) d'un FINGERPRINT.
func fpTotalSize() int { return 4 + fpSize }

// Decode — parse une trame STUN complète (attributs bruts, intégrité NON
// vérifiée ici — voir VerifyIntegrity). Retourne l'erreur sur cookie magique
// invalide ou longueurs incohérentes.
func Decode(b []byte) (*Message, error) {
	if len(b) < headerSize {
		return nil, fmt.Errorf("stun: %d octets < en-tête", len(b))
	}
	if cookie := binary.BigEndian.Uint32(b[4:8]); cookie != magicCookie {
		return nil, fmt.Errorf("stun: cookie magique invalide 0x%08x", cookie)
	}
	msgLen := int(binary.BigEndian.Uint16(b[2:4]))
	if headerSize+msgLen > len(b) {
		return nil, fmt.Errorf("stun: trame tronquée (%d < %d)", len(b), headerSize+msgLen)
	}
	method, class := decodeType(binary.BigEndian.Uint16(b[0:2]))
	m := &Message{Method: method, Class: class}
	copy(m.TxID[:], b[8:20])
	p := headerSize
	end := headerSize + msgLen
	for p+4 <= end {
		t := binary.BigEndian.Uint16(b[p : p+2])
		l := int(binary.BigEndian.Uint16(b[p+2 : p+4]))
		if p+4+l > end {
			return nil, fmt.Errorf("stun: attribut 0x%04x déborde", t)
		}
		v := append([]byte(nil), b[p+4:p+4+l]...)
		m.Attrs = append(m.Attrs, Attr{Type: t, Value: v})
		p += 4 + l + ((4 - l%4) % 4)
	}
	return m, nil
}

// VerifyIntegrity — recalcul de l'HMAC short-term sur l'attribut
// MESSAGE-INTEGRITY présent (le FINGERPRINT éventuel est ignoré : il suit MI).
func VerifyIntegrity(m *Message, raw []byte, key string) bool {
	if key == "" {
		return false
	}
	mi := m.Get(AttrMessageIntegrity)
	if len(mi) != miSize {
		return false
	}
	length := int(binary.BigEndian.Uint16(raw[2:4]))
	if m.Get(AttrFingerprint) != nil {
		length -= fpTotalSize()
	}
	hdr := append([]byte(nil), raw[:headerSize]...)
	binary.BigEndian.PutUint16(hdr[2:4], uint16(length))
	// Corps jusqu'à l'attribut MI exclus.
	body := raw[headerSize : headerSize+length]
	cut := len(body)
	for p := 0; p+4 <= cut; {
		t := binary.BigEndian.Uint16(body[p : p+2])
		l := int(binary.BigEndian.Uint16(body[p+2 : p+4]))
		if t == AttrMessageIntegrity {
			cut = p
			break
		}
		p += 4 + l + ((4 - l%4) % 4)
	}
	mac := hmac.New(sha1.New, []byte(key))
	mac.Write(append(hdr, body[:cut]...))
	return hmac.Equal(mac.Sum(nil), mi)
}

// EncodeXorAddr — XOR-PEER/RELAYED/MAPPED-ADDRESS (RFC 5389 §15.2).
func EncodeXorAddr(ip net.IP, port int, txid [12]byte) ([]byte, error) {
	ip4 := ip.To4()
	var fam byte
	var mask, ipb []byte
	if ip4 != nil {
		fam = 0x01
		mask = make([]byte, 4)
		binary.BigEndian.PutUint32(mask, magicCookie)
		ipb = ip4
	} else {
		fam = 0x02
		mask = make([]byte, 16)
		binary.BigEndian.PutUint32(mask, magicCookie)
		copy(mask[4:], txid[:])
		ipb = ip.To16()
	}
	if ipb == nil {
		return nil, fmt.Errorf("stun: IP invalide %v", ip)
	}
	out := []byte{0x00, fam, byte(port>>8) ^ mask[0], byte(port) ^ mask[1]}
	for i, b := range ipb {
		out = append(out, b^mask[i])
	}
	return out, nil
}

// DecodeXorAddr — inverse de EncodeXorAddr.
func DecodeXorAddr(v []byte, txid [12]byte) (net.IP, int, error) {
	if len(v) != 8 && len(v) != 20 {
		return nil, 0, fmt.Errorf("stun: adresse XOR de %d octets", len(v))
	}
	var mask []byte
	switch v[1] {
	case 0x01:
		mask = make([]byte, 4)
		binary.BigEndian.PutUint32(mask, magicCookie)
	case 0x02:
		mask = make([]byte, 16)
		binary.BigEndian.PutUint32(mask, magicCookie)
		copy(mask[4:], txid[:])
	default:
		return nil, 0, fmt.Errorf("stun: famille inconnue 0x%02x", v[1])
	}
	port := int(binary.BigEndian.Uint16(v[2:4])) ^ int(binary.BigEndian.Uint16(mask[:2]))
	ip := make(net.IP, len(v)-4)
	for i := range ip {
		ip[i] = v[4+i] ^ mask[i]
	}
	return ip, port, nil
}

// ParseErrorCode — ERROR-CODE (RFC 5389 §15.6) : classe (3 bits) + numéro.
func ParseErrorCode(v []byte) (int, string, error) {
	if len(v) < 4 {
		return 0, "", fmt.Errorf("stun: ERROR-CODE trop court (%d)", len(v))
	}
	num := int(v[2]&0x07)*100 + int(v[3])
	return num, string(v[4:]), nil
}
