package com.chat.backend.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    public record ConversationDto(UUID id, UUID otherUserId, String otherUsername, long lastSeq,
                                  String lastBody, UUID lastSenderId, Instant lastAt) {
    }

    // clientMsgId lets the client match a stored message to its own pending send, so a
    // sync after reconnect doesn't show the same message twice.
    public record MessageDto(long seq, UUID senderId, String body, Instant createdAt, UUID clientMsgId) {
    }

    private final JdbcTemplate jdbc;

    ConversationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<ConversationDto> list(@RequestParam(defaultValue = "") String q,
                               Authentication auth) {
        UUID me = UUID.fromString(auth.getName());

        List<ConversationDto> conversationList = jdbc.query(
                "SELECT c.id, u.id, u.username::text, c.last_seq, m.body, m.sender_id, m.created_at "
                        + "FROM conversations c "
                        + "JOIN users u ON u.id = CASE WHEN c.user_a = ? THEN c.user_b ELSE c.user_a END "
                        + "JOIN messages m ON m.conversation_id = c.id AND m.seq = c.last_seq "
                        + "WHERE c.user_a = ? OR c.user_b = ? "
                        + "ORDER BY m.created_at DESC",
                (rs, i) -> new ConversationDto(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getString(5),
                        rs.getObject(6, UUID.class),
                        rs.getTimestamp(7).toInstant()
                ),
                me, me, me);

        return conversationList;
    }

    @GetMapping("/{id}/messages")
    List<MessageDto> messages(@PathVariable UUID id,
                              @RequestParam(required = false) Long afterSeq,
                              @RequestParam(required = false) Long beforeSeq,
                              @RequestParam(defaultValue = "50") int limit,
                              Authentication auth) {
        UUID me = UUID.fromString(auth.getName());

        // Without this check any logged-in user could read any conversation by guessing
        // its id. 404 rather than 403 so we don't reveal that the id exists.
        Boolean member = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM conversations WHERE id=? AND (user_a=? OR user_b=?))",
                Boolean.class,
                id, me, me);

        if (Boolean.FALSE.equals(member)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        int max = Math.max(1, Math.min(limit, 100)); // never let a client request an unbounded page

        var mapper = (org.springframework.jdbc.core.RowMapper<MessageDto>) (rs, i) -> new MessageDto(
                rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getObject(5, UUID.class)
        );

        if (afterSeq != null) {
            return jdbc.query(
                    "SELECT seq, sender_id, body, created_at, client_msg_iD FROM messages "
                            + "WHERE conversation_id=? AND seq>? "
                            + "ORDER BY seq ASC LIMIT ?",
                    mapper, id, afterSeq, max);
        }

        long before = beforeSeq != null ? beforeSeq : Long.MAX_VALUE;
        return jdbc.query(
                "SELECT * FROM (SELECT seq, sender_id, body, created_at, client_msg_id FROM messages "
                        + "WHERE conversation_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?) t ORDER BY seq ASC",
                mapper, id, before, max);
    }
}
