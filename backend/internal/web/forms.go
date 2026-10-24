package web

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"

	"leblanc.io/wool-stock/backend/internal/store"
)

// fieldError est un message de formulaire : une clé de traduction et ses données.
type fieldError struct {
	Key  string
	Data map[string]any
	// Count, quand il est posé, choisit la forme plurielle du message (pelotes restantes, déjà comptées).
	Count *int
}

// formErrors indexe les messages d'un formulaire par champ ; "form" porte une erreur d'ensemble.
type formErrors map[string]*fieldError

func (e *formErrors) fail(field, key string, data map[string]any) {
	e.set(field, &fieldError{Key: key, Data: data})
}

// failCount pose un message qui dépend d'un nombre de pelotes.
func (e *formErrors) failCount(field, key string, count int) {
	e.set(field, &fieldError{Key: key, Count: &count})
}

func (e *formErrors) set(field string, err *fieldError) {
	if *e == nil {
		*e = formErrors{}
	}
	(*e)[field] = err
}

// formEAN lit un EAN obligatoire, validé comme dans l'API.
func formEAN(errs *formErrors, field, raw string) string {
	if strings.TrimSpace(raw) == "" {
		errs.fail(field, "validation.ean_required", nil)
		return ""
	}
	ean, ok := store.NormalizeEAN(raw)
	if !ok {
		errs.fail(field, "validation.ean_format", nil)
	}
	return ean
}

// formText lit un texte libre d'au plus max caractères.
func formText(errs *formErrors, field, raw string, max int) string {
	value, ok := store.CleanText(raw, max)
	if !ok {
		errs.fail(field, "validation.too_long", map[string]any{"Max": max})
	}
	return value
}

// formQuantity lit un nombre de pelotes entre min et store.MaxQuantity ; key est le message d'erreur.
func formQuantity(errs *formErrors, field, raw string, min int, key string) int {
	n, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil || n < min || n > store.MaxQuantity {
		errs.fail(field, key, nil)
		return 0
	}
	return n
}

// maxNonce laisse la place de l'empreinte du contenu dans store.MaxRequestID.
const maxNonce = store.MaxRequestID - 1 - 16

// newRequestID identifie une saisie : chaque formulaire rendu en porte un neuf.
func newRequestID() string {
	return "web-" + rand.Text()
}

// formRequestID lit l'identifiant de saisie du formulaire et le lie au contenu envoyé,
// comme le request_id de l'API : renvoyé tel quel (double clic, nouvel envoi), le
// formulaire n'est enregistré qu'une fois ; rouvert par « Précédent » puis modifié,
// c'est une nouvelle saisie. Sans identifiant valide, la saisie n'est pas rejouable.
func formRequestID(r *http.Request, content ...string) string {
	nonce := strings.TrimSpace(r.PostFormValue("request_id"))
	if nonce == "" || len(nonce) > maxNonce {
		return ""
	}
	sum := sha256.Sum256([]byte(strings.Join(content, "\x00")))
	return nonce + "." + hex.EncodeToString(sum[:8])
}

// optionalText renvoie nil pour un champ laissé vide.
func optionalText(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}
