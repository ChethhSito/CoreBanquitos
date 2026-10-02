package com.chethhsito.bankcore.outbox;

import com.chethhsito.bankcore.transfer.TransferResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class OutboxJdbcRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxJdbcRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID recordTransferCompleted(TransferResult transfer, UUID recipientUserId) {
        UUID eventId = UUID.randomUUID();
        TransferCompletedEvent event = new TransferCompletedEvent(eventId, transfer.id(), recipientUserId,
                transfer.amount().toPlainString(), transfer.currency(), transfer.completedAt());
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_id, event_type, payload)
                VALUES (?, ?, 'TRANSFER_COMPLETED', ?::jsonb)
                """, eventId, transfer.id(), json.writeValueAsString(event));
        return eventId;
    }

    public Optional<ClaimedEvent> claimNext() {
        UUID claimId = UUID.randomUUID();
        List<ClaimedEvent> claimed = jdbc.query("""
                WITH next_event AS (
                    SELECT id FROM outbox_events
                    WHERE (status = 'PENDING' AND available_at <= now())
                       OR (status = 'IN_FLIGHT' AND claimed_until <= now())
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE outbox_events e
                SET status = 'IN_FLIGHT', claimed_until = now() + interval '30 seconds',
                    claim_id = ?, attempts = attempts + 1
                FROM next_event n WHERE e.id = n.id
                RETURNING e.id, e.payload::text AS payload, e.attempts
                """, (rs, row) -> new ClaimedEvent(
                rs.getObject("id", UUID.class), claimId, rs.getString("payload"), rs.getInt("attempts")), claimId);
        return claimed.stream().findFirst();
    }

    public void markPublished(ClaimedEvent event) {
        jdbc.update("""
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = now(), claimed_until = NULL, claim_id = NULL
                WHERE id = ? AND claim_id = ? AND status = 'IN_FLIGHT'
                """, event.id(), event.claimId());
    }

    public void reschedule(ClaimedEvent event) {
        int delaySeconds = Math.min(60, 1 << Math.min(event.attempts(), 6));
        jdbc.update("""
                UPDATE outbox_events
                SET status = 'PENDING', available_at = now() + (? * interval '1 second'),
                    claimed_until = NULL, claim_id = NULL
                WHERE id = ? AND claim_id = ? AND status = 'IN_FLIGHT'
                """, delaySeconds, event.id(), event.claimId());
    }

    public record ClaimedEvent(UUID id, UUID claimId, String payload, int attempts) {
    }
}
