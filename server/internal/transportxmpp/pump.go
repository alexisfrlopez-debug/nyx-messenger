// pump.go — pompe journal ↔ salon MUC : chaque réplica publie SES événements
// propres dans le salon et intègre ceux des autres (validation + dé-dup par
// id natives au journal.Store — transport-xmpp.md §6). Reconnexion en backoff.
package transportxmpp

import (
	"log"
	"sync"
	"time"

	"github.com/alexisfrlopez-debug/nyx-messenger/server/internal/journal"
)

// bornes de reconnexion (même modèle que le gossip).
const (
	backoffStart = 30 * time.Second
	backoffMax   = 15 * time.Minute
	sendPause    = 300 * time.Millisecond // flood control serveurs publics
	flushLimit   = 200                    // événements par cycle d'envoi
)

// Pump — session XMPP récurrente branchée sur le journal.
type Pump struct {
	store     *journal.Store
	selfOnion string
	kick      <-chan struct{} // signal « événement local nouveau » (main.go)
	opt       Options
	interval  time.Duration // polling de sûreté (kick perdu)

	// OnNew — callback optionnel appelé quand un événement NOUVEAU est intégré
	// depuis le salon (main.go : re-propagation gossip immédiate).
	OnNew func()

	asm      *Assembler // réassemblage des enveloppes fragmentées reçues
	stop     chan struct{}
	stopOnce sync.Once
}

// NewPump — pompe prête à tourner (Run dans une goroutine, Stop à l'arrêt).
func NewPump(store *journal.Store, selfOnion string, kick <-chan struct{}, opt Options, interval time.Duration) *Pump {
	if interval <= 0 {
		interval = 10 * time.Second
	}
	return &Pump{
		store: store, selfOnion: selfOnion, kick: kick, opt: opt,
		interval: interval, stop: make(chan struct{}),
		asm: NewAssembler(),
	}
}

// Stop — arrête la boucle (idempotent).
func (p *Pump) Stop() { p.stopOnce.Do(func() { close(p.stop) }) }

// Run — boucle de connexion : dial → join → MAM → échange ; backoff sur erreur.
func (p *Pump) Run() {
	backoff := backoffStart
	for {
		select {
		case <-p.stop:
			return
		default:
		}
		if err := p.session(); err != nil {
			log.Printf("[xmpp] session: %v — reconnexion dans %s", err, backoff)
			select {
			case <-p.stop:
				return
			case <-time.After(backoff):
			}
			backoff = min(backoff*2, backoffMax)
			continue
		}
		backoff = backoffStart
		log.Printf("[xmpp] session terminée proprement — reconnexion dans %s", backoff)
		select {
		case <-p.stop:
			return
		case <-time.After(backoff):
		}
	}
}

// session — une connexion complète jusqu'à la première erreur.
func (p *Pump) session() error {
	cli, err := Dial(p.opt)
	if err != nil {
		return err
	}
	defer cli.Close()
	if err := cli.JoinMUC(); err != nil {
		return err
	}
	if _, err := cli.QueryMAM(50); err != nil {
		return err
	}
	cli.ReadPump()
	log.Printf("[xmpp] connecté — jid=%s salon=%s", cli.JID(), p.opt.MUC)

	cursor := map[string]uint64{p.selfOnion: p.store.LastSeq(p.selfOnion)} // dernier seq SELF publié
	ticker := time.NewTicker(p.interval)
	defer ticker.Stop()
	for {
		select {
		case <-p.stop:
			return nil
		case err := <-cli.ErrC():
			return err
		case body := <-cli.Out():
			p.handleBody(body)
		case <-p.kick:
			cursor = p.flush(cli, cursor)
		case <-ticker.C:
			cursor = p.flush(cli, cursor)
		}
	}
}

// flush — publie les événements SELF non encore envoyés (cursor par auteur).
func (p *Pump) flush(cli *Client, cursor map[string]uint64) map[string]uint64 {
	events, _ := p.store.EventsFor(cursor, flushLimit)
	out := cursor
	for _, ev := range events {
		data, err := ev.Encode()
		if err != nil {
			log.Printf("[xmpp] encodage évènement seq %d: %v", ev.Seq, err)
			continue
		}
		bodies, err := EncodeBodies(p.selfOnion, ev.Seq, data)
		if err != nil {
			log.Printf("[xmpp] préparation enveloppe seq %d: %v", ev.Seq, err)
			continue
		}
		sent := true
		for _, b := range bodies {
			if err := cli.SendGroupChat(b); err != nil {
				log.Printf("[xmpp] envoi seq %d: %v", ev.Seq, err)
				sent = false
				break
			}
			time.Sleep(sendPause)
		}
		if !sent {
			return out
		}
		if out == nil {
			out = map[string]uint64{}
		}
		if ev.Seq > out[ev.Author] {
			out[ev.Author] = ev.Seq
		}
	}
	return out
}

// handleBody — corps reçu → enveloppe → fragments → Event → journal.
func (p *Pump) handleBody(body string) {
	env, err := ParseBody(body)
	if err != nil {
		if err != ErrNotNyx { // bruit tiers du salon : silencieux
			log.Printf("[xmpp] corps rejeté: %v", err)
		}
		return
	}
	payloads, err := p.asm.Add(env)
	if err != nil {
		log.Printf("[xmpp] assemblage: %v", err)
		return
	}
	now := time.Now().Unix()
	for _, data := range payloads {
		ev, err := journal.Decode(data)
		if err != nil {
			log.Printf("[xmpp] event illisible (%d octets): %v", len(data), err)
			continue
		}
		if _, err := p.store.Append(ev, now); err != nil {
			log.Printf("[xmpp] intégration %s seq %d refusée: %v", ev.Author, ev.Seq, err)
			continue
		}
		log.Printf("[xmpp] intégré %s seq %d depuis le salon", ev.Author, ev.Seq)
		if p.OnNew != nil {
			p.OnNew()
		}
	}
}
