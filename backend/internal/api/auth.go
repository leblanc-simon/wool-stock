package api

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"leblanc.io/wool-stock/backend/internal/store"
)

type userKey struct{}

// currentUser est le compte de la requête, posé par authenticate.
func currentUser(r *http.Request) store.User {
	u, _ := r.Context().Value(userKey{}).(store.User)
	return u
}

// bearer lit le jeton de « Authorization: Bearer … ».
func bearer(r *http.Request) string {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok {
		return ""
	}
	return strings.TrimSpace(token)
}

// authenticate exige une session : sans jeton valide, 401.
func (a *App) authenticate(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := bearer(r)
		if token == "" {
			a.unauthorized(w, r)
			return
		}
		user, err := a.Store.SessionUser(r.Context(), token)
		if errors.Is(err, store.ErrNotFound) {
			a.unauthorized(w, r)
			return
		}
		if err != nil {
			a.internalError(w, r, err)
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), userKey{}, user)))
	}
}

// adminOnly exige une session d'admin : 403 pour un gestionnaire.
func (a *App) adminOnly(next http.HandlerFunc) http.HandlerFunc {
	return a.authenticate(func(w http.ResponseWriter, r *http.Request) {
		if currentUser(r).Role != store.RoleAdmin {
			a.fail(w, r, &problem{status: http.StatusForbidden, code: CodeForbidden})
			return
		}
		next(w, r)
	})
}

func (a *App) unauthorized(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("WWW-Authenticate", "Bearer")
	a.fail(w, r, &problem{status: http.StatusUnauthorized, code: CodeUnauthorized})
}

// parseEmail valide une adresse simple (sans nom affiché) et la normalise.
func parseEmail(raw, field string) (string, *problem) {
	email, ok := store.ParseEmail(raw)
	if !ok {
		return "", badRequest(CodeEmail, field)
	}
	return email, nil
}

type loginRequest struct {
	Email string `json:"email"`
	// Device nomme le téléphone, pour reconnaître ses sessions.
	Device string `json:"device"`
}

type loginResponse struct {
	Token string     `json:"token"`
	User  store.User `json:"user"`
}

// login ouvre une session pour une adresse autorisée ; 403 sinon.
func (a *App) login(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if p := decode(w, r, &req); p != nil {
		a.fail(w, r, p)
		return
	}
	email, p := parseEmail(req.Email, "email")
	if p != nil {
		a.fail(w, r, p)
		return
	}
	device, p := text(req.Device, "device", store.MaxText)
	if p != nil {
		a.fail(w, r, p)
		return
	}
	user, err := a.Store.UserByEmail(r.Context(), email)
	if errors.Is(err, store.ErrNotFound) {
		a.fail(w, r, &problem{status: http.StatusForbidden, code: CodeEmailNotAllowed, field: "email"})
		return
	}
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	token, err := a.Store.OpenSession(r.Context(), user.ID, device)
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	a.Logger.Info("login", "user", user.Email, "device", device)
	writeJSON(w, http.StatusCreated, loginResponse{Token: token, User: user})
}

func (a *App) me(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]store.User{"user": currentUser(r)})
}

// deleteMe supprime le compte connecté et toutes ses sessions, à la demande de son
// titulaire ; son nom reste sur les mouvements et inventaires déjà saisis. Le dernier
// administrateur est refusé (409) : il doit d'abord en nommer un autre.
func (a *App) deleteMe(w http.ResponseWriter, r *http.Request) {
	user := currentUser(r)
	err := a.Store.DeleteUser(r.Context(), user.ID)
	if errors.Is(err, store.ErrLastAdmin) {
		a.fail(w, r, &problem{status: http.StatusConflict, code: CodeLastAdmin})
		return
	}
	// ErrNotFound : un admin vient de retirer ce compte, le résultat est le même.
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		a.internalError(w, r, err)
		return
	}
	a.Logger.Info("account deleted", "user", user.Email)
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (a *App) logout(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.CloseSession(r.Context(), bearer(r)); err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (a *App) listUsers(w http.ResponseWriter, r *http.Request) {
	users, err := a.Store.Users(r.Context())
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"users": users})
}

type userRequest struct {
	Email string `json:"email"`
	Name  string `json:"name"`
	// Role vaut "admin" ou "manager".
	Role string `json:"role"`
}

// createUser autorise une nouvelle adresse ; réservé aux admins.
func (a *App) createUser(w http.ResponseWriter, r *http.Request) {
	var req userRequest
	if p := decode(w, r, &req); p != nil {
		a.fail(w, r, p)
		return
	}
	email, p := parseEmail(req.Email, "email")
	if p != nil {
		a.fail(w, r, p)
		return
	}
	name, p := text(req.Name, "name", store.MaxText)
	if p != nil {
		a.fail(w, r, p)
		return
	}
	if name == "" {
		a.fail(w, r, badRequest(CodeNameRequired, "name"))
		return
	}
	role := store.Role(strings.TrimSpace(req.Role))
	if role != store.RoleAdmin && role != store.RoleManager {
		a.fail(w, r, badRequest(CodeRole, "role"))
		return
	}
	user, err := a.Store.CreateUser(r.Context(), email, name, role, currentUser(r).ID)
	if errors.Is(err, store.ErrEmailTaken) {
		a.fail(w, r, &problem{status: http.StatusConflict, code: CodeEmailTaken, field: "email"})
		return
	}
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, user)
}
