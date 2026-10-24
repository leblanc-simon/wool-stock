package web

import (
	"errors"
	"net/http"
	"sort"
	"strconv"

	"golang.org/x/text/collate"

	"leblanc.io/wool-stock/backend/internal/store"
)

type inventoriesView struct {
	Inventories []store.InventorySummary
}

func (a *App) inventories(w http.ResponseWriter, r *http.Request) {
	list, err := a.Store.Inventories(r.Context())
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	a.render(w, r, http.StatusOK, "inventories", "inventories", "inventories.title", inventoriesView{Inventories: list})
}

// inventoryView est le détail d'un inventaire : les anomalies d'abord, puis les bains conformes.
type inventoryView struct {
	store.Inventory
	// Anomalies sont les bains dont la quantité comptée diffère du stock connu.
	Anomalies []store.InventoryCount
	Conform   []store.InventoryCount
	Counted   int
	Zeroed    int
	Net       int
	// Uncounted, pour un inventaire complet en cours, compte les bains qui seront remis à 0.
	Uncounted int
}

func (a *App) inventory(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		a.notFound(w, r)
		return
	}
	inv, err := a.Store.Inventory(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		a.notFound(w, r)
		return
	}
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	counts, err := a.Store.InventoryCounts(r.Context(), id)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	view := splitCounts(counts, collate.New(a.I18n.FromRequest(r).Lang(), collate.IgnoreCase, collate.Loose))
	view.Inventory = inv
	if inv.ClosedAt == nil && inv.Scope == store.ScopeFull {
		if view.Uncounted, err = a.Store.UncountedLots(r.Context(), id); err != nil {
			a.serverError(w, r, err)
			return
		}
	}
	a.render(w, r, http.StatusOK, "inventory", "inventories", "inventories.title", view)
}

// splitCounts sépare anomalies et bains conformes, triés par marque, coloris puis bain.
func splitCounts(counts []store.InventoryCount, col *collate.Collator) inventoryView {
	sort.SliceStable(counts, func(i, j int) bool {
		a, b := counts[i], counts[j]
		return itemLess(col,
			itemGroup{EAN: a.EAN, DyeLot: a.DyeLot, Brand: a.Brand, ColorCode: a.ColorCode, ColorName: a.ColorName},
			itemGroup{EAN: b.EAN, DyeLot: b.DyeLot, Brand: b.Brand, ColorCode: b.ColorCode, ColorName: b.ColorName})
	})
	var view inventoryView
	for _, c := range counts {
		if c.Zeroed {
			view.Zeroed++
		} else {
			view.Counted++
		}
		view.Net += c.Gap()
		if c.Gap() != 0 {
			view.Anomalies = append(view.Anomalies, c)
		} else {
			view.Conform = append(view.Conform, c)
		}
	}
	return view
}

func (a *App) notFound(w http.ResponseWriter, r *http.Request) {
	a.render(w, r, http.StatusNotFound, "error", "", "error.page_not_found", "error.page_not_found")
}
