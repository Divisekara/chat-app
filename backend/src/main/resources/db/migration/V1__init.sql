CREATE EXTENSION IF NOT EXISTS citext;

-- citext makes username comparison case-insensitive inside the database, backed by the
-- UNIQUE index below: "Alice" and "alice" can never both exist, and no code path can forget
-- to lowercase before comparing.
CREATE TABLE users
(
    id            uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    username      citext UNIQUE NOT NULL,
    password_hash text          NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now()
);