-- UNIQUE (user_a, user_b) only serves lookups by user_a. Listing a user's
-- conversations also filters on user_b, which would otherwise scan the table.
CREATE INDEX conversations_user_b_idx ON conversations (user_b);