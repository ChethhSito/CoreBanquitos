package com.chethhsito.bankcore.transfer;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferJdbcRepository {
    private final JdbcTemplate jdbc;

    public TransferJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TransferResult transfer) {
        jdbc.update(
                """
                INSERT INTO transfers (
                    id, source_account_id, destination_account_id, transfer_type,
                    amount, currency, status, completed_at
                ) VALUES (?, ?, ?, 'INTERNAL_TRANSFER', ?, ?, 'COMPLETED', ?)
                """,
                transfer.id(),
                transfer.sourceAccountId(),
                transfer.destinationAccountId(),
                transfer.amount(),
                transfer.currency(),
                java.sql.Timestamp.from(transfer.completedAt())
        );
    }
}
