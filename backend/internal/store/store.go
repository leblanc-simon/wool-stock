// Package store persiste dans SQLite le cache des codes-barres, le stock par
// bain et le journal des entrées / sorties.
package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strings"
	"time"

	_ "modernc.org/sqlite" // enregistre le driver "sqlite"
)

//go:embed migrations/*.sql
var migrations embed.FS

// ErrNotFound signale qu'une référence demandée n'existe pas.
var ErrNotFound = errors.New("store: not found")

// ErrRequestReused signale un request_id déjà enregistré avec un autre contenu : ce n'est
// pas un renvoi de la même saisie, et la rendre ferait croire au client qu'elle est passée.
var ErrRequestReused = errors.New("store: request_id reused with a different content")

// Store est la persistance SQLite du serveur.
type Store struct {
	db *sql.DB
	// now donne l'heure des écritures ; remplaçable dans les tests.
	now func() time.Time
}

// querier est commun à *sql.DB et *sql.Tx.
type querier interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// Open ouvre la base SQLite désignée par dsn et joue les migrations en attente.
// Les pragmas sont ajoutés au DSN pour que chaque connexion du pool les reçoive.
func Open(ctx context.Context, dsn string) (*Store, error) {
	sep := "?"
	if strings.Contains(dsn, "?") {
		sep = "&"
	}
	dsn += sep + "_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)"

	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("store: open: %w", err)
	}
	// SQLite sérialise les écritures : une seule connexion évite SQLITE_BUSY et
	// rend atomique la vérification du stock avant une sortie.
	db.SetMaxOpenConns(1)

	s := &Store{db: db, now: time.Now}
	if err := s.migrate(ctx); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

// Close libère la base.
func (s *Store) Close() error {
	return s.db.Close()
}

// Ping vérifie que la base répond.
func (s *Store) Ping(ctx context.Context) error {
	return s.db.PingContext(ctx)
}

// migrate joue, dans l'ordre des noms, chaque migration embarquée pas encore enregistrée.
func (s *Store) migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)`); err != nil {
		return fmt.Errorf("store: migrations table: %w", err)
	}
	names, err := fs.Glob(migrations, "migrations/*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	for _, name := range names {
		var done int
		if err := s.db.QueryRowContext(ctx,
			`SELECT COUNT(*) FROM schema_migrations WHERE name = ?`, name).Scan(&done); err != nil {
			return fmt.Errorf("store: migration %s: %w", name, err)
		}
		if done > 0 {
			continue
		}
		body, err := migrations.ReadFile(name)
		if err != nil {
			return err
		}
		err = s.tx(ctx, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(ctx, string(body)); err != nil {
				return err
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO schema_migrations (name, applied_at) VALUES (?, ?)`,
				name, s.timestamp())
			return err
		})
		if err != nil {
			return fmt.Errorf("store: migration %s: %w", name, err)
		}
	}
	return nil
}

func (s *Store) tx(ctx context.Context, fn func(*sql.Tx) error) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		tx.Rollback()
		return err
	}
	return tx.Commit()
}

// timestampLayout est RFC 3339 à largeur fixe (nanosecondes toujours écrites) :
// les horodatages se trient comme du texte.
const timestampLayout = "2006-01-02T15:04:05.000000000Z07:00"

// timestamp formate l'heure courante en UTC.
func (s *Store) timestamp() string {
	return s.now().UTC().Format(timestampLayout)
}

func parseTime(value string) time.Time {
	t, _ := time.Parse(time.RFC3339Nano, value)
	return t
}

// requestHash résume le contenu d'un envoi rejouable, pour vérifier qu'un request_id
// renvoyé porte bien la même saisie. content est une structure du store : l'encodage
// JSON en est stable et ne peut pas échouer.
func requestHash(content any) string {
	body, err := json.Marshal(content)
	if err != nil {
		panic(fmt.Sprintf("store: request hash: %v", err))
	}
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

// sameRequest dit si l'empreinte enregistrée correspond au contenu renvoyé ; un envoi
// enregistré sans empreinte (avant la migration 005) se rejoue sans comparaison.
func sameRequest(stored sql.NullString, hash string) bool {
	return !stored.Valid || stored.String == hash
}

// nullable convertit une chaîne vide en NULL.
func nullable(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func optional(value sql.NullString) *string {
	if !value.Valid {
		return nil
	}
	return &value.String
}
