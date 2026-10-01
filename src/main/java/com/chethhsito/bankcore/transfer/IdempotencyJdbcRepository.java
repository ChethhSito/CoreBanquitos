package com.chethhsito.bankcore.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyJdbcRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean reserve(UUID userId, UUID key, String requestHash) {
        return jdbc.update("""
                INSERT INTO idempotency_keys (user_id, key, request_hash)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, key) DO NOTHING
                """, userId, key, requestHash) == 1;
    }

    public StoredAttempt find(UUID userId, UUID key) {
        return jdbc.queryForObject("""
                SELECT request_hash, response_status,
                       response_body->>'id' AS transfer_id,
                       response_body->>'sourceAccountId' AS source_id,
                       response_body->>'destinationAccountId' AS destination_id,
                       response_body->>'amount' AS amount,
                       response_body->>'currency' AS currency,
                       response_body->>'completedAt' AS completed_at,
                       response_body->>'code' AS error_code,
                       response_body->>'message' AS error_message
                FROM idempotency_keys WHERE user_id = ? AND key = ?
                """, (rs, row) -> {
                    int status = rs.getInt("response_status");
                    if (rs.wasNull()) {
                        throw new IllegalStateException("Idempotency record has no final response");
                    }
                    TransferAttempt attempt;
                    if (status == 201) {
                        TransferResult transfer = new TransferResult(
                                UUID.fromString(rs.getString("transfer_id")),
                                UUID.fromString(rs.getString("source_id")),
                                UUID.fromString(rs.getString("destination_id")),
                                new BigDecimal(rs.getString("amount")),
                                rs.getString("currency"),
                                Instant.parse(rs.getString("completed_at"))
                        );
                        attempt = TransferAttempt.completed(transfer, true);
                    } else if (status == 422) {
                        attempt = TransferAttempt.rejected(
                                rs.getString("error_code"), rs.getString("error_message"), true);
                    } else {
                        throw new IllegalStateException("Unexpected stored response status: " + status);
                    }
                    return new StoredAttempt(rs.getString("request_hash"), attempt);
                }, userId, key);
    }

    public void complete(UUID userId, UUID key, TransferResult transfer) {
        int updated = jdbc.update("""
                UPDATE idempotency_keys
                SET response_status = 201,
                    response_body = jsonb_build_object(
                        'id', ?::text,
                        'sourceAccountId', ?::text,
                        'destinationAccountId', ?::text,
                        'amount', ?,
                        'currency', ?,
                        'status', 'COMPLETED',
                        'completedAt', ?)
                WHERE user_id = ? AND key = ?
                """, transfer.id(), transfer.sourceAccountId(), transfer.destinationAccountId(),
                transfer.amount().toPlainString(), transfer.currency(), transfer.completedAt().toString(),
                userId, key);
        requireUpdated(updated);
    }

    public void reject(UUID userId, UUID key, String code, String message) {
        int updated = jdbc.update("""
                UPDATE idempotency_keys
                SET response_status = 422,
                    response_body = jsonb_build_object('code', ?, 'message', ?)
                WHERE user_id = ? AND key = ?
                """, code, message, userId, key);
        requireUpdated(updated);
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record disappeared before completion");
        }
    }

    public record StoredAttempt(String requestHash, TransferAttempt attempt) {
    }
}
