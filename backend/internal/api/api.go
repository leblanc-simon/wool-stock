// Package api expose en JSON le cache des codes-barres, le stock global, les
// entrées / sorties de pelotes et les inventaires, et en Excel l'export du stock.
// On s'y connecte par adresse email seule ; chaque mouvement porte le nom du compte
// connecté.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"leblanc.io/open-go-base/i18n"

	"leblanc.io/wool-stock/backend/internal/store"
)

// Codes d'erreur renvoyés au client. Ce sont aussi les clés de traduction du
// message : un client peut afficher message tel quel, ou résoudre code lui-même.
const (
	CodeInvalidJSON       = "error.invalid_json"
	CodeNotFound          = "error.not_found"
	CodeInternal          = "error.internal"
	CodeInsufficientStock = "error.insufficient_stock"
	CodeRateLimit         = "error.rate_limit"
	CodeAlreadyCounted    = "error.already_counted"
	CodeInventoryOpen     = "error.inventory_open"
	CodeInventoryClosed   = "error.inventory_closed"
	CodeUnauthorized      = "error.unauthorized"
	CodeForbidden         = "error.forbidden"
	CodeEmailNotAllowed   = "error.email_not_allowed"
	CodeEmailTaken        = "error.email_taken"
	CodeLastAdmin         = "error.last_admin"
	CodeEANRequired       = "validation.ean_required"
	CodeEANFormat         = "validation.ean_format"
	CodeKind              = "validation.kind"
	CodeQuantity          = "validation.quantity"
	CodeRecipientRequired = "validation.recipient_required"
	CodeTooLong           = "validation.too_long"
	CodeLimit             = "validation.limit"
	CodeMode              = "validation.mode"
	CodeCount             = "validation.count"
	CodeScope             = "validation.scope"
	CodeEmail             = "validation.email"
	CodeNameRequired      = "validation.name_required"
	CodeRole              = "validation.role"
)

// Codes liste tous les codes d'erreur, pour vérifier leurs traductions.
var Codes = []string{
	CodeInvalidJSON, CodeNotFound, CodeInternal, CodeInsufficientStock, CodeRateLimit,
	CodeAlreadyCounted, CodeInventoryOpen, CodeInventoryClosed,
	CodeUnauthorized, CodeForbidden, CodeEmailNotAllowed, CodeEmailTaken, CodeLastAdmin,
	CodeEANRequired, CodeEANFormat, CodeKind, CodeQuantity, CodeRecipientRequired, CodeTooLong, CodeLimit,
	CodeMode, CodeCount, CodeScope, CodeEmail, CodeNameRequired, CodeRole,
}

// pluralCodes sont les codes dont le message dépend d'un nombre (available ou counted).
var pluralCodes = map[string]bool{CodeInsufficientStock: true, CodeAlreadyCounted: true}

// maxBodyBytes borne le corps des requêtes : un mouvement tient en quelques centaines d'octets.
const maxBodyBytes = 16 << 10

// App porte les dépendances des handlers.
type App struct {
	Store   *store.Store
	I18n    *i18n.Bundle
	Logger  *slog.Logger
	Version string
	// Export construit le classeur Excel de GET /api/export ; nil, la route répond 404.
	Export Exporter
}

// Handler renvoie les routes de l'API. Seules health et la connexion sont ouvertes ;
// le reste demande une session, et la gestion des comptes un admin.
func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/health", a.health)
	mux.HandleFunc("POST /api/auth/login", a.login)
	mux.HandleFunc("GET /api/auth/me", a.authenticate(a.me))
	mux.HandleFunc("DELETE /api/auth/me", a.authenticate(a.deleteMe))
	mux.HandleFunc("POST /api/auth/logout", a.authenticate(a.logout))
	mux.HandleFunc("GET /api/users", a.adminOnly(a.listUsers))
	mux.HandleFunc("POST /api/users", a.adminOnly(a.createUser))
	mux.HandleFunc("GET /api/references", a.authenticate(a.listReferences))
	mux.HandleFunc("GET /api/references/{ean}", a.authenticate(a.getReference))
	mux.HandleFunc("PUT /api/references/{ean}", a.authenticate(a.putReference))
	mux.HandleFunc("GET /api/stock", a.authenticate(a.listStock))
	mux.HandleFunc("GET /api/export", a.authenticate(a.export))
	mux.HandleFunc("GET /api/movements", a.authenticate(a.listMovements))
	mux.HandleFunc("POST /api/movements", a.authenticate(a.createMovement))
	mux.HandleFunc("GET /api/inventories/current", a.authenticate(a.currentInventory))
	mux.HandleFunc("POST /api/inventories", a.authenticate(a.startInventory))
	mux.HandleFunc("GET /api/inventories/{id}", a.authenticate(a.getInventory))
	mux.HandleFunc("POST /api/inventories/{id}/counts", a.authenticate(a.countInventory))
	mux.HandleFunc("POST /api/inventories/{id}/close", a.authenticate(a.closeInventory))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		a.fail(w, r, &problem{status: http.StatusNotFound, code: CodeNotFound})
	})
	return headers(mux)
}

// RateLimitHandler répond 429 avec le même corps d'erreur que le reste de l'API ;
// le limiteur a déjà posé Retry-After et X-RateLimit-*.
func (a *App) RateLimitHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a.fail(w, r, &problem{status: http.StatusTooManyRequests, code: CodeRateLimit})
	})
}

// headers pose les en-têtes communs : réponses JSON jamais mises en cache ni réinterprétées.
func headers(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

// problem décrit une réponse en erreur avant traduction.
type problem struct {
	status int
	code   string
	// field nomme le champ fautif d'une erreur de validation.
	field string
	// max est la longueur maximale, pour validation.too_long.
	max int
	// available est le stock du bain, pour error.insufficient_stock.
	available *int
	// counted est la quantité déjà comptée, pour error.already_counted.
	counted *int
}

func badRequest(code, field string) *problem {
	return &problem{status: http.StatusBadRequest, code: code, field: field}
}

// apiError est le corps d'une réponse en erreur, sous la clé "error".
type apiError struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Field     string `json:"field,omitempty"`
	Available *int   `json:"available,omitempty"`
	Counted   *int   `json:"counted,omitempty"`
}

func (a *App) fail(w http.ResponseWriter, r *http.Request, p *problem) {
	loc := a.I18n.FromRequest(r)
	var message string
	switch {
	case p.available != nil:
		message = loc.Tn(p.code, *p.available)
	case p.counted != nil:
		message = loc.Tn(p.code, *p.counted)
	case p.max > 0:
		message = loc.T(p.code, map[string]any{"Max": p.max})
	default:
		message = loc.T(p.code)
	}
	writeJSON(w, p.status, map[string]apiError{"error": {
		Code: p.code, Message: message, Field: p.field, Available: p.available, Counted: p.counted,
	}})
}

func (a *App) internalError(w http.ResponseWriter, r *http.Request, err error) {
	a.Logger.Error("request failed", "method", r.Method, "path", r.URL.Path, "err", err)
	a.fail(w, r, &problem{status: http.StatusInternalServerError, code: CodeInternal})
}

// decode lit un corps JSON borné ; un corps vide, tronqué ou trop gros est refusé.
func decode(w http.ResponseWriter, r *http.Request, v any) *problem {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err := dec.Decode(v); err != nil {
		return badRequest(CodeInvalidJSON, "")
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return badRequest(CodeInvalidJSON, "")
	}
	return nil
}

// decodeOptional est decode pour un corps facultatif : vide, v garde ses valeurs zéro.
func decodeOptional(w http.ResponseWriter, r *http.Request, v any) *problem {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	err := dec.Decode(v)
	if errors.Is(err, io.EOF) {
		return nil
	}
	if err != nil {
		return badRequest(CodeInvalidJSON, "")
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return badRequest(CodeInvalidJSON, "")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
