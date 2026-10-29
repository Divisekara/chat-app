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

    -- Enforces the canonical ordering even if application code gets it wrong. The service
    -- compares ids as strings because Java and Postgres order UUIDs differently
    -- (see MessageService.insert).
    CHECK (user_a < user_b),

    -- Also what makes "INSERT ... ON CONFLICT DO NOTHING" a safe get-or-create when both
    -- users send their first message at the same moment.
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

    -- Backstop for the row lock: even if a bug skipped it, two messages can never share a
    -- seq. It is also the index behind history queries
    -- (WHERE conversation_id = ? AND seq > ? ORDER BY seq).
    UNIQUE (conversation_id, seq),

    -- Makes retries idempotent. A resend after a lost ack violates this constraint, and
    -- MessageService turns that into "return the original seq" instead of a second copy.
    -- Scoped per sender so one user can never block another user's ids.
    UNIQUE (sender_id, client_msg_id)
);