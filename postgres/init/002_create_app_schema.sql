\connect app

CREATE TABLE IF NOT EXISTS movies (
    id        TEXT PRIMARY KEY,
    title     TEXT NOT NULL,
    synopsis  TEXT
);

CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    display_name  TEXT NOT NULL
);

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO app_user;
