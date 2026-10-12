// Package journal — stockage append-only du journal (JSONL) + index mémoire.
package journal

import (
        "bufio"
        "fmt"
        "os"
        "path/filepath"
        "sort"
        "sync"
)

// Store — journal local. Thread-safe.
type Store struct {
        mu       sync.RWMutex
        path     string
        all      []*Event           // journal en mémoire (ordre d'insertion)
        byID     map[string]*Event  // dé-duplication (sha256 canonique)
        lastSeq  map[string]uint64  // dernier seq accepté, par auteur
        pending  map[string][]*Event // événements hors séquence en attente
        byAlias  map[string]*Event  // alias → claim gagnant (règle min-id, wire-format.md §6.3)
        maxIndex int64              // curseur global (position dans le journal)
}

// OpenStore — charge le journal existant (JSONL) et reconstruit les index.
func OpenStore(dataDir string) (*Store, error) {
        s := &Store{
                path:    filepath.Join(dataDir, "events.jsonl"),
                byID:    map[string]*Event{},
                lastSeq: map[string]uint64{},
                pending: map[string][]*Event{},
                byAlias: map[string]*Event{},
        }
        f, err := os.Open(s.path)
        if err != nil {
                if os.IsNotExist(err) {
                        return s, nil // journal neuf
                }
                return nil, err
        }
        defer f.Close()
        sc := bufio.NewScanner(f)
        sc.Buffer(make([]byte, 1024*1024), MaxBodySize*2)
        for sc.Scan() {
                line := sc.Bytes()
                if len(line) == 0 {
                        continue
                }
                ev, err := Decode(line)
                if err != nil {
                        // journal corrompu : on s'arrête net (pas de troncature silencieuse)
                        return nil, fmt.Errorf("journal corrompu à l'index %d: %w", s.maxIndex, err)
                }
                // Chargement sans re-validation cryptographique (déjà validée à l'insertion)
                s.byID[ev.ID()] = ev
                s.all = append(s.all, ev)
                s.indexAlias(ev)
                if ev.Seq > s.lastSeq[ev.Author] {
                        s.lastSeq[ev.Author] = ev.Seq
                }
                s.maxIndex++
        }
        return s, sc.Err()
}

// Append — validation complète puis insertion atomique.
// Retourne (déjàPrésent, erreur). Séquence stricte par auteur ; les événements
// futurs sont mis en attente et admis dès que le trou se comble.
func (s *Store) Append(ev *Event, now int64) (bool, error) {
        if err := ev.Validate(now); err != nil {
                return false, err
        }
        s.mu.Lock()
        defer s.mu.Unlock()
        id := ev.ID()
        if _, ok := s.byID[id]; ok {
                return true, nil
        }
        if ev.Seq != s.lastSeq[ev.Author]+1 {
                if ev.Seq > s.lastSeq[ev.Author]+1 {
                        s.pending[ev.Author] = append(s.pending[ev.Author], ev)
                        return false, fmt.Errorf("seq %d en attente (attendu %d)", ev.Seq, s.lastSeq[ev.Author]+1)
                }
                return false, fmt.Errorf("seq %d déjà dépassée (dernière %d)", ev.Seq, s.lastSeq[ev.Author])
        }
        if err := s.writeLine(ev); err != nil {
                return false, err
        }
        s.byID[id] = ev
        s.all = append(s.all, ev)
        s.lastSeq[ev.Author] = ev.Seq
        s.maxIndex++
        s.indexAlias(ev)
        // Admission des événements en attente devenus consécutifs
        changed := true
        for changed {
                changed = false
                pend := s.pending[ev.Author]
                for i, p := range pend {
                        if p.Seq == s.lastSeq[ev.Author]+1 {
                                if err := s.writeLine(p); err != nil {
                                        return false, err
                                }
                                s.byID[p.ID()] = p
                                s.all = append(s.all, p)
                                s.lastSeq[ev.Author] = p.Seq
                                s.maxIndex++
                                s.indexAlias(p)
                                s.pending[ev.Author] = append(pend[:i:i], pend[i+1:]...)
                                changed = true
                                break
                        }
                }
        }
        return false, nil
}

// indexAlias — met à jour l'index des pseudonymes (type "alias") selon la règle
// déterministe de convergence : le claim gagnant est celui au plus petit id
// (sha256 canonique hexadécimal, comparaison lexicographique — wire-format.md §6.3).
// Tolérant aux erreurs : un événement alias invalide n'a jamais dû passer Append
// (Validate vérifie le body) ; au replay, on ignore silencieusement un corps
// illisible plutôt que de refuser le démarrage.
func (s *Store) indexAlias(ev *Event) {
        if ev.Type != "alias" {
                return
        }
        alias, err := AliasFromEvent(ev)
        if err != nil {
                return
        }
        cur, ok := s.byAlias[alias]
        if !ok || ev.ID() < cur.ID() {
                s.byAlias[alias] = ev
        }
}

// ResolveAlias — détenteur d'un pseudonyme. Retourne ("", false) si libre.
func (s *Store) ResolveAlias(name string) (string, bool) {
        s.mu.RLock()
        defer s.mu.RUnlock()
        ev, ok := s.byAlias[name]
        if !ok {
                return "", false
        }
        return ev.Author, true
}

func (s *Store) writeLine(ev *Event) error {
        b, err := ev.Encode()
        if err != nil {
                return err
        }
        f, err := os.OpenFile(s.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
        if err != nil {
                return err
        }
        defer f.Close()
        if _, err := f.Write(append(b, '\n')); err != nil {
                return err
        }
        return f.Sync()
}

// Inbox — événements adressés à `to` au-delà d'un curseur global.
// Retourne (événements, nouveauCurseur).
func (s *Store) Inbox(to string, after int64, limit int) ([]*Event, int64) {
        s.mu.RLock()
        defer s.mu.RUnlock()
        if limit <= 0 || limit > 500 {
                limit = 500
        }
        out := []*Event{}
        cursor := s.maxIndex
        for i, ev := range s.all {
                gi := int64(i) + 1 // index global 1-based
                if gi <= after {
                        continue
                }
                if ev.To == to {
                        if len(out) < limit {
                                out = append(out, ev)
                        } else {
                                // il reste des événements au-delà : le curseur s'arrête ici
                                cursor = gi - 1
                                return out, cursor
                        }
                }
        }
        return out, cursor
}

// EventsByAuthor — journal public d'un auteur, seq > since.
func (s *Store) EventsByAuthor(author string, since uint64, limit int) []*Event {
        s.mu.RLock()
        defer s.mu.RUnlock()
        if limit <= 0 || limit > 500 {
                limit = 500
        }
        out := []*Event{}
        for _, ev := range s.all {
                if ev.Author == author && ev.Seq > since {
                        out = append(out, ev)
                        if len(out) >= limit {
                                break
                        }
                }
        }
        return out
}

// SinceMap — carte des séquences connues par auteur (curseur de gossip).
func (s *Store) SinceMap() map[string]uint64 {
        s.mu.RLock()
        defer s.mu.RUnlock()
        m := make(map[string]uint64, len(s.lastSeq))
        for a, seq := range s.lastSeq {
                m[a] = seq
        }
        return m
}

// EventsFor — événements strictement au-delà des seq fournies par auteur
// (pour les auteurs inconnus : tout le journal de cet auteur).
func (s *Store) EventsFor(want map[string]uint64, limit int) ([]*Event, bool) {
        s.mu.RLock()
        defer s.mu.RUnlock()
        if limit <= 0 || limit > 500 {
                limit = 500
        }
        out := []*Event{}
        for _, ev := range s.all {
                if ev.Seq > want[ev.Author] {
                        out = append(out, ev)
                        if len(out) >= limit {
                                sort.SliceStable(out, func(i, j int) bool { return out[i].Seq < out[j].Seq })
                                return out, true
                        }
                }
        }
        return out, false
}

// Count — nombre total d'événements.
func (s *Store) Count() int {
        s.mu.RLock()
        defer s.mu.RUnlock()
        return len(s.all)
}
