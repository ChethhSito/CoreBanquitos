package com.chethhsito.bankcore.identity;

import com.chethhsito.bankcore.audit.AuditLogJdbcRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuditLogJdbcRepository audit;

    public IdentityService(JdbcTemplate jdbc, PasswordEncoder passwords, TokenService tokens,
                           AuditLogJdbcRepository audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
    }

    @Transactional
    public RegistrationResult register(String email, String password) {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        jdbc.update("INSERT INTO users (id, email, password_hash, status) VALUES (?, ?, ?, 'ACTIVE')",
                userId, normalizedEmail, passwords.encode(password));
        jdbc.update("""
                INSERT INTO accounts (id, owner_id, account_number, account_type, currency, available_balance, status)
                VALUES (?, ?, ?, 'USER', 'PEN', 0, 'ACTIVE')
                """, accountId, userId, accountId.toString());
        audit.userRegistered(userId);
        return new RegistrationResult(userId, accountId, normalizedEmail);
    }

    public TokenService.TokenResponse login(String email, String password) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        var users = jdbc.query("""
                SELECT id, password_hash, status, role FROM users WHERE lower(email) = ?
                """, (rs, row) -> new LoginUser(
                rs.getObject("id", UUID.class),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getString("role")), normalizedEmail);
        if (users.size() != 1 || !"ACTIVE".equals(users.getFirst().status())
                || !passwords.matches(password, users.getFirst().passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return tokens.issue(users.getFirst().id(), users.getFirst().role());
    }

    public record RegistrationResult(UUID userId, UUID accountId, String email) {
    }

    private record LoginUser(UUID id, String passwordHash, String status, String role) {
    }
}
