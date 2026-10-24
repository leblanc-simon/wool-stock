// Package config décrit la configuration du serveur. Elle est composée des
// fragments partagés d'open-go-base ; le préfixe d'environnement WS_
// (« Wool Stock ») est posé ici, au point de composition, car cleanenv ne
// supporte qu'un préfixe statique.
package config

import "leblanc.io/open-go-base/appconf"

type Config struct {
	// Web expose WS_HOST, WS_PORT, WS_RATE_LIMIT, WS_TRUSTED_PROXIES.
	Web appconf.Web `yaml:"web" env-prefix:"WS_"`
	// Log expose WS_LOG_LEVEL, WS_LOG_FORMAT, WS_LOG_SOURCE.
	Log appconf.Logging `yaml:"logging" env-prefix:"WS_"`
	// I18n expose WS_I18N_DEFAULT_LANGUAGE ; les locales sont embarquées, Dir n'est pas lu.
	I18n appconf.I18n `yaml:"i18n" env-prefix:"WS_"`
	// Database expose WS_DB_PATH.
	Database Database `yaml:"database" env-prefix:"WS_"`
	// Login expose WS_LOGIN_RATE_LIMIT.
	Login Login `yaml:"login" env-prefix:"WS_"`
}

// Database désigne le fichier SQLite qui porte le cache des codes-barres et le stock.
type Database struct {
	Path string `yaml:"path" env:"DB_PATH" env-default:"wool-stock.db" env-description:"SQLite database file"`
}

// Login borne les tentatives de connexion par client, celles de l'app et des pages
// web confondues, en plus de la limite générale (Web.RateLimit). Sans mot de passe,
// c'est ce qui freine qui essaierait des adresses à la chaîne.
type Login struct {
	RateLimit int `yaml:"rate_limit" env:"LOGIN_RATE_LIMIT" env-default:"5" env-description:"Max sign-in attempts per minute per client, app and web pages together"`
}
