package com.chethhsito.bankcore.account;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountJdbcRepository {
    private final JdbcTemplate jdbc;

    public AccountJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> lock(UUID id) {
        return jdbc.query(
                """
                SELECT id, owner_id, account_type, currency, available_balance, status
                FROM accounts WHERE id = ? FOR UPDATE
                """,
                (rs, row) -> new Account(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getString("account_type"),
                        rs.getString("currency"),
                        rs.getBigDecimal("available_balance"),
                        rs.getString("status")
                ),
                id
        ).stream().findFirst();
    }

    public void setBalance(UUID id, BigDecimal balance) {
        int updated = jdbc.update(
                "UPDATE accounts SET available_balance = ? WHERE id = ?",
                balance,
                id
        );
        if (updated != 1) {
            throw new IllegalStateException("Account disappeared while locked: " + id);
        }
    }
}
