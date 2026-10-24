package web

import (
	"context"
	"net/http"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/xuri/excelize/v2"
	"golang.org/x/text/language"

	"leblanc.io/wool-stock/backend/internal/store"
)

func TestNaturalLess(t *testing.T) {
	for _, tc := range []struct {
		a, b string
		want bool
	}{
		{"11", "101", true},
		{"101", "11", false},
		{"9", "10", true},
		{"A2", "A10", true},
		{"a2", "A10", true},
		{"011", "11", false},
		{"11", "011", false},
		{"", "1", true},
		{"1", "", false},
		{"11", "A2", true},
		{"204", "204B", true},
	} {
		if got := naturalLess(tc.a, tc.b); got != tc.want {
			t.Errorf("naturalLess(%q, %q) = %v", tc.a, tc.b, got)
		}
	}
}

// Les n° de coloris se suivent comme des nombres, sur la page comme dans l'export.
func TestGroupStockSortsCodesAsNumbers(t *testing.T) {
	var items []store.StockItem
	// Ordre texte du store : "", "101", "11", "A10", "A2".
	for i, code := range []string{"", "101", "11", "A10", "A2"} {
		items = append(items, store.StockItem{EAN: string(rune('1' + i)), Quantity: 1, Brand: ptr("Cheval Blanc"), ColorCode: ptr(code)})
	}
	var codes []string
	for _, c := range groupStock(items).Brands[0].Colors {
		codes = append(codes, c.Code)
	}
	if want := []string{"", "11", "101", "A2", "A10"}; !slices.Equal(codes, want) {
		t.Fatalf("codes = %q, want %q", codes, want)
	}
}

func TestStackBrands(t *testing.T) {
	for _, tc := range []struct {
		name    string
		heights []int
		want    [][]int
	}{
		// Les tables du classeur d'origine : Cheval Blanc, Jenny, Katia, Lisa, Unico, Zeeman.
		{"original", []int{14, 4, 7, 25, 33, 18}, [][]int{{0, 1, 2}, {3}, {4}, {5}}},
		{"one per column", []int{3, 3, 3}, [][]int{{0}, {1}, {2}}},
		{"more brands than columns", []int{3, 3, 3, 3, 3}, [][]int{{0, 1}, {2}, {3}, {4}}},
		{"empty", nil, nil},
	} {
		if got := stackBrands(tc.heights, stockStacks); !slices.EqualFunc(got, tc.want, slices.Equal[[]int]) {
			t.Errorf("%s: %v, want %v", tc.name, got, tc.want)
		}
	}
}

func TestDistributions(t *testing.T) {
	at := func(s string) time.Time {
		v, _ := time.Parse(time.RFC3339, s)
		return v
	}
	// Du plus récent au plus ancien, comme le store les renvoie.
	list := []store.Movement{
		{ID: 4, EAN: "1", DyeLot: "A", Quantity: 2, Recipient: ptr("Marie"), CreatedAt: at("2026-10-23T09:00:00Z")},
		{ID: 3, EAN: "1", DyeLot: "A", Quantity: 1, Recipient: ptr("Léa"), CreatedAt: at("2026-10-23T08:00:00Z")},
		{ID: 2, EAN: "2", DyeLot: "B", Quantity: 4, Recipient: ptr("Marie"), CreatedAt: at("2026-10-20T10:00:00Z")},
		{ID: 1, EAN: "1", DyeLot: "A", Quantity: 3, Recipient: ptr("Marie"), CreatedAt: at("2026-10-20T09:00:00Z")},
	}
	rows := distributions(list, time.UTC, language.French)
	var got []string
	for _, r := range rows {
		got = append(got, r.Day.Format(time.DateOnly)+" "+r.Recipient)
	}
	// Du plus ancien jour au plus récent ; le même jour, dans l'ordre alphabétique.
	if want := []string{"2026-10-20 Marie", "2026-10-23 Léa", "2026-10-23 Marie"}; !slices.Equal(got, want) {
		t.Fatalf("rows = %q, want %q", got, want)
	}
	if rows[0].Total != 7 || len(rows[0].Items) != 2 {
		t.Fatalf("first row = %+v", rows[0])
	}
}

func TestExport(t *testing.T) {
	srv, st := newSite(t)
	ctx := context.Background()
	for _, in := range []store.MovementInput{
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 3,
			Reference: &store.ReferenceFields{Brand: ptr("Katia"), ColorCode: ptr("1"), ColorName: ptr("blanc")}},
		{Kind: store.KindIn, EAN: "8435407948594", DyeLot: "81790", Quantity: 1},
		{Kind: store.KindIn, EAN: "3664263018102", DyeLot: "A12", Quantity: 2,
			Reference: &store.ReferenceFields{Brand: ptr("Bergère de France"), ColorCode: ptr("10")}},
		{Kind: store.KindIn, EAN: "3000000000101", DyeLot: "53553", Quantity: 19,
			Reference: &store.ReferenceFields{Brand: ptr("Cheval Blanc"), ColorCode: ptr("101"), ColorName: ptr("Tournesol")}},
		{Kind: store.KindIn, EAN: "3000000000011", DyeLot: "", Quantity: 4,
			Reference: &store.ReferenceFields{Brand: ptr("Cheval Blanc"), ColorCode: ptr("11"), ColorName: ptr("Blanche")}},
		{Kind: store.KindOut, EAN: "8435407948594", DyeLot: "81790", Quantity: 1, Recipient: "Marie"},
		{Kind: store.KindOut, EAN: "8435407948594", DyeLot: "81784", Quantity: 2, Recipient: "Marie"},
		{Kind: store.KindOut, EAN: "3664263018102", DyeLot: "A12", Quantity: 2, Recipient: "Léa"},
	} {
		if _, _, _, err := st.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}

	for _, page := range []string{"/stock", "/exits"} {
		if _, _, body := get(t, srv, page, "fr"); !strings.Contains(body, `href="/stock/export" download>Exporter en Excel</a>`) {
			t.Errorf("%s: export link missing", page)
		}
	}

	status, header, body := get(t, srv, "/stock/export", "fr")
	if status != http.StatusOK || header.Get("Content-Type") != "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" {
		t.Fatalf("export: status %d, headers %v", status, header)
	}
	if cd := header.Get("Content-Disposition"); !strings.HasPrefix(cd, `attachment; filename=stock-laine-`) || !strings.HasSuffix(cd, `.xlsx`) {
		t.Errorf("content-disposition = %q", cd)
	}
	f, err := excelize.OpenReader(strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if sheets := f.GetSheetList(); !slices.Equal(sheets, []string{"Stock laine", "Suivi distribution pelotes"}) {
		t.Fatalf("sheets = %q", sheets)
	}

	cell := func(sheet, name string) string {
		t.Helper()
		v, err := f.GetCellValue(sheet, name, excelize.Options{RawCellValue: true})
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	const stock = "Stock laine"
	// Trois marques, trois colonnes de tables, dans l'ordre alphabétique.
	for name, want := range map[string]string{
		"A2": "Laine BERGÈRE DE FRANCE", "F2": "Laine CHEVAL BLANC", "K2": "Laine KATIA",
		"A3": "COULEUR", "B3": "N° de bain", "C3": "N° Couleur",
		// Coloris épuisé : une ligne sans bain, à 0.
		"A4": "", "B4": "", "C4": "10", "D4": "0",
		// 11 avant 101 ; un bain sans numéro laisse la cellule vide.
		"F4": "Blanche", "G4": "", "H4": "11", "I4": "4",
		"F5": "Tournesol", "G5": "53553", "H5": "101", "I5": "19",
		// Le bain épuisé 81790 disparaît, le coloris reste avec 81784.
		"K4": "blanc", "L4": "81784", "M4": "1", "N4": "1", "K5": "",
	} {
		if got := cell(stock, name); got != want {
			t.Errorf("%s = %q, want %q", name, got, want)
		}
	}
	if title := cell(stock, "A1"); !strings.HasPrefix(title, "STOCK DE LAINE AU ") {
		t.Errorf("title = %q", title)
	}
	if head := cell(stock, "D3"); !strings.HasPrefix(head, "Stock du ") {
		t.Errorf("stock column = %q", head)
	}
	// Les n° restent des nombres, comme dans le classeur d'origine.
	for _, name := range []string{"G5", "H5", "L4"} {
		if typ, _ := f.GetCellType(stock, name); typ == excelize.CellTypeSharedString || typ == excelize.CellTypeInlineString {
			t.Errorf("%s is text", name)
		}
	}
	// 3,5 cm, 2,10 cm et 2,20 cm : 132, 79 et 83 pixels à 8 pixels par chiffre, dans chaque table.
	for col, want := range map[string]float64{"A": 16.5, "B": 9.875, "C": 10.375, "F": 16.5, "G": 9.875, "H": 10.375} {
		if got, err := f.GetColWidth(stock, col); err != nil || got != want {
			t.Errorf("width %s = %v (%v), want %v", col, got, err, want)
		}
	}
	merged, err := f.GetMergeCells(stock)
	if err != nil {
		t.Fatal(err)
	}
	var ranges []string
	for _, m := range merged {
		ranges = append(ranges, m.GetStartAxis()+":"+m.GetEndAxis())
	}
	for _, want := range []string{"A1:N1", "A2:D2", "F2:I2", "K2:N2"} {
		if !slices.Contains(ranges, want) {
			t.Errorf("merge %s missing from %q", want, ranges)
		}
	}

	const exits = "Suivi distribution pelotes"
	exitList, err := st.Movements(ctx, store.MovementFilter{Kind: store.KindOut})
	if err != nil {
		t.Fatal(err)
	}
	day := exitList[0].CreatedAt.UTC().Format("02/01/2006")
	for name, want := range map[string]string{
		"A3": "DATE", "B3": "NOM", "C3": "Nombre de pelotes", "D3": "Détail",
		"B4": "Léa", "C4": "2", "D4": "Bergère de France 10, bain A12 : 2 pelotes",
		"B5": "Marie", "C5": "3", "D5": "Katia 1 blanc, bain 81784 : 2 pelotes\nKatia 1 blanc, bain 81790 : 1 pelote",
	} {
		if got := cell(exits, name); got != want {
			t.Errorf("%s!%s = %q, want %q", exits, name, got, want)
		}
	}
	if got, _ := f.GetCellValue(exits, "A4"); got != day {
		t.Errorf("date = %q, want %q", got, day)
	}

	// En anglais : noms des feuilles, titres et nom du fichier suivent la langue.
	_, header, body = get(t, srv, "/stock/export", "en")
	if cd := header.Get("Content-Disposition"); !strings.Contains(cd, "yarn-stock-") {
		t.Errorf("en content-disposition = %q", cd)
	}
	en, err := excelize.OpenReader(strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer en.Close()
	if sheets := en.GetSheetList(); !slices.Equal(sheets, []string{"Yarn stock", "Ball distribution"}) {
		t.Errorf("en sheets = %q", sheets)
	}
	if v, _ := en.GetCellValue("Yarn stock", "K2"); v != "KATIA yarn" {
		t.Errorf("en brand = %q", v)
	}

	// Sans session, direction la connexion.
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/stock/export", nil)
	if status, header, _ := send(t, req); status != http.StatusSeeOther || !strings.HasPrefix(header.Get("Location"), "/login") {
		t.Errorf("anonymous export: %d %v", status, header)
	}
}
