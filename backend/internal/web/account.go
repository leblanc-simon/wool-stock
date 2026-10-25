package web

import (
	"errors"
	"net/http"

	"leblanc.io/wool-stock/backend/internal/store"
)

// myAccountView est la page du compte connecté ; Error est une clé de traduction.
type myAccountView struct {
	User  *store.User
	Error string
}

// myAccount présente le compte connecté et permet de le supprimer. C'est l'adresse à
// déclarer aux stores pour la suppression de compte : sans session, la connexion y ramène.
func (a *App) myAccount(w http.ResponseWriter, r *http.Request) {
	a.render(w, r, http.StatusOK, "my_account", "account", "my_account.title", myAccountView{User: currentUser(r)})
}

// deleteMyAccount supprime le compte connecté et toutes ses sessions, puis renvoie à la
// connexion ; son nom reste sur les mouvements et inventaires déjà saisis. Le dernier
// administrateur est refusé.
func (a *App) deleteMyAccount(w http.ResponseWriter, r *http.Request) {
	user := currentUser(r)
	err := a.Store.DeleteUser(r.Context(), user.ID)
	if errors.Is(err, store.ErrLastAdmin) {
		a.render(w, r, http.StatusConflict, "my_account", "account", "my_account.title",
			myAccountView{User: user, Error: "my_account.last_admin"})
		return
	}
	// ErrNotFound : un admin vient de retirer ce compte, le résultat est le même.
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		a.serverError(w, r, err)
		return
	}
	a.Logger.Info("account deleted", "user", user.Email)
	// signedIn vient de renouveler le cookie : seul l'effacement doit partir.
	w.Header().Del("Set-Cookie")
	clearSessionCookie(w, r)
	http.Redirect(w, r, "/login?deleted=1", http.StatusSeeOther)
}
