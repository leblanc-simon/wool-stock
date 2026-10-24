package web

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"leblanc.io/wool-stock/backend/internal/store"
)

// entryForm est le formulaire d'une entrée : le bain, le nombre de pelotes et, pour le
// cache des codes-barres, la marque et le coloris.
type entryForm struct {
	EAN       string
	DyeLot    string
	Quantity  string
	Brand     string
	ColorCode string
	ColorName string
	// Errors est indexé par champ (ean, dye_lot, quantity, brand, color_code, color_name).
	Errors formErrors
}

// readEntryForm lit et valide une entrée comme POST /api/movements. Marque et coloris
// tous vides laissent le cache tel quel ; sinon ils le remplacent, un champ vide étant effacé.
func readEntryForm(r *http.Request) (entryForm, store.MovementInput) {
	f := entryForm{
		EAN:       strings.TrimSpace(r.PostFormValue("ean")),
		DyeLot:    strings.TrimSpace(r.PostFormValue("dye_lot")),
		Quantity:  strings.TrimSpace(r.PostFormValue("quantity")),
		Brand:     strings.TrimSpace(r.PostFormValue("brand")),
		ColorCode: strings.TrimSpace(r.PostFormValue("color_code")),
		ColorName: strings.TrimSpace(r.PostFormValue("color_name")),
	}
	in := store.MovementInput{Kind: store.KindIn}
	in.EAN = formEAN(&f.Errors, "ean", f.EAN)
	in.DyeLot = formText(&f.Errors, "dye_lot", f.DyeLot, store.MaxDyeLot)
	in.Quantity = formQuantity(&f.Errors, "quantity", f.Quantity, 1, "validation.quantity")
	brand := formText(&f.Errors, "brand", f.Brand, store.MaxText)
	code := formText(&f.Errors, "color_code", f.ColorCode, store.MaxText)
	name := formText(&f.Errors, "color_name", f.ColorName, store.MaxText)
	if brand != "" || code != "" || name != "" {
		in.Reference = &store.ReferenceFields{Brand: optionalText(brand), ColorCode: optionalText(code), ColorName: optionalText(name)}
	}
	in.RequestID = formRequestID(r, string(in.Kind), in.EAN, in.DyeLot, strconv.Itoa(in.Quantity), brand, code, name)
	return f, in
}

// entriesView est l'historique des entrées et le formulaire d'ajout.
type entriesView struct {
	period
	Movements []store.Movement
	Total     int
	Truncated bool
	Limit     int
	Form      entryForm
	// References et Brands alimentent les suggestions des champs EAN et Marque.
	References []store.Reference
	Brands     []string
	// Added est l'entrée qui vient d'être enregistrée, Lot son bain après l'entrée.
	Added *store.Movement
	Lot   *store.StockItem
}

func (a *App) entries(w http.ResponseWriter, r *http.Request) {
	a.renderEntries(w, r, http.StatusOK, entryForm{})
}

func (a *App) renderEntries(w http.ResponseWriter, r *http.Request, status int, form entryForm) {
	p := a.parsePeriod(r)
	list, truncated, err := a.movements(r, store.KindIn, p)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	refs, err := a.Store.References(r.Context())
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	view := entriesView{period: p, Movements: list, Truncated: truncated, Limit: movementLimit,
		Form: form, References: refs, Brands: knownBrands(refs)}
	for _, m := range list {
		view.Total += m.Quantity
	}
	if id, err := strconv.ParseInt(r.URL.Query().Get("added"), 10, 64); err == nil {
		if view.Added = findMovement(list, id); view.Added != nil {
			items, err := a.Store.Stock(r.Context(), store.StockFilter{EAN: view.Added.EAN, IncludeEmpty: true})
			if err != nil {
				a.serverError(w, r, err)
				return
			}
			if lot, ok := findLot(items, view.Added.LotID); ok {
				view.Lot = &lot
			}
		}
	}
	a.render(w, r, status, "entries", "entries", "entries.title", view)
}

// createEntry ajoute des pelotes au stock, au nom du compte connecté.
func (a *App) createEntry(w http.ResponseWriter, r *http.Request) {
	form, in := readEntryForm(r)
	if len(form.Errors) > 0 {
		a.renderEntries(w, r, http.StatusUnprocessableEntity, form)
		return
	}
	in.Author = currentUser(r).Name
	movement, _, _, err := a.Store.Record(r.Context(), in)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	http.Redirect(w, r, "/entries?added="+strconv.FormatInt(movement.ID, 10), http.StatusSeeOther)
}

// exitForm est le formulaire d'une sortie : un bain en stock, combien, pour qui.
type exitForm struct {
	Lot       int64
	Quantity  string
	Recipient string
	// Errors est indexé par champ (lot, quantity, recipient).
	Errors formErrors
}

// readExitForm lit et valide une sortie comme POST /api/movements ; le bain est choisi
// parmi stock, et une sortie ne touche jamais au cache des références.
func readExitForm(r *http.Request, stock []store.StockItem) (exitForm, store.MovementInput) {
	f := exitForm{
		Quantity:  strings.TrimSpace(r.PostFormValue("quantity")),
		Recipient: strings.TrimSpace(r.PostFormValue("recipient")),
	}
	in := store.MovementInput{Kind: store.KindOut}
	f.Lot, _ = strconv.ParseInt(r.PostFormValue("lot"), 10, 64)
	if lot, ok := findLot(stock, f.Lot); ok {
		in.EAN, in.DyeLot = lot.EAN, lot.DyeLot
	} else {
		f.Errors.fail("lot", "validation.lot_required", nil)
	}
	in.Quantity = formQuantity(&f.Errors, "quantity", f.Quantity, 1, "validation.quantity")
	in.Recipient = formText(&f.Errors, "recipient", f.Recipient, store.MaxText)
	if in.Recipient == "" && f.Errors["recipient"] == nil {
		f.Errors.fail("recipient", "validation.recipient_required", nil)
	}
	in.RequestID = formRequestID(r, string(in.Kind), strconv.FormatInt(f.Lot, 10), strconv.Itoa(in.Quantity), in.Recipient)
	return f, in
}

// exitsView est l'historique des sorties et le formulaire de sortie.
type exitsView struct {
	period
	Recipients []recipientGroup
	Total      int
	Truncated  bool
	Limit      int
	Form       exitForm
	// Shelf est ce qui peut sortir : les bains qui ont des pelotes, et le bain choisi.
	Shelf []shelfBrand
	// Taken est la sortie qui vient d'être enregistrée, Left ce qui reste de son bain.
	Taken *store.Movement
	Left  int
}

func (a *App) exits(w http.ResponseWriter, r *http.Request) {
	a.renderExits(w, r, http.StatusOK, exitForm{})
}

func (a *App) renderExits(w http.ResponseWriter, r *http.Request, status int, form exitForm) {
	p := a.parsePeriod(r)
	list, truncated, err := a.movements(r, store.KindOut, p)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	items, err := a.Store.Stock(r.Context(), store.StockFilter{IncludeEmpty: true})
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	view := exitsView{period: p, Truncated: truncated, Limit: movementLimit, Form: form,
		Recipients: groupExits(list, a.Location, a.I18n.FromRequest(r).Lang())}
	for _, m := range list {
		view.Total += m.Quantity
	}
	var inStock []store.StockItem
	for _, item := range items {
		// Le bain choisi reste proposé même épuisé : son message d'erreur garde son contexte.
		if item.Quantity > 0 || item.LotID == form.Lot {
			inStock = append(inStock, item)
		}
	}
	view.Shelf = groupShelf(inStock, nil)
	if id, err := strconv.ParseInt(r.URL.Query().Get("taken"), 10, 64); err == nil {
		if view.Taken = findMovement(list, id); view.Taken != nil {
			lot, _ := findLot(items, view.Taken.LotID)
			view.Left = lot.Quantity
		}
	}
	a.render(w, r, status, "exits", "exits", "exits.title", view)
}

// createExit sort des pelotes d'un bain, au nom du compte connecté ; au-delà du stock
// du bain, la sortie est refusée et le formulaire dit combien il en reste.
func (a *App) createExit(w http.ResponseWriter, r *http.Request) {
	stock, err := a.Store.Stock(r.Context(), store.StockFilter{IncludeEmpty: true})
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	form, in := readExitForm(r, stock)
	if len(form.Errors) > 0 {
		a.renderExits(w, r, http.StatusUnprocessableEntity, form)
		return
	}
	in.Author = currentUser(r).Name
	movement, _, _, err := a.Store.Record(r.Context(), in)
	var insufficient *store.InsufficientStockError
	if errors.As(err, &insufficient) {
		form.Errors.failCount("quantity", "error.insufficient_stock", insufficient.Available)
		a.renderExits(w, r, http.StatusConflict, form)
		return
	}
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	http.Redirect(w, r, "/exits?taken="+strconv.FormatInt(movement.ID, 10), http.StatusSeeOther)
}

// findMovement cherche un mouvement par son identifiant ; nil s'il n'est pas dans la liste.
func findMovement(list []store.Movement, id int64) *store.Movement {
	for i := range list {
		if list[i].ID == id {
			return &list[i]
		}
	}
	return nil
}

// knownBrands donne les marques du cache, une fois chacune, dans l'ordre du store.
func knownBrands(refs []store.Reference) []string {
	var brands []string
	seen := map[string]int{}
	for _, ref := range refs {
		brand := strings.TrimSpace(deref(ref.Brand))
		if brand == "" {
			continue
		}
		i, ok := seen[strings.ToLower(brand)]
		if !ok {
			seen[strings.ToLower(brand)] = len(brands)
			brands = append(brands, brand)
			continue
		}
		if startsUpper(brand) && !startsUpper(brands[i]) {
			brands[i] = brand
		}
	}
	return brands
}
