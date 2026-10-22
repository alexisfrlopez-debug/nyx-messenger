//go:build linux

package turn

// Couplage RFC 6062 §4.4 : le relais ouvre la connexion peer ENTRANTE vers le
// port source de la connexion de contrôle — le client doit écouter ce port.
// Un listener pré-lié avec SO_REUSEPORT + un dial partant du même ip:port
// partagent le port (stdlib syscall ; x/sys volontairement écarté, ADR-005).

import (
	"context"
	"net"
	"syscall"
)

// newCoupledListener — écouteur TCP 0.0.0.0:0 (port éphémère P) + mutateur
// forçant un Dialer à émettre depuis ce même ip:port (groupe SO_REUSEPORT).
func newCoupledListener() (net.Listener, func(*net.Dialer), error) {
	lc := net.ListenConfig{Control: reuseControl}
	ln, err := lc.Listen(context.Background(), "tcp", "0.0.0.0:0")
	if err != nil {
		return nil, nil, err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	tune := func(d *net.Dialer) {
		d.Control = reuseControl
		d.LocalAddr = &net.TCPAddr{Port: port} // IP laissée au choix de la route
	}
	return ln, tune, nil
}

// soReusePort — SO_REUSEPORT (15) : constante ABI Linux (asm-generic/socket.h)
// absente de la stdlib syscall ; x/sys/unix volontairement écarté (ADR-005).
const soReusePort = 0xF

// reuseControl — pose SO_REUSEADDR+SO_REUSEPORT avant bind/connect.
func reuseControl(network, address string, c syscall.RawConn) error {
	var serr error
	if err := c.Control(func(fd uintptr) {
		serr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
		if serr == nil {
			serr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, soReusePort, 1)
		}
	}); err != nil {
		return err
	}
	return serr
}
