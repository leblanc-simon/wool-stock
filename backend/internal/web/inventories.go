package web

import (
	"errors"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"golang.org/x/text/collate"

	"leblanc.io/wool-stock/backend/internal/store"
)

type inventoriesView struct {
	Inventories []store.InventorySummary
	// Open est l'inventaire en cours, partagé avec les téléphones ; nil s'il n'y en a pas.
	Open *store.InventorySummary
	Form startForm
}

// startForm lance un inventaire ; Errors est indexé par champ (scope) ou "form".
type startForm struct {
	Scope  store.Scope
	Errors formErrors
}

func (a *App) inventories(w http.ResponseWriter, r *http.Request) {
	a.renderInventories(w, r, http.StatusOK, startForm{Scope: store.ScopePartial})
}

func (a *App) renderInventories(w http.ResponseWriter, r *http.Request, status int, form startForm) {
	list, err := a.Store.Inventories(r.Context())
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	view := inventoriesView{Inventories: list, Form: form}
	for i := range list {
		if list[i].ClosedAt == nil {
			view.Open = &list[i]
		}
	}
	a.render(w, r, status, "inventories", "inventories", "inventories.title", view)
}

// startInventory lance un inventaire, partiel ou complet, comme POST /api/inventories :
// un seul est ouvert à la fois. Sa feuille de comptage s'ouvre ensuite.
func (a *App) startInventory(w http.ResponseWriter, r *http.Request) {
	// À la différence de l'API, le formulaire fait choisir la portée : un inventaire
	// complet remet des bains à 0.
	scope, ok := store.ParseScope(r.PostFormValue("scope"))
	form := startForm{Scope: scope}
	if !ok {
		form.Errors.fail("scope", "validation.scope_required", nil)
		a.renderInventories(w, r, http.StatusUnprocessableEntity, form)
		return
	}
	inv, err := a.Store.StartInventory(r.Context(), currentUser(r).Name, form.Scope)
	if errors.Is(err, store.ErrInventoryOpen) {
		form.Errors.fail("form", "error.inventory_open", nil)
		a.renderInventories(w, r, http.StatusConflict, form)
		return
	}
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	http.Redirect(w, r, countPath(inv.ID), http.StatusSeeOther)
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
	// Finished annonce une clôture qui vient d'être faite ; Error est une clé de traduction.
	Finished bool
	Error    string
}

func (a *App) inventory(w http.ResponseWriter, r *http.Request) {
	inv, ok := a.loadInventory(w, r)
	if !ok {
		return
	}
	a.renderInventory(w, r, http.StatusOK, inv, "")
}

func (a *App) renderInventory(w http.ResponseWriter, r *http.Request, status int, inv store.Inventory, errKey string) {
	counts, err := a.Store.InventoryCounts(r.Context(), inv.ID)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	view := splitCounts(counts, collate.New(a.I18n.FromRequest(r).Lang(), collate.IgnoreCase, collate.Loose))
	view.Inventory = inv
	view.Finished = r.URL.Query().Has("closed")
	view.Error = errKey
	if inv.ClosedAt == nil && inv.Scope == store.ScopeFull {
		if view.Uncounted, err = a.Store.UncountedLots(r.Context(), inv.ID); err != nil {
			a.serverError(w, r, err)
			return
		}
	}
	a.render(w, r, status, "inventory", "inventories", "inventories.title", view)
}

// loadInventory lit l'inventaire de l'adresse ; 404 s'il n'existe pas.
func (a *App) loadInventory(w http.ResponseWriter, r *http.Request) (store.Inventory, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		a.notFound(w, r)
		return store.Inventory{}, false
	}
	inv, err := a.Store.Inventory(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		a.notFound(w, r)
		return inv, false
	}
	if err != nil {
		a.serverError(w, r, err)
		return inv, false
	}
	return inv, true
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

// countView est la feuille de comptage d'un inventaire en cours : chaque bain, par
// marque puis coloris, avec son stock connu et son comptage s'il est déjà compté.
type countView struct {
	store.Inventory
	Brands []shelfBrand
	// Lots est le nombre de bains de la feuille, Counted ceux déjà comptés.
	Lots    int
	Counted int
	// Uncounted, pour un inventaire complet, compte les bains en stock qui seront remis à 0.
	Uncounted int
	// Empty compte les bains épuisés pas encore comptés, affichés seulement avec ShowEmpty.
	Empty     int
	ShowEmpty bool
	// Saved est le bain dont le comptage vient d'être enregistré.
	Saved int64
	// Form est un comptage refusé, repris sur son bain.
	Form countForm
}

// countForm est le comptage d'un bain ; Errors est indexé par champ (quantity) ou "form".
type countForm struct {
	Lot      int64
	Quantity string
	Errors   formErrors
}

// countSheet affiche la feuille de comptage ; un inventaire terminé renvoie à son détail.
func (a *App) countSheet(w http.ResponseWriter, r *http.Request) {
	inv, ok := a.loadInventory(w, r)
	if !ok {
		return
	}
	if inv.ClosedAt != nil {
		http.Redirect(w, r, inventoryPath(inv.ID), http.StatusSeeOther)
		return
	}
	saved, _ := strconv.ParseInt(r.URL.Query().Get("counted"), 10, 64)
	a.renderCount(w, r, http.StatusOK, inv, countForm{}, saved)
}

func (a *App) renderCount(w http.ResponseWriter, r *http.Request, status int, inv store.Inventory, form countForm, saved int64) {
	items, err := a.Store.Stock(r.Context(), store.StockFilter{IncludeEmpty: true})
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	counts, err := a.Store.InventoryCounts(r.Context(), inv.ID)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	byLot := make(map[int64]store.InventoryCount, len(counts))
	for _, c := range counts {
		byLot[c.LotID] = c
	}
	view := countView{Inventory: inv, ShowEmpty: r.FormValue("empty") != "", Saved: saved, Form: form}
	var listed []store.StockItem
	for _, item := range items {
		_, counted := byLot[item.LotID]
		switch {
		case counted:
			view.Counted++
		case item.Quantity > 0:
			if inv.Scope == store.ScopeFull {
				view.Uncounted++
			}
		default:
			view.Empty++
			if !view.ShowEmpty && item.LotID != form.Lot {
				continue
			}
		}
		listed = append(listed, item)
	}
	view.Lots = len(listed)
	view.Brands = groupShelf(listed, byLot)
	a.render(w, r, status, "count", "inventories", "count.title", view)
}

// count enregistre le comptage d'un bain, comme POST /api/inventories/{id}/counts.
// « Valider le stock » envoie le stock affiché, le champ une autre quantité ; un bain
// déjà compté (ici ou depuis un téléphone) se complète (add) ou se recompte (set).
func (a *App) count(w http.ResponseWriter, r *http.Request) {
	inv, ok := a.loadInventory(w, r)
	if !ok {
		return
	}
	if inv.ClosedAt != nil {
		a.renderInventory(w, r, http.StatusConflict, inv, "count.closed")
		return
	}
	stock, err := a.Store.Stock(r.Context(), store.StockFilter{IncludeEmpty: true})
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	form := countForm{Quantity: strings.TrimSpace(r.PostFormValue("quantity"))}
	form.Lot, _ = strconv.ParseInt(r.PostFormValue("lot"), 10, 64)
	in := store.CountInput{InventoryID: inv.ID}
	if lot, ok := findLot(stock, form.Lot); ok {
		in.EAN, in.DyeLot = lot.EAN, lot.DyeLot
	} else {
		form.Errors.fail("form", "validation.lot_required", nil)
	}
	in.Quantity = formQuantity(&form.Errors, "quantity", form.Quantity, 0, "validation.count")
	if in.Mode, ok = store.ParseCountMode(r.PostFormValue("mode")); !ok {
		form.Errors.fail("form", "validation.mode", nil)
	}
	if len(form.Errors) > 0 {
		a.renderCount(w, r, http.StatusUnprocessableEntity, inv, form, 0)
		return
	}
	in.RequestID = formRequestID(r, strconv.FormatInt(form.Lot, 10), strconv.Itoa(in.Quantity), string(in.Mode))
	in.Author = currentUser(r).Name

	_, _, _, err = a.Store.Count(r.Context(), in)
	var already *store.AlreadyCountedError
	switch {
	case errors.As(err, &already):
		form.Errors.failCount("quantity", "error.already_counted", already.Counted)
		a.renderCount(w, r, http.StatusConflict, inv, form, 0)
		return
	case errors.Is(err, store.ErrInventoryClosed):
		// Terminé depuis un téléphone pendant la saisie.
		if inv, err = a.Store.Inventory(r.Context(), inv.ID); err != nil {
			a.serverError(w, r, err)
			return
		}
		a.renderInventory(w, r, http.StatusConflict, inv, "count.closed")
		return
	case err != nil:
		a.serverError(w, r, err)
		return
	}
	// Retour sur le bain compté, à sa place dans la feuille.
	target := countPath(inv.ID) + "?counted=" + strconv.FormatInt(form.Lot, 10)
	if r.PostFormValue("empty") != "" {
		target += "&empty=1"
	}
	http.Redirect(w, r, target+"#lot-"+strconv.FormatInt(form.Lot, 10), http.StatusSeeOther)
}

// closeInventory termine un inventaire, comme POST /api/inventories/{id}/close : les
// bains non comptés gardent leur stock, sauf pour un inventaire complet où ils passent à 0.
func (a *App) closeInventory(w http.ResponseWriter, r *http.Request) {
	inv, ok := a.loadInventory(w, r)
	if !ok {
		return
	}
	_, err := a.Store.CloseInventory(r.Context(), inv.ID, currentUser(r).Name)
	if errors.Is(err, store.ErrInventoryClosed) {
		a.renderInventory(w, r, http.StatusConflict, inv, "error.inventory_closed")
		return
	}
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	http.Redirect(w, r, inventoryPath(inv.ID)+"?closed=1", http.StatusSeeOther)
}

func inventoryPath(id int64) string {
	return "/inventories/" + strconv.FormatInt(id, 10)
}

func countPath(id int64) string {
	return inventoryPath(id) + "/count"
}

func (a *App) notFound(w http.ResponseWriter, r *http.Request) {
	a.render(w, r, http.StatusNotFound, "error", "", "error.page_not_found", "error.page_not_found")
}
