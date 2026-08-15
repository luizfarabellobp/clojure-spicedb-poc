\connect app

-- Dados descritivos de catálogo/perfil (o "o quê" e o "quem"). Nunca
-- guardam autorização — quem pode ver o quê vive inteiramente no grafo
-- de relações do SpiceDB (database spicedb), não aqui.
CREATE TABLE IF NOT EXISTS movies (
    id                TEXT PRIMARY KEY,
    title             TEXT NOT NULL,
    synopsis          TEXT,
    genre             TEXT,
    release_year      INTEGER,
    duration_minutes  INTEGER
);

CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    display_name  TEXT NOT NULL,
    country       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO app_user;
