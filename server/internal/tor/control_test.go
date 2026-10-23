package tor

import (
	"bufio"
	"errors"
	"net"
	"strings"
	"testing"
	"time"
)

// fakeControl — démon tor de substitution : répond AUTHENTICATE et ADD_ONION
// selon un scénario (publication fraîche vs collision 550).
type fakeControl struct {
	ln         net.Listener
	addOnionRx chan string
	scenario   string // "fresh" | "collision"
}

func startFakeControl(t *testing.T, scenario string) *fakeControl {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	fc := &fakeControl{ln: ln, addOnionRx: make(chan string, 4), scenario: scenario}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go fc.serve(conn)
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return fc
}

func (fc *fakeControl) serve(conn net.Conn) {
	defer conn.Close()
	r := bufio.NewReader(conn)
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(line, "AUTHENTICATE"):
			conn.Write([]byte("250 OK\r\n"))
		case strings.HasPrefix(line, "ADD_ONION"):
			select {
			case fc.addOnionRx <- line:
			default:
			}
			if fc.scenario == "collision" {
				conn.Write([]byte("550 Onion address collision\r\n"))
			} else {
				conn.Write([]byte("250-ServiceID=fakefakefakefakefakefakefakefakefakefake\r\n250 OK\r\n"))
			}
		default:
			conn.Write([]byte("250 OK\r\n"))
		}
	}
}

// TestPublishOnionDetach — v0.2.37 : la commande ADD_ONION doit porter
// Flags=Detach (survie du service au systemctl stop du déploiement 1-clic)
// et le mapping explicite 127.0.0.1.
func TestPublishOnionDetach(t *testing.T) {
	fc := startFakeControl(t, "fresh")
	ctrl, err := Dial(fc.ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer ctrl.Close()
	sid, err := ctrl.PublishOnion("aGVsbG8=", 36103)
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	if sid != "fakefakefakefakefakefakefakefakefakefake" {
		t.Fatalf("ServiceID inattendu: %q", sid)
	}
	cmd := <-fc.addOnionRx
	if !strings.Contains(cmd, "Flags=Detach,DiscardPK") {
		t.Fatalf("ADD_ONION sans Flags=Detach,DiscardPK : %q", cmd)
	}
	if !strings.Contains(cmd, "Port=80,127.0.0.1:36103") {
		t.Fatalf("mapping port absent/incorrect : %q", cmd)
	}
}

// TestPublishOnionCollision — v0.2.37 : une collision 550 (service déjà
// vivant via Detach) doit être renvoyée comme ErrOnionCollision (à ADOPTER
// par l'appelant), pas comme un échec générique.
func TestPublishOnionCollision(t *testing.T) {
	fc := startFakeControl(t, "collision")
	ctrl, err := Dial(fc.ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer ctrl.Close()
	_, err = ctrl.PublishOnion("aGVsbG8=", 36103)
	if !errors.Is(err, ErrOnionCollision) {
		t.Fatalf("attendu ErrOnionCollision, reçu: %v", err)
	}
}
