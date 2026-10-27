// Package web sert les pages HTML, réservées aux comptes connectés : le stock, les
// entrées, les sorties et les inventaires, à consulter comme à saisir. Les pages marchent
// sans JavaScript ; un script d'appoint (static/entries.js) remplit la marque et le coloris
// d'un EAN connu. Les textes passent par les traductions du serveur.
package web

import (
	"bytes"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"html/template"
	"io/fs"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"leblanc.io/open-go-base/i18n"

	"leblanc.io/wool-stock/backend/internal/store"
)

//go:embed templates/*.html
var templateFiles embed.FS

//go:embed static
var staticFiles embed.FS

// pageNames liste les pages rendues dans la mise en page commune.
var pageNames = []string{"login", "stock", "entries", "exits", "inventories", "inventory", "count", "users", "user", "my_account", "error"}

// App porte les dépendances des pages.
type App struct {
	Store  *store.Store
	I18n   *i18n.Bundle
	Logger *slog.Logger
	// Now et Location décident de « aujourd'hui » et du jour affiché d'un mouvement.
	Now      func() time.Time
	Location *time.Location

	pages map[string]*template.Template
	// assets donne l'adresse de chaque fichier statique, avec une empreinte de son contenu :
	// le cache du navigateur suit les mises à jour.
	assets map[string]string
	// exports n'admet qu'un classeur en construction à la fois (voir Workbook).
	exports chan struct{}
}

// New analyse les gabarits embarqués et renvoie des pages prêtes à servir.
func New(st *store.Store, bundle *i18n.Bundle, logger *slog.Logger) (*App, error) {
	assets, err := assetURLs()
	if err != nil {
		return nil, err
	}
	a := &App{Store: st, I18n: bundle, Logger: logger, Now: time.Now, Location: time.Local,
		pages: make(map[string]*template.Template, len(pageNames)), assets: assets, exports: make(chan struct{}, 1)}
	// Les fonctions liées à la requête (langue) sont remplacées au rendu ; ces
	// versions ne servent qu'à l'analyse des gabarits.
	stubs := a.funcs(nil)
	for _, name := range pageNames {
		t, err := template.New(name).Funcs(stubs).ParseFS(templateFiles,
			"templates/layout.html", "templates/partials.html", "templates/"+name+".html")
		if err != nil {
			return nil, err
		}
		a.pages[name] = t
	}
	return a, nil
}

// Handler renvoie les routes des pages. Seule la connexion est ouverte ; les
// formulaires venus d'un autre site sont refusés.
func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /login", a.loginForm)
	mux.HandleFunc("POST /login", a.login)
	mux.HandleFunc("POST /logout", a.logout)
	mux.HandleFunc("GET /{$}", a.signedIn(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, defaultPage, http.StatusSeeOther)
	}))
	mux.HandleFunc("GET /stock", a.signedIn(a.stock))
	mux.HandleFunc("GET /stock/export", a.signedIn(a.export))
	mux.HandleFunc("GET /entries", a.signedIn(a.entries))
	mux.HandleFunc("POST /entries", a.signedIn(a.createEntry))
	mux.HandleFunc("GET /exits", a.signedIn(a.exits))
	mux.HandleFunc("POST /exits", a.signedIn(a.createExit))
	mux.HandleFunc("GET /inventories", a.signedIn(a.inventories))
	mux.HandleFunc("POST /inventories", a.signedIn(a.startInventory))
	mux.HandleFunc("GET /inventories/{id}", a.signedIn(a.inventory))
	mux.HandleFunc("GET /inventories/{id}/count", a.signedIn(a.countSheet))
	mux.HandleFunc("POST /inventories/{id}/count", a.signedIn(a.count))
	mux.HandleFunc("POST /inventories/{id}/close", a.signedIn(a.closeInventory))
	mux.HandleFunc("GET /account", a.signedIn(a.myAccount))
	mux.HandleFunc("POST /account/delete", a.signedIn(a.deleteMyAccount))
	mux.HandleFunc("GET /users", a.adminOnly(a.users))
	mux.HandleFunc("POST /users", a.adminOnly(a.createUser))
	mux.HandleFunc("GET /users/{id}", a.adminOnly(a.user))
	mux.HandleFunc("POST /users/{id}", a.adminOnly(a.updateUser))
	mux.HandleFunc("POST /users/{id}/sessions", a.adminOnly(a.closeSessions))
	mux.HandleFunc("POST /users/{id}/delete", a.adminOnly(a.deleteUser))
	mux.HandleFunc("/", a.signedIn(a.notFound))

	sameSite := http.NewCrossOriginProtection()
	sameSite.SetDenyHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, a.I18n.FromRequest(r).T("error.cross_origin"), http.StatusForbidden)
	}))
	return securityHeaders(sameSite.Handler(mux))
}

// assetURLs calcule l'adresse versionnée de chaque fichier de static/.
func assetURLs() (map[string]string, error) {
	entries, err := staticFiles.ReadDir("static")
	if err != nil {
		return nil, err
	}
	urls := make(map[string]string, len(entries))
	for _, entry := range entries {
		body, err := staticFiles.ReadFile("static/" + entry.Name())
		if err != nil {
			return nil, err
		}
		sum := sha256.Sum256(body)
		urls[entry.Name()] = "/static/" + entry.Name() + "?v=" + hex.EncodeToString(sum[:4])
	}
	return urls, nil
}

// Static sert la feuille de style et le logo ; leur adresse change avec leur contenu, ils se gardent longtemps.
func Static() http.Handler {
	sub, err := fs.Sub(staticFiles, "static")
	if err != nil {
		panic(err)
	}
	files := http.StripPrefix("/static/", http.FileServer(http.FS(sub)))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		files.ServeHTTP(w, r)
	})
}

// RateLimitHandler répond 429 en HTML : le formulaire de connexion et son message
// pour une tentative de connexion, une page d'erreur ailleurs. Le limiteur a déjà
// posé Retry-After.
func (a *App) RateLimitHandler() http.Handler {
	return securityHeaders(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && r.URL.Path == "/login" {
			view := loginView{Email: strings.TrimSpace(r.PostFormValue("email")), Next: localPath(r.PostFormValue("next")), Error: "error.rate_limit"}
			a.render(w, r, http.StatusTooManyRequests, "login", "", "login.title", view)
			return
		}
		a.render(w, r, http.StatusTooManyRequests, "error", "", "error.rate_limit", "error.rate_limit")
	}))
}

// securityHeaders : pages sans ressource externe ni script en ligne, jamais encadrées.
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

// page est ce que la mise en page reçoit.
type page struct {
	Lang string
	// User est le compte connecté ; nil sur la page de connexion.
	User *store.User
	// Nav désigne l'onglet courant : stock, entries, exits, inventories ou users ;
	// account pour la page du compte connecté.
	Nav string
	// Title est une clé de traduction.
	Title string
	Data  any
}

func (a *App) render(w http.ResponseWriter, r *http.Request, status int, name, nav, title string, data any) {
	loc := a.I18n.FromRequest(r)
	t, err := a.pages[name].Clone()
	if err == nil {
		t = t.Funcs(a.funcs(loc))
	}
	var buf bytes.Buffer
	if err == nil {
		err = t.ExecuteTemplate(&buf, "layout", page{Lang: loc.Lang().String(), User: currentUser(r), Nav: nav, Title: title, Data: data})
	}
	if err != nil {
		a.Logger.Error("render", "page", name, "err", err)
		http.Error(w, loc.T("error.internal"), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	_, _ = buf.WriteTo(w)
}

func (a *App) serverError(w http.ResponseWriter, r *http.Request, err error) {
	a.Logger.Error("page failed", "path", r.URL.Path, "err", err)
	http.Error(w, a.I18n.FromRequest(r).T("error.internal"), http.StatusInternalServerError)
}

// funcs renvoie les fonctions des gabarits. Les formats de date et d'heure sont
// des traductions (format.*) : chaque langue choisit le sien.
func (a *App) funcs(loc *i18n.Localizer) template.FuncMap {
	t := func(id string, data ...any) string {
		if loc == nil {
			return ""
		}
		return loc.T(id, data...)
	}
	tn := func(id string, count int, data ...any) string {
		if loc == nil {
			return ""
		}
		return loc.Tn(id, count, data...)
	}
	date := func(at time.Time) string { return at.In(a.Location).Format(t("format.date")) }
	clock := func(at time.Time) string { return at.In(a.Location).Format(t("format.time")) }
	return template.FuncMap{
		// asset donne l'adresse versionnée d'un fichier statique.
		"asset": func(name string) string { return a.assets[name] },
		"T":     t,
		"Tn":    tn,
		// message traduit une erreur de formulaire, au pluriel quand elle compte des pelotes.
		"message": func(e *fieldError) string {
			if e.Count != nil {
				return tn(e.Key, *e.Count)
			}
			return t(e.Key, e.Data)
		},
		// newRequestID donne à chaque formulaire de saisie son identifiant (voir formRequestID).
		"newRequestID": newRequestID,
		"date":         date,
		"clock":        clock,
		"dateTime": func(at time.Time) string {
			return t("format.date_time", map[string]any{"Date": date(at), "Time": clock(at)})
		},
		// recordedBy dit qui a saisi un mouvement, et à quelle heure.
		"recordedBy": func(author *string, at time.Time) string {
			if author == nil {
				return t("movements.recorded_at", map[string]any{"Time": clock(at)})
			}
			return t("movements.recorded_by", map[string]any{"Author": *author, "Time": clock(at)})
		},
		"dayHeading": func(day time.Time) string {
			weekday := t("format.weekday." + strconv.Itoa(int(day.Weekday())))
			return t("format.day_heading", map[string]any{"Weekday": weekday, "Date": date(day)})
		},
		// signedBalls écrit un écart avec son signe : « +2 pelotes », « −3 pelotes ».
		"signedBalls": func(n int) string {
			return tn("unit.balls_signed", abs(n), map[string]any{"Signed": signed(n), "Count": abs(n)})
		},
		"signed": signed,
		// gapClass colore un écart : manque ou surplus.
		"gapClass": func(n int) string {
			switch {
			case n < 0:
				return "gap--negative"
			case n > 0:
				return "gap--positive"
			}
			return ""
		},
		// dict construit les données d'une traduction : dict "Clé" valeur …
		"dict": func(pairs ...any) map[string]any {
			m := make(map[string]any, len(pairs)/2)
			for i := 0; i+1 < len(pairs); i += 2 {
				if key, ok := pairs[i].(string); ok {
					m[key] = pairs[i+1]
				}
			}
			return m
		},
		// list construit une liste dans un gabarit : range list "a" "b".
		"list": func(items ...string) []string { return items },
		// isoDate et iso donnent les valeurs machine (champs date, attribut datetime).
		"isoDate": func(day time.Time) string { return day.Format(time.DateOnly) },
		"iso":     func(at time.Time) string { return at.Format(time.RFC3339) },
		"deref": func(s *string) string {
			if s == nil {
				return ""
			}
			return *s
		},
	}
}

// signed écrit un entier avec son signe, le moins typographique (U+2212) compris.
func signed(n int) string {
	switch {
	case n > 0:
		return "+" + strconv.Itoa(n)
	case n < 0:
		return "\u2212" + strconv.Itoa(-n)
	}
	return "0"
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}
