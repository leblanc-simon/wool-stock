package web

import (
	"context"
	"errors"
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
		{EAN: "5", DyeLot: "E", Quantity: 0, Brand: ptr("Katia"), ColorCode: ptr("9")},
		{EAN: "4", DyeLot: "", Quantity: 3},
	})
	if view.Balls != 10 || view.EmptyColors != 1 || len(view.Brands) != 2 {
		t.Fatalf("view = %+v", view)
	}
	katia := view.Brands[0]
	if katia.Total != 7 || len(katia.Colors) != 3 {
		t.Fatalf("katia = %+v", katia)
	}
	// Deux qualités au même coloris : un seul groupe, deux EAN ; le bain épuisé disparaît.
	if katia.Brand != "Katia" {
		t.Fatalf("brand spelling = %q", katia.Brand)
	}
	if white := katia.Colors[0]; len(white.Lots) != 2 || white.Lots[0].DyeLot != "A" || white.Lots[1].DyeLot != "C" ||
		len(white.EANs) != 2 || white.Total != 3 {
		t.Fatalf("white = %+v", white)
	}
	// Un coloris épuisé reste, sans bain.
	if empty := katia.Colors[2]; empty.Code != "9" || empty.Total != 0 || len(empty.Lots) != 0 {
		t.Fatalf("empty shade = %+v", empty)
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
		{Kind: store.KindIn, EAN: "3664263018102", DyeLot: "A12", Quantity: 2,
			Reference: &store.ReferenceFields{Brand: ptr("Bergère de France"), ColorCode: ptr("10")}},
		{Kind: store.KindOut, EAN: "3664263018102", DyeLot: "A12", Quantity: 2, Recipient: "Léa"},
	} {
		if _, _, _, err := st.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}

	status, header, body := get(t, srv, "/stock", "fr")
	if status != http.StatusOK || !strings.Contains(header.Get("Content-Security-Policy"), "default-src 'none'") {
		t.Fatalf("stock: status %d, headers %v", status, header)
	}
	for _, want := range []string{`lang="fr"`, `rel="icon" type="image/svg+xml" href="/static/logo.svg?v=`, `class="masthead__logo"`, "Katia", "blanc", "81784", "1 pelote en stock.", "1 coloris épuisé reste dans la liste.", `aria-current="page"`,
		"Bergère de France", `class="shade shade--empty"`, "0 pelote",
		`<form class="account" method="post" action="/logout">`, `title="simon@example.com">Simon</span>`, "Se déconnecter"} {
		if !strings.Contains(body, want) {
			t.Errorf("stock: %q missing", want)
		}
	}

	// Les bains épuisés (81790, A12) ne s'affichent plus ; leur coloris, si.
	for _, gone := range []string{"81790", "A12"} {
		if strings.Contains(body, gone) {
			t.Errorf("stock: empty dye lot %s listed", gone)
		}
	}

	_, _, body = get(t, srv, "/entries", "en")
	for _, want := range []string{"Entries", "Simon", "anonymous", "6 balls added over this period."} {
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
		"validation.ean_required": false, "validation.ean_format": false, "validation.quantity": false,
		"validation.count": false, "validation.mode": false, "validation.lot_required": false,
		"validation.recipient_required": false, "validation.scope_required": false,
		"error.insufficient_stock": true, "error.already_counted": true, "error.inventory_open": false,
		"error.inventory_closed": false, "scope.partial_help": false, "scope.full_help": false,
		"count.title": false, "count.closed": false, "count.lead_partial": false, "count.lead_full": false,
		"count.close_help_partial": false, "count.close_help_full": false,
		"export.filename": false, "export.stock_sheet": false, "export.stock_title": false, "export.brand": false,
		"export.col_shade": false, "export.col_dye_lot": false, "export.col_code": false, "export.col_stock": false,
		"export.exits_sheet": false, "export.exits_title": false, "export.col_date": false,
		"export.col_recipient": false, "export.col_balls": false, "export.col_detail": false,
		"export.item": true, "export.item_no_dye_lot": true, "stock.unknown_color": false,
		"format.spreadsheet_date": false, "format.spreadsheet_balls": false,
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

// post envoie un formulaire avec la session de token (aucune si vide), sans suivre les redirections.
func post(t *testing.T, srv *testSite, token, path string, form url.Values) (int, http.Header, string) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, srv.URL+path, strings.NewReader(form.Encode()))
	req.Header.Set("Accept-Language", "fr")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if token != "" {
		req.AddCookie(&http.Cookie{Name: sessionCookie, Value: token})
	}
	return send(t, req)
}

// lotIDs donne l'identifiant de chaque bain, par numéro de bain.
func lotIDs(t *testing.T, st *store.Store) map[string]string {
	t.Helper()
	items, err := st.Stock(context.Background(), store.StockFilter{IncludeEmpty: true})
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]string{}
	for _, item := range items {
		ids[item.DyeLot] = strconv.FormatInt(item.LotID, 10)
	}
	return ids
}

func TestGroupShelf(t *testing.T) {
	brands := groupShelf([]store.StockItem{
		{LotID: 1, EAN: "1", DyeLot: "A", Quantity: 2, Brand: ptr("katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")},
		{LotID: 2, EAN: "1", DyeLot: "B", Quantity: 0, Brand: ptr("Katia"), ColorCode: ptr("1"), ColorName: ptr("Blanc")},
		{LotID: 3, EAN: "2", DyeLot: "C", Quantity: 1, Brand: ptr("Katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")},
		{LotID: 4, EAN: "4", Quantity: 3},
	}, map[int64]store.InventoryCount{2: {LotID: 2, Quantity: 5}})
	if len(brands) != 2 || brands[0].Brand != "Katia" || brands[1].Brand != "" {
		t.Fatalf("brands = %+v", brands)
	}
	// Le bain épuisé reste ; deux qualités au même coloris donnent un seul coloris, deux EAN.
	white := brands[0].Shades
	if len(white) != 1 || len(white[0].Lots) != 3 || len(white[0].EANs) != 2 || white[0].Label() != "1 blanc" {
		t.Fatalf("white = %+v", white)
	}
	if lots := white[0].Lots; lots[0].Count != nil || lots[1].Count == nil || lots[1].Count.Quantity != 5 {
		t.Fatalf("counts = %+v", lots)
	}
	if unknown := brands[1].Shades[0]; unknown.Label() != "" || unknown.Lots[0].DyeLot != "" {
		t.Fatalf("unknown = %+v", unknown)
	}
}

func TestEntryForm(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	if _, err := st.SaveReference(ctx, "8435407948594", store.ReferenceFields{Brand: ptr("Katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")}); err != nil {
		t.Fatal(err)
	}

	status, _, body := get(t, srv, "/entries", "fr")
	for _, want := range []string{"Nouvelle entrée", `<form class="form" method="post" action="/entries">`, `name="request_id" value="web-`,
		`<option value="8435407948594">Katia 1 blanc</option>`, `<option value="Katia">`, "Aucune pelote ajoutée"} {
		if !strings.Contains(body, want) {
			t.Errorf("entries form: %q missing", want)
		}
	}
	if status != http.StatusOK {
		t.Fatalf("entries: %d", status)
	}

	// Une référence inconnue : marque et coloris remplissent le cache ; l'UPC-A devient un EAN-13.
	form := url.Values{"request_id": {"web-test-1"}, "ean": {" 0 12345 67890 5 "}, "dye_lot": {" A12 "}, "quantity": {"3"},
		"brand": {"Drops"}, "color_code": {"07"}, "color_name": {""}}
	status, header, _ := post(t, srv, srv.token, "/entries", form)
	added := header.Get("Location")
	if status != http.StatusSeeOther || !strings.HasPrefix(added, "/entries?added=") {
		t.Fatalf("create: %d → %q", status, added)
	}
	// Le même envoi (double clic) n'est compté qu'une fois.
	if _, header, _ := post(t, srv, srv.token, "/entries", form); header.Get("Location") != added {
		t.Errorf("replay → %q, want %q", header.Get("Location"), added)
	}
	_, _, body = get(t, srv, added, "fr")
	for _, want := range []string{`role="status">3 pelotes ajoutées au stock :`, "Drops", "Ce bain compte maintenant 3 pelotes.", `class="ledger__fresh"`, "Simon"} {
		if !strings.Contains(body, want) {
			t.Errorf("after create: %q missing", want)
		}
	}
	ref, err := st.Reference(ctx, "0012345678905")
	if err != nil || deref(ref.Brand) != "Drops" || deref(ref.ColorCode) != "07" || ref.ColorName != nil {
		t.Errorf("reference = %+v, err = %v", ref, err)
	}

	// Un EAN connu, marque et coloris laissés vides : le cache reste tel quel.
	if status, _, _ := post(t, srv, srv.token, "/entries", url.Values{"request_id": {"web-test-2"}, "ean": {"8435407948594"}, "dye_lot": {"81784"}, "quantity": {"2"}}); status != http.StatusSeeOther {
		t.Fatalf("known EAN: %d", status)
	}
	if ref, _ := st.Reference(ctx, "8435407948594"); deref(ref.Brand) != "Katia" || deref(ref.ColorName) != "blanc" {
		t.Errorf("known reference changed: %+v", ref)
	}

	// Le même identifiant avec un autre contenu (formulaire rouvert par « Précédent ») est une nouvelle saisie.
	form.Set("quantity", "4")
	if _, header, _ := post(t, srv, srv.token, "/entries", form); header.Get("Location") == added {
		t.Errorf("modified form taken for a replay")
	}
	movements, _ := st.Movements(ctx, store.MovementFilter{Limit: 10})
	if len(movements) != 3 || movements[0].Quantity != 4 || deref(movements[0].Author) != "Simon" {
		t.Fatalf("movements = %+v", movements)
	}

	// Validation, comme l'API ; le formulaire garde la saisie.
	for _, c := range []struct {
		form url.Values
		want string
	}{
		{url.Values{"ean": {""}, "quantity": {"1"}}, "L’EAN est obligatoire"},
		{url.Values{"ean": {"1234"}, "quantity": {"1"}}, "8, 12, 13 ou 14 chiffres attendus"},
		{url.Values{"ean": {"12345670"}, "quantity": {"0"}}, "Nombre entier entre 1 et 10 000"},
		{url.Values{"ean": {"12345670"}, "quantity": {"trois"}}, "Nombre entier entre 1 et 10 000"},
		{url.Values{"ean": {"12345670"}, "quantity": {"10001"}}, "Nombre entier entre 1 et 10 000"},
		{url.Values{"ean": {"12345670"}, "quantity": {"1"}, "dye_lot": {strings.Repeat("9", 33)}}, "32 caractères au maximum"},
		{url.Values{"ean": {"12345670"}, "quantity": {"1"}, "brand": {strings.Repeat("é", 101)}}, "100 caractères au maximum"},
	} {
		status, _, body := post(t, srv, srv.token, "/entries", c.form)
		if status != http.StatusUnprocessableEntity || !strings.Contains(body, c.want) || !strings.Contains(body, `aria-invalid="true"`) ||
			!strings.Contains(body, `value="`+c.form.Get("ean")+`"`) {
			t.Errorf("create %v: %d, %q missing", c.form, status, c.want)
		}
	}
	if movements, _ := st.Movements(ctx, store.MovementFilter{Limit: 10}); len(movements) != 3 {
		t.Errorf("invalid entries recorded: %d movements", len(movements))
	}
}

func TestExitForm(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	for _, in := range []store.MovementInput{
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 3,
			Reference: &store.ReferenceFields{Brand: ptr("Katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")}},
		{Kind: store.KindIn, EAN: "12345670", DyeLot: "", Quantity: 1},
		{Kind: store.KindIn, EAN: "3664263018102", DyeLot: "A12", Quantity: 2, Reference: &store.ReferenceFields{Brand: ptr("Drops")}},
		{Kind: store.KindOut, EAN: "3664263018102", DyeLot: "A12", Quantity: 2, Recipient: "Léa"},
	} {
		if _, _, _, err := st.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}
	lots := lotIDs(t, st)

	_, _, body := get(t, srv, "/exits", "fr")
	for _, want := range []string{"Nouvelle sortie", `<optgroup label="Katia">`, `<option value="` + lots["81784"] + `">1 blanc · bain 81784 · 3 pelotes · EAN 8435407948594</option>`,
		`<optgroup label="Marque inconnue">`, "Coloris inconnu · sans bain · 1 pelote · EAN 12345670", `<option value="Léa">`} {
		if !strings.Contains(body, want) {
			t.Errorf("exits form: %q missing", want)
		}
	}
	// Le bain épuisé n'est pas proposé.
	if strings.Contains(body, `<option value="`+lots["A12"]+`"`) {
		t.Errorf("empty dye lot offered")
	}

	status, header, _ := post(t, srv, srv.token, "/exits", url.Values{"request_id": {"web-out-1"}, "lot": {lots["81784"]}, "quantity": {"2"}, "recipient": {" Marie "}})
	taken := header.Get("Location")
	if status != http.StatusSeeOther || !strings.HasPrefix(taken, "/exits?taken=") {
		t.Fatalf("exit: %d → %q", status, taken)
	}
	_, _, body = get(t, srv, taken, "fr")
	for _, want := range []string{"2 pelotes sorties pour Marie :", "Il reste 1 pelote de ce bain.", "Saisie par Simon à"} {
		if !strings.Contains(body, want) {
			t.Errorf("after exit: %q missing", want)
		}
	}
	movements, _ := st.Movements(ctx, store.MovementFilter{Kind: store.KindOut, Limit: 10})
	if len(movements) != 2 || deref(movements[0].Recipient) != "Marie" || deref(movements[0].Author) != "Simon" || movements[0].DyeLot != "81784" {
		t.Fatalf("exits = %+v", movements)
	}

	// Stock insuffisant : le formulaire dit combien il en reste et garde la saisie.
	status, _, body = post(t, srv, srv.token, "/exits", url.Values{"lot": {lots["81784"]}, "quantity": {"5"}, "recipient": {"Marie"}})
	if status != http.StatusConflict || !strings.Contains(body, "Stock insuffisant pour ce bain : il reste 1 pelote") ||
		!strings.Contains(body, `value="5"`) || !strings.Contains(body, `<option value="`+lots["81784"]+`" selected>`) {
		t.Errorf("insufficient stock: %d", status)
	}
	// Un bain épuisé entre-temps reste choisi, pour que le message garde son contexte.
	status, _, body = post(t, srv, srv.token, "/exits", url.Values{"lot": {lots["A12"]}, "quantity": {"1"}, "recipient": {"Marie"}})
	if status != http.StatusConflict || !strings.Contains(body, "il reste 0 pelote") || !strings.Contains(body, `<option value="`+lots["A12"]+`" selected>`) {
		t.Errorf("empty dye lot: %d", status)
	}

	for _, c := range []struct {
		form url.Values
		want string
	}{
		{url.Values{"lot": {""}, "quantity": {"1"}, "recipient": {"Marie"}}, "Choisissez un bain en stock"},
		{url.Values{"lot": {"9999"}, "quantity": {"1"}, "recipient": {"Marie"}}, "Choisissez un bain en stock"},
		{url.Values{"lot": {lots["81784"]}, "quantity": {"0"}, "recipient": {"Marie"}}, "Nombre entier entre 1 et 10 000"},
		{url.Values{"lot": {lots["81784"]}, "quantity": {"1"}, "recipient": {"  "}}, "Indiquez pour qui les pelotes sont sorties"},
		{url.Values{"lot": {lots["81784"]}, "quantity": {"1"}, "recipient": {strings.Repeat("a", 101)}}, "100 caractères au maximum"},
	} {
		status, _, body := post(t, srv, srv.token, "/exits", c.form)
		if status != http.StatusUnprocessableEntity || !strings.Contains(body, c.want) || !strings.Contains(body, `aria-invalid="true"`) {
			t.Errorf("exit %v: %d, %q missing", c.form, status, c.want)
		}
	}
	if items, _ := st.Stock(ctx, store.StockFilter{EAN: "8435407948594"}); len(items) != 1 || items[0].Quantity != 1 {
		t.Errorf("stock after refused exits = %+v", items)
	}

	// Sans stock, rien à choisir.
	empty, _ := newSite(t)
	if _, _, body := get(t, empty, "/exits", "fr"); !strings.Contains(body, "Aucun bain en stock") || strings.Contains(body, `action="/exits"`) {
		t.Errorf("exits without stock offer a form")
	}
}

func TestInventoryForms(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	for _, in := range []store.MovementInput{
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 4,
			Reference: &store.ReferenceFields{Brand: ptr("Katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")}},
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81790", Quantity: 2},
		{Kind: store.KindIn, EAN: "3664263018102", DyeLot: "A12", Quantity: 5, Reference: &store.ReferenceFields{Brand: ptr("Bergère de France")}},
		{Kind: store.KindIn, EAN: "3664263018102", DyeLot: "B0", Quantity: 1},
		{Kind: store.KindOut, EAN: "3664263018102", DyeLot: "B0", Quantity: 1, Recipient: "Léa"},
	} {
		if _, _, _, err := st.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}
	lots := lotIDs(t, st)
	counted := func(dyeLot string) int {
		t.Helper()
		inv, err := st.CurrentInventory(ctx)
		if err != nil {
			t.Fatal(err)
		}
		counts, _ := st.InventoryCounts(ctx, inv.ID)
		for _, c := range counts {
			if c.DyeLot == dyeLot {
				return c.Quantity
			}
		}
		return -1
	}

	_, _, body := get(t, srv, "/inventories", "fr")
	for _, want := range []string{"Lancer un inventaire", `<form class="form" method="post" action="/inventories">`, `value="partial" checked`, "Seuls les bains comptés changent", "Aucun inventaire"} {
		if !strings.Contains(body, want) {
			t.Errorf("start form: %q missing", want)
		}
	}
	if status, _, body := post(t, srv, srv.token, "/inventories", url.Values{"scope": {"chef"}}); status != http.StatusUnprocessableEntity || !strings.Contains(body, "Choisissez le type d’inventaire") {
		t.Errorf("invalid scope: %d", status)
	}
	status, header, _ := post(t, srv, srv.token, "/inventories", url.Values{"scope": {"full"}})
	if status != http.StatusSeeOther || header.Get("Location") != "/inventories/1/count" {
		t.Fatalf("start: %d → %q", status, header.Get("Location"))
	}
	// Un seul inventaire à la fois : la page renvoie vers celui en cours.
	status, _, body = post(t, srv, srv.token, "/inventories", url.Values{"scope": {"partial"}})
	if status != http.StatusConflict || !strings.Contains(body, "Un inventaire est déjà en cours") || !strings.Contains(body, `href="/inventories/1/count"`) {
		t.Errorf("second start: %d", status)
	}
	if inv, err := st.CurrentInventory(ctx); err != nil || inv.Scope != store.ScopeFull || deref(inv.StartedBy) != "Simon" {
		t.Fatalf("current = %+v, err = %v", inv, err)
	}

	_, _, body = get(t, srv, "/inventories/1/count", "fr")
	for _, want := range []string{"Inventaire complet du", "Lancé par Simon", "0 bain compté sur 3.", "3 bains en stock ne sont pas encore comptés",
		`id="lot-` + lots["81784"] + `"`, "Valider le stock", `name="quantity" value="4"`, "Bergère de France", "Afficher aussi le bain épuisé", `href="#close"`} {
		if !strings.Contains(body, want) {
			t.Errorf("count sheet: %q missing", want)
		}
	}
	// Le bain épuisé n'apparaît qu'à la demande.
	if strings.Contains(body, `id="lot-`+lots["B0"]+`"`) {
		t.Errorf("empty dye lot listed")
	}
	if _, _, body := get(t, srv, "/inventories/1/count?empty=1", "fr"); !strings.Contains(body, `id="lot-`+lots["B0"]+`"`) || !strings.Contains(body, "Masquer le bain épuisé") {
		t.Errorf("empty dye lot hidden with ?empty=1")
	}

	// « Valider le stock » : le compté vaut le stock affiché ; retour sur le bain.
	count := func(form url.Values) (int, http.Header, string) {
		t.Helper()
		return post(t, srv, srv.token, "/inventories/1/count", form)
	}
	status, header, _ = count(url.Values{"request_id": {"web-c1"}, "lot": {lots["81784"]}, "quantity": {"4"}, "mode": {"new"}})
	if want := "/inventories/1/count?counted=" + lots["81784"] + "#lot-" + lots["81784"]; status != http.StatusSeeOther || header.Get("Location") != want {
		t.Fatalf("confirm: %d → %q, want %q", status, header.Get("Location"), want)
	}
	_, _, body = get(t, srv, "/inventories/1/count?counted="+lots["81784"], "fr")
	for _, want := range []string{`role="status">Enregistré`, "Compté <b>4</b>", "1 bain compté sur 3."} {
		if !strings.Contains(body, want) {
			t.Errorf("after confirm: %q missing", want)
		}
	}

	// Une autre quantité, bains épuisés affichés : le retour les garde.
	status, header, _ = count(url.Values{"request_id": {"web-c2"}, "lot": {lots["81790"]}, "quantity": {"1"}, "mode": {"new"}, "empty": {"1"}})
	if want := "/inventories/1/count?counted=" + lots["81790"] + "&empty=1#lot-" + lots["81790"]; status != http.StatusSeeOther || header.Get("Location") != want {
		t.Fatalf("count: %d → %q", status, header.Get("Location"))
	}
	// Déjà compté (ici ou par un téléphone) : refusé, puis ajouté ou remplacé.
	status, _, body = count(url.Values{"lot": {lots["81790"]}, "quantity": {"3"}, "mode": {"new"}})
	if status != http.StatusConflict || !strings.Contains(body, "Ce bain a déjà été compté dans cet inventaire : 1 pelote") ||
		!strings.Contains(body, `value="3" autofocus`) || !strings.Contains(body, `value="set"`) {
		t.Errorf("already counted: %d", status)
	}
	add := url.Values{"request_id": {"web-c3"}, "lot": {lots["81790"]}, "quantity": {"1"}, "mode": {"add"}}
	for range 2 { // le double clic n'ajoute qu'une fois
		if status, _, _ := count(add); status != http.StatusSeeOther {
			t.Fatalf("add: %d", status)
		}
	}
	if n := counted("81790"); n != 2 {
		t.Errorf("after add: counted %d, want 2", n)
	}
	if status, _, _ := count(url.Values{"lot": {lots["81790"]}, "quantity": {"3"}, "mode": {"set"}}); status != http.StatusSeeOther || counted("81790") != 3 {
		t.Errorf("set: %d, counted %d", status, counted("81790"))
	}

	for _, c := range []struct {
		form url.Values
		want string
	}{
		{url.Values{"lot": {lots["A12"]}, "quantity": {"-1"}}, "Nombre entier entre 0 et 10 000"},
		{url.Values{"lot": {lots["A12"]}, "quantity": {""}}, "Nombre entier entre 0 et 10 000"},
		{url.Values{"lot": {"9999"}, "quantity": {"1"}}, "Choisissez un bain en stock"},
		{url.Values{"lot": {lots["A12"]}, "quantity": {"1"}, "mode": {"merge"}}, "Le mode doit valoir"},
	} {
		if status, _, body := count(c.form); status != http.StatusUnprocessableEntity || !strings.Contains(body, c.want) {
			t.Errorf("count %v: %d, %q missing", c.form, status, c.want)
		}
	}

	// Terminer : le bain A12, en stock et pas compté, passe à 0.
	status, header, _ = post(t, srv, srv.token, "/inventories/1/close", url.Values{})
	if status != http.StatusSeeOther || header.Get("Location") != "/inventories/1?closed=1" {
		t.Fatalf("close: %d → %q", status, header.Get("Location"))
	}
	_, _, body = get(t, srv, "/inventories/1?closed=1", "fr")
	for _, want := range []string{"Inventaire terminé.", "Terminé par Simon", "non compté, remis à 0", "A12"} {
		if !strings.Contains(body, want) {
			t.Errorf("closed: %q missing", want)
		}
	}
	if strings.Contains(body, "Continuer le comptage") {
		t.Errorf("closed inventory offers to continue counting")
	}
	if items, _ := st.Stock(ctx, store.StockFilter{EAN: "3664263018102", IncludeEmpty: true}); items[0].Quantity != 0 {
		t.Errorf("uncounted lot = %+v", items[0])
	}

	// Terminé, l'inventaire ne se compte ni ne se termine plus.
	if status, header, _ := get(t, srv, "/inventories/1/count", "fr"); status != http.StatusSeeOther || header.Get("Location") != "/inventories/1" {
		t.Errorf("count sheet of a closed inventory: %d → %q", status, header.Get("Location"))
	}
	if status, _, body := count(url.Values{"lot": {lots["A12"]}, "quantity": {"1"}}); status != http.StatusConflict || !strings.Contains(body, "ce comptage n’a pas été enregistré") {
		t.Errorf("count after close: %d", status)
	}
	if status, _, body := post(t, srv, srv.token, "/inventories/1/close", url.Values{}); status != http.StatusConflict || !strings.Contains(body, "Cet inventaire est terminé") {
		t.Errorf("second close: %d", status)
	}
	if _, _, body := get(t, srv, "/inventories", "fr"); !strings.Contains(body, "Lancer un inventaire") {
		t.Errorf("start form missing after close")
	}
	if status, _, _ := post(t, srv, srv.token, "/inventories/42/count", url.Values{"lot": {lots["A12"]}, "quantity": {"1"}}); status != http.StatusNotFound {
		t.Errorf("unknown inventory: %d", status)
	}
}

// Un inventaire lancé depuis un téléphone se reprend sur le web, avec ses comptages.
func TestInventoryStartedFromAPhone(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	for _, in := range []store.MovementInput{
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 4, Reference: &store.ReferenceFields{Brand: ptr("Katia")}},
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81790", Quantity: 2},
	} {
		if _, _, _, err := st.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}
	inv, err := st.StartInventory(ctx, "Marie", store.ScopePartial)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := st.Count(ctx, store.CountInput{InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 3, Author: "Marie"}); err != nil {
		t.Fatal(err)
	}

	_, _, body := get(t, srv, "/inventories", "fr")
	for _, want := range []string{"Inventaire en cours", "Inventaire partiel du", "Lancé par Marie", "1 bain compté.", `href="/inventories/1/count"`} {
		if !strings.Contains(body, want) {
			t.Errorf("open inventory: %q missing", want)
		}
	}
	if strings.Contains(body, `action="/inventories"`) {
		t.Errorf("start form offered while an inventory is open")
	}
	if _, _, body := get(t, srv, "/inventories/1", "fr"); !strings.Contains(body, `href="/inventories/1/count"`) {
		t.Errorf("report does not link to the count sheet")
	}
	_, _, body = get(t, srv, "/inventories/1/count", "fr")
	for _, want := range []string{"Inventaire partiel :", "1 bain compté sur 2.", "Stock connu <b>4</b>", "Compté <b>3</b>", "−1", "Nouveau comptage", "Remplacer"} {
		if !strings.Contains(body, want) {
			t.Errorf("count sheet: %q missing", want)
		}
	}
	if strings.Contains(body, "bains en stock ne sont pas encore comptés") {
		t.Errorf("partial inventory announces lots reset to 0")
	}

	// Un inventaire partiel terminé laisse les bains non comptés tels quels.
	if status, _, _ := post(t, srv, srv.token, "/inventories/1/close", url.Values{}); status != http.StatusSeeOther {
		t.Fatalf("close: %d", status)
	}
	items, _ := st.Stock(ctx, store.StockFilter{IncludeEmpty: true})
	if len(items) != 2 || items[0].Quantity != 3 || items[1].Quantity != 2 {
		t.Errorf("stock after partial inventory = %+v", items)
	}
}

// Sans session, aucun formulaire n'enregistre rien ; un gestionnaire saisit comme un admin.
func TestFormsNeedASession(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	if _, _, _, err := st.Record(ctx, store.MovementInput{Kind: store.KindIn, EAN: "12345670", Quantity: 2}); err != nil {
		t.Fatal(err)
	}
	lot := lotIDs(t, st)[""]
	for path, form := range map[string]url.Values{
		"/entries":             {"ean": {"12345670"}, "quantity": {"1"}},
		"/exits":               {"lot": {lot}, "quantity": {"1"}, "recipient": {"Marie"}},
		"/inventories":         {"scope": {"full"}},
		"/inventories/1/count": {"lot": {lot}, "quantity": {"1"}},
		"/inventories/1/close": {},
	} {
		if status, header, _ := post(t, srv, "", path, form); status != http.StatusSeeOther || header.Get("Location") != "/login" {
			t.Errorf("anonymous %s: %d → %q", path, status, header.Get("Location"))
		}
	}
	if movements, _ := st.Movements(ctx, store.MovementFilter{Limit: 10}); len(movements) != 1 {
		t.Errorf("anonymous forms recorded %d movements", len(movements)-1)
	}
	if _, err := st.CurrentInventory(ctx); !errors.Is(err, store.ErrNotFound) {
		t.Errorf("anonymous form started an inventory: %v", err)
	}

	// Un formulaire posté depuis un autre site est refusé.
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/entries", strings.NewReader(url.Values{"ean": {"12345670"}, "quantity": {"1"}}.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Sec-Fetch-Site", "cross-site")
	req.AddCookie(&http.Cookie{Name: sessionCookie, Value: srv.token})
	if status, _, _ := send(t, req); status != http.StatusForbidden {
		t.Errorf("cross-site entry: %d", status)
	}

	marie, err := st.CreateUser(ctx, "marie@example.com", "Marie", store.RoleManager, 0)
	if err != nil {
		t.Fatal(err)
	}
	token, err := st.OpenSession(ctx, marie.ID, "test")
	if err != nil {
		t.Fatal(err)
	}
	if status, _, _ := post(t, srv, token, "/exits", url.Values{"lot": {lot}, "quantity": {"1"}, "recipient": {"Léa"}}); status != http.StatusSeeOther {
		t.Fatalf("manager exit: %d", status)
	}
	if status, header, _ := post(t, srv, token, "/inventories", url.Values{"scope": {"partial"}}); status != http.StatusSeeOther || header.Get("Location") != "/inventories/1/count" {
		t.Fatalf("manager start: %d", status)
	}
	if movements, _ := st.Movements(ctx, store.MovementFilter{Kind: store.KindOut, Limit: 1}); len(movements) != 1 || deref(movements[0].Author) != "Marie" {
		t.Errorf("manager exit = %+v", movements)
	}
}
