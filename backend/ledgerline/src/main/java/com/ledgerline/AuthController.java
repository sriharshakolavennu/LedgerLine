package com.ledgerline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder enc;
    private final JwtService jwt;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder enc, JwtService jwt) {
        this.jdbc = jdbc; this.enc = enc; this.jwt = jwt;
    }

    public record LoginReq(@NotBlank String email, @NotBlank String password) {}

    public record SignupReq(@NotBlank @Email @Size(max = 120) String email,
                            @NotBlank @Size(min = 8, max = 72) String password) {}

    /** Public signup. The role is fixed to HOLDER on the server; a client can never choose its own role. */
    @PostMapping("/signup")
    @Transactional
    public Map<String, Object> signup(@Valid @RequestBody SignupReq r) {
        String email = r.email().trim().toLowerCase();
        if (!r.password().matches(".*[A-Za-z].*") || !r.password().matches(".*\\d.*"))
            throw new ApiException(400, "WEAK_PASSWORD", "Password must contain at least one letter and one digit");
        long id;
        try {
            id = jdbc.queryForObject("INSERT INTO users(email,password_hash,role) VALUES (?,?,'HOLDER') RETURNING id",
                    Long.class, email, enc.encode(r.password()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "EMAIL_TAKEN", "An account with this email already exists. Try signing in.");
        }
        jdbc.update("INSERT INTO accounts(owner_user_id,name,type) VALUES (?,?,'USER')", id, "Primary Wallet");
        jdbc.update("INSERT INTO audit_log(actor_user_id, action, entity, entity_id) VALUES (?,'SIGNUP','USER',?)", id, String.valueOf(id));
        return Map.of("token", jwt.issue(id, email, "HOLDER"), "email", email, "role", "HOLDER", "id", id);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginReq r) {
        String email = r.email().trim().toLowerCase();
        List<Map<String, Object>> u = jdbc.queryForList("SELECT id, password_hash, role FROM users WHERE email=?", email);
        if (u.isEmpty() || !enc.matches(r.password(), (String) u.get(0).get("password_hash"))) {
            jdbc.update("INSERT INTO audit_log(action, entity, detail) VALUES ('LOGIN_FAILED','USER',?)", email);
            throw new ApiException(401, "BAD_CREDENTIALS", "Invalid email or password");
        }
        long id = ((Number) u.get(0).get("id")).longValue();
        String role = (String) u.get(0).get("role");
        jdbc.update("INSERT INTO audit_log(actor_user_id, action, entity, entity_id) VALUES (?,'LOGIN','USER',?)", id, String.valueOf(id));
        return Map.of("token", jwt.issue(id, email, role), "email", email, "role", role, "id", id);
    }
}
