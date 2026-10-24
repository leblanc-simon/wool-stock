package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Kind distingue une entrée d'une sortie de stock.
type Kind string

const (
	KindIn  Kind = "in"
	KindOut Kind = "out"
)

// Movement est une entrée ou une sortie de pelotes d'un bain.
type Movement struct {
	ID        int64   `json:"id"`
	RequestID *string `json:"request_id"`
	Kind      Kind    `json:"kind"`
	LotID     int64   `json:"lot_id"`
	EAN       string  `json:"ean"`
	DyeLot    string  `json:"dye_lot"`
	Quantity  int     `json:"quantity"`
	// Recipient est la personne pour qui les pelotes sont sorties.
	Recipient *string `json:"recipient"`
	// Author est la personne qui a saisi le mouvement, telle qu'elle s'est nommée.
	Author    *string   `json:"author"`
	CreatedAt time.Time `json:"created_at"`
	// Marque et coloris de la référence, au moment de la lecture.
	Brand     *string `json:"brand"`
	ColorCode *string `json:"color_code"`
	ColorName *string `json:"color_name"`
}

// MovementInput est un mouvement à appliquer, déjà validé.
type MovementInput struct {
	// RequestID identifie l'envoi côté client ; vide, l'envoi n'est pas rejouable.
	RequestID string
	Kind      Kind
	EAN       string
	DyeLot    string
	Quantity  int
	Recipient string
	Author    string
	// Reference, sur une entrée, remplace la marque et le coloris en cache.
	Reference *ReferenceFields
}

// MovementFilter restreint l'historique ; un champ vide ne filtre pas.
type MovementFilter struct {
	EAN  string
	Kind Kind
	// Since et Until bornent la date du mouvement : Since incluse, Until exclue.
	Since time.Time
	Until time.Time
	Limit int
}

// InsufficientStockError signale une sortie supérieure au stock du bain.
type InsufficientStockError struct {
	Available int
}

func (e *InsufficientStockError) Error() string {
	return fmt.Sprintf("store: insufficient stock (%d available)", e.Available)
}

// Record applique un mouvement et renvoie le bain mis à jour. Un RequestID déjà
// enregistré renvoie le mouvement d'origine sans rien rejouer (replayed vaut true) :
// un client qui renvoie après une coupure réseau ne compte pas deux fois.
func (s *Store) Record(ctx context.Context, in MovementInput) (movement Movement, lot StockItem, replayed bool, err error) {
	err = s.tx(ctx, func(tx *sql.Tx) error {
		if in.RequestID != "" {
			previous, err := scanMovement(tx.QueryRowContext(ctx, movementQuery+` WHERE m.request_id = ?`, in.RequestID))
			if err == nil {
				movement, replayed = previous, true
				lot, err = stockItem(ctx, tx, previous.LotID)
				return err
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return fmt.Errorf("store: replay: %w", err)
			}
		}

		var lotID int64
		var err error
		switch in.Kind {
		case KindIn:
			lotID, err = s.addToLot(ctx, tx, in)
		case KindOut:
			lotID, err = s.takeFromLot(ctx, tx, in)
		default:
			err = fmt.Errorf("store: unknown movement kind %q", in.Kind)
		}
		if err != nil {
			return err
		}

		res, err := tx.ExecContext(ctx, `INSERT INTO movements
			(request_id, lot_id, kind, quantity, recipient, author, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
			nullable(in.RequestID), lotID, in.Kind, in.Quantity, nullable(in.Recipient), nullable(in.Author), s.timestamp())
		if err != nil {
			return fmt.Errorf("store: insert movement: %w", err)
		}
		id, err := res.LastInsertId()
		if err != nil {
			return err
		}
		if movement, err = scanMovement(tx.QueryRowContext(ctx, movementQuery+` WHERE m.id = ?`, id)); err != nil {
			return fmt.Errorf("store: movement: %w", err)
		}
		lot, err = stockItem(ctx, tx, lotID)
		return err
	})
	return movement, lot, replayed, err
}

func (s *Store) addToLot(ctx context.Context, tx *sql.Tx, in MovementInput) (int64, error) {
	var err error
	if in.Reference != nil {
		err = s.saveReference(ctx, tx, in.EAN, *in.Reference)
	} else {
		err = s.ensureReference(ctx, tx, in.EAN)
	}
	if err != nil {
		return 0, err
	}
	var lotID int64
	err = tx.QueryRowContext(ctx, `INSERT INTO lots (ean, dye_lot, quantity, updated_at) VALUES (?, ?, ?, ?)
		ON CONFLICT (ean, dye_lot) DO UPDATE SET quantity = quantity + excluded.quantity, updated_at = excluded.updated_at
		RETURNING id`, in.EAN, in.DyeLot, in.Quantity, s.timestamp()).Scan(&lotID)
	if err != nil {
		return 0, fmt.Errorf("store: add to lot: %w", err)
	}
	return lotID, nil
}

func (s *Store) takeFromLot(ctx context.Context, tx *sql.Tx, in MovementInput) (int64, error) {
	var lotID int64
	var available int
	err := tx.QueryRowContext(ctx, `SELECT id, quantity FROM lots WHERE ean = ? AND dye_lot = ?`,
		in.EAN, in.DyeLot).Scan(&lotID, &available)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, &InsufficientStockError{Available: 0}
	}
	if err != nil {
		return 0, fmt.Errorf("store: take from lot: %w", err)
	}
	if available < in.Quantity {
		return 0, &InsufficientStockError{Available: available}
	}
	if _, err := tx.ExecContext(ctx, `UPDATE lots SET quantity = quantity - ?, updated_at = ? WHERE id = ?`,
		in.Quantity, s.timestamp(), lotID); err != nil {
		return 0, fmt.Errorf("store: take from lot: %w", err)
	}
	return lotID, nil
}

const movementQuery = `SELECT m.id, m.request_id, m.kind, m.lot_id, l.ean, l.dye_lot, m.quantity,
	m.recipient, m.author, m.created_at, r.brand, r.color_code, r.color_name
	FROM movements m JOIN lots l ON l.id = m.lot_id JOIN yarn_references r ON r.ean = l.ean`

// Movements renvoie l'historique, du plus récent au plus ancien.
func (s *Store) Movements(ctx context.Context, filter MovementFilter) ([]Movement, error) {
	var (
		where []string
		args  []any
	)
	if filter.EAN != "" {
		where = append(where, "l.ean = ?")
		args = append(args, filter.EAN)
	}
	if filter.Kind != "" {
		where = append(where, "m.kind = ?")
		args = append(args, filter.Kind)
	}
	// julianday compare des instants, quel que soit le nombre de décimales écrites.
	if !filter.Since.IsZero() {
		where = append(where, "julianday(m.created_at) >= julianday(?)")
		args = append(args, filter.Since.UTC().Format(timestampLayout))
	}
	if !filter.Until.IsZero() {
		where = append(where, "julianday(m.created_at) < julianday(?)")
		args = append(args, filter.Until.UTC().Format(timestampLayout))
	}
	query := movementQuery
	if len(where) > 0 {
		query += " WHERE " + strings.Join(where, " AND ")
	}
	query += ` ORDER BY m.id DESC LIMIT ?`
	args = append(args, filter.Limit)

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("store: movements: %w", err)
	}
	defer rows.Close()
	movements := []Movement{}
	for rows.Next() {
		m, err := scanMovement(rows)
		if err != nil {
			return nil, fmt.Errorf("store: movements: %w", err)
		}
		movements = append(movements, m)
	}
	return movements, rows.Err()
}

func scanMovement(row scanner) (Movement, error) {
	var (
		m                            Movement
		requestID, recipient, author sql.NullString
		brand, colorCode, colorName  sql.NullString
		createdAt                    string
	)
	if err := row.Scan(&m.ID, &requestID, &m.Kind, &m.LotID, &m.EAN, &m.DyeLot, &m.Quantity,
		&recipient, &author, &createdAt, &brand, &colorCode, &colorName); err != nil {
		return Movement{}, err
	}
	m.RequestID, m.Recipient, m.Author = optional(requestID), optional(recipient), optional(author)
	m.Brand, m.ColorCode, m.ColorName = optional(brand), optional(colorCode), optional(colorName)
	m.CreatedAt = parseTime(createdAt)
	return m, nil
}
