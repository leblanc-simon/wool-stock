package api

import (
	"strings"
	"unicode/utf8"
)

const (
	maxText      = 100
	maxDyeLot    = 32
	maxRequestID = 64
	maxQuantity  = 10_000
)

// normalizeEAN ne garde que les GTIN plausibles (8, 12, 13 ou 14 chiffres) ; un
// UPC-A (12 chiffres) devient un EAN-13, comme le fait l'application.
func normalizeEAN(raw string) (string, bool) {
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

// parseEAN valide un EAN obligatoire.
func parseEAN(raw, field string) (string, *problem) {
	if strings.TrimSpace(raw) == "" {
		return "", badRequest(CodeEANRequired, field)
	}
	ean, ok := normalizeEAN(raw)
	if !ok {
		return "", badRequest(CodeEANFormat, field)
	}
	return ean, nil
}

// text nettoie une chaîne libre et borne sa longueur (en caractères).
func text(value, field string, max int) (string, *problem) {
	value = strings.TrimSpace(value)
	if utf8.RuneCountInString(value) > max {
		p := badRequest(CodeTooLong, field)
		p.max = max
		return "", p
	}
	return value, nil
}

// optionalText est text pour un champ facultatif : vide ou absent donne nil.
func optionalText(value *string, field string, max int) (*string, *problem) {
	if value == nil {
		return nil, nil
	}
	cleaned, p := text(*value, field, max)
	if p != nil || cleaned == "" {
		return nil, p
	}
	return &cleaned, nil
}
