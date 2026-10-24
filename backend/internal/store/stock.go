package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// StockItem est un bain d'une référence et sa quantité, avec la marque et le coloris en cache.
type StockItem struct {
	LotID     int64     `json:"lot_id"`
	EAN       string    `json:"ean"`
	DyeLot    string    `json:"dye_lot"`
	Quantity  int       `json:"quantity"`
	UpdatedAt time.Time `json:"updated_at"`
	Brand     *string   `json:"brand"`
	ColorCode *string   `json:"color_code"`
	ColorName *string   `json:"color_name"`
}

// StockFilter restreint la liste du stock.
type StockFilter struct {
	// EAN limite la liste aux bains d'une référence.
	EAN string
	// IncludeEmpty garde les bains épuisés.
	IncludeEmpty bool
}

const stockQuery = `SELECT l.id, l.ean, l.dye_lot, l.quantity, l.updated_at, r.brand, r.color_code, r.color_name
	FROM lots l JOIN yarn_references r ON r.ean = l.ean`

// Stock renvoie les bains en stock, triés par marque, coloris, référence puis bain.
func (s *Store) Stock(ctx context.Context, filter StockFilter) ([]StockItem, error) {
	var (
		where []string
		args  []any
	)
	if filter.EAN != "" {
		where = append(where, "l.ean = ?")
		args = append(args, filter.EAN)
	}
	if !filter.IncludeEmpty {
		where = append(where, "l.quantity > 0")
	}
	query := stockQuery
	if len(where) > 0 {
		query += " WHERE " + strings.Join(where, " AND ")
	}
	query += ` ORDER BY r.brand IS NULL, r.brand COLLATE NOCASE, r.color_code, r.color_name COLLATE NOCASE, l.ean, l.dye_lot`

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("store: stock: %w", err)
	}
	defer rows.Close()
	items := []StockItem{}
	for rows.Next() {
		item, err := scanStockItem(rows)
		if err != nil {
			return nil, fmt.Errorf("store: stock: %w", err)
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func stockItem(ctx context.Context, q querier, lotID int64) (StockItem, error) {
	item, err := scanStockItem(q.QueryRowContext(ctx, stockQuery+` WHERE l.id = ?`, lotID))
	if errors.Is(err, sql.ErrNoRows) {
		return StockItem{}, ErrNotFound
	}
	if err != nil {
		return StockItem{}, fmt.Errorf("store: lot: %w", err)
	}
	return item, nil
}

func scanStockItem(row scanner) (StockItem, error) {
	var (
		item                        StockItem
		updatedAt                   string
		brand, colorCode, colorName sql.NullString
	)
	if err := row.Scan(&item.LotID, &item.EAN, &item.DyeLot, &item.Quantity, &updatedAt,
		&brand, &colorCode, &colorName); err != nil {
		return StockItem{}, err
	}
	item.UpdatedAt = parseTime(updatedAt)
	item.Brand, item.ColorCode, item.ColorName = optional(brand), optional(colorCode), optional(colorName)
	return item, nil
}
