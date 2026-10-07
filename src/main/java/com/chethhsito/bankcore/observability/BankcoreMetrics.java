package com.chethhsito.bankcore.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BankcoreMetrics {
    private final Counter transfersCompleted;
    private final Counter transfersRejected;
    private final Counter depositsCompleted;
    private final Counter eventsPublished;
    private final Counter publishRetries;
    private final Counter notificationsProcessed;
    private final Counter notificationsDuplicate;

    public BankcoreMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        transfersCompleted = registry.counter("bankcore.transfer.completed");
        transfersRejected = registry.counter("bankcore.transfer.rejected");
        depositsCompleted = registry.counter("bankcore.deposit.completed");
        eventsPublished = registry.counter("bankcore.outbox.published");
        publishRetries = registry.counter("bankcore.outbox.retry");
        notificationsProcessed = registry.counter("bankcore.notification.processed");
        notificationsDuplicate = registry.counter("bankcore.notification.duplicate");
        Gauge.builder("bankcore.outbox.pending", jdbc,
                db -> count(db, "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED'"))
                .description("Events awaiting publication or with an expired claim")
                .register(registry);
        Gauge.builder("bankcore.notification.deliveries", jdbc,
                db -> count(db, "SELECT count(*) FROM notification_deliveries"))
                .description("Simulated notifications recorded in PostgreSQL")
                .register(registry);
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    public void transferCompleted() { transfersCompleted.increment(); }
    public void transferRejected() { transfersRejected.increment(); }
    public void depositCompleted() { depositsCompleted.increment(); }
    public void eventPublished() { eventsPublished.increment(); }
    public void publishRetry() { publishRetries.increment(); }
    public void notificationProcessed() { notificationsProcessed.increment(); }
    public void notificationDuplicate() { notificationsDuplicate.increment(); }
}
