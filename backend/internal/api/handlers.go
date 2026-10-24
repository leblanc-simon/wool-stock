package api

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"leblanc.io/wool-stock/backend/internal/store"
)

const (
	defaultMovementLimit = 50
	maxMovementLimit     = 500
)

func (a *App) health(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.Ping(r.Context()); err != nil {
		a.Logger.Error("health", "err", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable", "version": a.Version})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "version": a.Version})
}

// listReferences renvoie tout le cache des codes-barres : l'app le recopie pour
// préremplir marque et coloris sans attendre le réseau.
func (a *App) listReferences(w http.ResponseWriter, r *http.Request) {
	references, err := a.Store.References(r.Context())
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"references": references})
}

func (a *App) getReference(w http.ResponseWriter, r *http.Request) {
	ean, p := parseEAN(r.PathValue("ean"), "ean")
	if p != nil {
		a.fail(w, r, p)
		return
	}
	ref, err := a.Store.Reference(r.Context(), ean)
	if errors.Is(err, store.ErrNotFound) {
		a.fail(w, r, &problem{status: http.StatusNotFound, code: CodeNotFound})
		return
	}
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, ref)
}

// referenceRequest porte la marque et le coloris d'une référence ; un champ vide ou absent est effacé.
type referenceRequest struct {
	Brand     *string `json:"brand"`
	ColorCode *string `json:"color_code"`
	ColorName *string `json:"color_name"`
}

func (req referenceRequest) validate(prefix string) (store.ReferenceFields, *problem) {
	var (
		fields store.ReferenceFields
		p      *problem
	)
	if fields.Brand, p = optionalText(req.Brand, prefix+"brand", maxText); p != nil {
		return fields, p
	}
	if fields.ColorCode, p = optionalText(req.ColorCode, prefix+"color_code", maxText); p != nil {
		return fields, p
	}
	fields.ColorName, p = optionalText(req.ColorName, prefix+"color_name", maxText)
	return fields, p
}

func (a *App) putReference(w http.ResponseWriter, r *http.Request) {
	ean, p := parseEAN(r.PathValue("ean"), "ean")
	if p != nil {
		a.fail(w, r, p)
		return
	}
	var req referenceRequest
	if p := decode(w, r, &req); p != nil {
		a.fail(w, r, p)
		return
	}
	fields, p := req.validate("")
	if p != nil {
		a.fail(w, r, p)
		return
	}
	ref, err := a.Store.SaveReference(r.Context(), ean, fields)
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, ref)
}

// listStock renvoie les bains en stock ; ?ean= limite à une référence,
// ?include_empty=true garde les bains épuisés.
func (a *App) listStock(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	var filter store.StockFilter
	if raw := query.Get("ean"); raw != "" {
		var p *problem
		if filter.EAN, p = parseEAN(raw, "ean"); p != nil {
			a.fail(w, r, p)
			return
		}
	}
	filter.IncludeEmpty, _ = strconv.ParseBool(query.Get("include_empty"))
	items, err := a.Store.Stock(r.Context(), filter)
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

// listMovements renvoie l'historique, du plus récent au plus ancien ; ?ean= et ?limit= (1 à 500).
func (a *App) listMovements(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	filter := store.MovementFilter{Limit: defaultMovementLimit}
	if raw := query.Get("ean"); raw != "" {
		var p *problem
		if filter.EAN, p = parseEAN(raw, "ean"); p != nil {
			a.fail(w, r, p)
			return
		}
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > maxMovementLimit {
			a.fail(w, r, badRequest(CodeLimit, "limit"))
			return
		}
		filter.Limit = limit
	}
	movements, err := a.Store.Movements(r.Context(), filter)
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"movements": movements})
}

// movementRequest est une entrée ou une sortie de pelotes.
type movementRequest struct {
	// RequestID, généré par le client pour chaque saisie, rend un renvoi sans effet.
	RequestID string `json:"request_id"`
	Kind      string `json:"kind"`
	EAN       string `json:"ean"`
	// DyeLot vide désigne une laine sans numéro de bain.
	DyeLot    string `json:"dye_lot"`
	Quantity  int    `json:"quantity"`
	Recipient string `json:"recipient"`
	// Reference, sur une entrée, met à jour la marque et le coloris en cache.
	Reference *referenceRequest `json:"reference"`
}

func (req movementRequest) validate() (store.MovementInput, *problem) {
	var (
		in store.MovementInput
		p  *problem
	)
	if in.RequestID, p = text(req.RequestID, "request_id", maxRequestID); p != nil {
		return in, p
	}
	switch kind := store.Kind(strings.TrimSpace(req.Kind)); kind {
	case store.KindIn, store.KindOut:
		in.Kind = kind
	default:
		return in, badRequest(CodeKind, "kind")
	}
	if in.EAN, p = parseEAN(req.EAN, "ean"); p != nil {
		return in, p
	}
	if in.DyeLot, p = text(req.DyeLot, "dye_lot", maxDyeLot); p != nil {
		return in, p
	}
	if req.Quantity < 1 || req.Quantity > maxQuantity {
		return in, badRequest(CodeQuantity, "quantity")
	}
	in.Quantity = req.Quantity
	if in.Recipient, p = text(req.Recipient, "recipient", maxText); p != nil {
		return in, p
	}
	switch in.Kind {
	case store.KindOut:
		// Une sortie dit pour qui ; elle ne touche jamais au cache des références.
		if in.Recipient == "" {
			return in, badRequest(CodeRecipientRequired, "recipient")
		}
	case store.KindIn:
		in.Recipient = ""
		if req.Reference != nil {
			fields, p := req.Reference.validate("reference.")
			if p != nil {
				return in, p
			}
			in.Reference = &fields
		}
	}
	return in, nil
}

func (a *App) createMovement(w http.ResponseWriter, r *http.Request) {
	var req movementRequest
	if p := decode(w, r, &req); p != nil {
		a.fail(w, r, p)
		return
	}
	in, p := req.validate()
	if p != nil {
		a.fail(w, r, p)
		return
	}
	in.Author = currentUser(r).Name
	movement, lot, replayed, err := a.Store.Record(r.Context(), in)
	var insufficient *store.InsufficientStockError
	if errors.As(err, &insufficient) {
		available := insufficient.Available
		a.fail(w, r, &problem{status: http.StatusConflict, code: CodeInsufficientStock, field: "quantity", available: &available})
		return
	}
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	writeJSON(w, status, map[string]any{"movement": movement, "lot": lot, "replayed": replayed})
}
