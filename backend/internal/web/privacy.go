package web

import (
	"net/http"
	"time"
)

// privacyUpdated date la version en vigueur des règles de confidentialité : l'avancer
// quand leur texte change.
var privacyUpdated = time.Date(2026, time.October, 30, 12, 0, 0, 0, time.UTC)

// Privacy est ce que les règles de confidentialité disent du déploiement : qui tient le
// serveur et comment le joindre (vides, la page renvoie aux administrateurs), et combien
// de temps vivent les sessions, les sauvegardes et les journaux (0 : pas d'expiration
// pour les sessions, pas de sauvegarde, pas de durée annoncée pour les journaux).
type Privacy struct {
	Controller      string
	Contact         string
	SessionIdleDays int
	BackupDays      int
	LogDays         int
}

// privacyView est la page des règles ; Host nomme le serveur dont elles parlent.
type privacyView struct {
	Privacy
	Host    string
	Updated time.Time
}

// privacy présente les règles de confidentialité, l'adresse publique à déclarer aux stores.
func (a *App) privacy(w http.ResponseWriter, r *http.Request) {
	a.render(w, r, http.StatusOK, "privacy", "privacy", "privacy.title",
		privacyView{Privacy: a.Privacy, Host: r.Host, Updated: privacyUpdated})
}
