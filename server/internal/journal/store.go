// Package journal — stockage append-only du journal (JSONL) + index mémoire.
package journal

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
)

// Store — journal local. Thread-safe.
type Store struct {
	mu        sync.RWMutex
	path      string
	all       []*Event            // journal en mémoire (ordre d'insertion)
	byID      map[string]*Event   // dé-duplication (sha256 canonique)
	lastSeq   map[string]uint64   // dernier seq accepté, par auteur
	pending   map[string][]*Event // événements hors séquence en attente
	byAlias   map[string]*Event   // alias → claim gagnant (règle min-id, wire-format.md §6.3)
	byProfile map[string]*Event   // auteur → carte de profil courante (dernier seq, §7.3)
	maxIndex  int64               // curseur global (position dans le journal)
}

// OpenStore — charge le journal existant (JSONL) et reconstruit les index.
func OpenStore(dataDir string) (*Store, error) {
	s := &Store{
		path:      filepath.Join(dataDir, "events.jsonl"),
		byID:      map[string]*Event{},
		lastSeq:   map[string]uint64{},
		pending:   map[string][]*Event{},
		byAlias:   map[string]*Event{},
		byProfile: map[string]*Event{},
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
		s.indexProfile(ev)
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
	s.indexProfile(ev)
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
				s.indexProfile(p)
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

// indexProfile — carte de profil courante d'un auteur (dernier seq gagne,
// wire-format.md §7.3 — les événements arrivent dans l'ordre du journal).
// Tolérant aux erreurs (même logique que indexAlias : un corps illisible au
// replay n'empêche jamais le démarrage).
func (s *Store) indexProfile(ev *Event) {
	if ev.Type != "profile" || ev.To != "" {
		return
	}
	if _, err := ProfileFromEvent(ev); err != nil {
		return
	}
	cur, ok := s.byProfile[ev.Author]
	if !ok || ev.Seq > cur.Seq {
		s.byProfile[ev.Author] = ev
	}
}

// ResolveProfile — carte publique courante d'un auteur (nil si aucune).
func (s *Store) ResolveProfile(author string) *Event {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.byProfile[author]
}

// JournalBytes — taille sur disque du journal (monitoring admin).
func (s *Store) JournalBytes() int64 {
	st, err := os.Stat(s.path)
	if err != nil {
		return 0
	}
	return st.Size()
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

// LastSeq — dernier seq accepté d'un auteur (0 = inconnu). Sert au transport
// XMPP (ADR-014) pour positionner son curseur de publication par auteur.
func (s *Store) LastSeq(author string) uint64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.lastSeq[author]
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

// ---- checkpoint (élagage vérifiable — APPLI_ADMIN.md §4, spec privée) ----
//
// Le journal est append-only (ADR-001) : la suppression UNITAIRE est interdite.
// L'élagage se fait par PRÉFIXE, archivé hors ligne et prouvé par une racine de
// Merkle (RFC 6962) signée par le nœud : les événements archivés restent
// vérifiables (présence par preuve d'inclusion) après la troncature.

// merkleLeafHash / merkleNodeHash — RFC 6962 (domain separation 0x00 / 0x01).
func merkleLeafHash(id []byte) []byte {
	h := sha256.New()
	h.Write([]byte{0x00})
	h.Write(id)
	return h.Sum(nil)
}

func merkleNodeHash(l, r []byte) []byte {
	h := sha256.New()
	h.Write([]byte{0x01})
	h.Write(l)
	h.Write(r)
	return h.Sum(nil)
}

// MerkleRoot — racine RFC 6962 du préfixe [0, beforeSeq) du journal (positions
// globales, ordre d'insertion). beforeSeq est ici un NOMBRE d'événements (le
// préfixe), pas un seq applicatif. Les feuilles impaires sont dupliquées
// (convention RFC 6962 §2.1 — MTH({d[n]}) = H(0x01 || MTH({d[n-1]}) || MTH({d[n-1]}))).
func (s *Store) MerkleRoot(before int) (string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if before < 0 || before > len(s.all) {
		return "", fmt.Errorf("préfixe invalide (%d > %d)", before, len(s.all))
	}
	if before == 0 {
		return fmt.Sprintf("%x", sha256.Sum256(nil)), nil
	}
	leaves := make([][]byte, before)
	for i := 0; i < before; i++ {
		id, _ := hex.DecodeString(s.all[i].ID())
		leaves[i] = merkleLeafHash(id)
	}
	root := merkleRootOf(leaves)
	return fmt.Sprintf("%x", root), nil
}

func merkleRootOf(leaves [][]byte) []byte {
	if len(leaves) == 1 {
		return leaves[0]
	}
	k := 1
	for k*2 < len(leaves) {
		k *= 2
	}
	return merkleNodeHash(merkleRootOf(leaves[:k]), merkleRootOf(leaves[k:]))
}

// ApplyCheckpoint — DEUXIÈME étape de l'élagage (après vérification hors ligne
// de la racine proposée) : archive le préfixe [0, before) dans
// <data-dir>/archive/checkpoint-<before>-<racine8>.jsonl puis tronque le
// journal et reconstruit les index. La racine est recalculée et DOIT
// correspondre à expectedRoot (protection contre une course pendant l'archivage).
func (s *Store) ApplyCheckpoint(before int, expectedRoot string) (archivePath string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if before <= 0 || before > len(s.all) {
		return "", fmt.Errorf("préfixe invalide (%d, journal %d)", before, len(s.all))
	}
	root, err := func() (string, error) {
		leaves := make([][]byte, before)
		for i := 0; i < before; i++ {
			id, e := hex.DecodeString(s.all[i].ID())
			if e != nil {
				return "", e
			}
			leaves[i] = merkleLeafHash(id)
		}
		return fmt.Sprintf("%x", merkleRootOf(leaves)), nil
	}()
	if err != nil {
		return "", err
	}
	if root != expectedRoot {
		return "", fmt.Errorf("racine recalculée %s ≠ proposée %s — élagage annulé", root, expectedRoot)
	}
	// 1. Archivage hors ligne (le préfixe reste prouvable via la racine signée)
	dir := filepath.Join(filepath.Dir(s.path), "archive")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	archivePath = filepath.Join(dir, fmt.Sprintf("checkpoint-%d-%s.jsonl", before, root[:8]))
	af, err := os.OpenFile(archivePath, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
	if err != nil {
		return "", err
	}
	aw := bufio.NewWriter(af)
	for i := 0; i < before; i++ {
		b, err := s.all[i].Encode()
		if err != nil {
			af.Close()
			return "", err
		}
		aw.Write(b)
		aw.WriteByte('\n')
	}
	if err := aw.Flush(); err != nil {
		af.Close()
		return "", err
	}
	if err := af.Sync(); err != nil {
		af.Close()
		return "", err
	}
	af.Close()
	// 2. Troncature du fichier (réécriture du suffixe — le plus sûr après sync archivage)
	tmp := s.path + ".tmp"
	tf, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return "", err
	}
	tw := bufio.NewWriter(tf)
	for i := before; i < len(s.all); i++ {
		b, err := s.all[i].Encode()
		if err != nil {
			tf.Close()
			return "", err
		}
		tw.Write(b)
		tw.WriteByte('\n')
	}
	if err := tw.Flush(); err != nil {
		tf.Close()
		return "", err
	}
	if err := tf.Sync(); err != nil {
		tf.Close()
		return "", err
	}
	tf.Close()
	if err := os.Rename(tmp, s.path); err != nil {
		return "", err
	}
	// 3. Reconstruction des index en mémoire (le suffixe reste valide)
	rest := append([]*Event{}, s.all[before:]...)
	s.all = rest
	s.byID = map[string]*Event{}
	s.lastSeq = map[string]uint64{}
	s.pending = map[string][]*Event{}
	s.byAlias = map[string]*Event{}
	s.byProfile = map[string]*Event{}
	for _, ev := range s.all {
		s.byID[ev.ID()] = ev
		s.indexAlias(ev)
		s.indexProfile(ev)
		if ev.Seq > s.lastSeq[ev.Author] {
			s.lastSeq[ev.Author] = ev.Seq
		}
	}
	s.maxIndex = int64(len(s.all))
	return archivePath, nil
}
