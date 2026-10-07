package com.chethhsito.bankcore.outbox;

import com.chethhsito.bankcore.observability.BankcoreMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("messaging-publisher")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxJdbcRepository outbox;
    private final RabbitEventPublisher rabbit;
    private final BankcoreMetrics metrics;

    public OutboxPublisher(OutboxJdbcRepository outbox, RabbitEventPublisher rabbit,
                           BankcoreMetrics metrics) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${bankcore.outbox.poll-ms:1000}")
    public void dispatch() {
        outbox.claimNext().ifPresent(event -> {
            try {
                rabbit.publish(event);
                outbox.markPublished(event);
                metrics.eventPublished();
                log.info("Outbox event {} published", event.id());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                outbox.reschedule(event);
                metrics.publishRetry();
            } catch (Exception error) {
                log.warn("Outbox event {} not published; retry scheduled ({})",
                        event.id(), error.getClass().getSimpleName());
                outbox.reschedule(event);
                metrics.publishRetry();
            }
        });
    }
}
