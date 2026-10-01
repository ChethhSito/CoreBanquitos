package com.chethhsito.bankcore.transfer;

import java.util.UUID;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferJdbcRepository {
    private final JdbcTemplate jdbc;

    public TransferJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TransferResult transfer) {
        insert(transfer, "INTERNAL_TRANSFER");
    }

    public void insert(TransferResult transfer, String type) {
        jdbc.update(
                """
                INSERT INTO transfers (
                    id, source_account_id, destination_account_id, transfer_type,
                    amount, currency, status, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', ?)
                """,
                transfer.id(),
                transfer.sourceAccountId(),
                transfer.destinationAccountId(),
                type,
                transfer.amount(),
                transfer.currency(),
                java.sql.Timestamp.from(transfer.completedAt())
        );
    }

    public Optional<TransferResult> findVisible(UUID transferId, UUID userId) {
        return jdbc.query("""
                SELECT t.id, t.source_account_id, t.destination_account_id,
                       t.amount, t.currency, t.completed_at
                FROM transfers t
                JOIN accounts source ON source.id = t.source_account_id
                JOIN accounts destination ON destination.id = t.destination_account_id
                WHERE t.id = ? AND (source.owner_id = ? OR destination.owner_id = ?)
                """, (rs, row) -> new TransferResult(
                rs.getObject("id", UUID.class),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getTimestamp("completed_at").toInstant()), transferId, userId, userId)
                .stream().findFirst();
    }
}
