package web

import (
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode"

	"golang.org/x/text/collate"
	"golang.org/x/text/language"

	"leblanc.io/wool-stock/backend/internal/store"
)

const (
	// movementLimit borne une page d'entrées ou de sorties ; au-delà, on invite à resserrer la période.
	movementLimit = 2000
	// defaultPeriodDays est la période affichée sans filtre : les 30 derniers jours.
	defaultPeriodDays = 30
)

// stockView est le stock complet, bains épuisés compris.
type stockView struct {
	Brands []brandGroup
	Balls  int
	// EmptyColors compte les coloris sans aucune pelote, qui restent dans la liste.
	EmptyColors int
}

type brandGroup struct {
	// Brand vide : marque inconnue, toujours en dernier.
	Brand  string
	Total  int
	Colors []colorGroup
}

// colorGroup réunit les bains en stock d'un coloris (n° et nom) ; deux qualités
// d'une marque peuvent partager un coloris, d'où plusieurs EAN possibles. Un coloris
// épuisé garde sa place, sans bain.
type colorGroup struct {
	Code  string
	Name  string
	EANs  []string
	Total int
	Lots  []store.StockItem
	// Received liste tous les bains reçus, épuisés compris, pour l'export Excel.
	Received []store.StockItem
}

func (a *App) stock(w http.ResponseWriter, r *http.Request) {
	items, err := a.Store.Stock(r.Context(), store.StockFilter{IncludeEmpty: true})
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	a.render(w, r, http.StatusOK, "stock", "stock", "stock.title", groupStock(items))
}

// groupStock regroupe par marque puis coloris comme groupShelf, dans l'ordre du store
// (marque, n°, nom, EAN, bain), à ceci près que les n° de coloris se comparent comme des
// nombres, et compte les pelotes. Les bains épuisés disparaissent, pas les coloris.
func groupStock(items []store.StockItem) stockView {
	var view stockView
	for _, shelf := range groupShelf(items, nil) {
		b := brandGroup{Brand: shelf.Brand}
		for _, shade := range shelf.Shades {
			c := colorGroup{Code: shade.Code, Name: shade.Name, EANs: shade.EANs}
			for _, lot := range shade.Lots {
				if lot.Quantity > 0 {
					c.Lots = append(c.Lots, lot.StockItem)
				}
				c.Received = append(c.Received, lot.StockItem)
				c.Total += lot.Quantity
			}
			if c.Total == 0 {
				view.EmptyColors++
			}
			b.Total += c.Total
			b.Colors = append(b.Colors, c)
		}
		// Le store trie les n° comme du texte (« 101 » avant « 11 »).
		sort.SliceStable(b.Colors, func(i, j int) bool { return naturalLess(b.Colors[i].Code, b.Colors[j].Code) })
		view.Balls += b.Total
		view.Brands = append(view.Brands, b)
	}
	return view
}

// naturalLess compare deux n° de coloris en lisant les suites de chiffres comme des
// nombres : « 11 » avant « 101 », « A2 » avant « A10 » ; les lettres, sans la casse.
func naturalLess(a, b string) bool {
	for a != "" && b != "" {
		ca, cb := leadingRun(a), leadingRun(b)
		a, b = a[len(ca):], b[len(cb):]
		if isDigit(ca[0]) && isDigit(cb[0]) {
			na, nb := strings.TrimLeft(ca, "0"), strings.TrimLeft(cb, "0")
			if len(na) != len(nb) {
				return len(na) < len(nb)
			}
			if na != nb {
				return na < nb
			}
			continue
		}
		if la, lb := strings.ToLower(ca), strings.ToLower(cb); la != lb {
			return la < lb
		}
	}
	return len(a) < len(b)
}

// leadingRun renvoie le début de s fait que de chiffres, ou que d'autres caractères.
func leadingRun(s string) string {
	digits := isDigit(s[0])
	for i := 1; i < len(s); i++ {
		if isDigit(s[i]) != digits {
			return s[:i]
		}
	}
	return s
}

func isDigit(c byte) bool { return '0' <= c && c <= '9' }

// period est la période filtrée, en dates locales ; To est inclus.
type period struct {
	From time.Time
	To   time.Time
}

// parsePeriod lit ?from= et ?to= (AAAA-MM-JJ) ; par défaut les 30 derniers jours.
func (a *App) parsePeriod(r *http.Request) period {
	now := a.Now().In(a.Location)
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, a.Location)
	p := period{To: today}
	if to, err := time.ParseInLocation(time.DateOnly, r.URL.Query().Get("to"), a.Location); err == nil {
		p.To = to
	}
	p.From = p.To.AddDate(0, 0, 1-defaultPeriodDays)
	if from, err := time.ParseInLocation(time.DateOnly, r.URL.Query().Get("from"), a.Location); err == nil {
		p.From = from
	}
	return p
}

// movements lit les mouvements d'un type sur la période, du plus récent au plus ancien.
func (a *App) movements(r *http.Request, kind store.Kind, p period) ([]store.Movement, bool, error) {
	list, err := a.Store.Movements(r.Context(), store.MovementFilter{
		Kind:  kind,
		Since: p.From,
		Until: p.To.AddDate(0, 0, 1),
		Limit: movementLimit + 1,
	})
	if err != nil {
		return nil, false, err
	}
	if len(list) > movementLimit {
		return list[:movementLimit], true, nil
	}
	return list, false, nil
}

type recipientGroup struct {
	Name  string
	Total int
	Days  []dayGroup
}

type dayGroup struct {
	Day   time.Time
	Total int
	Items []itemGroup
}

// itemGroup est ce qui est sorti : un bain d'une référence.
type itemGroup struct {
	EAN       string
	DyeLot    string
	Brand     *string
	ColorCode *string
	ColorName *string
	Total     int
	Movements []store.Movement
}

// groupExits regroupe les sorties par destinataire (sans tenir compte de la casse),
// puis par jour local, puis par bain. Destinataires et références sont triés
// selon la langue ; les jours et les mouvements vont du plus récent au plus ancien.
func groupExits(list []store.Movement, loc *time.Location, lang language.Tag) []recipientGroup {
	col := collate.New(lang, collate.IgnoreCase, collate.Loose)
	var groups []recipientGroup
	recipients := map[string]int{}
	for _, m := range list {
		name := strings.TrimSpace(deref(m.Recipient))
		ri, ok := recipients[strings.ToLower(name)]
		if !ok {
			ri = len(groups)
			recipients[strings.ToLower(name)] = ri
			groups = append(groups, recipientGroup{Name: name})
		}
		g := &groups[ri]
		g.Total += m.Quantity
		// Même personne saisie « marie » puis « Marie » : on garde la forme avec majuscule.
		if name != g.Name && startsUpper(name) && !startsUpper(g.Name) {
			g.Name = name
		}

		at := m.CreatedAt.In(loc)
		day := time.Date(at.Year(), at.Month(), at.Day(), 0, 0, 0, 0, loc)
		if n := len(g.Days); n == 0 || !g.Days[n-1].Day.Equal(day) {
			g.Days = append(g.Days, dayGroup{Day: day})
		}
		d := &g.Days[len(g.Days)-1]
		d.Total += m.Quantity

		ii := -1
		for i := range d.Items {
			if d.Items[i].EAN == m.EAN && d.Items[i].DyeLot == m.DyeLot {
				ii = i
				break
			}
		}
		if ii < 0 {
			ii = len(d.Items)
			d.Items = append(d.Items, itemGroup{EAN: m.EAN, DyeLot: m.DyeLot,
				Brand: m.Brand, ColorCode: m.ColorCode, ColorName: m.ColorName})
		}
		d.Items[ii].Total += m.Quantity
		d.Items[ii].Movements = append(d.Items[ii].Movements, m)
	}

	sort.SliceStable(groups, func(i, j int) bool { return col.CompareString(groups[i].Name, groups[j].Name) < 0 })
	for gi := range groups {
		for di := range groups[gi].Days {
			items := groups[gi].Days[di].Items
			sort.SliceStable(items, func(i, j int) bool { return itemLess(col, items[i], items[j]) })
		}
	}
	return groups
}

// itemLess trie par marque (inconnue en dernier), n° et nom de coloris, puis bain.
func itemLess(col *collate.Collator, a, b itemGroup) bool {
	if (a.Brand == nil) != (b.Brand == nil) {
		return b.Brand == nil
	}
	for _, pair := range [][2]string{
		{deref(a.Brand), deref(b.Brand)},
		{deref(a.ColorCode), deref(b.ColorCode)},
		{deref(a.ColorName), deref(b.ColorName)},
		{a.EAN, b.EAN},
		{a.DyeLot, b.DyeLot},
	} {
		if c := col.CompareString(pair[0], pair[1]); c != 0 {
			return c < 0
		}
	}
	return false
}

func startsUpper(s string) bool {
	for _, r := range s {
		return unicode.IsUpper(r)
	}
	return false
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
