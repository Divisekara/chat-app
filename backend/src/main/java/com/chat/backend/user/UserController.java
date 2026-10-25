package com.chat.backend.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {
    public record UserSummary(UUID id, String username) {
    }

    private final JdbcTemplate jdbc;

    public UserController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<UserSummary> search(@RequestParam(defaultValue = "") String q,
                             Authentication auth) {
        UUID me = UUID.fromString(auth.getName());

        // starts_with instead of LIKE: usernames can contain '_', which LIKE treats as a
        // wildcard. LIMIT keeps the response bounded as the user table grows.
        List<UserSummary> userSummaryList = jdbc.query(
                "SELECT id, username::text FROM users WHERE id <> ? AND starts_with(lower(username::text), lower(?)) ORDER BY username LIMIT 20",
                (rs, i) -> new UserSummary(rs.getObject(1, UUID.class), rs.getString(2)),
                me,
                q
        );

        return userSummaryList;
    }

}
