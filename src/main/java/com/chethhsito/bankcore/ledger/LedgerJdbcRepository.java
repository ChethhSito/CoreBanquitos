package com.chethhsito.bankcore.ledger;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerJdbcRepository {
    private final JdbcTemplate jdbc;

    public LedgerJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordTransfer(UUID transferId, UUID sourceId, UUID destinationId, BigDecimal amount) {
        UUID ledgerTransactionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ledger_transactions (id, transfer_id) VALUES (?, ?)",
                ledgerTransactionId,
                transferId
        );
        insertEntry(ledgerTransactionId, sourceId, amount.negate());
        insertEntry(ledgerTransactionId, destinationId, amount);
    }

    private void insertEntry(UUID ledgerTransactionId, UUID accountId, BigDecimal signedAmount) {
        jdbc.update(
                """
                INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, signed_amount)
                VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                ledgerTransactionId,
                accountId,
                signedAmount
        );
    }
}
