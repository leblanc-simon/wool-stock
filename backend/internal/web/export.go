package web

import (
	"bytes"
	"mime"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/xuri/excelize/v2"
	"golang.org/x/text/cases"
	"golang.org/x/text/language"

	"leblanc.io/open-go-base/i18n"

	"leblanc.io/wool-stock/backend/internal/store"
)

// L'export reprend le classeur tenu à la main avant l'application : une feuille de
// stock, une table par marque (coloris, bain, n° de coloris, stock) réparties sur
// quatre colonnes de tables, et une feuille de suivi des distributions.
const (
	// stockStacks est le nombre de colonnes de tables côte à côte.
	stockStacks = 4
	// stockBlockWidth compte les colonnes d'une table ; une colonne étroite les sépare.
	stockBlockWidth = 4
	// stockGap est le nombre de lignes vides entre deux tables d'une même colonne.
	stockGap = 2
)

// plainNumber reconnaît un n° qu'Excel peut garder en nombre sans le changer : ni zéro
// en tête, ni plus de 15 chiffres.
var plainNumber = regexp.MustCompile(`^[1-9][0-9]{0,14}$`)

// export envoie le classeur : le stock du jour, bains épuisés masqués comme sur la
// page Stock, et toutes les sorties, regroupées par jour et par destinataire.
func (a *App) export(w http.ResponseWriter, r *http.Request) {
	items, err := a.Store.Stock(r.Context(), store.StockFilter{IncludeEmpty: true})
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	exits, err := a.Store.Movements(r.Context(), store.MovementFilter{Kind: store.KindOut})
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	loc := a.I18n.FromRequest(r)
	now := a.Now().In(a.Location)
	book, err := workbook(loc, now, groupStock(items), distributions(exits, a.Location, loc.Lang()))
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	defer book.Close()
	// Écrit d'abord en mémoire : une erreur donne encore une page d'erreur.
	var buf bytes.Buffer
	if err := book.Write(&buf); err != nil {
		a.serverError(w, r, err)
		return
	}
	name := loc.T("export.filename", map[string]any{"Date": now.Format(time.DateOnly)})
	w.Header().Set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	_, _ = buf.WriteTo(w)
}

// distribution est une ligne du suivi : ce qu'une personne a reçu un jour donné.
type distribution struct {
	Day       time.Time
	Recipient string
	Total     int
	Items     []itemGroup
}

// distributions met les sorties à plat, du plus ancien au plus récent jour ; le même
// jour, les destinataires restent dans l'ordre de la langue.
func distributions(list []store.Movement, loc *time.Location, lang language.Tag) []distribution {
	var rows []distribution
	for _, g := range groupExits(list, loc, lang) {
		for _, d := range g.Days {
			rows = append(rows, distribution{Day: d.Day, Recipient: g.Name, Total: d.Total, Items: d.Items})
		}
	}
	sort.SliceStable(rows, func(i, j int) bool { return rows[i].Day.Before(rows[j].Day) })
	return rows
}

// exportStyles sont les styles du classeur d'origine : Arial 11, titre rouge, nom de
// marque bleu, en-têtes de colonnes bleu clair, cellules encadrées d'un filet noir.
type exportStyles struct {
	title, brand, header                int
	shade, dyeLot, code, stock          int
	columnTitle, day, name, balls, item int
}

func newExportStyles(f *excelize.File, loc *i18n.Localizer) (exportStyles, error) {
	frame := []excelize.Border{
		{Type: "left", Color: "000000", Style: 1}, {Type: "right", Color: "000000", Style: 1},
		{Type: "top", Color: "000000", Style: 1}, {Type: "bottom", Color: "000000", Style: 1},
	}
	font := func(bold bool, color string, size float64) *excelize.Font {
		return &excelize.Font{Family: "Arial", Size: size, Bold: bold, Color: color}
	}
	align := func(horizontal, vertical string) *excelize.Alignment {
		return &excelize.Alignment{Horizontal: horizontal, Vertical: vertical}
	}
	// Les formats de date et de pelotes sont ceux d'Excel, choisis par chaque langue.
	dayFormat, ballsFormat := loc.T("format.spreadsheet_date"), loc.T("format.spreadsheet_balls")
	var s exportStyles
	for _, d := range []struct {
		id    *int
		style excelize.Style
	}{
		{&s.title, excelize.Style{Font: font(true, "FF0000", 12), Alignment: align("center", "center")}},
		{&s.brand, excelize.Style{Font: font(true, "0000FF", 11), Alignment: align("center", "center"), Border: frame}},
		{&s.header, excelize.Style{Font: font(false, "4A86E8", 11), Alignment: align("center", "center"), Border: frame}},
		{&s.shade, excelize.Style{Font: font(false, "000000", 11), Alignment: align("left", "center"), Border: frame}},
		{&s.dyeLot, excelize.Style{Font: font(false, "000000", 11), Alignment: align("center", "center"), Border: frame}},
		{&s.code, excelize.Style{Font: font(true, "000000", 11), Alignment: align("center", "center"), Border: frame}},
		{&s.stock, excelize.Style{Font: font(false, "000000", 11), Alignment: align("center", "center"), Border: frame}},
		{&s.columnTitle, excelize.Style{Font: font(true, "0000FF", 11), Alignment: align("center", "center"), Border: frame}},
		{&s.day, excelize.Style{Font: font(false, "000000", 11), Alignment: align("center", "top"), Border: frame, CustomNumFmt: &dayFormat}},
		{&s.name, excelize.Style{Font: font(false, "000000", 11), Alignment: align("left", "top"), Border: frame}},
		{&s.balls, excelize.Style{Font: font(false, "000000", 11), Alignment: align("left", "top"), Border: frame, CustomNumFmt: &ballsFormat}},
		{&s.item, excelize.Style{Font: font(false, "000000", 11), Border: frame,
			Alignment: &excelize.Alignment{Horizontal: "left", Vertical: "top", WrapText: true}}},
	} {
		id, err := f.NewStyle(&d.style)
		if err != nil {
			return s, err
		}
		*d.id = id
	}
	return s, nil
}

// sheetWriter écrit une feuille en gardant la première erreur : excelize en renvoie une
// à chaque appel, qu'on vérifie une fois la feuille finie.
type sheetWriter struct {
	f     *excelize.File
	sheet string
	err   error
}

func (s *sheetWriter) check(err error) {
	if s.err == nil {
		s.err = err
	}
}

func (s *sheetWriter) cell(col, row int) string {
	name, err := excelize.CoordinatesToCellName(col, row)
	s.check(err)
	return name
}

// set écrit une valeur et son style ; nil laisse la cellule vide, encadrée quand même.
func (s *sheetWriter) set(col, row int, value any, style int) {
	cell := s.cell(col, row)
	if value != nil {
		s.check(s.f.SetCellValue(s.sheet, cell, value))
	}
	s.check(s.f.SetCellStyle(s.sheet, cell, cell, style))
}

// merge fusionne une plage de la ligne row et y écrit une valeur.
func (s *sheetWriter) merge(fromCol, toCol, row int, value any, style int) {
	from, to := s.cell(fromCol, row), s.cell(toCol, row)
	s.check(s.f.SetCellValue(s.sheet, from, value))
	s.check(s.f.SetCellStyle(s.sheet, from, to, style))
	if toCol > fromCol {
		s.check(s.f.MergeCell(s.sheet, from, to))
	}
}

// printLayout reprend la mise en page du classeur d'origine : A4 paysage, toute la
// largeur sur une page (autant de pages qu'il faut en hauteur), centrée.
func (s *sheetWriter) printLayout() {
	a4, landscape, wide, tall, on := 9, "landscape", 1, 0, true
	s.check(s.f.SetPageLayout(s.sheet, &excelize.PageLayoutOptions{Size: &a4, Orientation: &landscape, FitToWidth: &wide, FitToHeight: &tall}))
	s.check(s.f.SetSheetProps(s.sheet, &excelize.SheetPropsOptions{FitToPage: &on}))
	s.check(s.f.SetPageMargins(s.sheet, &excelize.PageLayoutMarginsOptions{Horizontally: &on}))
}

func (s *sheetWriter) width(col int, width float64) {
	name, err := excelize.ColumnNumberToName(col)
	s.check(err)
	s.check(s.f.SetColWidth(s.sheet, name, name, width))
}

// workbook construit les deux feuilles. now date le stock ; le titre et les en-têtes
// suivent la langue de la requête.
func workbook(loc *i18n.Localizer, now time.Time, view stockView, rows []distribution) (*excelize.File, error) {
	f := excelize.NewFile()
	fail := func(err error) (*excelize.File, error) {
		_ = f.Close()
		return nil, err
	}
	if err := f.SetDefaultFont("Arial"); err != nil {
		return fail(err)
	}
	styles, err := newExportStyles(f, loc)
	if err != nil {
		return fail(err)
	}
	stockName := loc.T("export.stock_sheet")
	if err := f.SetSheetName(f.GetSheetName(0), stockName); err != nil {
		return fail(err)
	}
	stockSheet := &sheetWriter{f: f, sheet: stockName}
	writeStockSheet(stockSheet, styles, loc, now.Format(loc.T("format.date")), view)
	if stockSheet.err != nil {
		return fail(stockSheet.err)
	}

	exitsName := loc.T("export.exits_sheet")
	if _, err := f.NewSheet(exitsName); err != nil {
		return fail(err)
	}
	exitsSheet := &sheetWriter{f: f, sheet: exitsName}
	writeExitsSheet(exitsSheet, styles, loc, rows)
	if exitsSheet.err != nil {
		return fail(exitsSheet.err)
	}
	f.SetActiveSheet(0)
	return f, nil
}

// stockRow est une ligne d'une table de marque : un bain en stock, ou un coloris épuisé
// (sans bain, stock 0).
type stockRow struct {
	Shade  string
	DyeLot string
	Code   string
	Stock  int
}

// writeStockSheet écrit une table par marque, en colonnes de tables qui se lisent de
// haut en bas puis de gauche à droite, dans l'ordre alphabétique comme la page Stock.
func writeStockSheet(s *sheetWriter, st exportStyles, loc *i18n.Localizer, today string, view stockView) {
	upper := cases.Upper(loc.Lang())
	rows := make([][]stockRow, len(view.Brands))
	heights := make([]int, len(view.Brands))
	for i, b := range view.Brands {
		rows[i] = stockRows(b, loc)
		heights[i] = 2 + len(rows[i]) // nom de la marque, en-têtes, lignes
	}
	stacks := stackBrands(heights, stockStacks)
	for stack, brands := range stacks {
		col, row := 1+stack*(stockBlockWidth+1), 2
		for _, bi := range brands {
			brand := loc.T("stock.unknown_brand")
			if b := view.Brands[bi].Brand; b != "" {
				brand = loc.T("export.brand", map[string]any{"Brand": upper.String(b)})
			}
			s.merge(col, col+stockBlockWidth-1, row, brand, st.brand)
			for i, key := range []string{"export.col_shade", "export.col_dye_lot", "export.col_code", "export.col_stock"} {
				s.set(col+i, row+1, loc.T(key, map[string]any{"Date": today}), st.header)
			}
			for i, line := range rows[bi] {
				r := row + 2 + i
				s.set(col, r, nonEmpty(line.Shade), st.shade)
				s.set(col+1, r, cellValue(line.DyeLot), st.dyeLot)
				s.set(col+2, r, cellValue(line.Code), st.code)
				s.set(col+3, r, line.Stock, st.stock)
			}
			row += heights[bi] + stockGap
		}
	}

	used := max(len(stacks), 1)
	last := used*(stockBlockWidth+1) - 1
	s.merge(1, last, 1, loc.T("export.stock_title", map[string]any{"Date": today}), st.title)
	// Les lignes aérées du classeur d'origine ; le titre, plus haut.
	height, custom := 19.5, true
	s.check(s.f.SetSheetProps(s.sheet, &excelize.SheetPropsOptions{DefaultRowHeight: &height, CustomHeight: &custom}))
	s.check(s.f.SetRowHeight(s.sheet, 1, 36))
	for stack := range used {
		col := 1 + stack*(stockBlockWidth+1)
		for i, width := range []float64{24, 12, 12, 18} {
			s.width(col+i, width)
		}
		if stack < used-1 {
			s.width(col+stockBlockWidth, 1.75)
		}
	}
	// Le titre et les en-têtes de la première rangée de tables restent visibles.
	s.check(s.f.SetPanes(s.sheet, &excelize.Panes{Freeze: true, YSplit: 3, TopLeftCell: "A4", ActivePane: "bottomLeft"}))
	s.printLayout()
}

// stackBrands répartit des tables de hauteurs données, dans leur ordre, en au plus
// stacks colonnes contiguës, de sorte que la plus haute soit la plus courte possible.
// Renvoie les indices des tables de chaque colonne.
func stackBrands(heights []int, stacks int) [][]int {
	// pack remplit chaque colonne tant qu'elle ne dépasse pas capacity, et passe à la
	// suivante dès qu'il reste une colonne par table restante ; nil s'il faut plus de
	// stacks colonnes.
	pack := func(capacity int) [][]int {
		var cols [][]int
		used := 0
		for i, h := range heights {
			if n := len(cols); n > 0 && used+stockGap+h <= capacity && stacks-n < len(heights)-i {
				cols[n-1] = append(cols[n-1], i)
				used += stockGap + h
				continue
			}
			if len(cols) == stacks {
				return nil
			}
			cols = append(cols, []int{i})
			used = h
		}
		return cols
	}
	lo, hi := 0, 0
	for _, h := range heights {
		lo = max(lo, h)
		hi += h + stockGap
	}
	for lo < hi {
		if mid := (lo + hi) / 2; pack(mid) != nil {
			hi = mid
		} else {
			lo = mid + 1
		}
	}
	return pack(lo)
}

// stockRows donne les lignes d'une marque : chaque bain en stock, et une ligne sans bain
// pour un coloris épuisé, qui reste dans la liste.
func stockRows(b brandGroup, loc *i18n.Localizer) []stockRow {
	var rows []stockRow
	for _, c := range b.Colors {
		shade := c.Name
		if c.Code == "" && c.Name == "" {
			shade = loc.T("stock.unknown_color")
		}
		if len(c.Lots) == 0 {
			rows = append(rows, stockRow{Shade: shade, Code: c.Code, Stock: c.Total})
			continue
		}
		for _, lot := range c.Lots {
			rows = append(rows, stockRow{Shade: shade, DyeLot: lot.DyeLot, Code: c.Code, Stock: lot.Quantity})
		}
	}
	return rows
}

// writeExitsSheet écrit une ligne par personne et par jour : la date, le nom, le nombre
// de pelotes, puis le détail des bains, un par ligne dans la cellule.
func writeExitsSheet(s *sheetWriter, st exportStyles, loc *i18n.Localizer, rows []distribution) {
	s.merge(1, 4, 1, loc.T("export.exits_title"), st.title)
	s.check(s.f.SetRowHeight(s.sheet, 1, 22.5))
	for i, key := range []string{"export.col_date", "export.col_recipient", "export.col_balls", "export.col_detail"} {
		s.set(1+i, 3, loc.T(key), st.columnTitle)
	}
	for i, d := range rows {
		r := 4 + i
		s.set(1, r, d.Day, st.day)
		s.set(2, r, d.Recipient, st.name)
		s.set(3, r, d.Total, st.balls)
		s.set(4, r, distributionDetail(d.Items, loc), st.item)
	}
	for i, width := range []float64{14, 30, 18, 60} {
		s.width(1+i, width)
	}
	s.printLayout()
}

// distributionDetail décrit chaque bain sorti : « Katia 1 blanc, bain 81784 : 2 pelotes ».
func distributionDetail(items []itemGroup, loc *i18n.Localizer) string {
	lines := make([]string, 0, len(items))
	for _, item := range items {
		yarn := deref(item.Brand)
		if yarn == "" {
			yarn = loc.T("stock.unknown_brand")
		}
		for _, part := range []*string{item.ColorCode, item.ColorName} {
			if p := strings.TrimSpace(deref(part)); p != "" {
				yarn += " " + p
			}
		}
		data := map[string]any{"Yarn": yarn, "DyeLot": item.DyeLot, "Count": item.Total}
		if item.DyeLot == "" {
			lines = append(lines, loc.Tn("export.item_no_dye_lot", item.Total, data))
		} else {
			lines = append(lines, loc.Tn("export.item", item.Total, data))
		}
	}
	return strings.Join(lines, "\n")
}

// cellValue écrit un n° en nombre quand Excel le garderait tel quel, comme dans le
// classeur d'origine ; sinon en texte (« 61816A », « 0123 »). Vide : cellule vide.
func cellValue(s string) any {
	if s == "" {
		return nil
	}
	if plainNumber.MatchString(s) {
		if n, err := strconv.ParseInt(s, 10, 64); err == nil {
			return n
		}
	}
	return s
}

// nonEmpty laisse vide la cellule d'un texte vide.
func nonEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}
