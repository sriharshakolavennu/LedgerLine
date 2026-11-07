package com.ledgerline;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final long ttlMs;

    public JwtService(@Value("${ledgerline.jwt.secret}") String secret,
                      @Value("${ledgerline.jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMs = ttlMinutes * 60_000;
    }

    public String issue(long id, String email, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject(String.valueOf(id)).claim("email", email).claim("role", role)
                .issuedAt(new Date(now)).expiration(new Date(now + ttlMs)).signWith(key).compact();
    }

    public UserPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new UserPrincipal(Long.parseLong(c.getSubject()), c.get("email", String.class), c.get("role", String.class));
    }
}
