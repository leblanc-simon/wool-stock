package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// ErrInventoryOpen signale qu'un inventaire est déjà en cours.
var ErrInventoryOpen = errors.New("store: an inventory is already open")

// ErrInventoryClosed signale un inventaire déjà terminé.
var ErrInventoryClosed = errors.New("store: inventory closed")

// Scope dit si un inventaire porte sur tout le stock.
type Scope string

const (
	// ScopePartial ne change que les bains comptés.
	ScopePartial Scope = "partial"
	// ScopeFull remet à 0, à la clôture, les bains en stock qui n'ont pas été comptés.
	ScopeFull Scope = "full"
)

// Inventory est une session de comptage du stock.
type Inventory struct {
	ID        int64      `json:"id"`
	Scope     Scope      `json:"scope"`
	StartedAt time.Time  `json:"started_at"`
	StartedBy *string    `json:"started_by"`
	ClosedAt  *time.Time `json:"closed_at"`
	ClosedBy  *string    `json:"closed_by"`
}

// InventoryCount est la quantité comptée d'un bain pendant un inventaire.
type InventoryCount struct {
	LotID     int64   `json:"lot_id"`
	EAN       string  `json:"ean"`
	DyeLot    string  `json:"dye_lot"`
	Brand     *string `json:"brand"`
	ColorCode *string `json:"color_code"`
	ColorName *string `json:"color_name"`
	Quantity  int     `json:"quantity"`
	// PreviousQuantity est le stock du bain avant son premier comptage.
	PreviousQuantity int `json:"previous_quantity"`
	// Zeroed : bain non compté d'un inventaire complet, remis à 0 à la clôture.
	Zeroed    bool      `json:"zeroed"`
	UpdatedAt time.Time `json:"updated_at"`
}

// Gap est l'écart entre la quantité comptée et le stock connu : négatif s'il manque des pelotes.
func (c InventoryCount) Gap() int {
	return c.Quantity - c.PreviousQuantity
}

// InventorySummary résume un inventaire pour la liste des inventaires.
type InventorySummary struct {
	Inventory
	// Counted est le nombre de bains comptés (hors bains remis à 0 sans comptage).
	Counted int
	// Zeroed est le nombre de bains remis à 0 à la clôture d'un inventaire complet.
	Zeroed int
	// Anomalies est le nombre de bains dont la quantité diffère du stock connu.
	Anomalies int
	// Net est la somme des écarts, en pelotes.
	Net int
}

// CountMode dit comment un comptage se combine à un comptage déjà fait du même bain.
type CountMode string

const (
	// CountNew est un premier comptage ; refusé si le bain est déjà compté.
	CountNew CountMode = "new"
	// CountAdd ajoute la quantité au comptage déjà fait (pelotes trouvées ailleurs).
	CountAdd CountMode = "add"
	// CountSet remplace le comptage déjà fait (bain recompté).
	CountSet CountMode = "set"
)

// CountInput est un comptage à appliquer, déjà validé.
type CountInput struct {
	RequestID   string
	InventoryID int64
	EAN         string
	DyeLot      string
	Quantity    int
	Mode        CountMode
	Author      string
	// Reference remplace la marque et le coloris en cache, comme une entrée.
	Reference *ReferenceFields
}

// AlreadyCountedError signale un bain déjà compté dans l'inventaire : le client
// demande alors s'il faut ajouter (CountAdd) ou remplacer (CountSet).
type AlreadyCountedError struct {
	Counted int
}

func (e *AlreadyCountedError) Error() string {
	return fmt.Sprintf("store: dye lot already counted (%d)", e.Counted)
}

const inventoryColumns = `id, scope, started_at, started_by, closed_at, closed_by`

// StartInventory ouvre un inventaire, ou renvoie ErrInventoryOpen s'il y en a déjà un.
func (s *Store) StartInventory(ctx context.Context, author string, scope Scope) (Inventory, error) {
	var inv Inventory
	err := s.tx(ctx, func(tx *sql.Tx) error {
		var open int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM inventories WHERE closed_at IS NULL`).Scan(&open); err != nil {
			return err
		}
		if open > 0 {
			return ErrInventoryOpen
		}
		res, err := tx.ExecContext(ctx, `INSERT INTO inventories (scope, started_at, started_by) VALUES (?, ?, ?)`,
			scope, s.timestamp(), nullable(author))
		if err != nil {
			return fmt.Errorf("store: start inventory: %w", err)
		}
		id, err := res.LastInsertId()
		if err != nil {
			return err
		}
		inv, err = inventory(ctx, tx, id)
		return err
	})
	return inv, err
}

// CurrentInventory renvoie l'inventaire en cours, ou ErrNotFound.
func (s *Store) CurrentInventory(ctx context.Context) (Inventory, error) {
	inv, err := scanInventory(s.db.QueryRowContext(ctx,
		`SELECT `+inventoryColumns+` FROM inventories WHERE closed_at IS NULL`))
	if errors.Is(err, sql.ErrNoRows) {
		return Inventory{}, ErrNotFound
	}
	if err != nil {
		return Inventory{}, fmt.Errorf("store: current inventory: %w", err)
	}
	return inv, nil
}

// Inventory renvoie un inventaire, ou ErrNotFound.
func (s *Store) Inventory(ctx context.Context, id int64) (Inventory, error) {
	return inventory(ctx, s.db, id)
}

// CloseInventory termine un inventaire. Les bains non comptés gardent leur quantité,
// sauf pour un inventaire complet : ceux qui ont du stock sont remis à 0, et l'écart
// est gardé comme un comptage (zeroed).
func (s *Store) CloseInventory(ctx context.Context, id int64, author string) (Inventory, error) {
	var inv Inventory
	err := s.tx(ctx, func(tx *sql.Tx) error {
		current, err := inventory(ctx, tx, id)
		if err != nil {
			return err
		}
		if current.ClosedAt != nil {
			return ErrInventoryClosed
		}
		now := s.timestamp()
		if current.Scope == ScopeFull {
			if _, err := tx.ExecContext(ctx, `INSERT INTO inventory_counts
				(inventory_id, lot_id, quantity, previous_quantity, zeroed, updated_at)
				SELECT ?, l.id, 0, l.quantity, 1, ? FROM lots l
				WHERE l.quantity > 0 AND NOT EXISTS (
					SELECT 1 FROM inventory_counts c WHERE c.inventory_id = ? AND c.lot_id = l.id)`,
				id, now, id); err != nil {
				return fmt.Errorf("store: close inventory: %w", err)
			}
			if _, err := tx.ExecContext(ctx, `UPDATE lots SET quantity = 0, updated_at = ?
				WHERE id IN (SELECT lot_id FROM inventory_counts WHERE inventory_id = ? AND zeroed = 1)`,
				now, id); err != nil {
				return fmt.Errorf("store: close inventory: %w", err)
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE inventories SET closed_at = ?, closed_by = ? WHERE id = ?`,
			now, nullable(author), id); err != nil {
			return fmt.Errorf("store: close inventory: %w", err)
		}
		inv, err = inventory(ctx, tx, id)
		return err
	})
	return inv, err
}

// UncountedLots compte les bains en stock pas encore comptés dans l'inventaire : ceux
// qu'un inventaire complet remettra à 0 à la clôture.
func (s *Store) UncountedLots(ctx context.Context, id int64) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM lots l WHERE l.quantity > 0 AND NOT EXISTS (
		SELECT 1 FROM inventory_counts c WHERE c.inventory_id = ? AND c.lot_id = l.id)`, id).Scan(&n)
	if err != nil {
		return 0, fmt.Errorf("store: uncounted lots: %w", err)
	}
	return n, nil
}

// Inventories renvoie tous les inventaires, du plus récent au plus ancien.
func (s *Store) Inventories(ctx context.Context) ([]InventorySummary, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT i.id, i.scope, i.started_at, i.started_by, i.closed_at, i.closed_by,
		COALESCE(SUM(c.zeroed = 0), 0), COALESCE(SUM(c.zeroed), 0),
		COALESCE(SUM(c.quantity <> c.previous_quantity), 0), COALESCE(SUM(c.quantity - c.previous_quantity), 0)
		FROM inventories i LEFT JOIN inventory_counts c ON c.inventory_id = i.id
		GROUP BY i.id ORDER BY i.id DESC`)
	if err != nil {
		return nil, fmt.Errorf("store: inventories: %w", err)
	}
	defer rows.Close()
	list := []InventorySummary{}
	for rows.Next() {
		var sum InventorySummary
		inv, err := scanInventory(withExtra{rows, []any{&sum.Counted, &sum.Zeroed, &sum.Anomalies, &sum.Net}})
		if err != nil {
			return nil, fmt.Errorf("store: inventories: %w", err)
		}
		sum.Inventory = inv
		list = append(list, sum)
	}
	return list, rows.Err()
}

// withExtra ajoute des colonnes à la fin du Scan d'une ligne.
type withExtra struct {
	row   scanner
	extra []any
}

func (w withExtra) Scan(dest ...any) error {
	return w.row.Scan(append(dest, w.extra...)...)
}

const countQuery = `SELECT c.lot_id, l.ean, l.dye_lot, r.brand, r.color_code, r.color_name,
	c.quantity, c.previous_quantity, c.zeroed, c.updated_at
	FROM inventory_counts c JOIN lots l ON l.id = c.lot_id JOIN yarn_references r ON r.ean = l.ean`

// InventoryCounts renvoie les bains comptés d'un inventaire, du plus récent au plus ancien.
func (s *Store) InventoryCounts(ctx context.Context, id int64) ([]InventoryCount, error) {
	rows, err := s.db.QueryContext(ctx, countQuery+` WHERE c.inventory_id = ? ORDER BY c.updated_at DESC, c.lot_id DESC`, id)
	if err != nil {
		return nil, fmt.Errorf("store: inventory counts: %w", err)
	}
	defer rows.Close()
	counts := []InventoryCount{}
	for rows.Next() {
		c, err := scanCount(rows)
		if err != nil {
			return nil, fmt.Errorf("store: inventory counts: %w", err)
		}
		counts = append(counts, c)
	}
	return counts, rows.Err()
}

// Count enregistre un comptage et met à jour le stock du bain : un premier
// comptage ou un remplacement fixe la quantité, un ajout l'augmente. Un RequestID
// déjà enregistré renvoie le résultat sans rien rejouer (replayed vaut true).
func (s *Store) Count(ctx context.Context, in CountInput) (count InventoryCount, lot StockItem, replayed bool, err error) {
	err = s.tx(ctx, func(tx *sql.Tx) error {
		if in.RequestID != "" {
			var inventoryID, lotID int64
			err := tx.QueryRowContext(ctx, `SELECT inventory_id, lot_id FROM inventory_scans WHERE request_id = ?`,
				in.RequestID).Scan(&inventoryID, &lotID)
			if err == nil {
				replayed = true
				return s.readCount(ctx, tx, inventoryID, lotID, &count, &lot)
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return fmt.Errorf("store: replay count: %w", err)
			}
		}

		inv, err := inventory(ctx, tx, in.InventoryID)
		if err != nil {
			return err
		}
		if inv.ClosedAt != nil {
			return ErrInventoryClosed
		}

		if in.Reference != nil {
			err = s.saveReference(ctx, tx, in.EAN, *in.Reference)
		} else {
			err = s.ensureReference(ctx, tx, in.EAN)
		}
		if err != nil {
			return err
		}
		now := s.timestamp()
		if _, err := tx.ExecContext(ctx, `INSERT INTO lots (ean, dye_lot, quantity, updated_at) VALUES (?, ?, 0, ?)
			ON CONFLICT (ean, dye_lot) DO NOTHING`, in.EAN, in.DyeLot, now); err != nil {
			return fmt.Errorf("store: count lot: %w", err)
		}
		var lotID int64
		var stock int
		if err := tx.QueryRowContext(ctx, `SELECT id, quantity FROM lots WHERE ean = ? AND dye_lot = ?`,
			in.EAN, in.DyeLot).Scan(&lotID, &stock); err != nil {
			return fmt.Errorf("store: count lot: %w", err)
		}

		var counted int
		err = tx.QueryRowContext(ctx, `SELECT quantity FROM inventory_counts WHERE inventory_id = ? AND lot_id = ?`,
			in.InventoryID, lotID).Scan(&counted)
		alreadyCounted := err == nil
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return fmt.Errorf("store: count: %w", err)
		}

		switch {
		case !alreadyCounted:
			// Premier comptage du bain, quel que soit le mode : il fixe le stock.
			_, err = tx.ExecContext(ctx, `INSERT INTO inventory_counts
				(inventory_id, lot_id, quantity, previous_quantity, updated_at) VALUES (?, ?, ?, ?, ?)`,
				in.InventoryID, lotID, in.Quantity, stock, now)
			if err == nil {
				_, err = tx.ExecContext(ctx, `UPDATE lots SET quantity = ?, updated_at = ? WHERE id = ?`, in.Quantity, now, lotID)
			}
		case in.Mode == CountAdd:
			_, err = tx.ExecContext(ctx, `UPDATE inventory_counts SET quantity = quantity + ?, updated_at = ?
				WHERE inventory_id = ? AND lot_id = ?`, in.Quantity, now, in.InventoryID, lotID)
			if err == nil {
				_, err = tx.ExecContext(ctx, `UPDATE lots SET quantity = quantity + ?, updated_at = ? WHERE id = ?`, in.Quantity, now, lotID)
			}
		case in.Mode == CountSet:
			_, err = tx.ExecContext(ctx, `UPDATE inventory_counts SET quantity = ?, updated_at = ?
				WHERE inventory_id = ? AND lot_id = ?`, in.Quantity, now, in.InventoryID, lotID)
			if err == nil {
				_, err = tx.ExecContext(ctx, `UPDATE lots SET quantity = ?, updated_at = ? WHERE id = ?`, in.Quantity, now, lotID)
			}
		default:
			return &AlreadyCountedError{Counted: counted}
		}
		if err != nil {
			return fmt.Errorf("store: count: %w", err)
		}

		mode := in.Mode
		if !alreadyCounted {
			mode = CountNew
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO inventory_scans
			(request_id, inventory_id, lot_id, mode, quantity, author, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
			nullable(in.RequestID), in.InventoryID, lotID, mode, in.Quantity, nullable(in.Author), now); err != nil {
			return fmt.Errorf("store: count scan: %w", err)
		}
		return s.readCount(ctx, tx, in.InventoryID, lotID, &count, &lot)
	})
	return count, lot, replayed, err
}

func (s *Store) readCount(ctx context.Context, tx *sql.Tx, inventoryID, lotID int64, count *InventoryCount, lot *StockItem) error {
	c, err := scanCount(tx.QueryRowContext(ctx, countQuery+` WHERE c.inventory_id = ? AND c.lot_id = ?`, inventoryID, lotID))
	if err != nil {
		return fmt.Errorf("store: count: %w", err)
	}
	item, err := stockItem(ctx, tx, lotID)
	if err != nil {
		return err
	}
	*count, *lot = c, item
	return nil
}

func inventory(ctx context.Context, q querier, id int64) (Inventory, error) {
	inv, err := scanInventory(q.QueryRowContext(ctx, `SELECT `+inventoryColumns+` FROM inventories WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return Inventory{}, ErrNotFound
	}
	if err != nil {
		return Inventory{}, fmt.Errorf("store: inventory: %w", err)
	}
	return inv, nil
}

func scanInventory(row scanner) (Inventory, error) {
	var (
		inv                         Inventory
		startedAt                   string
		startedBy, closedAt, closed sql.NullString
	)
	if err := row.Scan(&inv.ID, &inv.Scope, &startedAt, &startedBy, &closedAt, &closed); err != nil {
		return Inventory{}, err
	}
	inv.StartedAt = parseTime(startedAt)
	inv.StartedBy, inv.ClosedBy = optional(startedBy), optional(closed)
	if closedAt.Valid {
		t := parseTime(closedAt.String)
		inv.ClosedAt = &t
	}
	return inv, nil
}

func scanCount(row scanner) (InventoryCount, error) {
	var (
		c                           InventoryCount
		brand, colorCode, colorName sql.NullString
		zeroed                      int
		updatedAt                   string
	)
	if err := row.Scan(&c.LotID, &c.EAN, &c.DyeLot, &brand, &colorCode, &colorName,
		&c.Quantity, &c.PreviousQuantity, &zeroed, &updatedAt); err != nil {
		return InventoryCount{}, err
	}
	c.Zeroed = zeroed == 1
	c.Brand, c.ColorCode, c.ColorName = optional(brand), optional(colorCode), optional(colorName)
	c.UpdatedAt = parseTime(updatedAt)
	return c, nil
}
