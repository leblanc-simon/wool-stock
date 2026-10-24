package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// Reference est ce que le code-barre permet de préremplir : marque et coloris.
type Reference struct {
	EAN       string    `json:"ean"`
	Brand     *string   `json:"brand"`
	ColorCode *string   `json:"color_code"`
	ColorName *string   `json:"color_name"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

// ReferenceFields sont les champs modifiables d'une référence ; nil efface le champ.
type ReferenceFields struct {
	Brand     *string
	ColorCode *string
	ColorName *string
}

const referenceColumns = `ean, brand, color_code, color_name, created_at, updated_at`

// References renvoie tout le cache des codes-barres, trié par marque puis coloris.
func (s *Store) References(ctx context.Context) ([]Reference, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+referenceColumns+` FROM yarn_references
		ORDER BY brand IS NULL, brand COLLATE NOCASE, color_code, color_name COLLATE NOCASE, ean`)
	if err != nil {
		return nil, fmt.Errorf("store: references: %w", err)
	}
	defer rows.Close()
	references := []Reference{}
	for rows.Next() {
		ref, err := scanReference(rows)
		if err != nil {
			return nil, fmt.Errorf("store: references: %w", err)
		}
		references = append(references, ref)
	}
	return references, rows.Err()
}

// Reference renvoie la référence d'un EAN, ou ErrNotFound.
func (s *Store) Reference(ctx context.Context, ean string) (Reference, error) {
	return reference(ctx, s.db, ean)
}

// SaveReference crée ou remplace la marque et le coloris d'un EAN.
func (s *Store) SaveReference(ctx context.Context, ean string, fields ReferenceFields) (Reference, error) {
	var ref Reference
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if err := s.saveReference(ctx, tx, ean, fields); err != nil {
			return err
		}
		var err error
		ref, err = reference(ctx, tx, ean)
		return err
	})
	return ref, err
}

func (s *Store) saveReference(ctx context.Context, q querier, ean string, fields ReferenceFields) error {
	now := s.timestamp()
	_, err := q.ExecContext(ctx, `INSERT INTO yarn_references (`+referenceColumns+`) VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT (ean) DO UPDATE SET brand = excluded.brand, color_code = excluded.color_code,
			color_name = excluded.color_name, updated_at = excluded.updated_at`,
		ean, fields.Brand, fields.ColorCode, fields.ColorName, now, now)
	if err != nil {
		return fmt.Errorf("store: save reference: %w", err)
	}
	return nil
}

// ensureReference crée une référence vide pour un EAN inconnu, sans toucher une référence existante.
func (s *Store) ensureReference(ctx context.Context, q querier, ean string) error {
	now := s.timestamp()
	_, err := q.ExecContext(ctx, `INSERT INTO yarn_references (ean, created_at, updated_at) VALUES (?, ?, ?)
		ON CONFLICT (ean) DO NOTHING`, ean, now, now)
	if err != nil {
		return fmt.Errorf("store: ensure reference: %w", err)
	}
	return nil
}

func reference(ctx context.Context, q querier, ean string) (Reference, error) {
	ref, err := scanReference(q.QueryRowContext(ctx, `SELECT `+referenceColumns+` FROM yarn_references WHERE ean = ?`, ean))
	if errors.Is(err, sql.ErrNoRows) {
		return Reference{}, ErrNotFound
	}
	if err != nil {
		return Reference{}, fmt.Errorf("store: reference: %w", err)
	}
	return ref, nil
}

type scanner interface {
	Scan(dest ...any) error
}

func scanReference(row scanner) (Reference, error) {
	var (
		ref                         Reference
		brand, colorCode, colorName sql.NullString
		createdAt, updatedAt        string
	)
	if err := row.Scan(&ref.EAN, &brand, &colorCode, &colorName, &createdAt, &updatedAt); err != nil {
		return Reference{}, err
	}
	ref.Brand, ref.ColorCode, ref.ColorName = optional(brand), optional(colorCode), optional(colorName)
	ref.CreatedAt, ref.UpdatedAt = parseTime(createdAt), parseTime(updatedAt)
	return ref, nil
}
