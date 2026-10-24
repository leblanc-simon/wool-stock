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
	Brands    []brandGroup
	Balls     int
	EmptyLots int
}

type brandGroup struct {
	// Brand vide : marque inconnue, toujours en dernier.
	Brand  string
	Total  int
	Colors []colorGroup
}

// colorGroup réunit les bains d'un coloris (n° et nom) ; deux qualités d'une marque
// peuvent partager un coloris, d'où plusieurs EAN possibles.
type colorGroup struct {
	Code  string
	Name  string
	EANs  []string
	Total int
	Lots  []store.StockItem
}

func (a *App) stock(w http.ResponseWriter, r *http.Request) {
	items, err := a.Store.Stock(r.Context(), store.StockFilter{IncludeEmpty: true})
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	a.render(w, r, http.StatusOK, "stock", "stock", "stock.title", groupStock(items))
}

// groupStock regroupe par marque puis coloris, dans l'ordre du store (marque, n°, nom, EAN, bain).
func groupStock(items []store.StockItem) stockView {
	var view stockView
	brands := map[string]int{}
	colors := map[string]int{}
	for _, item := range items {
		brand := strings.TrimSpace(deref(item.Brand))
		bi, ok := brands[strings.ToLower(brand)]
		if !ok {
			bi = len(view.Brands)
			brands[strings.ToLower(brand)] = bi
			view.Brands = append(view.Brands, brandGroup{Brand: brand})
		}
		b := &view.Brands[bi]
		// Même marque saisie « stylecraft » et « Stylecraft » : on garde la forme avec majuscule.
		if brand != b.Brand && startsUpper(brand) && !startsUpper(b.Brand) {
			b.Brand = brand
		}

		code, name := strings.TrimSpace(deref(item.ColorCode)), strings.TrimSpace(deref(item.ColorName))
		key := strings.ToLower(brand + "\x00" + code + "\x00" + name)
		ci, ok := colors[key]
		if !ok {
			ci = len(b.Colors)
			colors[key] = ci
			b.Colors = append(b.Colors, colorGroup{Code: code, Name: name})
		}
		c := &b.Colors[ci]
		if len(c.EANs) == 0 || c.EANs[len(c.EANs)-1] != item.EAN {
			c.EANs = append(c.EANs, item.EAN)
		}
		c.Lots = append(c.Lots, item)
		c.Total += item.Quantity
		b.Total += item.Quantity
		view.Balls += item.Quantity
		if item.Quantity == 0 {
			view.EmptyLots++
		}
	}
	return view
}

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

type entriesView struct {
	period
	Movements []store.Movement
	Total     int
	Truncated bool
	Limit     int
}

func (a *App) entries(w http.ResponseWriter, r *http.Request) {
	p := a.parsePeriod(r)
	list, truncated, err := a.movements(r, store.KindIn, p)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	view := entriesView{period: p, Movements: list, Truncated: truncated, Limit: movementLimit}
	for _, m := range list {
		view.Total += m.Quantity
	}
	a.render(w, r, http.StatusOK, "entries", "entries", "entries.title", view)
}

type exitsView struct {
	period
	Recipients []recipientGroup
	Total      int
	Truncated  bool
	Limit      int
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

func (a *App) exits(w http.ResponseWriter, r *http.Request) {
	p := a.parsePeriod(r)
	list, truncated, err := a.movements(r, store.KindOut, p)
	if err != nil {
		a.serverError(w, r, err)
		return
	}
	view := exitsView{period: p, Truncated: truncated, Limit: movementLimit,
		Recipients: groupExits(list, a.Location, a.I18n.FromRequest(r).Lang())}
	for _, m := range list {
		view.Total += m.Quantity
	}
	a.render(w, r, http.StatusOK, "exits", "exits", "exits.title", view)
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
