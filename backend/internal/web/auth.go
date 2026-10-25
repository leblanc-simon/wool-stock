package web

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"

	"leblanc.io/wool-stock/backend/internal/store"
)

// sessionCookie porte le jeton de session du navigateur, le même que celui de l'application.
const sessionCookie = "ws_session"

// cookieMaxAge est le plafond des navigateurs (400 jours) ; le cookie est renouvelé à
// chaque page, la session reste donc ouverte tant qu'on revient dans l'année.
const cookieMaxAge = 400 * 24 * time.Hour

// maxDevice tronque le nom d'appareil enregistré avec la session (l'agent du navigateur).
const maxDevice = 100

// defaultPage accueille après la connexion quand aucune page n'était demandée.
const defaultPage = "/stock"

type userKey struct{}

// currentUser est le compte de la requête, posé par signedIn ; nil hors session.
func currentUser(r *http.Request) *store.User {
	u, _ := r.Context().Value(userKey{}).(*store.User)
	return u
}

// sessionUser lit le cookie de session et renvoie son compte et son jeton ;
// ErrNotFound sans session valide.
func (a *App) sessionUser(r *http.Request) (*store.User, string, error) {
	cookie, err := r.Cookie(sessionCookie)
	if err != nil || cookie.Value == "" {
		return nil, "", store.ErrNotFound
	}
	user, err := a.Store.SessionUser(r.Context(), cookie.Value)
	if err != nil {
		return nil, "", err
	}
	return &user, cookie.Value, nil
}

// signedIn réserve une page aux comptes connectés : sans session, direction la
// page de connexion, qui ramène ensuite à la page demandée (un formulaire envoyé
// sans session n'est pas rejoué).
func (a *App) signedIn(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user, token, err := a.sessionUser(r)
		if errors.Is(err, store.ErrNotFound) {
			target := "/login"
			if r.Method == http.MethodGet || r.Method == http.MethodHead {
				target += "?" + url.Values{"next": {r.URL.RequestURI()}}.Encode()
			}
			http.Redirect(w, r, target, http.StatusSeeOther)
			return
		}
		if err != nil {
			a.serverError(w, r, err)
			return
		}
		setSessionCookie(w, r, token)
		next(w, r.WithContext(context.WithValue(r.Context(), userKey{}, user)))
	}
}

// loginView est le formulaire de connexion ; Error est une clé de traduction.
type loginView struct {
	Email string
	Next  string
	Error string
	// Deleted annonce que le compte vient d'être supprimé.
	Deleted bool
}

func (a *App) loginForm(w http.ResponseWriter, r *http.Request) {
	next := localPath(r.URL.Query().Get("next"))
	if _, _, err := a.sessionUser(r); err == nil {
		http.Redirect(w, r, next, http.StatusSeeOther)
		return
	}
	a.render(w, r, http.StatusOK, "login", "", "login.title", loginView{Next: next, Deleted: r.URL.Query().Has("deleted")})
}

// login ouvre une session sans fin pour une adresse autorisée, comme l'application.
func (a *App) login(w http.ResponseWriter, r *http.Request) {
	view := loginView{Email: strings.TrimSpace(r.PostFormValue("email")), Next: localPath(r.PostFormValue("next"))}
	email, ok := store.ParseEmail(view.Email)
	if !ok {
		view.Error = "validation.email"
		a.render(w, r, http.StatusUnprocessableEntity, "login", "", "login.title", view)
		return
	}
	user, err := a.Store.UserByEmail(r.Context(), email)
	if errors.Is(err, store.ErrNotFound) {
		view.Error = "error.email_not_allowed"
		a.render(w, r, http.StatusForbidden, "login", "", "login.title", view)
		return
	}
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	token, err := a.Store.OpenSession(r.Context(), user.ID, device(r))
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	a.Logger.Info("login", "user", user.Email, "device", "web")
	setSessionCookie(w, r, token)
	http.Redirect(w, r, view.Next, http.StatusSeeOther)
}

// logout ferme la session du navigateur ; les sessions des téléphones restent ouvertes.
func (a *App) logout(w http.ResponseWriter, r *http.Request) {
	if cookie, err := r.Cookie(sessionCookie); err == nil && cookie.Value != "" {
		if err := a.Store.CloseSession(r.Context(), cookie.Value); err != nil {
			a.serverError(w, r, err)
			return
		}
	}
	clearSessionCookie(w, r)
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

// setSessionCookie : illisible depuis une page, jamais envoyé par un autre site
// pour un POST, réservé à HTTPS quand le serveur y est servi.
func setSessionCookie(w http.ResponseWriter, r *http.Request, token string) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    token,
		Path:     "/",
		MaxAge:   int(cookieMaxAge.Seconds()),
		HttpOnly: true,
		Secure:   secure(r),
		SameSite: http.SameSiteLaxMode,
	})
}

// clearSessionCookie efface le cookie de session du navigateur.
func clearSessionCookie(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Path: "/", MaxAge: -1, HttpOnly: true,
		Secure: secure(r), SameSite: http.SameSiteLaxMode})
}

// secure dit si le navigateur parle en HTTPS, directement ou derrière un reverse proxy.
func secure(r *http.Request) bool {
	return r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https"
}

// device nomme la session d'après le navigateur, pour la reconnaître parmi celles des téléphones.
func device(r *http.Request) string {
	agent := strings.TrimSpace(r.UserAgent())
	if runes := []rune(agent); len(runes) > maxDevice {
		agent = string(runes[:maxDevice])
	}
	return agent
}

// localPath n'accepte qu'une adresse de ce site comme retour après connexion ;
// tout le reste (autre domaine, « //hôte », vide) ramène au stock.
func localPath(next string) string {
	if !strings.HasPrefix(next, "/") || strings.HasPrefix(next, "//") || strings.HasPrefix(next, "/\\") {
		return defaultPage
	}
	u, err := url.Parse(next)
	if err != nil || u.Scheme != "" || u.Host != "" || u.Path == "/login" {
		return defaultPage
	}
	return next
}
