package api

import (
	"context"
	"errors"
	"io"
	"net/http"
	"testing"

	"leblanc.io/open-go-base/i18n"
)

func TestExport(t *testing.T) {
	srv := newServer(t)

	// Sans exportateur branché, la route n'existe pas.
	if res := call(t, srv, http.MethodGet, "/api/export", ""); res.status != http.StatusNotFound || res.errorField("code") != CodeNotFound {
		t.Fatalf("no exporter: %d %v", res.status, res.body)
	}

	var lang string
	srv.app.Export = func(_ context.Context, loc *i18n.Localizer) ([]byte, string, error) {
		base, _ := loc.Lang().Base()
		lang = base.String()
		return []byte("PK classeur"), "stock-laine-2026-10-24.xlsx", nil
	}
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/export", nil)
	req.Header.Set("Authorization", "Bearer "+srv.token)
	req.Header.Set("Accept-Language", "fr-FR")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusOK || string(body) != "PK classeur" || lang != "fr" {
		t.Fatalf("export: %d %q in %q", res.StatusCode, body, lang)
	}
	for header, want := range map[string]string{
		"Content-Type":        xlsxType,
		"Content-Disposition": "attachment; filename=stock-laine-2026-10-24.xlsx",
		"Cache-Control":       "no-store",
	} {
		if got := res.Header.Get(header); got != want {
			t.Errorf("%s = %q, want %q", header, got, want)
		}
	}

	// Sans session : 401, comme le reste de l'API.
	if res := call(t, srv, http.MethodGet, "/api/export", "", "Authorization", ""); res.status != http.StatusUnauthorized {
		t.Errorf("anonymous export: %d", res.status)
	}

	// Un classeur impossible à construire donne l'erreur JSON habituelle.
	srv.app.Export = func(context.Context, *i18n.Localizer) ([]byte, string, error) {
		return nil, "", errors.New("disk full")
	}
	if res := call(t, srv, http.MethodGet, "/api/export", ""); res.status != http.StatusInternalServerError || res.errorField("code") != CodeInternal {
		t.Errorf("failed export: %d %v", res.status, res.body)
	}
}
