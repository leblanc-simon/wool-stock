package main

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"leblanc.io/open-go-base/i18n"
	"leblanc.io/open-go-base/ratelimit"

	"leblanc.io/wool-stock/backend/internal/api"
	"leblanc.io/wool-stock/backend/internal/store"
	"leblanc.io/wool-stock/backend/internal/web"
	"leblanc.io/wool-stock/backend/locales"
)

// La connexion a son propre limiteur, partagé par l'app et les pages ; le reste n'y passe pas.
func TestLoginRateLimit(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, "file:"+filepath.Join(t.TempDir(), "routes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	bundle, err := i18n.NewFS(locales.FS, ".", "fr")
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	app := &api.App{Store: st, I18n: bundle, Logger: logger, Version: "test"}
	site, err := web.New(st, bundle, logger)
	if err != nil {
		t.Fatal(err)
	}
	onLimit := limited(app, site)
	general, _ := ratelimit.New(100, nil, ratelimit.WithLimitHandler(onLimit))
	login, _ := ratelimit.New(2, nil, ratelimit.WithLimitHandler(onLimit))
	srv := httptest.NewServer(routes(app, site, general, login))
	t.Cleanup(srv.Close)

	send := func(method, path, contentType, body string) (*http.Response, string) {
		t.Helper()
		req, _ := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
		req.Header.Set("Accept-Language", "fr")
		if contentType != "" {
			req.Header.Set("Content-Type", contentType)
		}
		res, err := (&http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}).Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res, string(b)
	}
	apiLogin := func() (*http.Response, string) {
		return send(http.MethodPost, "/api/auth/login", "application/json", `{"email":"marie@example.org","device":"test"}`)
	}
	webLogin := func() (*http.Response, string) {
		return send(http.MethodPost, "/login", "application/x-www-form-urlencoded",
			url.Values{"email": {"marie@example.org"}, "next": {"/exits"}}.Encode())
	}

	// Deux tentatives par minute, l'app et les pages confondues.
	if res, _ := apiLogin(); res.StatusCode != http.StatusForbidden {
		t.Fatalf("first api attempt: %d", res.StatusCode)
	}
	if res, _ := webLogin(); res.StatusCode != http.StatusForbidden {
		t.Fatalf("first web attempt: %d", res.StatusCode)
	}

	res, body := apiLogin()
	if res.StatusCode != http.StatusTooManyRequests || res.Header.Get("Retry-After") == "" ||
		!strings.Contains(body, `"code":"error.rate_limit"`) || !strings.HasPrefix(res.Header.Get("Content-Type"), "application/json") {
		t.Errorf("limited api login: %d %q %s", res.StatusCode, res.Header.Get("Retry-After"), body)
	}
	res, body = webLogin()
	if res.StatusCode != http.StatusTooManyRequests || !strings.HasPrefix(res.Header.Get("Content-Type"), "text/html") ||
		!strings.Contains(body, "Trop de requêtes") || !strings.Contains(body, `value="marie@example.org"`) ||
		!strings.Contains(body, `name="next" value="/exits"`) || res.Header.Get("Content-Security-Policy") == "" {
		t.Errorf("limited web login: %d %s", res.StatusCode, body)
	}

	// Le formulaire, les autres routes et la feuille de style restent servis.
	for path, want := range map[string]int{"/login": http.StatusOK, "/api/health": http.StatusOK,
		"/stock": http.StatusSeeOther, "/api/stock": http.StatusUnauthorized, "/static/style.css": http.StatusOK} {
		if res, _ := send(http.MethodGet, path, "", ""); res.StatusCode != want {
			t.Errorf("GET %s: %d, want %d", path, res.StatusCode, want)
		}
	}
}
