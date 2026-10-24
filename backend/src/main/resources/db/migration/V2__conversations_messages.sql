-- One row per pair of users. user_a < user_b keeps a canonical order,
-- so (A,B) and (B,A) can't both exist.
CREATE TABLE conversations
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_a     uuid        NOT NULL REFERENCES users (id),
    user_b     uuid        NOT NULL REFERENCES users (id),
    last_seq   bigint      NOT NULL DEFAULT 0,
    a_read_seq bigint      NOT NULL DEFAULT 0,
    b_read_seq bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (user_a < user_b),
    UNIQUE (user_a, user_b)
);

CREATE TABLE messages
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    conversation_id uuid        NOT NULL REFERENCES conversations (id),
    seq             bigint      NOT NULL,
    sender_id       uuid        NOT NULL REFERENCES users (id),
    client_msg_id   uuid        NOT NULL,
    body            text        NOT NULL CHECK (length(body) <= 4000),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, seq),
    UNIQUE (sender_id, client_msg_id)
);