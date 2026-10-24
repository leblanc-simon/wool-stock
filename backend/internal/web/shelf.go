package web

import (
	"strings"

	"leblanc.io/wool-stock/backend/internal/store"
)

// shelfBrand réunit, pour choisir le bain d'une sortie ou compter un inventaire, des
// bains par marque puis coloris. À la différence de la page Stock, chaque bain reçu
// reste, épuisé ou non : l'appelant choisit lesquels garder.
type shelfBrand struct {
	// Brand vide : marque inconnue, en dernier comme dans le store.
	Brand  string
	Shades []shelfShade
}

// shelfShade est un coloris (n° et nom) ; deux qualités d'une marque peuvent le
// partager, d'où plusieurs EAN possibles.
type shelfShade struct {
	Code string
	Name string
	EANs []string
	Lots []shelfLot
}

// Label est le n° et le nom du coloris, vide s'il est inconnu.
func (s shelfShade) Label() string {
	return strings.TrimSpace(s.Code + " " + s.Name)
}

// shelfLot est un bain et, pendant un inventaire, son comptage s'il est déjà compté.
type shelfLot struct {
	store.StockItem
	Count *store.InventoryCount
}

// groupShelf regroupe les bains dans l'ordre du store (marque, n°, nom, EAN, bain), sans
// tenir compte de la casse ; counts, facultatif, donne le comptage de chaque bain.
func groupShelf(items []store.StockItem, counts map[int64]store.InventoryCount) []shelfBrand {
	var brands []shelfBrand
	brandIndex := map[string]int{}
	shadeIndex := map[string]int{}
	for _, item := range items {
		brand := strings.TrimSpace(deref(item.Brand))
		bi, ok := brandIndex[strings.ToLower(brand)]
		if !ok {
			bi = len(brands)
			brandIndex[strings.ToLower(brand)] = bi
			brands = append(brands, shelfBrand{Brand: brand})
		}
		b := &brands[bi]
		// Même marque saisie « katia » et « Katia » : on garde la forme avec majuscule.
		if brand != b.Brand && startsUpper(brand) && !startsUpper(b.Brand) {
			b.Brand = brand
		}

		code, name := strings.TrimSpace(deref(item.ColorCode)), strings.TrimSpace(deref(item.ColorName))
		key := strings.ToLower(brand + "\x00" + code + "\x00" + name)
		si, ok := shadeIndex[key]
		if !ok {
			si = len(b.Shades)
			shadeIndex[key] = si
			b.Shades = append(b.Shades, shelfShade{Code: code, Name: name})
		}
		s := &b.Shades[si]
		if len(s.EANs) == 0 || s.EANs[len(s.EANs)-1] != item.EAN {
			s.EANs = append(s.EANs, item.EAN)
		}
		lot := shelfLot{StockItem: item}
		if c, ok := counts[item.LotID]; ok {
			lot.Count = &c
		}
		s.Lots = append(s.Lots, lot)
	}
	return brands
}

// findLot cherche un bain par son identifiant.
func findLot(items []store.StockItem, id int64) (store.StockItem, bool) {
	for _, item := range items {
		if item.LotID == id {
			return item, true
		}
	}
	return store.StockItem{}, false
}
