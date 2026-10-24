package store

import (
	"strings"
	"unicode/utf8"
)

// Bornes des saisies, communes à l'API et aux pages.
const (
	// MaxText borne un texte libre : marque, coloris, destinataire, nom, appareil.
	MaxText      = 100
	MaxDyeLot    = 32
	MaxRequestID = 64
	MaxQuantity  = 10_000
)

// NormalizeEAN ne garde que les GTIN plausibles (8, 12, 13 ou 14 chiffres) ; un
// UPC-A (12 chiffres) devient un EAN-13, comme le fait l'application.
func NormalizeEAN(raw string) (string, bool) {
	digits := strings.Join(strings.Fields(raw), "")
	switch len(digits) {
	case 8, 12, 13, 14:
	default:
		return "", false
	}
	for _, c := range digits {
		if c < '0' || c > '9' {
			return "", false
		}
	}
	if len(digits) == 12 {
		digits = "0" + digits
	}
	return digits, true
}

// CleanText retire les espaces autour d'un texte libre ; ok vaut false s'il dépasse
// max caractères.
func CleanText(value string, max int) (cleaned string, ok bool) {
	value = strings.TrimSpace(value)
	if utf8.RuneCountInString(value) > max {
		return "", false
	}
	return value, true
}
