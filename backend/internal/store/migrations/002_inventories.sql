-- Inventaire : on compte chaque bain, et le comptage remplace la quantité en stock.
-- Un seul inventaire ouvert à la fois, partagé par tous les téléphones.
CREATE TABLE inventories (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    started_by TEXT,
    closed_at  TEXT,
    closed_by  TEXT
);

CREATE UNIQUE INDEX inventories_one_open ON inventories ((closed_at IS NULL)) WHERE closed_at IS NULL;

-- Quantité comptée par bain dans un inventaire ; previous_quantity est le stock
-- du bain avant son premier comptage.
CREATE TABLE inventory_counts (
    inventory_id      INTEGER NOT NULL REFERENCES inventories (id),
    lot_id            INTEGER NOT NULL REFERENCES lots (id),
    quantity          INTEGER NOT NULL CHECK (quantity >= 0),
    previous_quantity INTEGER NOT NULL,
    updated_at        TEXT    NOT NULL,
    PRIMARY KEY (inventory_id, lot_id)
);

-- Journal des comptages validés ; request_id rend un envoi rejoué sans effet.
-- mode : 'new' premier comptage, 'add' ajouté au comptage, 'set' comptage remplacé.
CREATE TABLE inventory_scans (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id   TEXT    UNIQUE,
    inventory_id INTEGER NOT NULL REFERENCES inventories (id),
    lot_id       INTEGER NOT NULL REFERENCES lots (id),
    mode         TEXT    NOT NULL CHECK (mode IN ('new', 'add', 'set')),
    quantity     INTEGER NOT NULL CHECK (quantity >= 0),
    author       TEXT,
    created_at   TEXT    NOT NULL
);
