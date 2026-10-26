mkfile_path := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
build_path  := $(mkfile_path)build/
app_name    := wool-stock
version     := $(shell git describe --tags --abbrev=0 2>/dev/null || echo "develop")
ldflags     := -s -w -X 'main.version=$(version)'

.DEFAULT_GOAL := help

.PHONY: help debug run test vet vuln release clean-build build-linux build-darwin build-windows

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

debug: clean-build ## Build a debug version
	@mkdir -p $(build_path)
	@go build -o $(build_path)$(app_name) .
	@echo "Debug build done"

run: ## Run from source with config.yaml (or WS_* env vars)
	@go run . -c config.yaml

test: ## Run the test suite
	@go test -race ./...

vet: ## Run go vet
	@go vet ./...

vuln: ## Check the code, its dependencies and the Go toolchain for known vulnerabilities
	@go run golang.org/x/vuln/cmd/govulncheck@v1.9.0 ./...

release: clean-build build-linux build-darwin build-windows ## Build the release version
	@echo "Release $(version) done"

clean-build: ## Clean the build directory
	@rm -fr $(build_path)

build-linux: ## Build release version for GNU/Linux
	@mkdir -p $(build_path)
	@CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="$(ldflags)" -o $(build_path)$(app_name)-$(version)-linux-amd64 .
	@CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -ldflags="$(ldflags)" -o $(build_path)$(app_name)-$(version)-linux-arm64 .

build-darwin: ## Build release version for macOS
	@mkdir -p $(build_path)
	@CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -ldflags="$(ldflags)" -o $(build_path)$(app_name)-$(version)-darwin-amd64 .
	@CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -ldflags="$(ldflags)" -o $(build_path)$(app_name)-$(version)-darwin-arm64 .

build-windows: ## Build release version for Windows
	@mkdir -p $(build_path)
	@CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -ldflags="$(ldflags)" -o $(build_path)$(app_name)-$(version)-windows-amd64.exe .
