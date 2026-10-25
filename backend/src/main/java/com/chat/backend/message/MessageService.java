package com.chat.backend.message;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class MessageService {

    public record Saved(UUID conversationId,
                        long seq,
                        Instant createdAt,
                        boolean duplicate
    ) {
    }

    public static class RecipientNotFound extends RuntimeException {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    MessageService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Saved send(UUID sender,
                      UUID recipient,
                      UUID clientMsgId,
                      String body) {
        if (sender == recipient) throw new IllegalArgumentException("Cannot message yourself");

        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE ID=?)", Boolean.class, recipient
        );

        if (Boolean.FALSE.equals(exists)) throw new RecipientNotFound();

        try {
            return tx.execute(status -> insert(sender, recipient, clientMsgId, body));
        } catch (DuplicateKeyException e) {
            // A retry of a message we already stored (the ack was lost). The unique
            // (sender_id, client_msg_id) constraint is the only race-free dedupe. It is
            // caught here, outside the transaction, because a failed statement aborts the
            // whole Postgres transaction. The rollback also undoes the last_seq bump,
            // so retries never leave gaps in seq.
            return findExising(sender, clientMsgId);
        }
    }

    private Saved insert(UUID sender, UUID recipient, UUID clientMsgId, String body) {
        // Compare as strings, not UUID.compareTo(): Java compares UUIDs as signed longs
        // while Postgres compares bytes unsigned, so they disagree for some ids and the
        // CHECK (user_a < user_b) constraint would reject valid pairs.

        UUID a = sender.toString().compareTo(recipient.toString()) < 0 ? sender : recipient;
        UUID b = a.equals(sender) ? recipient : sender;

        jdbc.update(
                "INSERT INTO conversations (user_a, user_b) VALUES (?, ?) ON CONFLICT (user_a, user_b) DO NOTHING",
                a, b
        );

        UUID convId = jdbc.queryForObject(
                "SELECT id FROM conversations WHERE user_a=? AND user_b=?", UUID.class, a, b
        );

        // The row lock taken by this UPDATE serializes concurrent senders in the same
        // conversation, so seq has no gaps or duplicates and no global counter is needed.
        Long seq = jdbc.queryForObject(
                "UPDATE conversations SET last_seq = last_seq + 1 WHERE id=? RETURNING last_seq",
                Long.class, convId
        );

        Instant createdAt = jdbc.queryForObject(
                "INSERT INTO messages (conversation_id, seq, sender_id, client_msg_id, body) VALUES (?, ?, ?, ?, ?) RETURNING created_at",
                (rs, i) -> rs.getTimestamp(1).toInstant(),
                convId, seq, sender, clientMsgId, body
        );

        return new Saved(convId, seq, createdAt, false);
    }

    private Saved findExising(UUID sender, UUID clientMsgId) {
        return jdbc.queryForObject(
                "SELECT conversation_id, seq, created_at FROM messages WHERE sender_id=? AND client_msg_id=?",
                (rs, i) -> new Saved(
                        rs.getObject(1, UUID.class),
                        rs.getLong(2),
                        rs.getTimestamp(3).toInstant(),
                        true
                ),
                sender,
                clientMsgId
        );
    }


}
