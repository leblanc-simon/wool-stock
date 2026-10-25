package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"leblanc.io/open-go-base/i18n"

	"leblanc.io/wool-stock/backend/internal/store"
	"leblanc.io/wool-stock/backend/locales"
)

func newBundle(t *testing.T) *i18n.Bundle {
	t.Helper()
	bundle, err := i18n.NewFS(locales.FS, ".", "en")
	if err != nil {
		t.Fatal(err)
	}
	return bundle
}

// testServer est une API de test, avec la session d'un admin utilisée par défaut.
type testServer struct {
	*httptest.Server
	app   *App
	store *store.Store
	token string
}

func newServer(t *testing.T) *testServer {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, "file:"+filepath.Join(t.TempDir(), "api.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	admin, err := st.CreateUser(ctx, "simon@example.org", "Simon", store.RoleAdmin, 0)
	if err != nil {
		t.Fatal(err)
	}
	token, err := st.OpenSession(ctx, admin.ID, "test")
	if err != nil {
		t.Fatal(err)
	}
	app := &App{Store: st, I18n: newBundle(t), Logger: slog.New(slog.NewTextHandler(io.Discard, nil)), Version: "test"}
	srv := httptest.NewServer(app.Handler())
	t.Cleanup(srv.Close)
	return &testServer{Server: srv, app: app, store: st, token: token}
}

type response struct {
	status int
	body   map[string]any
}

func (r response) errorField(name string) any {
	e, _ := r.body["error"].(map[string]any)
	return e[name]
}

// call envoie une requête avec la session de l'admin, sauf si headers fixe
// Authorization (une valeur vide n'envoie aucun jeton).
func call(t *testing.T, srv *testServer, method, path, body string, headers ...string) response {
	t.Helper()
	req, err := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+srv.token)
	for i := 0; i+1 < len(headers); i += 2 {
		if headers[i+1] == "" {
			req.Header.Del(headers[i])
			continue
		}
		req.Header.Set(headers[i], headers[i+1])
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	out := response{status: res.StatusCode}
	if err := json.Unmarshal(raw, &out.body); err != nil {
		t.Fatalf("%s %s: invalid JSON %q", method, path, raw)
	}
	if ct := res.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("%s %s: Content-Type = %q", method, path, ct)
	}
	return out
}

func toJSON(t *testing.T, v any) string {
	t.Helper()
	var buf bytes.Buffer
	if err := json.NewEncoder(&buf).Encode(v); err != nil {
		t.Fatal(err)
	}
	return buf.String()
}

func TestHealth(t *testing.T) {
	srv := newServer(t)
	res := call(t, srv, http.MethodGet, "/api/health", "")
	if res.status != http.StatusOK || res.body["status"] != "ok" || res.body["version"] != "test" {
		t.Fatalf("health = %+v", res)
	}
}

func TestEntryExitFlow(t *testing.T) {
	srv := newServer(t)
	entry := toJSON(t, map[string]any{
		"request_id": "phone-1", "kind": "in", "ean": "8435407948594", "dye_lot": " 81784 ", "quantity": 3,
		"author": "Simon", "reference": map[string]any{"brand": "Katia", "color_code": "1", "color_name": ""},
	})
	res := call(t, srv, http.MethodPost, "/api/movements", entry)
	lot, _ := res.body["lot"].(map[string]any)
	if res.status != http.StatusCreated || lot["quantity"] != 3.0 || lot["dye_lot"] != "81784" || lot["color_name"] != nil {
		t.Fatalf("entry = %+v", res)
	}

	// Le même envoi rejoué (réseau coupé avant la réponse) ne compte pas deux fois.
	res = call(t, srv, http.MethodPost, "/api/movements", entry)
	lot, _ = res.body["lot"].(map[string]any)
	if res.status != http.StatusOK || res.body["replayed"] != true || lot["quantity"] != 3.0 {
		t.Fatalf("replay = %+v", res)
	}

	res = call(t, srv, http.MethodGet, "/api/references/8435407948594", "")
	if res.status != http.StatusOK || res.body["brand"] != "Katia" {
		t.Fatalf("reference = %+v", res)
	}

	exit := map[string]any{"kind": "out", "ean": "8435407948594", "dye_lot": "81784", "quantity": 2}
	res = call(t, srv, http.MethodPost, "/api/movements", toJSON(t, exit))
	if res.status != http.StatusBadRequest || res.errorField("code") != CodeRecipientRequired || res.errorField("field") != "recipient" {
		t.Fatalf("exit without recipient = %+v", res)
	}

	exit["recipient"] = "Marie"
	exit["quantity"] = 4
	res = call(t, srv, http.MethodPost, "/api/movements", toJSON(t, exit), "Accept-Language", "fr-FR,fr;q=0.9")
	if res.status != http.StatusConflict || res.errorField("code") != CodeInsufficientStock || res.errorField("available") != 3.0 {
		t.Fatalf("exit too large = %+v", res)
	}
	if msg, _ := res.errorField("message").(string); !strings.Contains(msg, "il reste 3 pelotes") {
		t.Fatalf("french message = %q", msg)
	}

	exit["quantity"] = 2
	res = call(t, srv, http.MethodPost, "/api/movements", toJSON(t, exit))
	lot, _ = res.body["lot"].(map[string]any)
	movement, _ := res.body["movement"].(map[string]any)
	if res.status != http.StatusCreated || lot["quantity"] != 1.0 || movement["recipient"] != "Marie" {
		t.Fatalf("exit = %+v", res)
	}

	res = call(t, srv, http.MethodGet, "/api/stock", "")
	items, _ := res.body["items"].([]any)
	if res.status != http.StatusOK || len(items) != 1 {
		t.Fatalf("stock = %+v", res)
	}

	res = call(t, srv, http.MethodGet, "/api/movements?ean=8435407948594&limit=10", "")
	movements, _ := res.body["movements"].([]any)
	if res.status != http.StatusOK || len(movements) != 2 {
		t.Fatalf("movements = %+v", res)
	}
}

func TestUPCIsStoredAsEAN13(t *testing.T) {
	srv := newServer(t)
	res := call(t, srv, http.MethodPut, "/api/references/012345678905", `{"brand":"Lion Brand"}`)
	if res.status != http.StatusOK || res.body["ean"] != "0012345678905" {
		t.Fatalf("put = %+v", res)
	}
	res = call(t, srv, http.MethodGet, "/api/references", "")
	refs, _ := res.body["references"].([]any)
	if len(refs) != 1 {
		t.Fatalf("references = %+v", res)
	}
}

func TestValidation(t *testing.T) {
	srv := newServer(t)
	tests := []struct {
		name, method, path, body string
		status                   int
		code, field              string
	}{
		{"invalid json", http.MethodPost, "/api/movements", `{"kind":`, 400, CodeInvalidJSON, ""},
		{"two documents", http.MethodPost, "/api/movements", `{} {}`, 400, CodeInvalidJSON, ""},
		{"kind", http.MethodPost, "/api/movements", `{"kind":"move","ean":"12345670","quantity":1}`, 400, CodeKind, "kind"},
		{"ean missing", http.MethodPost, "/api/movements", `{"kind":"in","quantity":1}`, 400, CodeEANRequired, "ean"},
		{"ean format", http.MethodPost, "/api/movements", `{"kind":"in","ean":"12AB5670","quantity":1}`, 400, CodeEANFormat, "ean"},
		{"quantity", http.MethodPost, "/api/movements", `{"kind":"in","ean":"12345670","quantity":0}`, 400, CodeQuantity, "quantity"},
		{"dye lot", http.MethodPost, "/api/movements", `{"kind":"in","ean":"12345670","quantity":1,"dye_lot":"` + strings.Repeat("9", 33) + `"}`, 400, CodeTooLong, "dye_lot"},
		{"brand", http.MethodPost, "/api/movements", `{"kind":"in","ean":"12345670","quantity":1,"reference":{"brand":"` + strings.Repeat("é", 101) + `"}}`, 400, CodeTooLong, "reference.brand"},
		{"stock ean", http.MethodGet, "/api/stock?ean=123", "", 400, CodeEANFormat, "ean"},
		{"limit", http.MethodGet, "/api/movements?limit=0", "", 400, CodeLimit, "limit"},
		{"unknown reference", http.MethodGet, "/api/references/12345670", "", 404, CodeNotFound, ""},
		{"unknown route", http.MethodGet, "/api/nope", "", 404, CodeNotFound, ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			res := call(t, srv, tt.method, tt.path, tt.body)
			if res.status != tt.status || res.errorField("code") != tt.code {
				t.Fatalf("got %+v", res)
			}
			if field, _ := res.errorField("field").(string); field != tt.field {
				t.Fatalf("field = %q, want %q", field, tt.field)
			}
			if msg, _ := res.errorField("message").(string); msg == "" || msg == tt.code {
				t.Fatalf("message not translated: %q", msg)
			}
		})
	}
}

func TestTooLongMessageNamesTheLimit(t *testing.T) {
	srv := newServer(t)
	body := `{"kind":"out","ean":"12345670","quantity":1,"recipient":"` + strings.Repeat("x", 101) + `"}`
	res := call(t, srv, http.MethodPost, "/api/movements?lang=fr", body)
	if msg, _ := res.errorField("message").(string); msg != "100 caractères au maximum" {
		t.Fatalf("message = %q", msg)
	}
}

// Chaque code d'erreur a sa traduction dans chaque langue embarquée.
func TestEveryCodeIsTranslated(t *testing.T) {
	bundle := newBundle(t)
	for _, tag := range bundle.Languages() {
		loc := bundle.Localizer(tag.String(), "")
		for _, code := range Codes {
			var msg string
			if pluralCodes[code] {
				msg = loc.Tn(code, 2)
			} else {
				msg = loc.T(code, map[string]any{"Max": 10})
			}
			if msg == code || msg == "" {
				t.Errorf("%s: %s is not translated", tag, code)
			}
		}
	}
	if len(bundle.Languages()) < 2 {
		t.Fatalf("languages = %v", bundle.Languages())
	}
}

func TestInventoryFlow(t *testing.T) {
	srv := newServer(t)
	res := call(t, srv, http.MethodGet, "/api/inventories/current", "")
	if res.status != http.StatusNotFound || res.errorField("code") != CodeNotFound {
		t.Fatalf("no inventory = %+v", res)
	}
	call(t, srv, http.MethodPost, "/api/movements",
		`{"kind":"in","ean":"8435407948594","dye_lot":"81784","quantity":4,"reference":{"brand":"Katia"}}`)

	// Lancement sans corps, puis un second lancement refusé.
	res = call(t, srv, http.MethodPost, "/api/inventories", "")
	inventory, _ := res.body["inventory"].(map[string]any)
	if res.status != http.StatusCreated || inventory["closed_at"] != nil {
		t.Fatalf("start = %+v", res)
	}
	id := strconv.FormatFloat(inventory["id"].(float64), 'f', 0, 64)
	res = call(t, srv, http.MethodPost, "/api/inventories", `{"author":"Marie"}`)
	if res.status != http.StatusConflict || res.errorField("code") != CodeInventoryOpen {
		t.Fatalf("second start = %+v", res)
	}

	countPath := "/api/inventories/" + id + "/counts"
	res = call(t, srv, http.MethodPost, countPath, `{"request_id":"s1","ean":"8435407948594","dye_lot":"81784","quantity":6,"author":"Simon"}`)
	count, _ := res.body["count"].(map[string]any)
	lot, _ := res.body["lot"].(map[string]any)
	if res.status != http.StatusCreated || count["quantity"] != 6.0 || count["previous_quantity"] != 4.0 || lot["quantity"] != 6.0 {
		t.Fatalf("first count = %+v", res)
	}

	// Rescan du même bain : le serveur demande quoi faire, en français si on le lui demande.
	res = call(t, srv, http.MethodPost, countPath, `{"ean":"8435407948594","dye_lot":"81784","quantity":2}`, "Accept-Language", "fr")
	if res.status != http.StatusConflict || res.errorField("code") != CodeAlreadyCounted || res.errorField("counted") != 6.0 {
		t.Fatalf("rescan = %+v", res)
	}
	if msg, _ := res.errorField("message").(string); !strings.Contains(msg, "6 pelotes") {
		t.Fatalf("rescan message = %q", msg)
	}
	res = call(t, srv, http.MethodPost, countPath, `{"ean":"8435407948594","dye_lot":"81784","quantity":2,"mode":"add"}`)
	if count, _ = res.body["count"].(map[string]any); res.status != http.StatusCreated || count["quantity"] != 8.0 {
		t.Fatalf("add = %+v", res)
	}
	res = call(t, srv, http.MethodPost, countPath, `{"ean":"8435407948594","dye_lot":"81784","quantity":5,"mode":"set"}`)
	if lot, _ = res.body["lot"].(map[string]any); res.status != http.StatusCreated || lot["quantity"] != 5.0 {
		t.Fatalf("set = %+v", res)
	}

	res = call(t, srv, http.MethodGet, "/api/inventories/current", "")
	counts, _ := res.body["counts"].([]any)
	inventory, _ = res.body["inventory"].(map[string]any)
	if res.status != http.StatusOK || len(counts) != 1 || inventory["scope"] != "partial" || res.body["uncounted"] != 0.0 {
		t.Fatalf("current = %+v", res)
	}

	res = call(t, srv, http.MethodPost, "/api/inventories/"+id+"/close", `{"author":"Marie"}`)
	if inventory, _ = res.body["inventory"].(map[string]any); res.status != http.StatusOK || inventory["closed_by"] != "Simon" {
		t.Fatalf("close = %+v", res)
	}
	res = call(t, srv, http.MethodPost, countPath, `{"ean":"8435407948594","dye_lot":"81784","quantity":1}`)
	if res.status != http.StatusConflict || res.errorField("code") != CodeInventoryClosed {
		t.Fatalf("count after close = %+v", res)
	}
	res = call(t, srv, http.MethodPost, "/api/inventories/"+id+"/close", "")
	if res.status != http.StatusConflict || res.errorField("code") != CodeInventoryClosed {
		t.Fatalf("close twice = %+v", res)
	}
}

func TestInventoryValidation(t *testing.T) {
	srv := newServer(t)
	call(t, srv, http.MethodPost, "/api/inventories", "")
	tests := []struct {
		name, path, body string
		status           int
		code, field      string
	}{
		{"quantity missing", "/api/inventories/1/counts", `{"ean":"12345670"}`, 400, CodeCount, "quantity"},
		{"quantity negative", "/api/inventories/1/counts", `{"ean":"12345670","quantity":-1}`, 400, CodeCount, "quantity"},
		{"mode", "/api/inventories/1/counts", `{"ean":"12345670","quantity":1,"mode":"merge"}`, 400, CodeMode, "mode"},
		{"ean", "/api/inventories/1/counts", `{"ean":"123","quantity":1}`, 400, CodeEANFormat, "ean"},
		{"unknown inventory", "/api/inventories/42/counts", `{"ean":"12345670","quantity":1}`, 404, CodeNotFound, ""},
		{"bad id", "/api/inventories/abc/close", "", 404, CodeNotFound, ""},
		{"scope", "/api/inventories", `{"scope":"half"}`, 400, CodeScope, "scope"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			res := call(t, srv, http.MethodPost, tt.path, tt.body)
			if res.status != tt.status || res.errorField("code") != tt.code {
				t.Fatalf("got %+v", res)
			}
			if field, _ := res.errorField("field").(string); field != tt.field {
				t.Fatalf("field = %q, want %q", field, tt.field)
			}
		})
	}
}

func TestFullInventory(t *testing.T) {
	srv := newServer(t)
	call(t, srv, http.MethodPost, "/api/movements", `{"kind":"in","ean":"12345670","dye_lot":"A","quantity":4}`)
	call(t, srv, http.MethodPost, "/api/movements", `{"kind":"in","ean":"12345670","dye_lot":"B","quantity":2}`)
	res := call(t, srv, http.MethodPost, "/api/inventories", `{"scope":"full","author":"Simon"}`)
	inventory, _ := res.body["inventory"].(map[string]any)
	if res.status != http.StatusCreated || inventory["scope"] != "full" || res.body["uncounted"] != 2.0 {
		t.Fatalf("start = %+v", res)
	}
	id := strconv.FormatFloat(inventory["id"].(float64), 'f', 0, 64)
	call(t, srv, http.MethodPost, "/api/inventories/"+id+"/counts", `{"ean":"12345670","dye_lot":"A","quantity":3}`)
	res = call(t, srv, http.MethodPost, "/api/inventories/"+id+"/close", "")
	counts, _ := res.body["counts"].([]any)
	if res.status != http.StatusOK || len(counts) != 2 {
		t.Fatalf("close = %+v", res)
	}
	res = call(t, srv, http.MethodGet, "/api/stock?include_empty=true", "")
	items, _ := res.body["items"].([]any)
	for _, raw := range items {
		item := raw.(map[string]any)
		want := map[string]float64{"A": 3, "B": 0}[item["dye_lot"].(string)]
		if item["quantity"] != want {
			t.Errorf("lot %v = %v, want %v", item["dye_lot"], item["quantity"], want)
		}
	}
}

func TestAuthentication(t *testing.T) {
	srv := newServer(t)
	marie, err := srv.store.CreateUser(context.Background(), "marie@example.org", "Marie", store.RoleManager, 0)
	if err != nil {
		t.Fatal(err)
	}

	// Sans session, seules health et la connexion répondent.
	if res := call(t, srv, http.MethodGet, "/api/health", "", "Authorization", ""); res.status != http.StatusOK {
		t.Fatalf("health = %+v", res)
	}
	for _, path := range []string{"/api/stock", "/api/references", "/api/inventories/current", "/api/auth/me"} {
		res := call(t, srv, http.MethodGet, path, "", "Authorization", "")
		if res.status != http.StatusUnauthorized || res.errorField("code") != CodeUnauthorized {
			t.Errorf("%s without session = %+v", path, res)
		}
	}
	if res := call(t, srv, http.MethodGet, "/api/stock", "", "Authorization", "Bearer forged"); res.status != http.StatusUnauthorized {
		t.Fatalf("forged token = %+v", res)
	}

	// Adresse inconnue : refusée ; adresse autorisée (casse indifférente) : session ouverte.
	res := call(t, srv, http.MethodPost, "/api/auth/login", `{"email":"inconnu@example.org"}`, "Authorization", "", "Accept-Language", "fr")
	if res.status != http.StatusForbidden || res.errorField("code") != CodeEmailNotAllowed {
		t.Fatalf("unknown email = %+v", res)
	}
	res = call(t, srv, http.MethodPost, "/api/auth/login", `{"email":"pas une adresse"}`, "Authorization", "")
	if res.status != http.StatusBadRequest || res.errorField("code") != CodeEmail {
		t.Fatalf("bad email = %+v", res)
	}
	res = call(t, srv, http.MethodPost, "/api/auth/login", `{"email":" Marie@Example.org ","device":"Pixel 6"}`, "Authorization", "")
	user, _ := res.body["user"].(map[string]any)
	token, _ := res.body["token"].(string)
	if res.status != http.StatusCreated || token == "" || user["name"] != "Marie" || user["role"] != "manager" {
		t.Fatalf("login = %+v", res)
	}
	auth := "Bearer " + token

	res = call(t, srv, http.MethodGet, "/api/auth/me", "", "Authorization", auth)
	if me, _ := res.body["user"].(map[string]any); res.status != http.StatusOK || me["email"] != "marie@example.org" {
		t.Fatalf("me = %+v", res)
	}

	// L'auteur d'un mouvement est le compte connecté, quoi qu'envoie le client.
	res = call(t, srv, http.MethodPost, "/api/movements",
		`{"kind":"in","ean":"12345670","dye_lot":"A","quantity":2,"author":"Quelqu'un d'autre"}`, "Authorization", auth)
	if movement, _ := res.body["movement"].(map[string]any); res.status != http.StatusCreated || movement["author"] != "Marie" {
		t.Fatalf("movement author = %+v", res)
	}

	// Un gestionnaire ne gère pas les comptes.
	if res := call(t, srv, http.MethodGet, "/api/users", "", "Authorization", auth); res.status != http.StatusForbidden || res.errorField("code") != CodeForbidden {
		t.Fatalf("manager lists users = %+v", res)
	}
	if res := call(t, srv, http.MethodPost, "/api/users", `{"email":"x@example.org","name":"X","role":"manager"}`, "Authorization", auth); res.status != http.StatusForbidden {
		t.Fatalf("manager creates user = %+v", res)
	}

	// Déconnexion : le jeton ne vaut plus rien.
	if res := call(t, srv, http.MethodPost, "/api/auth/logout", "", "Authorization", auth); res.status != http.StatusOK {
		t.Fatalf("logout = %+v", res)
	}
	if res := call(t, srv, http.MethodGet, "/api/auth/me", "", "Authorization", auth); res.status != http.StatusUnauthorized {
		t.Fatalf("after logout = %+v", res)
	}
	_ = marie
}

func TestUserManagement(t *testing.T) {
	srv := newServer(t)
	res := call(t, srv, http.MethodPost, "/api/users", `{"email":"Lea.Martin@Example.org","name":"Léa Martin","role":"manager"}`)
	if res.status != http.StatusCreated || res.body["email"] != "lea.martin@example.org" || res.body["role"] != "manager" {
		t.Fatalf("create = %+v", res)
	}
	res = call(t, srv, http.MethodPost, "/api/users", `{"email":"lea.martin@example.org","name":"Doublon","role":"admin"}`)
	if res.status != http.StatusConflict || res.errorField("code") != CodeEmailTaken {
		t.Fatalf("duplicate = %+v", res)
	}
	for body, code := range map[string]string{
		`{"email":"oups","name":"X","role":"manager"}`:              CodeEmail,
		`{"email":"x@example.org","name":"  ","role":"manager"}`:    CodeNameRequired,
		`{"email":"x@example.org","name":"X","role":"superviseur"}`: CodeRole,
	} {
		if res := call(t, srv, http.MethodPost, "/api/users", body); res.status != http.StatusBadRequest || res.errorField("code") != code {
			t.Errorf("%s = %+v", body, res)
		}
	}
	res = call(t, srv, http.MethodGet, "/api/users", "")
	if users, _ := res.body["users"].([]any); res.status != http.StatusOK || len(users) != 2 {
		t.Fatalf("users = %+v", res)
	}
}

func TestDeleteOwnAccount(t *testing.T) {
	srv := newServer(t)
	ctx := context.Background()
	marie, err := srv.store.CreateUser(ctx, "marie@example.org", "Marie", store.RoleManager, 0)
	if err != nil {
		t.Fatal(err)
	}
	phone, err := srv.store.OpenSession(ctx, marie.ID, "Pixel 6")
	if err != nil {
		t.Fatal(err)
	}
	browser, err := srv.store.OpenSession(ctx, marie.ID, "Firefox")
	if err != nil {
		t.Fatal(err)
	}
	res := call(t, srv, http.MethodPost, "/api/movements", `{"kind":"in","ean":"12345670","dye_lot":"A","quantity":2}`, "Authorization", "Bearer "+phone)
	if res.status != http.StatusCreated {
		t.Fatalf("movement = %+v", res)
	}

	// Le dernier administrateur ne peut pas supprimer son compte.
	res = call(t, srv, http.MethodDelete, "/api/auth/me", "", "Accept-Language", "fr")
	if res.status != http.StatusConflict || res.errorField("code") != CodeLastAdmin || res.errorField("message") != "Il doit rester au moins un administrateur." {
		t.Fatalf("last admin = %+v", res)
	}

	// Le compte et toutes ses sessions disparaissent ; son nom reste sur ses mouvements.
	if res := call(t, srv, http.MethodDelete, "/api/auth/me", "", "Authorization", "Bearer "+phone); res.status != http.StatusOK {
		t.Fatalf("delete = %+v", res)
	}
	for _, token := range []string{phone, browser} {
		if res := call(t, srv, http.MethodGet, "/api/auth/me", "", "Authorization", "Bearer "+token); res.status != http.StatusUnauthorized {
			t.Errorf("session after deletion = %+v", res)
		}
	}
	if _, err := srv.store.UserByEmail(ctx, "marie@example.org"); err != store.ErrNotFound {
		t.Errorf("user after deletion: err = %v", err)
	}
	res = call(t, srv, http.MethodGet, "/api/movements", "")
	movements, _ := res.body["movements"].([]any)
	if len(movements) != 1 || movements[0].(map[string]any)["author"] != "Marie" {
		t.Errorf("movements after deletion = %+v", res)
	}
}
