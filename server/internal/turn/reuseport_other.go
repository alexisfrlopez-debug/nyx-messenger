//go:build !linux

package turn

import "net"

// Hors Linux (build devtool multi-plateforme, etc.) : pas de couplage
// SO_REUSEPORT — Allocate reste possible (adresses + refresh), Connect
// retournera ErrNoInbound explicitement (RFC 6062 §4.4 non satisfait).

func newCoupledListener() (net.Listener, func(*net.Dialer), error) {
	return nil, nil, ErrNoInbound
}
