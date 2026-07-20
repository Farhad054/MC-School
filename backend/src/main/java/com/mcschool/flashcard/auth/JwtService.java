package com.mcschool.flashcard.auth;

import com.mcschool.flashcard.config.JwtProperties;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the stateless HS256 access tokens used for API
 * authentication. The token carries only the user id and role; the
 * authentication filter re-loads the user from the database on every request,
 * so deleted accounts lose access immediately.
 */
@Service
public class JwtService {

    /** What we trust from a verified token. */
    public record TokenClaims(UUID userId, Role role) {
    }

    private final SecretKey key;
    private final Duration expiration;

    public JwtService(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().length() < 32) {
            throw new IllegalStateException(
                    "app.security.jwt.secret (JWT_SECRET) must be at least 32 characters long");
        }
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofMinutes(properties.expirationMinutes());
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key)
                .compact();
    }

    public Instant expiresAt(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    /** Returns the claims of a valid token, or empty for anything invalid, tampered or expired. */
    public Optional<TokenClaims> verify(String token) {
        try {
            Claims claims = parseClaims(token);
            UUID userId = UUID.fromString(claims.getSubject());
            Role role = Role.valueOf(claims.get("role", String.class));
            return Optional.of(new TokenClaims(userId, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
