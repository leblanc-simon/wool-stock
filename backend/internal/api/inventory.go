package api

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"leblanc.io/wool-stock/backend/internal/store"
)

// startRequest est le corps facultatif du lancement : scope vaut "partial" (défaut) ou "full".
type startRequest struct {
	Scope string `json:"scope"`
}

// inventoryResponse porte un inventaire et ses bains comptés.
type inventoryResponse struct {
	Inventory store.Inventory        `json:"inventory"`
	Counts    []store.InventoryCount `json:"counts"`
	// Uncounted, pour un inventaire en cours, est le nombre de bains en stock pas encore
	// comptés : ceux qu'un inventaire complet remettra à 0.
	Uncounted int `json:"uncounted"`
}

func (a *App) writeInventory(w http.ResponseWriter, r *http.Request, status int, inv store.Inventory) {
	res := inventoryResponse{Inventory: inv}
	var err error
	if res.Counts, err = a.Store.InventoryCounts(r.Context(), inv.ID); err == nil && inv.ClosedAt == nil {
		res.Uncounted, err = a.Store.UncountedLots(r.Context(), inv.ID)
	}
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, status, res)
}

// currentInventory renvoie l'inventaire en cours, ou 404 s'il n'y en a pas.
func (a *App) currentInventory(w http.ResponseWriter, r *http.Request) {
	inv, err := a.Store.CurrentInventory(r.Context())
	if errors.Is(err, store.ErrNotFound) {
		a.fail(w, r, &problem{status: http.StatusNotFound, code: CodeNotFound})
		return
	}
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	a.writeInventory(w, r, http.StatusOK, inv)
}

// startInventory ouvre un inventaire, partiel ou complet ; 409 si un autre est en cours.
func (a *App) startInventory(w http.ResponseWriter, r *http.Request) {
	var req startRequest
	if p := decodeOptional(w, r, &req); p != nil {
		a.fail(w, r, p)
		return
	}
	var scope store.Scope
	switch s := store.Scope(strings.TrimSpace(req.Scope)); s {
	case "", store.ScopePartial:
		scope = store.ScopePartial
	case store.ScopeFull:
		scope = store.ScopeFull
	default:
		a.fail(w, r, badRequest(CodeScope, "scope"))
		return
	}
	inv, err := a.Store.StartInventory(r.Context(), currentUser(r).Name, scope)
	if errors.Is(err, store.ErrInventoryOpen) {
		a.fail(w, r, &problem{status: http.StatusConflict, code: CodeInventoryOpen})
		return
	}
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	a.writeInventory(w, r, http.StatusCreated, inv)
}

func (a *App) getInventory(w http.ResponseWriter, r *http.Request) {
	id, ok := a.inventoryID(w, r)
	if !ok {
		return
	}
	inv, err := a.Store.Inventory(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		a.fail(w, r, &problem{status: http.StatusNotFound, code: CodeNotFound})
		return
	}
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	a.writeInventory(w, r, http.StatusOK, inv)
}

// countRequest est le comptage d'un bain pendant un inventaire.
type countRequest struct {
	RequestID string `json:"request_id"`
	EAN       string `json:"ean"`
	DyeLot    string `json:"dye_lot"`
	// Quantity est obligatoire, et peut valoir 0 : un bain compté vide.
	Quantity *int `json:"quantity"`
	// Mode : "new" (défaut), "add" ou "set" ; voir store.CountMode.
	Mode      string            `json:"mode"`
	Reference *referenceRequest `json:"reference"`
}

func (req countRequest) validate(inventoryID int64) (store.CountInput, *problem) {
	in := store.CountInput{InventoryID: inventoryID}
	var p *problem
	if in.RequestID, p = text(req.RequestID, "request_id", maxRequestID); p != nil {
		return in, p
	}
	if in.EAN, p = parseEAN(req.EAN, "ean"); p != nil {
		return in, p
	}
	if in.DyeLot, p = text(req.DyeLot, "dye_lot", maxDyeLot); p != nil {
		return in, p
	}
	if req.Quantity == nil || *req.Quantity < 0 || *req.Quantity > maxQuantity {
		return in, badRequest(CodeCount, "quantity")
	}
	in.Quantity = *req.Quantity
	switch mode := store.CountMode(strings.TrimSpace(req.Mode)); mode {
	case "", store.CountNew:
		in.Mode = store.CountNew
	case store.CountAdd, store.CountSet:
		in.Mode = mode
	default:
		return in, badRequest(CodeMode, "mode")
	}
	if req.Reference != nil {
		fields, p := req.Reference.validate("reference.")
		if p != nil {
			return in, p
		}
		in.Reference = &fields
	}
	return in, nil
}

// countInventory enregistre un comptage. Un bain déjà compté dans l'inventaire est
// refusé en 409 (error.already_counted, avec counted) tant que le client n'a pas
// choisi "add" ou "set".
func (a *App) countInventory(w http.ResponseWriter, r *http.Request) {
	id, ok := a.inventoryID(w, r)
	if !ok {
		return
	}
	var req countRequest
	if p := decode(w, r, &req); p != nil {
		a.fail(w, r, p)
		return
	}
	in, p := req.validate(id)
	if p != nil {
		a.fail(w, r, p)
		return
	}
	in.Author = currentUser(r).Name
	count, lot, replayed, err := a.Store.Count(r.Context(), in)
	var already *store.AlreadyCountedError
	switch {
	case errors.As(err, &already):
		counted := already.Counted
		a.fail(w, r, &problem{status: http.StatusConflict, code: CodeAlreadyCounted, field: "mode", counted: &counted})
		return
	case errors.Is(err, store.ErrInventoryClosed):
		a.fail(w, r, &problem{status: http.StatusConflict, code: CodeInventoryClosed})
		return
	case errors.Is(err, store.ErrNotFound):
		a.fail(w, r, &problem{status: http.StatusNotFound, code: CodeNotFound})
		return
	case err != nil:
		a.internalError(w, r, err)
		return
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	writeJSON(w, status, map[string]any{"count": count, "lot": lot, "replayed": replayed})
}

// closeInventory termine un inventaire. Les bains non comptés gardent leur quantité, sauf
// pour un inventaire complet : ils sont remis à 0.
func (a *App) closeInventory(w http.ResponseWriter, r *http.Request) {
	id, ok := a.inventoryID(w, r)
	if !ok {
		return
	}
	inv, err := a.Store.CloseInventory(r.Context(), id, currentUser(r).Name)
	switch {
	case errors.Is(err, store.ErrInventoryClosed):
		a.fail(w, r, &problem{status: http.StatusConflict, code: CodeInventoryClosed})
		return
	case errors.Is(err, store.ErrNotFound):
		a.fail(w, r, &problem{status: http.StatusNotFound, code: CodeNotFound})
		return
	case err != nil:
		a.internalError(w, r, err)
		return
	}
	a.writeInventory(w, r, http.StatusOK, inv)
}

// inventoryID lit l'identifiant du chemin ; un identifiant invalide est un inventaire introuvable.
func (a *App) inventoryID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id < 1 {
		a.fail(w, r, &problem{status: http.StatusNotFound, code: CodeNotFound})
		return 0, false
	}
	return id, true
}
