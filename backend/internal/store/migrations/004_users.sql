-- Comptes autorisés : la connexion se fait par adresse email seule.
-- admin : peut créer des comptes ; manager (gestionnaire) : tout le reste.
CREATE TABLE users (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    email      TEXT    NOT NULL UNIQUE,
    name       TEXT    NOT NULL,
    role       TEXT    NOT NULL CHECK (role IN ('admin', 'manager')),
    created_at TEXT    NOT NULL,
    created_by INTEGER REFERENCES users (id)
);

-- Sessions sans fin (le téléphone reste connecté) ; seule l'empreinte SHA-256 du
-- jeton est gardée, une copie de la base ne donne donc aucune session.
CREATE TABLE sessions (
    token_hash   TEXT    PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device       TEXT,
    created_at   TEXT    NOT NULL,
    last_seen_at TEXT    NOT NULL
);

CREATE INDEX sessions_user ON sessions (user_id);
