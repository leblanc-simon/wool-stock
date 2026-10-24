package web

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"leblanc.io/wool-stock/backend/internal/store"
)

// maxName est la longueur maximale d'un nom, comme dans l'API.
const maxName = 100

// adminOnly réserve une page aux administrateurs : 403 pour un gestionnaire.
func (a *App) adminOnly(next http.HandlerFunc) http.HandlerFunc {
	return a.signedIn(func(w http.ResponseWriter, r *http.Request) {
		if !currentUser(r).IsAdmin() {
			a.render(w, r, http.StatusForbidden, "error", "", "error.forbidden", "error.forbidden")
			return
		}
		next(w, r)
	})
}

// fieldError est un message de formulaire : une clé de traduction et ses données.
type fieldError struct {
	Key  string
	Data map[string]any
}

// userForm est le formulaire d'un compte, à l'ajout comme à la modification.
type userForm struct {
	Email string
	Name  string
	Role  store.Role
	// Errors est indexé par champ (email, name, role) ; "form" porte une erreur d'ensemble.
	Errors map[string]*fieldError
}

func (f *userForm) fail(field, key string, data map[string]any) {
	if f.Errors == nil {
		f.Errors = map[string]*fieldError{}
	}
	f.Errors[field] = &fieldError{Key: key, Data: data}
}

// readUserForm lit et valide le formulaire. guess déduit de l'adresse un nom laissé vide.
func readUserForm(r *http.Request, guess bool) (userForm, string, string) {
	f := userForm{
		Email: strings.TrimSpace(r.PostFormValue("email")),
		Name:  strings.TrimSpace(r.PostFormValue("name")),
		Role:  store.Role(r.PostFormValue("role")),
	}
	email, ok := store.ParseEmail(f.Email)
	if !ok {
		f.fail("email", "validation.email", nil)
	}
	name := f.Name
	if name == "" && guess && ok {
		name = guessName(email)
	}
	switch {
	case name == "":
		f.fail("name", "validation.name_required", nil)
	case utf8.RuneCountInString(name) > maxName:
		f.fail("name", "validation.too_long", map[string]any{"Max": maxName})
	}
	if f.Role != store.RoleAdmin && f.Role != store.RoleManager {
		f.fail("role", "validation.role_required", nil)
	}
	return f, email, name
}

// guessName devine un nom d'après l'adresse, comme l'application :
// « jean-luc.dupont+laine@… » donne « Jean-Luc Dupont ».
func guessName(email string) string {
	local, _, _ := strings.Cut(strings.TrimSpace(email), "@")
	local, _, _ = strings.Cut(local, "+")
	var words []string
	for _, word := range strings.FieldsFunc(local, func(c rune) bool { return c == '.' || c == '_' }) {
		word = strings.Map(func(c rune) rune {
			if unicode.IsDigit(c) {
				return -1
			}
			return c
		}, word)
		var parts []string
		for _, part := range strings.Split(word, "-") {
			if part == "" {
				continue
			}
			runes := []rune(strings.ToLower(part))
			runes[0] = unicode.ToTitle(runes[0])
			parts = append(parts, string(runes))
		}
		if len(parts) > 0 {
			words = append(words, strings.Join(parts, "-"))
		}
	}
	return strings.Join(words, " ")
}

// usersView est la liste des comptes et le formulaire d'ajout.
type usersView struct {
	Accounts []store.Account
	// Me est le compte connecté, signalé dans la liste.
	Me   int64
	Form userForm
	// Created nomme le compte qui vient d'être ajouté ; Removed annonce un retrait.
	Created string
	Removed bool
}

func (a *App) users(w http.ResponseWriter, r *http.Request) {
	a.renderUsers(w, r, http.StatusOK, userForm{Role: store.RoleManager})
}

func (a *App) renderUsers(w http.ResponseWriter, r *http.Request, status int, form userForm) {
	accounts, err := a.Store.Accounts(r.Context())
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	view := usersView{Accounts: accounts, Me: currentUser(r).ID, Form: form, Removed: r.URL.Query().Has("removed")}
	if id, err := strconv.ParseInt(r.URL.Query().Get("created"), 10, 64); err == nil {
		for _, acc := range accounts {
			if acc.ID == id {
				view.Created = acc.Name
			}
		}
	}
	a.render(w, r, status, "users", "users", "users.title", view)
}

// createUser autorise une nouvelle adresse ; le nom se déduit de l'adresse s'il est laissé vide.
func (a *App) createUser(w http.ResponseWriter, r *http.Request) {
	form, email, name := readUserForm(r, true)
	if len(form.Errors) > 0 {
		a.renderUsers(w, r, http.StatusUnprocessableEntity, form)
		return
	}
	admin := currentUser(r)
	user, err := a.Store.CreateUser(r.Context(), email, name, form.Role, admin.ID)
	if errors.Is(err, store.ErrEmailTaken) {
		form.fail("email", "error.email_taken", nil)
		a.renderUsers(w, r, http.StatusConflict, form)
		return
	}
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	a.Logger.Info("user created", "by", admin.Email, "user", user.Email, "role", user.Role)
	http.Redirect(w, r, "/users?created="+strconv.FormatInt(user.ID, 10), http.StatusSeeOther)
}

// userView est la fiche d'un compte : modification, sessions, retrait de l'accès.
type userView struct {
	Account store.Account
	// Self : l'admin consulte son propre compte, dont il ne peut changer ni le droit ni l'accès.
	Self bool
	Form userForm
	// Error est une clé de traduction pour un retrait ou une fermeture refusés.
	Error  string
	Saved  bool
	Closed int
}

// account renvoie un compte et son activité, ou ErrNotFound.
func (a *App) account(r *http.Request) (store.Account, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		return store.Account{}, store.ErrNotFound
	}
	accounts, err := a.Store.Accounts(r.Context())
	if err != nil {
		return store.Account{}, err
	}
	for _, acc := range accounts {
		if acc.ID == id {
			return acc, nil
		}
	}
	return store.Account{}, store.ErrNotFound
}

func (a *App) user(w http.ResponseWriter, r *http.Request) {
	acc, ok := a.loadAccount(w, r)
	if !ok {
		return
	}
	view := userView{Account: acc, Self: acc.ID == currentUser(r).ID, Saved: r.URL.Query().Has("saved"),
		Form: userForm{Email: acc.Email, Name: acc.Name, Role: acc.Role}}
	view.Closed, _ = strconv.Atoi(r.URL.Query().Get("closed"))
	a.renderUser(w, r, http.StatusOK, view)
}

// loadAccount lit le compte de l'adresse ; 404 s'il n'existe pas.
func (a *App) loadAccount(w http.ResponseWriter, r *http.Request) (store.Account, bool) {
	acc, err := a.account(r)
	if errors.Is(err, store.ErrNotFound) {
		a.notFound(w, r)
		return acc, false
	}
	if err != nil {
		a.serverError(w, r, err)
		return acc, false
	}
	return acc, true
}

func (a *App) renderUser(w http.ResponseWriter, r *http.Request, status int, view userView) {
	a.render(w, r, status, "user", "users", "user.title", view)
}

// updateUser change l'adresse, le nom et le droit ; un admin garde son propre droit.
func (a *App) updateUser(w http.ResponseWriter, r *http.Request) {
	acc, ok := a.loadAccount(w, r)
	if !ok {
		return
	}
	admin := currentUser(r)
	view := userView{Account: acc, Self: acc.ID == admin.ID}
	form, email, name := readUserForm(r, false)
	if view.Self {
		// Son propre droit ne change pas : le formulaire ne le propose pas.
		form.Role = acc.Role
		delete(form.Errors, "role")
	}
	view.Form = form
	if len(form.Errors) > 0 {
		a.renderUser(w, r, http.StatusUnprocessableEntity, view)
		return
	}
	user, err := a.Store.UpdateUser(r.Context(), acc.ID, email, name, form.Role)
	switch {
	case errors.Is(err, store.ErrEmailTaken):
		view.Form.fail("email", "error.email_taken", nil)
		a.renderUser(w, r, http.StatusConflict, view)
		return
	case errors.Is(err, store.ErrLastAdmin):
		view.Form.fail("form", "error.last_admin", nil)
		a.renderUser(w, r, http.StatusConflict, view)
		return
	case errors.Is(err, store.ErrNotFound):
		a.notFound(w, r)
		return
	case err != nil:
		a.serverError(w, r, err)
		return
	}
	a.Logger.Info("user updated", "by", admin.Email, "user", user.Email, "role", user.Role)
	http.Redirect(w, r, "/users/"+strconv.FormatInt(acc.ID, 10)+"?saved=1", http.StatusSeeOther)
}

// closeSessions déconnecte un compte de tous ses appareils ; son accès reste autorisé.
func (a *App) closeSessions(w http.ResponseWriter, r *http.Request) {
	acc, ok := a.loadAccount(w, r)
	if !ok {
		return
	}
	n, err := a.Store.CloseUserSessions(r.Context(), acc.ID)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	a.Logger.Info("sessions closed", "by", currentUser(r).Email, "user", acc.Email, "count", n)
	http.Redirect(w, r, "/users/"+strconv.FormatInt(acc.ID, 10)+"?closed="+strconv.Itoa(n), http.StatusSeeOther)
}

// deleteUser retire l'accès d'un compte ; un admin ne retire pas le sien.
func (a *App) deleteUser(w http.ResponseWriter, r *http.Request) {
	acc, ok := a.loadAccount(w, r)
	if !ok {
		return
	}
	admin := currentUser(r)
	view := userView{Account: acc, Self: acc.ID == admin.ID, Form: userForm{Email: acc.Email, Name: acc.Name, Role: acc.Role}}
	if view.Self {
		view.Error = "user.cannot_remove_self"
		a.renderUser(w, r, http.StatusForbidden, view)
		return
	}
	err := a.Store.DeleteUser(r.Context(), acc.ID)
	switch {
	case errors.Is(err, store.ErrLastAdmin):
		view.Error = "error.last_admin"
		a.renderUser(w, r, http.StatusConflict, view)
		return
	case errors.Is(err, store.ErrNotFound):
		a.notFound(w, r)
		return
	case err != nil:
		a.serverError(w, r, err)
		return
	}
	a.Logger.Info("user removed", "by", admin.Email, "user", acc.Email)
	http.Redirect(w, r, "/users?removed=1", http.StatusSeeOther)
}
