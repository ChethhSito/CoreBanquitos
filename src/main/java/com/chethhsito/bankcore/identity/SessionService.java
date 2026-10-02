package com.chethhsito.bankcore.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TokenService accessTokens;

    public SessionService(JdbcTemplate jdbc, TokenService accessTokens) {
        this.jdbc = jdbc;
        this.accessTokens = accessTokens;
    }

    @Transactional
    public SessionTokens start(UUID userId, String role) {
        return issue(userId, role, UUID.randomUUID());
    }

    @Transactional
    public SessionTokens refresh(String rawToken) {
        String hash = hashIfValid(rawToken);
        if (hash == null) throw new InvalidRefreshTokenException();
        List<StoredRefresh> matches = jdbc.query("""
                SELECT r.id, r.family_id, r.user_id, r.expires_at, r.consumed_at, r.revoked_at,
                       u.status, u.role
                FROM refresh_tokens r JOIN users u ON u.id = r.user_id
                WHERE r.token_hash = ? FOR UPDATE OF r
                """, (rs, row) -> new StoredRefresh(
                rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                instantOrNull(rs.getTimestamp("consumed_at")),
                instantOrNull(rs.getTimestamp("revoked_at")),
                rs.getString("status"), rs.getString("role")), hash);
        if (matches.isEmpty()) throw new InvalidRefreshTokenException();

        StoredRefresh stored = matches.getFirst();
        if (stored.consumedAt() != null) {
            revokeFamily(stored.familyId());
            return null;
        }
        if (stored.revokedAt() != null || !stored.expiresAt().isAfter(Instant.now())
                || !"ACTIVE".equals(stored.userStatus())) {
            revokeFamily(stored.familyId());
            return null;
        }

        jdbc.update("UPDATE refresh_tokens SET consumed_at = now() WHERE id = ?", stored.id());
        return issue(stored.userId(), stored.role(), stored.familyId());
    }

    @Transactional
    public void logout(String rawToken) {
        String hash = hashIfValid(rawToken);
        if (hash == null) return;
        List<UUID> families = jdbc.query("""
                SELECT family_id FROM refresh_tokens WHERE token_hash = ? FOR UPDATE
                """, (rs, row) -> rs.getObject("family_id", UUID.class), hash);
        if (!families.isEmpty()) revokeFamily(families.getFirst());
    }

    private SessionTokens issue(UUID userId, String role, UUID familyId) {
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expiry = Instant.now().plus(REFRESH_LIFETIME);
        jdbc.update("""
                INSERT INTO refresh_tokens (id, family_id, user_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), familyId, userId, hashIfValid(rawToken), Timestamp.from(expiry));
        TokenService.TokenResponse access = accessTokens.issue(userId, role);
        return new SessionTokens(access.accessToken(), access.tokenType(), access.expiresIn(),
                rawToken, REFRESH_LIFETIME.toSeconds());
    }

    private void revokeFamily(UUID familyId) {
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = now()
                WHERE family_id = ? AND revoked_at IS NULL
                """, familyId);
    }

    private static String hashIfValid(String rawToken) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record StoredRefresh(UUID id, UUID familyId, UUID userId, Instant expiresAt,
                                 Instant consumedAt, Instant revokedAt, String userStatus, String role) {
    }

    public record SessionTokens(String accessToken, String tokenType, long expiresIn,
                                String refreshToken, long refreshExpiresIn) {
    }
}
