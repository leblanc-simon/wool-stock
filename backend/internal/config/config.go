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
	// Login expose WS_LOGIN_RATE_LIMIT et WS_LOGIN_SESSION_IDLE_DAYS.
	Login Login `yaml:"login" env-prefix:"WS_"`
	// Privacy expose WS_PRIVACY_CONTROLLER, WS_PRIVACY_CONTACT, WS_PRIVACY_BACKUP_DAYS et
	// WS_PRIVACY_LOG_DAYS.
	Privacy Privacy `yaml:"privacy" env-prefix:"WS_"`
}

// Database désigne le fichier SQLite qui porte le cache des codes-barres et le stock.
type Database struct {
	Path string `yaml:"path" env:"DB_PATH" env-default:"wool-stock.db" env-description:"SQLite database file"`
}

// Login borne les tentatives de connexion par client, celles de l'app et des pages
// web confondues, en plus de la limite générale (Web.RateLimit). Sans mot de passe,
// c'est ce qui freine qui essaierait des adresses à la chaîne. SessionIdleDays fait
// expirer une session restée inutilisée (téléphone perdu, navigateur abandonné).
type Login struct {
	RateLimit       int `yaml:"rate_limit" env:"LOGIN_RATE_LIMIT" env-default:"5" env-description:"Max sign-in attempts per minute per client, app and web pages together"`
	SessionIdleDays int `yaml:"session_idle_days" env:"LOGIN_SESSION_IDLE_DAYS" env-default:"400" env-description:"Days without use after which a session expires; 0 never expires"`
}

// Privacy complète la page /privacy de ce que seul le déploiement connaît : qui tient le
// serveur, comment le joindre, et combien de temps vivent les sauvegardes de la base et les
// journaux (nginx, journald). Ces durées doivent suivre la configuration de la machine.
type Privacy struct {
	Controller string `yaml:"controller" env:"PRIVACY_CONTROLLER" env-description:"Person or organisation responsible for the data, named on /privacy"`
	Contact    string `yaml:"contact" env:"PRIVACY_CONTACT" env-description:"Email address for privacy requests, shown on /privacy"`
	BackupDays int    `yaml:"backup_days" env:"PRIVACY_BACKUP_DAYS" env-default:"30" env-description:"Days database backups are kept, stated on /privacy; 0 without backups"`
	LogDays    int    `yaml:"log_days" env:"PRIVACY_LOG_DAYS" env-default:"14" env-description:"Days web server and service logs are kept, stated on /privacy"`
}
