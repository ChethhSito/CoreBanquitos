package com.chethhsito.bankcore.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationWorkerService {
    private final JdbcTemplate jdbc;

    public NotificationWorkerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void process(TransferCompletedEvent event) {
        if (event == null || event.eventId() == null || event.transferId() == null
                || event.recipientUserId() == null) {
            throw new IllegalArgumentException("Invalid transfer event");
        }
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING
                """, event.eventId());
        if (inserted == 0) return;
        jdbc.update("""
                INSERT INTO notification_deliveries (event_id, transfer_id, recipient_user_id)
                VALUES (?, ?, ?)
                """, event.eventId(), event.transferId(), event.recipientUserId());
    }
}
