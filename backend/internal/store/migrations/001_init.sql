-- Cache des codes-barres : un EAN identifie une référence (marque + coloris), jamais un bain.
CREATE TABLE yarn_references (
    ean        TEXT PRIMARY KEY,
    brand      TEXT,
    color_code TEXT,
    color_name TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Un bain d'une référence et sa quantité en stock ; '' pour une laine sans numéro de bain.
CREATE TABLE lots (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ean        TEXT    NOT NULL REFERENCES yarn_references (ean),
    dye_lot    TEXT    NOT NULL,
    quantity   INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    updated_at TEXT    NOT NULL,
    UNIQUE (ean, dye_lot)
);

-- Journal des entrées et sorties : la quantité d'un bain en est la somme.
-- request_id, fourni par le client, rend un envoi rejoué sans effet.
CREATE TABLE movements (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id TEXT    UNIQUE,
    lot_id     INTEGER NOT NULL REFERENCES lots (id),
    kind       TEXT    NOT NULL CHECK (kind IN ('in', 'out')),
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    recipient  TEXT,
    author     TEXT,
    created_at TEXT    NOT NULL,
    CHECK (kind = 'in' OR recipient IS NOT NULL)
);

CREATE INDEX movements_lot ON movements (lot_id, id);
