package com.chat.backend.auth;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SecurityContextRepository contextRepo;
    private final String dummyHash;


    AuthController(JdbcTemplate jdbc,
                   PasswordEncoder encoder,
                   SecurityContextRepository contextRepo) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.contextRepo = contextRepo;

        // Compared against when the username doesn't exist, so "unknown user" and
        // "wrong password" take the same time and usernames can't be enumerated.
        this.dummyHash = encoder.encode("dummy-password");
    }

    record Credentials(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,30}$") String username,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {
    }

    record UserDto(UUID id, String username) {
    }

    @PostMapping("/register")
    ResponseEntity<UserDto> register(@Valid @RequestBody Credentials c,
                                     HttpServletRequest req,
                                     HttpServletResponse res) {

        try {
            // No "check if exists, then insert": two concurrent requests would both pass
            // the check. The UNIQUE constraint is the only race-free guard.
            UUID id = jdbc.queryForObject(
                    "INSERT INTO users(username, password_hash) VALUES (?::citext, ?) RETURNING id",
                    UUID.class, c.username, encoder.encode(c.password())
            );
            startSession(id, req, res);

            return ResponseEntity.status(HttpStatus.CREATED).body(new UserDto(id, c.username));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/login")
    ResponseEntity<UserDto> login(@Valid @RequestBody Credentials c,
                                  HttpServletRequest req,
                                  HttpServletResponse res) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username::text AS username, password_hash from users where username=?::citext", c.username
        );
        String hash = rows.isEmpty() ? dummyHash : (String) rows.get(0).get("password_hash");
        boolean ok = encoder.matches(c.password, hash);

        if (rows.isEmpty() || !ok) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        UUID id = (UUID) rows.get(0).get("id");
        startSession(id, req, res);

        return ResponseEntity.ok(
                new UserDto(id, (String) rows.get(0).get("username"))
        );
    }

    @GetMapping("/me")
    ResponseEntity<UserDto> me(Authentication auth) {
        UUID id = UUID.fromString(auth.getName());
        String username = jdbc.queryForObject(
                "SELECT username::text from users where id=?", String.class, id
        );

        return ResponseEntity.ok(
                new UserDto(id, username)
        );
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();

        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    private void startSession(UUID userId, HttpServletRequest req, HttpServletResponse res) {
        // Rotate the session id on login to prevent session fixation.
        if (req.getSession(false) != null) req.changeSessionId();
        var auth = UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);

        // Spring Security 6 no longer saves the context automatically; without this
        // the user is logged in for this request only and the cookie is never issued.
        this.contextRepo.saveContext(ctx, req, res);
    }

}
