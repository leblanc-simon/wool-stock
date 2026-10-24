package api

import (
	"strings"

	"leblanc.io/wool-stock/backend/internal/store"
)

// parseEAN valide un EAN obligatoire.
func parseEAN(raw, field string) (string, *problem) {
	if strings.TrimSpace(raw) == "" {
		return "", badRequest(CodeEANRequired, field)
	}
	ean, ok := store.NormalizeEAN(raw)
	if !ok {
		return "", badRequest(CodeEANFormat, field)
	}
	return ean, nil
}

// text nettoie une chaîne libre et borne sa longueur (en caractères).
func text(value, field string, max int) (string, *problem) {
	value, ok := store.CleanText(value, max)
	if !ok {
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
