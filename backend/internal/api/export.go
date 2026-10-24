package api

import (
	"context"
	"mime"
	"net/http"

	"leblanc.io/open-go-base/i18n"
)

// xlsxType est le type MIME du classeur exporté.
const xlsxType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

// Exporter construit le classeur Excel du stock et des distributions dans la langue de
// loc, et son nom de fichier : celui des pages (web.App.Workbook), sans que l'API
// dépende des pages.
type Exporter func(ctx context.Context, loc *i18n.Localizer) (body []byte, filename string, err error)

// export renvoie le classeur Excel, le même que le lien « Exporter en Excel » des pages,
// dans la langue du téléphone (Accept-Language).
func (a *App) export(w http.ResponseWriter, r *http.Request) {
	if a.Export == nil {
		a.fail(w, r, &problem{status: http.StatusNotFound, code: CodeNotFound})
		return
	}
	body, name, err := a.Export(r.Context(), a.I18n.FromRequest(r))
	if err != nil {
		a.internalError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", xlsxType)
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	_, _ = w.Write(body)
}
