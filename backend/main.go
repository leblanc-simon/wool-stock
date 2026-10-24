package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"leblanc.io/open-go-base/appconf"
	"leblanc.io/open-go-base/i18n"
	"leblanc.io/open-go-base/logx"
	"leblanc.io/open-go-base/ratelimit"

	"leblanc.io/wool-stock/backend/internal/api"
	"leblanc.io/wool-stock/backend/internal/config"
	"leblanc.io/wool-stock/backend/internal/store"
	"leblanc.io/wool-stock/backend/internal/web"
	"leblanc.io/wool-stock/backend/locales"
)

// version est injectée au build via -ldflags "-X 'main.version=...'".
var version = "develop"

const appName = "wool-stock"

func main() {
	var cfg config.Config
	appconf.MustLoad(&cfg, appconf.Options{Name: appName, Version: version})
	var err error
	if args := commandArgs(os.Args[1:]); len(args) == 0 || args[0] == "serve" {
		err = run(cfg)
	} else {
		err = command(cfg, args, os.Stdout)
	}
	if err != nil {
		logx.New(cfg.Log).Error("fatal", "err", err)
		os.Exit(1)
	}
}

// openStore ouvre la base, en créant son dossier au besoin.
func openStore(ctx context.Context, cfg config.Config) (*store.Store, error) {
	if dir := filepath.Dir(cfg.Database.Path); dir != "." {
		if err := os.MkdirAll(dir, 0o750); err != nil {
			return nil, fmt.Errorf("database directory: %w", err)
		}
	}
	return store.Open(ctx, "file:"+cfg.Database.Path)
}

// routes assemble l'API, les pages et leurs limiteurs. La feuille de style échappe
// aux limiteurs ; la connexion, de l'app comme des pages, passe en plus par le
// sien, plus strict.
func routes(app *api.App, site *web.App, general, login *ratelimit.Limiter) http.Handler {
	apiHandler, siteHandler := app.Handler(), site.Handler()
	mux := http.NewServeMux()
	mux.Handle("GET /static/", web.Static())
	mux.Handle("/api/", general.Middleware(apiHandler))
	mux.Handle("POST /api/auth/login", general.Middleware(login.Middleware(apiHandler)))
	mux.Handle("/", general.Middleware(siteHandler))
	mux.Handle("POST /login", general.Middleware(login.Middleware(siteHandler)))
	return mux
}

// limited répond 429 comme l'API sous /api/, comme les pages ailleurs.
func limited(app *api.App, site *web.App) http.Handler {
	apiLimited, siteLimited := app.RateLimitHandler(), site.RateLimitHandler()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			apiLimited.ServeHTTP(w, r)
			return
		}
		siteLimited.ServeHTTP(w, r)
	})
}

func run(cfg config.Config) error {
	logger := logx.New(cfg.Log)

	st, err := openStore(context.Background(), cfg)
	if err != nil {
		return err
	}
	defer st.Close()

	bundle, err := i18n.NewFS(locales.FS, ".", cfg.I18n.DefaultLanguage)
	if err != nil {
		return fmt.Errorf("i18n: %w", err)
	}

	app := &api.App{Store: st, I18n: bundle, Logger: logger, Version: version}
	site, err := web.New(st, bundle, logger)
	if err != nil {
		return fmt.Errorf("templates: %w", err)
	}
	// L'app télécharge le même classeur que les pages.
	app.Export = site.Workbook
	onLimit := limited(app, site)
	general, err := ratelimit.New(cfg.Web.RateLimit, cfg.Web.TrustedProxies, ratelimit.WithLimitHandler(onLimit))
	if err != nil {
		return fmt.Errorf("rate limiter: %w", err)
	}
	login, err := ratelimit.New(cfg.Login.RateLimit, cfg.Web.TrustedProxies, ratelimit.WithLimitHandler(onLimit))
	if err != nil {
		return fmt.Errorf("login rate limiter: %w", err)
	}

	addr := cfg.Web.Host + ":" + strconv.Itoa(cfg.Web.Port)
	srv := &http.Server{
		Addr:              addr,
		Handler:           routes(app, site, general, login),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	errc := make(chan error, 1)
	go func() {
		logger.Info("starting", "app", appName, "version", version, "addr", addr, "database", cfg.Database.Path)
		errc <- srv.ListenAndServe()
	}()

	select {
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	case <-ctx.Done():
	}

	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return srv.Shutdown(shutdownCtx)
}
