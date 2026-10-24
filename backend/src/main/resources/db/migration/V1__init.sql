CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users
(
    id            uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    username      citext UNIQUE NOT NULL,
    password_hash text          NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now()
);