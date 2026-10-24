package web

import (
	"context"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/text/language"

	"leblanc.io/open-go-base/i18n"

	"leblanc.io/wool-stock/backend/internal/store"
	"leblanc.io/wool-stock/backend/locales"
)

func ptr(s string) *string { return &s }

func newBundle(t *testing.T) *i18n.Bundle {
	t.Helper()
	bundle, err := i18n.NewFS(locales.FS, ".", "fr")
	if err != nil {
		t.Fatal(err)
	}
	return bundle
}

func TestGroupStock(t *testing.T) {
	view := groupStock([]store.StockItem{
		{EAN: "1", DyeLot: "A", Quantity: 2, Brand: ptr("katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")},
		{EAN: "1", DyeLot: "B", Quantity: 0, Brand: ptr("Katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")},
		{EAN: "2", DyeLot: "C", Quantity: 1, Brand: ptr("katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")},
		{EAN: "3", DyeLot: "D", Quantity: 4, Brand: ptr("Katia"), ColorCode: ptr("7")},
		{EAN: "4", DyeLot: "", Quantity: 3},
	})
	if view.Balls != 10 || view.EmptyLots != 1 || len(view.Brands) != 2 {
		t.Fatalf("view = %+v", view)
	}
	katia := view.Brands[0]
	if katia.Total != 7 || len(katia.Colors) != 2 {
		t.Fatalf("katia = %+v", katia)
	}
	// Deux qualités au même coloris : un seul groupe, deux EAN.
	if katia.Brand != "Katia" {
		t.Fatalf("brand spelling = %q", katia.Brand)
	}
	if white := katia.Colors[0]; len(white.Lots) != 3 || len(white.EANs) != 2 || white.Total != 3 {
		t.Fatalf("white = %+v", white)
	}
	if unknown := view.Brands[1]; unknown.Brand != "" || unknown.Colors[0].Code != "" {
		t.Fatalf("unknown = %+v", unknown)
	}
}

func TestGroupExits(t *testing.T) {
	paris, err := time.LoadLocation("Europe/Paris")
	if err != nil {
		t.Skip("tzdata missing")
	}
	at := func(s string) time.Time {
		v, _ := time.Parse(time.RFC3339, s)
		return v
	}
	// Du plus récent au plus ancien, comme le store les renvoie.
	list := []store.Movement{
		{ID: 5, EAN: "1", DyeLot: "A", Quantity: 1, Recipient: ptr("marie"), CreatedAt: at("2026-10-23T08:00:00Z"), Brand: ptr("Katia")},
		{ID: 4, EAN: "2", DyeLot: "B", Quantity: 2, Recipient: ptr("Léa"), CreatedAt: at("2026-10-22T23:30:00Z")},
		{ID: 3, EAN: "1", DyeLot: "A", Quantity: 3, Recipient: ptr("Marie "), CreatedAt: at("2026-10-23T07:00:00Z"), Brand: ptr("Katia")},
		{ID: 2, EAN: "3", DyeLot: "C", Quantity: 1, Recipient: ptr("Marie"), CreatedAt: at("2026-10-23T06:00:00Z"), Brand: ptr("Drops")},
		{ID: 1, EAN: "1", DyeLot: "A", Quantity: 1, Recipient: ptr("Marie"), CreatedAt: at("2026-10-21T10:00:00Z"), Brand: ptr("Katia")},
	}
	groups := groupExits(list, paris, language.French)
	if len(groups) != 2 || groups[0].Name != "Léa" || groups[1].Name != "Marie" {
		t.Fatalf("recipients = %+v", groups)
	}
	// 23:30 UTC le 22 est déjà le 23 à Paris.
	if day := groups[0].Days[0].Day; day.Day() != 23 {
		t.Fatalf("léa day = %v", day)
	}
	marie := groups[1]
	if marie.Total != 6 || len(marie.Days) != 2 || marie.Days[0].Day.Day() != 23 {
		t.Fatalf("marie = %+v", marie)
	}
	today := marie.Days[0]
	if today.Total != 5 || len(today.Items) != 2 || deref(today.Items[0].Brand) != "Drops" {
		t.Fatalf("today = %+v", today)
	}
	if katia := today.Items[1]; katia.Total != 4 || len(katia.Movements) != 2 || katia.Movements[0].ID != 5 {
		t.Fatalf("katia = %+v", katia)
	}
}

// testSite est le site de test, avec la session d'un admin connecté.
type testSite struct {
	*httptest.Server
	token string
}

func newSite(t *testing.T) (*testSite, *store.Store) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, "file:"+filepath.Join(t.TempDir(), "web.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	site, err := New(st, newBundle(t), slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	site.Location = time.UTC
	srv := httptest.NewServer(site.Handler())
	t.Cleanup(srv.Close)
	user, err := st.CreateUser(ctx, "simon@example.com", "Simon", store.RoleAdmin, 0)
	if err != nil {
		t.Fatal(err)
	}
	token, err := st.OpenSession(ctx, user.ID, "test")
	if err != nil {
		t.Fatal(err)
	}
	return &testSite{Server: srv, token: token}, st
}

// get lit une page avec la session de l'admin.
func get(t *testing.T, srv *testSite, path, lang string) (int, http.Header, string) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, srv.URL+path, nil)
	req.Header.Set("Accept-Language", lang)
	req.AddCookie(&http.Cookie{Name: sessionCookie, Value: srv.token})
	return send(t, req)
}

// send envoie une requête sans suivre les redirections.
func send(t *testing.T, req *http.Request) (int, http.Header, string) {
	t.Helper()
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	res, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	return res.StatusCode, res.Header, string(body)
}

func TestPages(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	for _, in := range []store.MovementInput{
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 3, Author: "Simon",
			Reference: &store.ReferenceFields{Brand: ptr("Katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")}},
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81790", Quantity: 1},
		{Kind: store.KindOut, EAN: "8435407948594", DyeLot: "81790", Quantity: 1, Recipient: "Marie", Author: "Simon"},
		{Kind: store.KindOut, EAN: "8435407948594", DyeLot: "81784", Quantity: 2, Recipient: "Marie"},
	} {
		if _, _, _, err := st.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}

	status, header, body := get(t, srv, "/stock", "fr")
	if status != http.StatusOK || !strings.Contains(header.Get("Content-Security-Policy"), "default-src 'none'") {
		t.Fatalf("stock: status %d, headers %v", status, header)
	}
	for _, want := range []string{`lang="fr"`, `rel="icon" type="image/svg+xml" href="/static/logo.svg?v=`, `class="masthead__logo"`, "Katia", "blanc", "81784", `class="lot lot--empty"`, "1 pelote en stock.", "1 bain épuisé reste dans la liste.", `aria-current="page"`,
		`<form class="account" method="post" action="/logout">`, `title="simon@example.com">Simon</span>`, "Se déconnecter"} {
		if !strings.Contains(body, want) {
			t.Errorf("stock: %q missing", want)
		}
	}

	_, _, body = get(t, srv, "/entries", "en")
	for _, want := range []string{"Entries", "Simon", "anonymous", "4 balls added over this period."} {
		if !strings.Contains(body, want) {
			t.Errorf("entries: %q missing", want)
		}
	}

	_, _, body = get(t, srv, "/exits", "fr")
	for _, want := range []string{"Marie", "3 pelotes", "Saisie par Simon à", "Saisie à"} {
		if !strings.Contains(body, want) {
			t.Errorf("exits: %q missing", want)
		}
	}

	// Une période sans mouvement.
	_, _, body = get(t, srv, "/exits?from=2020-01-01&to=2020-01-31", "fr")
	if !strings.Contains(body, "Aucune pelote sortie") || !strings.Contains(body, `value="2020-01-01"`) {
		t.Errorf("empty period: %s", body)
	}

	if status, _, body := get(t, srv, "/nope", "fr"); status != http.StatusNotFound || !strings.Contains(body, "Cette page n’existe pas") {
		t.Errorf("not found: %d", status)
	}
}

// Chaque clé employée par les gabarits et le code existe dans chaque langue.
func TestEveryKeyIsTranslated(t *testing.T) {
	bundle := newBundle(t)
	keys := map[string]bool{
		"error.internal": false, "format.date": false, "format.time": false, "format.date_time": false,
		"format.day_heading": false, "movements.recorded_at": false, "movements.recorded_by": false,
		"stock.title": false, "entries.title": false, "exits.title": false, "error.page_not_found": false,
		"inventories.title": false, "scope.partial": false, "scope.full": false,
		"inventory.title_partial": false, "inventory.title_full": false, "unit.balls_signed": true,
		"login.title": false, "validation.email": false, "error.email_not_allowed": false, "error.cross_origin": false,
		"error.forbidden": false, "error.rate_limit": false, "error.last_admin": false, "error.email_taken": false,
		"validation.name_required": false, "validation.too_long": false, "validation.role_required": false,
		"user.cannot_remove_self": false, "users.title": false, "user.title": false,
		"role.admin": false, "role.manager": false, "role.admin_help": false, "role.manager_help": false,
	}
	for d := 0; d < 7; d++ {
		keys["format.weekday."+string(rune('0'+d))] = false
	}
	used := regexp.MustCompile(`\b(Tn?) "([a-z_.]+)"`)
	err := fs.WalkDir(templateFiles, "templates", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		body, err := fs.ReadFile(templateFiles, path)
		for _, m := range used.FindAllStringSubmatch(string(body), -1) {
			keys[m[2]] = m[1] == "Tn"
		}
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, tag := range bundle.Languages() {
		loc := bundle.Localizer(tag.String(), "")
		for key, plural := range keys {
			data := map[string]any{"Limit": 1, "Date": "d", "Time": "t", "Weekday": "w", "Author": "a", "Signed": "+2", "Count": 2}
			msg := loc.T(key, data)
			if plural {
				msg = loc.Tn(key, 2, data)
			}
			if msg == key || msg == "" {
				t.Errorf("%s: %s is not translated", tag, key)
			}
		}
	}
}

func TestInventoryPages(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	for _, in := range []store.MovementInput{
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 4, Reference: &store.ReferenceFields{Brand: ptr("Katia")}},
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81790", Quantity: 2},
		{Kind: store.KindIn, EAN: "3664263018102", DyeLot: "A12", Quantity: 5, Reference: &store.ReferenceFields{Brand: ptr("Bergère de France")}},
	} {
		if _, _, _, err := st.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}
	inv, err := st.StartInventory(ctx, "Simon", store.ScopeFull)
	if err != nil {
		t.Fatal(err)
	}
	for _, in := range []store.CountInput{
		{InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 3},
		{InventoryID: inv.ID, EAN: "3664263018102", DyeLot: "A12", Quantity: 5},
	} {
		if _, _, _, err := st.Count(ctx, in); err != nil {
			t.Fatal(err)
		}
	}

	// En cours : le bain non compté est annoncé, pas encore remis à 0.
	_, _, body := get(t, srv, "/inventories/1", "fr")
	for _, want := range []string{"Inventaire complet du", "Lancé par Simon", "En cours", "1 bain en stock n’est pas encore compté", "−1 pelote"} {
		if !strings.Contains(body, want) {
			t.Errorf("open inventory: %q missing", want)
		}
	}

	if _, err := st.CloseInventory(ctx, inv.ID, "Marie"); err != nil {
		t.Fatal(err)
	}
	status, _, body := get(t, srv, "/inventories", "fr")
	for _, want := range []string{"Inventaires", "Complet", "Terminé le", "par Simon", "1 remis à 0", `href="/inventories/1"`, "−3 pelotes", `class="gap gap--negative"`} {
		if !strings.Contains(body, want) {
			t.Errorf("inventories: %q missing", want)
		}
	}
	if status != http.StatusOK {
		t.Fatalf("inventories status = %d", status)
	}

	_, _, body = get(t, srv, "/inventories/1", "fr")
	for _, want := range []string{"Terminé par Marie", "Anomalies", "81784", "81790", "non compté, remis à 0", "1 bain conforme au stock", "A12"} {
		if !strings.Contains(body, want) {
			t.Errorf("closed inventory: %q missing", want)
		}
	}
	// Le bain conforme est dans le bloc replié, pas parmi les anomalies.
	if i, j := strings.Index(body, "A12"), strings.Index(body, `class="conform"`); i < j {
		t.Errorf("conform lot listed among anomalies")
	}

	if status, _, _ := get(t, srv, "/inventories/42", "fr"); status != http.StatusNotFound {
		t.Errorf("unknown inventory: %d", status)
	}
}

func TestSignIn(t *testing.T) {
	srv, _ := newSite(t)
	request := func(method, path string, form url.Values, cookie string) *http.Request {
		var body io.Reader
		if form != nil {
			body = strings.NewReader(form.Encode())
		}
		req, _ := http.NewRequest(method, srv.URL+path, body)
		req.Header.Set("Accept-Language", "fr")
		if form != nil {
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		}
		if cookie != "" {
			req.AddCookie(&http.Cookie{Name: sessionCookie, Value: cookie})
		}
		return req
	}

	// Sans session, chaque page renvoie vers la connexion, puis y ramène.
	for path, next := range map[string]string{
		"/exits?from=2020-01-01": "/login?next=%2Fexits%3Ffrom%3D2020-01-01",
		"/nope":                  "/login?next=%2Fnope",
	} {
		status, header, _ := send(t, request(http.MethodGet, path, nil, ""))
		if status != http.StatusSeeOther || header.Get("Location") != next {
			t.Errorf("%s anonymous: %d → %q", path, status, header.Get("Location"))
		}
	}
	if status, header, _ := send(t, request(http.MethodGet, "/stock", nil, "jeton-inconnu")); status != http.StatusSeeOther ||
		!strings.HasPrefix(header.Get("Location"), "/login") {
		t.Errorf("unknown token: %d → %q", status, header.Get("Location"))
	}

	status, _, body := send(t, request(http.MethodGet, "/login?next=/entries", nil, ""))
	if status != http.StatusOK || strings.Contains(body, `class="masthead"`) ||
		!strings.Contains(body, `name="next" value="/entries"`) || !strings.Contains(body, "Se connecter") {
		t.Fatalf("login form: %d %s", status, body)
	}

	status, _, body = send(t, request(http.MethodPost, "/login", url.Values{"email": {"pas-une-adresse"}}, ""))
	if status != http.StatusUnprocessableEntity || !strings.Contains(body, "Saisissez une adresse email valide") || !strings.Contains(body, `aria-invalid="true"`) {
		t.Errorf("invalid email: %d", status)
	}
	status, _, body = send(t, request(http.MethodPost, "/login", url.Values{"email": {"marie@example.com"}}, ""))
	if status != http.StatusForbidden || !strings.Contains(body, "n’est pas autorisée") || !strings.Contains(body, `value="marie@example.com"`) {
		t.Errorf("unknown email: %d", status)
	}

	// Connexion : un cookie de session, puis retour à la page demandée.
	status, header, _ := send(t, request(http.MethodPost, "/login",
		url.Values{"email": {"  SIMON@example.com "}, "next": {"/exits?from=2020-01-01"}}, ""))
	if status != http.StatusSeeOther || header.Get("Location") != "/exits?from=2020-01-01" {
		t.Fatalf("login: %d → %q", status, header.Get("Location"))
	}
	cookie := header.Get("Set-Cookie")
	for _, want := range []string{sessionCookie + "=", "HttpOnly", "SameSite=Lax", "Max-Age=34560000"} {
		if !strings.Contains(cookie, want) {
			t.Errorf("cookie %q: %q missing", cookie, want)
		}
	}
	if strings.Contains(cookie, "Secure") {
		t.Errorf("plain HTTP cookie marked Secure: %q", cookie)
	}
	token := strings.TrimPrefix(strings.SplitN(cookie, ";", 2)[0], sessionCookie+"=")

	if status, _, body := send(t, request(http.MethodGet, "/exits", nil, token)); status != http.StatusOK || !strings.Contains(body, "Se déconnecter") {
		t.Errorf("signed in: %d", status)
	}
	if status, header, _ := send(t, request(http.MethodGet, "/login", nil, token)); status != http.StatusSeeOther || header.Get("Location") != "/stock" {
		t.Errorf("login while signed in: %d → %q", status, header.Get("Location"))
	}

	// Derrière un reverse proxy HTTPS, le cookie ne voyage qu'en HTTPS.
	req := request(http.MethodPost, "/login", url.Values{"email": {"simon@example.com"}}, "")
	req.Header.Set("X-Forwarded-Proto", "https")
	if _, header, _ := send(t, req); !strings.Contains(header.Get("Set-Cookie"), "Secure") {
		t.Errorf("https cookie: %q", header.Get("Set-Cookie"))
	}

	// Un formulaire posté depuis un autre site est refusé, la session reste ouverte.
	req = request(http.MethodPost, "/logout", url.Values{}, token)
	req.Header.Set("Sec-Fetch-Site", "cross-site")
	if status, _, body := send(t, req); status != http.StatusForbidden || !strings.Contains(body, "Requête refusée") {
		t.Errorf("cross-site logout: %d %s", status, body)
	}
	if status, _, _ := send(t, request(http.MethodGet, "/stock", nil, token)); status != http.StatusOK {
		t.Errorf("session closed by a cross-site form: %d", status)
	}

	req = request(http.MethodPost, "/logout", url.Values{}, token)
	req.Header.Set("Sec-Fetch-Site", "same-origin")
	status, header, _ = send(t, req)
	if status != http.StatusSeeOther || header.Get("Location") != "/login" || !strings.Contains(header.Get("Set-Cookie"), "Max-Age=0") {
		t.Errorf("logout: %d → %q, %q", status, header.Get("Location"), header.Get("Set-Cookie"))
	}
	if status, _, _ := send(t, request(http.MethodGet, "/stock", nil, token)); status != http.StatusSeeOther {
		t.Errorf("after logout: %d", status)
	}
}

func TestUserPages(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	simon, err := st.UserByEmail(ctx, "simon@example.com")
	if err != nil {
		t.Fatal(err)
	}
	marie, err := st.CreateUser(ctx, "marie@example.com", "Marie", store.RoleManager, simon.ID)
	if err != nil {
		t.Fatal(err)
	}
	marieToken, err := st.OpenSession(ctx, marie.ID, "Pixel 6")
	if err != nil {
		t.Fatal(err)
	}
	as := func(token, method, path string, form url.Values) (int, http.Header, string) {
		t.Helper()
		var body io.Reader
		if form != nil {
			body = strings.NewReader(form.Encode())
		}
		req, _ := http.NewRequest(method, srv.URL+path, body)
		req.Header.Set("Accept-Language", "fr")
		if form != nil {
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		}
		req.AddCookie(&http.Cookie{Name: sessionCookie, Value: token})
		return send(t, req)
	}
	marieURL := "/users/" + strconv.FormatInt(marie.ID, 10)
	simonURL := "/users/" + strconv.FormatInt(simon.ID, 10)

	status, _, body := as(srv.token, http.MethodGet, "/users", nil)
	for _, want := range []string{`href="/users" aria-current="page"`, "2 comptes", "(vous)", `href="` + marieURL + `"`,
		"marie@example.com", "Gestionnaire", "Administrateur", "1 session ouverte", "Dernière activité le",
		`<form class="form" method="post" action="/users">`, `value="manager" checked`, "Laissez vide pour le déduire"} {
		if !strings.Contains(body, want) {
			t.Errorf("users: %q missing", want)
		}
	}
	if status != http.StatusOK {
		t.Fatalf("users: %d", status)
	}

	// Un gestionnaire ne voit ni l'onglet ni les pages.
	if _, _, body := as(marieToken, http.MethodGet, "/stock", nil); strings.Contains(body, `href="/users"`) {
		t.Errorf("manager sees the users tab")
	}
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		if status, _, body := as(marieToken, method, "/users", url.Values{}); status != http.StatusForbidden || !strings.Contains(body, "Seul un administrateur") {
			t.Errorf("manager %s /users: %d", method, status)
		}
	}

	// Ajout : le nom laissé vide se déduit de l'adresse.
	status, header, _ := as(srv.token, http.MethodPost, "/users", url.Values{"email": {" Jean-Luc.Dupont+laine@Example.com "}, "name": {""}, "role": {"manager"}})
	if status != http.StatusSeeOther || !strings.HasPrefix(header.Get("Location"), "/users?created=") {
		t.Fatalf("create: %d → %q", status, header.Get("Location"))
	}
	if _, _, body := as(srv.token, http.MethodGet, header.Get("Location"), nil); !strings.Contains(body, "Jean-Luc Dupont peut maintenant se connecter") ||
		!strings.Contains(body, "Aucune session ouverte") {
		t.Errorf("created notice or idle account missing")
	}
	if u, err := st.UserByEmail(ctx, "jean-luc.dupont+laine@example.com"); err != nil || u.Name != "Jean-Luc Dupont" || u.CreatedAt.IsZero() {
		t.Errorf("created user = %+v, err = %v", u, err)
	}
	for _, c := range []struct {
		form   url.Values
		status int
		want   string
	}{
		{url.Values{"email": {"pas-une-adresse"}, "role": {"manager"}}, http.StatusUnprocessableEntity, "Saisissez une adresse email valide"},
		{url.Values{"email": {"marie@example.com"}, "role": {"manager"}}, http.StatusConflict, "Cette adresse a déjà un compte"},
		{url.Values{"email": {"lea@example.com"}, "role": {"chef"}}, http.StatusUnprocessableEntity, "Choisissez un droit"},
		{url.Values{"email": {"1234@example.com"}, "role": {"admin"}}, http.StatusUnprocessableEntity, "Le nom est obligatoire"},
		{url.Values{"email": {"lea@example.com"}, "name": {strings.Repeat("a", 101)}, "role": {"admin"}}, http.StatusUnprocessableEntity, "100 caractères au maximum"},
	} {
		status, _, body := as(srv.token, http.MethodPost, "/users", c.form)
		if status != c.status || !strings.Contains(body, c.want) || !strings.Contains(body, `aria-invalid="true"`) {
			t.Errorf("create %v: %d, %q missing", c.form, status, c.want)
		}
	}

	// Fiche d'un autre compte : modification, sessions, retrait.
	status, _, body = as(srv.token, http.MethodGet, marieURL, nil)
	for _, want := range []string{"Tous les utilisateurs", `value="marie@example.com"`, `name="role" value="manager" checked`,
		"Fermer la session", "Retirer l’accès…", "Oui, retirer l’accès de Marie"} {
		if !strings.Contains(body, want) {
			t.Errorf("user page: %q missing", want)
		}
	}
	status, header, _ = as(srv.token, http.MethodPost, marieURL, url.Values{"email": {"marie.curie@example.com"}, "name": {"Marie Curie"}, "role": {"admin"}})
	if status != http.StatusSeeOther || header.Get("Location") != marieURL+"?saved=1" {
		t.Fatalf("update: %d → %q", status, header.Get("Location"))
	}
	if u, _ := st.User(ctx, marie.ID); u.Email != "marie.curie@example.com" || u.Name != "Marie Curie" || !u.IsAdmin() {
		t.Errorf("updated = %+v", u)
	}
	if status, _, body := as(srv.token, http.MethodPost, marieURL, url.Values{"email": {"simon@example.com"}, "name": {"Marie"}, "role": {"admin"}}); status != http.StatusConflict ||
		!strings.Contains(body, "Cette adresse a déjà un compte") {
		t.Errorf("update with a taken email: %d", status)
	}

	// Sa propre fiche : ni droit, ni retrait ; un droit envoyé quand même est ignoré.
	_, _, body = as(srv.token, http.MethodGet, simonURL, nil)
	if strings.Contains(body, `name="role"`) || strings.Contains(body, "Retirer l’accès") || !strings.Contains(body, "C’est votre compte.") {
		t.Errorf("own page offers role or removal")
	}
	if status, _, _ := as(srv.token, http.MethodPost, simonURL, url.Values{"email": {"simon@example.com"}, "name": {"Simon L."}, "role": {"manager"}}); status != http.StatusSeeOther {
		t.Errorf("own update: %d", status)
	}
	if u, _ := st.User(ctx, simon.ID); u.Name != "Simon L." || !u.IsAdmin() {
		t.Errorf("own update = %+v", u)
	}
	if status, _, body := as(srv.token, http.MethodPost, simonURL+"/delete", url.Values{}); status != http.StatusForbidden ||
		!strings.Contains(body, "Vous ne pouvez pas retirer votre propre accès") {
		t.Errorf("own removal: %d", status)
	}

	status, header, _ = as(srv.token, http.MethodPost, marieURL+"/sessions", url.Values{})
	if status != http.StatusSeeOther || header.Get("Location") != marieURL+"?closed=1" {
		t.Fatalf("close sessions: %d → %q", status, header.Get("Location"))
	}
	if _, _, body := as(srv.token, http.MethodGet, header.Get("Location"), nil); !strings.Contains(body, "1 session fermée.") {
		t.Errorf("closed notice missing")
	}
	if status, _, _ := as(marieToken, http.MethodGet, "/stock", nil); status != http.StatusSeeOther {
		t.Errorf("closed session still open: %d", status)
	}

	status, header, _ = as(srv.token, http.MethodPost, marieURL+"/delete", url.Values{})
	if status != http.StatusSeeOther || header.Get("Location") != "/users?removed=1" {
		t.Fatalf("remove: %d → %q", status, header.Get("Location"))
	}
	if _, _, body := as(srv.token, http.MethodGet, "/users?removed=1", nil); !strings.Contains(body, "Accès retiré") || strings.Contains(body, "marie.curie@example.com") {
		t.Errorf("after removal: Marie still listed")
	}
	if status, _, _ := as(srv.token, http.MethodGet, marieURL, nil); status != http.StatusNotFound {
		t.Errorf("removed user page: %d", status)
	}
}

func TestGuessName(t *testing.T) {
	for email, want := range map[string]string{
		"jean-luc.dupont+laine@example.com": "Jean-Luc Dupont",
		"MARIE_curie@example.com":           "Marie Curie",
		"lea2026@example.com":               "Lea",
		"-.-@example.com":                   "",
		"élodie.ÉTÉ@example.com":            "Élodie Été",
	} {
		if got := guessName(email); got != want {
			t.Errorf("guessName(%q) = %q, want %q", email, got, want)
		}
	}
}

func TestLocalPath(t *testing.T) {
	for next, want := range map[string]string{
		"":                       "/stock",
		"/exits?from=2026-10-01": "/exits?from=2026-10-01",
		"/inventories/3":         "/inventories/3",
		"//evil.example/stock":   "/stock",
		"/\\evil.example":        "/stock",
		"https://evil.example/":  "/stock",
		"stock":                  "/stock",
		"/login?next=/stock":     "/stock",
	} {
		if got := localPath(next); got != want {
			t.Errorf("localPath(%q) = %q, want %q", next, got, want)
		}
	}
}

func TestStaticAssets(t *testing.T) {
	srv := httptest.NewServer(Static())
	t.Cleanup(srv.Close)
	for path, want := range map[string]string{"/static/logo.svg": "image/svg+xml", "/static/style.css": "text/css"} {
		res, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		if res.StatusCode != http.StatusOK || !strings.HasPrefix(res.Header.Get("Content-Type"), want) {
			t.Errorf("%s: status %d, type %q", path, res.StatusCode, res.Header.Get("Content-Type"))
		}
	}
}
