package store

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func open(t *testing.T) *Store {
	t.Helper()
	s, err := Open(context.Background(), "file:"+filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	// Rejouer les migrations ne doit rien faire.
	if err := s.migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	return s
}

func ptr(s string) *string { return &s }

func TestReferenceUpsert(t *testing.T) {
	ctx := context.Background()
	s := open(t)

	if _, err := s.Reference(ctx, "8435407948594"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown reference: err = %v", err)
	}
	created, err := s.SaveReference(ctx, "8435407948594", ReferenceFields{Brand: ptr("Katia"), ColorCode: ptr("1")})
	if err != nil {
		t.Fatal(err)
	}
	updated, err := s.SaveReference(ctx, "8435407948594", ReferenceFields{Brand: ptr("Katia"), ColorName: ptr("blanc")})
	if err != nil {
		t.Fatal(err)
	}
	if updated.ColorCode != nil || *updated.ColorName != "blanc" || !updated.CreatedAt.Equal(created.CreatedAt) {
		t.Fatalf("updated = %+v (created %+v)", updated, created)
	}
	refs, err := s.References(ctx)
	if err != nil || len(refs) != 1 {
		t.Fatalf("references = %+v, err = %v", refs, err)
	}
}

func TestEntryAndExit(t *testing.T) {
	ctx := context.Background()
	s := open(t)

	in := MovementInput{Kind: KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 3,
		Author: "Simon", Reference: &ReferenceFields{Brand: ptr("Katia"), ColorCode: ptr("1")}}
	movement, lot, replayed, err := s.Record(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if replayed || lot.Quantity != 3 || *lot.Brand != "Katia" || movement.Kind != KindIn || *movement.Author != "Simon" {
		t.Fatalf("entry: movement = %+v, lot = %+v", movement, lot)
	}

	// Une seconde entrée sans référence garde la marque en cache et cumule le bain.
	_, lot, _, err = s.Record(ctx, MovementInput{Kind: KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 2})
	if err != nil {
		t.Fatal(err)
	}
	if lot.Quantity != 5 || lot.Brand == nil || *lot.Brand != "Katia" {
		t.Fatalf("second entry: lot = %+v", lot)
	}

	out := MovementInput{Kind: KindOut, EAN: "8435407948594", DyeLot: "81784", Quantity: 6, Recipient: "Marie"}
	var insufficient *InsufficientStockError
	if _, _, _, err := s.Record(ctx, out); !errors.As(err, &insufficient) || insufficient.Available != 5 {
		t.Fatalf("too many: err = %v", err)
	}
	out.Quantity = 5
	movement, lot, _, err = s.Record(ctx, out)
	if err != nil {
		t.Fatal(err)
	}
	if lot.Quantity != 0 || *movement.Recipient != "Marie" {
		t.Fatalf("exit: movement = %+v, lot = %+v", movement, lot)
	}

	// Bain inconnu : rien à sortir.
	out.DyeLot = "99999"
	if _, _, _, err := s.Record(ctx, out); !errors.As(err, &insufficient) || insufficient.Available != 0 {
		t.Fatalf("unknown lot: err = %v", err)
	}

	stock, err := s.Stock(ctx, StockFilter{})
	if err != nil || len(stock) != 0 {
		t.Fatalf("stock without empty lots = %+v, err = %v", stock, err)
	}
	stock, err = s.Stock(ctx, StockFilter{EAN: "8435407948594", IncludeEmpty: true})
	if err != nil || len(stock) != 1 {
		t.Fatalf("stock with empty lots = %+v, err = %v", stock, err)
	}

	history, err := s.Movements(ctx, MovementFilter{EAN: "8435407948594", Limit: 10})
	if err != nil || len(history) != 3 || history[0].Kind != KindOut {
		t.Fatalf("history = %+v, err = %v", history, err)
	}
}

func TestReplayedRequestCountsOnce(t *testing.T) {
	ctx := context.Background()
	s := open(t)

	in := MovementInput{RequestID: "a1", Kind: KindIn, EAN: "4006369500144", DyeLot: "", Quantity: 4}
	first, _, _, err := s.Record(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	again, lot, replayed, err := s.Record(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if !replayed || again.ID != first.ID || lot.Quantity != 4 {
		t.Fatalf("replay: movement = %+v, lot = %+v, replayed = %v", again, lot, replayed)
	}

	// Un autre contenu sous le même identifiant (saisie corrigée après un délai dépassé)
	// est refusé : le rendre ferait croire au client que sa correction est passée.
	for _, changed := range []MovementInput{
		{RequestID: "a1", Kind: KindIn, EAN: "4006369500144", DyeLot: "", Quantity: 5},
		{RequestID: "a1", Kind: KindOut, EAN: "4006369500144", DyeLot: "", Quantity: 4, Recipient: "Marie"},
		{RequestID: "a1", Kind: KindIn, EAN: "4006369500144", DyeLot: "", Quantity: 4, Reference: &ReferenceFields{Brand: ptr("Katia")}},
	} {
		if _, _, _, err := s.Record(ctx, changed); !errors.Is(err, ErrRequestReused) {
			t.Fatalf("reused %+v: err = %v", changed, err)
		}
	}
	// L'auteur n'en fait pas partie : c'est le serveur qui le pose.
	in.Author = "Marie"
	if _, _, replayed, err := s.Record(ctx, in); err != nil || !replayed {
		t.Fatalf("replay by another author: replayed = %v, err = %v", replayed, err)
	}
	stock, err := s.Stock(ctx, StockFilter{EAN: "4006369500144"})
	if err != nil || len(stock) != 1 || stock[0].Quantity != 4 {
		t.Fatalf("stock = %+v, err = %v", stock, err)
	}
}

// Un envoi enregistré avant l'empreinte (migration 005) se rejoue comme avant.
func TestReplayWithoutStoredHash(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	in := MovementInput{RequestID: "old", Kind: KindIn, EAN: "4006369500144", Quantity: 2}
	if _, _, _, err := s.Record(ctx, in); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE movements SET request_hash = NULL`); err != nil {
		t.Fatal(err)
	}
	in.Quantity = 3
	if _, lot, replayed, err := s.Record(ctx, in); err != nil || !replayed || lot.Quantity != 2 {
		t.Fatalf("legacy replay: lot = %+v, replayed = %v, err = %v", lot, replayed, err)
	}
}

func TestStockOrder(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	for _, in := range []MovementInput{
		{Kind: KindIn, EAN: "00000001", DyeLot: "B", Quantity: 1},
		{Kind: KindIn, EAN: "00000002", DyeLot: "2", Quantity: 1, Reference: &ReferenceFields{Brand: ptr("phildar")}},
		{Kind: KindIn, EAN: "00000003", DyeLot: "1", Quantity: 1, Reference: &ReferenceFields{Brand: ptr("Drops")}},
		{Kind: KindIn, EAN: "00000002", DyeLot: "1", Quantity: 1},
	} {
		if _, _, _, err := s.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}
	stock, err := s.Stock(ctx, StockFilter{})
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, item := range stock {
		got = append(got, item.EAN+"/"+item.DyeLot)
	}
	want := []string{"00000003/1", "00000002/1", "00000002/2", "00000001/B"}
	if len(got) != len(want) {
		t.Fatalf("order = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("order = %v, want %v", got, want)
		}
	}
}

func TestMovementFilters(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	day := time.Date(2026, 10, 20, 9, 0, 0, 0, time.UTC)
	s.now = func() time.Time { return day }
	for _, in := range []MovementInput{
		{Kind: KindIn, EAN: "00000001", DyeLot: "1", Quantity: 5, Reference: &ReferenceFields{Brand: ptr("Drops")}},
		{Kind: KindOut, EAN: "00000001", DyeLot: "1", Quantity: 1, Recipient: "Marie"},
	} {
		if _, _, _, err := s.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}
	day = day.Add(48 * time.Hour)
	if _, _, _, err := s.Record(ctx, MovementInput{Kind: KindOut, EAN: "00000001", DyeLot: "1", Quantity: 2, Recipient: "Léa"}); err != nil {
		t.Fatal(err)
	}

	exits, err := s.Movements(ctx, MovementFilter{Kind: KindOut, Limit: 10})
	if err != nil || len(exits) != 2 || exits[0].Brand == nil || *exits[0].Brand != "Drops" {
		t.Fatalf("exits = %+v, err = %v", exits, err)
	}
	since := time.Date(2026, 10, 21, 0, 0, 0, 0, time.UTC)
	recent, err := s.Movements(ctx, MovementFilter{Since: since, Limit: 10})
	if err != nil || len(recent) != 1 || *recent[0].Recipient != "Léa" {
		t.Fatalf("since = %+v, err = %v", recent, err)
	}
	older, err := s.Movements(ctx, MovementFilter{Until: since, Limit: 10})
	if err != nil || len(older) != 2 {
		t.Fatalf("until = %+v, err = %v", older, err)
	}
}

func TestInventory(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	if _, _, _, err := s.Record(ctx, MovementInput{Kind: KindIn, EAN: "8435407948594", DyeLot: "81784", Quantity: 4,
		Reference: &ReferenceFields{Brand: ptr("Katia")}}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.CurrentInventory(ctx); !errors.Is(err, ErrNotFound) {
		t.Fatalf("no inventory: err = %v", err)
	}
	inv, err := s.StartInventory(ctx, "Simon", ScopePartial)
	if err != nil || *inv.StartedBy != "Simon" || inv.ClosedAt != nil {
		t.Fatalf("start = %+v, err = %v", inv, err)
	}
	if _, err := s.StartInventory(ctx, "Marie", ScopeFull); !errors.Is(err, ErrInventoryOpen) {
		t.Fatalf("second start: err = %v", err)
	}

	// Premier comptage : le stock prend la quantité comptée.
	in := CountInput{RequestID: "c1", InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 6, Mode: CountNew}
	count, lot, _, err := s.Count(ctx, in)
	if err != nil || count.Quantity != 6 || count.PreviousQuantity != 4 || lot.Quantity != 6 || *count.Brand != "Katia" {
		t.Fatalf("first count = %+v, lot = %+v, err = %v", count, lot, err)
	}
	// Rejoué : rien ne change.
	if count, _, replayed, err := s.Count(ctx, in); err != nil || !replayed || count.Quantity != 6 {
		t.Fatalf("replay = %+v, %v, err = %v", count, replayed, err)
	}
	// Le même identifiant pour un autre comptage, ou un autre mode : refusé.
	for _, changed := range []CountInput{
		{RequestID: "c1", InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 7, Mode: CountNew},
		{RequestID: "c1", InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 6, Mode: CountSet},
	} {
		if _, _, _, err := s.Count(ctx, changed); !errors.Is(err, ErrRequestReused) {
			t.Fatalf("reused %+v: err = %v", changed, err)
		}
	}
	// Même bain rescanné sans dire quoi faire : refusé, avec le comptage existant.
	var already *AlreadyCountedError
	_, _, _, err = s.Count(ctx, CountInput{InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 2, Mode: CountNew})
	if !errors.As(err, &already) || already.Counted != 6 {
		t.Fatalf("rescan: err = %v", err)
	}
	// Une sortie entre deux comptages, puis un ajout : il porte sur le stock courant.
	if _, _, _, err := s.Record(ctx, MovementInput{Kind: KindOut, EAN: "8435407948594", DyeLot: "81784", Quantity: 1, Recipient: "Léa"}); err != nil {
		t.Fatal(err)
	}
	count, lot, _, err = s.Count(ctx, CountInput{InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 2, Mode: CountAdd})
	if err != nil || count.Quantity != 8 || lot.Quantity != 7 || count.PreviousQuantity != 4 {
		t.Fatalf("add = %+v, lot = %+v, err = %v", count, lot, err)
	}
	count, lot, _, err = s.Count(ctx, CountInput{InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 3, Mode: CountSet})
	if err != nil || count.Quantity != 3 || lot.Quantity != 3 {
		t.Fatalf("set = %+v, lot = %+v, err = %v", count, lot, err)
	}
	// Un bain inconnu est créé, avec sa référence ; compter zéro est permis.
	count, lot, _, err = s.Count(ctx, CountInput{InventoryID: inv.ID, EAN: "4006369500144", DyeLot: "", Quantity: 0, Mode: CountAdd,
		Reference: &ReferenceFields{Brand: ptr("Lana Grossa")}})
	if err != nil || count.Quantity != 0 || count.PreviousQuantity != 0 || lot.Quantity != 0 || *lot.Brand != "Lana Grossa" {
		t.Fatalf("new lot = %+v, lot = %+v, err = %v", count, lot, err)
	}

	counts, err := s.InventoryCounts(ctx, inv.ID)
	if err != nil || len(counts) != 2 || counts[0].EAN != "4006369500144" {
		t.Fatalf("counts = %+v, err = %v", counts, err)
	}

	closed, err := s.CloseInventory(ctx, inv.ID, "Marie")
	if err != nil || closed.ClosedAt == nil || *closed.ClosedBy != "Marie" {
		t.Fatalf("close = %+v, err = %v", closed, err)
	}
	if _, err := s.CloseInventory(ctx, inv.ID, ""); !errors.Is(err, ErrInventoryClosed) {
		t.Fatalf("close twice: err = %v", err)
	}
	if _, _, _, err := s.Count(ctx, CountInput{InventoryID: inv.ID, EAN: "8435407948594", DyeLot: "81784", Quantity: 1}); !errors.Is(err, ErrInventoryClosed) {
		t.Fatalf("count closed: err = %v", err)
	}
	if _, err := s.CloseInventory(ctx, 999, ""); !errors.Is(err, ErrNotFound) {
		t.Fatalf("close unknown: err = %v", err)
	}
	// Un nouvel inventaire peut s'ouvrir une fois le précédent terminé.
	if _, err := s.StartInventory(ctx, "", ScopePartial); err != nil {
		t.Fatal(err)
	}
}

func TestFullInventoryZeroesUncountedLots(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	for _, in := range []MovementInput{
		{Kind: KindIn, EAN: "00000001", DyeLot: "A", Quantity: 4},
		{Kind: KindIn, EAN: "00000001", DyeLot: "B", Quantity: 2},
		{Kind: KindIn, EAN: "00000002", DyeLot: "C", Quantity: 3},
	} {
		if _, _, _, err := s.Record(ctx, in); err != nil {
			t.Fatal(err)
		}
	}
	// Un bain déjà vide n'est pas une anomalie : il ne doit pas apparaître.
	if _, _, _, err := s.Record(ctx, MovementInput{Kind: KindOut, EAN: "00000002", DyeLot: "C", Quantity: 3, Recipient: "Léa"}); err != nil {
		t.Fatal(err)
	}

	inv, err := s.StartInventory(ctx, "Simon", ScopeFull)
	if err != nil || inv.Scope != ScopeFull {
		t.Fatalf("start = %+v, err = %v", inv, err)
	}
	if _, _, _, err := s.Count(ctx, CountInput{InventoryID: inv.ID, EAN: "00000001", DyeLot: "A", Quantity: 4}); err != nil {
		t.Fatal(err)
	}
	if n, err := s.UncountedLots(ctx, inv.ID); err != nil || n != 1 {
		t.Fatalf("uncounted = %d, err = %v", n, err)
	}
	if _, err := s.CloseInventory(ctx, inv.ID, "Simon"); err != nil {
		t.Fatal(err)
	}

	stock, _ := s.Stock(ctx, StockFilter{IncludeEmpty: true})
	for _, item := range stock {
		want := map[string]int{"A": 4, "B": 0, "C": 0}[item.DyeLot]
		if item.Quantity != want {
			t.Errorf("lot %s = %d, want %d", item.DyeLot, item.Quantity, want)
		}
	}
	counts, _ := s.InventoryCounts(ctx, inv.ID)
	if len(counts) != 2 {
		t.Fatalf("counts = %+v", counts)
	}
	for _, c := range counts {
		if c.DyeLot == "B" && (!c.Zeroed || c.PreviousQuantity != 2 || c.Gap() != -2) {
			t.Errorf("zeroed lot = %+v", c)
		}
		if c.DyeLot == "A" && (c.Zeroed || c.Gap() != 0) {
			t.Errorf("counted lot = %+v", c)
		}
	}

	// Un inventaire partiel ne touche pas aux bains non comptés.
	partial, _ := s.StartInventory(ctx, "", ScopePartial)
	if _, _, _, err := s.Count(ctx, CountInput{InventoryID: partial.ID, EAN: "00000001", DyeLot: "A", Quantity: 6}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.CloseInventory(ctx, partial.ID, ""); err != nil {
		t.Fatal(err)
	}

	list, err := s.Inventories(ctx)
	if err != nil || len(list) != 2 {
		t.Fatalf("inventories = %+v, err = %v", list, err)
	}
	if p := list[0]; p.ID != partial.ID || p.Scope != ScopePartial || p.Counted != 1 || p.Anomalies != 1 || p.Net != 2 {
		t.Errorf("partial summary = %+v", p)
	}
	if f := list[1]; f.Counted != 1 || f.Zeroed != 1 || f.Anomalies != 1 || f.Net != -2 {
		t.Errorf("full summary = %+v", f)
	}
}

func TestUsersAndSessions(t *testing.T) {
	ctx := context.Background()
	s := open(t)

	admin, err := s.CreateUser(ctx, "  Simon@Example.org ", "Simon", RoleAdmin, 0)
	if err != nil || admin.Email != "simon@example.org" || admin.Role != RoleAdmin {
		t.Fatalf("admin = %+v, err = %v", admin, err)
	}
	if _, err := s.CreateUser(ctx, "SIMON@example.org", "Autre", RoleManager, admin.ID); !errors.Is(err, ErrEmailTaken) {
		t.Fatalf("duplicate email: err = %v", err)
	}
	marie, err := s.CreateUser(ctx, "marie@example.org", "Marie", RoleManager, admin.ID)
	if err != nil {
		t.Fatal(err)
	}
	if u, err := s.UserByEmail(ctx, "Marie@Example.org"); err != nil || u.ID != marie.ID {
		t.Fatalf("by email = %+v, err = %v", u, err)
	}
	if _, err := s.UserByEmail(ctx, "inconnu@example.org"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown email: err = %v", err)
	}
	if users, err := s.Users(ctx); err != nil || len(users) != 2 || users[0].Name != "Marie" {
		t.Fatalf("users = %+v, err = %v", users, err)
	}

	token, err := s.OpenSession(ctx, marie.ID, "Pixel 6")
	if err != nil || len(token) < 40 {
		t.Fatalf("token = %q, err = %v", token, err)
	}
	// Seule l'empreinte est enregistrée.
	var stored int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM sessions WHERE token_hash = ?`, token).Scan(&stored); err != nil || stored != 0 {
		t.Fatalf("raw token stored: %d, err = %v", stored, err)
	}
	if u, err := s.SessionUser(ctx, token); err != nil || u.ID != marie.ID {
		t.Fatalf("session user = %+v, err = %v", u, err)
	}
	if err := s.CloseSession(ctx, token); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SessionUser(ctx, token); !errors.Is(err, ErrNotFound) {
		t.Fatalf("closed session: err = %v", err)
	}
}

// Une session inutilisée expire ; une session qui sert reste ouverte. Les sessions
// expirées sont effacées à la connexion suivante et ne comptent plus dans l'activité.
func TestSessionsExpireWhenUnused(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	start := time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)
	now := start
	s.now = func() time.Time { return now }
	s.SetSessionIdle(100 * 24 * time.Hour)
	marie, err := s.CreateUser(ctx, "marie@example.org", "Marie", RoleManager, 0)
	if err != nil {
		t.Fatal(err)
	}
	used, _ := s.OpenSession(ctx, marie.ID, "Pixel 6")
	forgotten, _ := s.OpenSession(ctx, marie.ID, "Firefox")

	// Le téléphone sert tous les 60 jours : sa session tient au-delà des 100 jours.
	for day := 60; day <= 240; day += 60 {
		now = start.AddDate(0, 0, day)
		if _, err := s.SessionUser(ctx, used); err != nil {
			t.Fatalf("day %d: used session: %v", day, err)
		}
	}
	if _, err := s.SessionUser(ctx, forgotten); !errors.Is(err, ErrNotFound) {
		t.Fatalf("forgotten session: err = %v", err)
	}
	accounts, err := s.Accounts(ctx)
	if err != nil || len(accounts) != 1 || accounts[0].Sessions != 1 {
		t.Fatalf("accounts = %+v, err = %v", accounts, err)
	}

	// Une connexion efface les sessions expirées.
	if _, err := s.OpenSession(ctx, marie.ID, "iPhone"); err != nil {
		t.Fatal(err)
	}
	var rows int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM sessions`).Scan(&rows); err != nil || rows != 2 {
		t.Fatalf("sessions after purge = %d, err = %v", rows, err)
	}

	// Sans durée, rien n'expire.
	s.SetSessionIdle(0)
	now = start.AddDate(10, 0, 0)
	if _, err := s.SessionUser(ctx, used); err != nil {
		t.Fatalf("no expiry: %v", err)
	}
}

func TestManageUsers(t *testing.T) {
	ctx := context.Background()
	s := open(t)

	simon, err := s.CreateUser(ctx, "simon@example.org", "Simon", RoleAdmin, 0)
	if err != nil {
		t.Fatal(err)
	}
	marie, err := s.CreateUser(ctx, "marie@example.org", "Marie", RoleManager, simon.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.OpenSession(ctx, marie.ID, "Pixel 6"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.OpenSession(ctx, marie.ID, "Firefox"); err != nil {
		t.Fatal(err)
	}

	accounts, err := s.Accounts(ctx)
	if err != nil || len(accounts) != 2 {
		t.Fatalf("accounts = %+v, err = %v", accounts, err)
	}
	if m := accounts[0]; m.Name != "Marie" || m.Sessions != 2 || m.LastSeenAt == nil {
		t.Fatalf("marie = %+v", m)
	}
	if a := accounts[1]; a.Sessions != 0 || a.LastSeenAt != nil || !a.IsAdmin() {
		t.Fatalf("simon = %+v", a)
	}
	if acc, err := s.Account(ctx, marie.ID); err != nil || acc.Email != "marie@example.org" || acc.Sessions != 2 {
		t.Fatalf("account = %+v, err = %v", acc, err)
	}
	if _, err := s.Account(ctx, 999); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown account: err = %v", err)
	}

	// Le seul administrateur ne peut ni perdre son droit ni disparaître.
	if _, err := s.UpdateUser(ctx, simon.ID, simon.Email, simon.Name, RoleManager); !errors.Is(err, ErrLastAdmin) {
		t.Fatalf("demote last admin: err = %v", err)
	}
	if err := s.DeleteUser(ctx, simon.ID); !errors.Is(err, ErrLastAdmin) {
		t.Fatalf("delete last admin: err = %v", err)
	}

	updated, err := s.UpdateUser(ctx, marie.ID, " Marie.Dupont@Example.org ", " Marie Dupont ", RoleAdmin)
	if err != nil || updated.Email != "marie.dupont@example.org" || updated.Name != "Marie Dupont" || !updated.IsAdmin() {
		t.Fatalf("updated = %+v, err = %v", updated, err)
	}
	if _, err := s.UpdateUser(ctx, marie.ID, "simon@example.org", "Marie", RoleAdmin); !errors.Is(err, ErrEmailTaken) {
		t.Fatalf("email taken: err = %v", err)
	}
	if _, err := s.UpdateUser(ctx, 42, "x@example.org", "X", RoleManager); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown user: err = %v", err)
	}

	// Deux administrateurs : Simon peut partir, même s'il a créé le compte de Marie.
	if err := s.DeleteUser(ctx, simon.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.User(ctx, simon.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("deleted user: err = %v", err)
	}

	if n, err := s.CloseUserSessions(ctx, marie.ID); err != nil || n != 2 {
		t.Fatalf("closed = %d, err = %v", n, err)
	}
	if accounts, _ := s.Accounts(ctx); len(accounts) != 1 || accounts[0].Sessions != 0 {
		t.Fatalf("after closing sessions: %+v", accounts)
	}

	// Retirer un compte ferme ses sessions.
	lea, _ := s.CreateUser(ctx, "lea@example.org", "Léa", RoleManager, marie.ID)
	token, _ := s.OpenSession(ctx, lea.ID, "Pixel 8")
	if err := s.DeleteUser(ctx, lea.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SessionUser(ctx, token); !errors.Is(err, ErrNotFound) {
		t.Fatalf("session of a deleted user: err = %v", err)
	}
}

func TestNormalizeEAN(t *testing.T) {
	for raw, want := range map[string]string{
		"8435407948594":    "8435407948594",
		" 8435 4079 48594": "8435407948594",
		"012345678905":     "0012345678905",
		"12345670":         "12345670",
		"12345678901234":   "12345678901234",
		"1234567":          "",
		"84354079485941A":  "",
		"843540794859X":    "",
	} {
		got, ok := NormalizeEAN(raw)
		if got != want || ok != (want != "") {
			t.Errorf("NormalizeEAN(%q) = %q, %v; want %q", raw, got, ok, want)
		}
	}
}

func TestCleanText(t *testing.T) {
	if got, ok := CleanText("  Katia\t", MaxText); got != "Katia" || !ok {
		t.Errorf("CleanText = %q, %v", got, ok)
	}
	// La limite compte des caractères, pas des octets.
	if got, ok := CleanText(strings.Repeat("é", MaxText), MaxText); !ok || got == "" {
		t.Errorf("%d accented characters refused", MaxText)
	}
	if _, ok := CleanText(strings.Repeat("a", MaxText+1), MaxText); ok {
		t.Errorf("%d characters accepted", MaxText+1)
	}
}
