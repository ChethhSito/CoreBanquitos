package com.chethhsito.bankcore.outbox;

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

    public OutboxPublisher(OutboxJdbcRepository outbox, RabbitEventPublisher rabbit) {
        this.outbox = outbox;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${bankcore.outbox.poll-ms:1000}")
    public void dispatch() {
        outbox.claimNext().ifPresent(event -> {
            try {
                rabbit.publish(event);
                outbox.markPublished(event);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                outbox.reschedule(event);
            } catch (Exception error) {
                log.warn("Outbox event {} not published; retry scheduled ({})",
                        event.id(), error.getClass().getSimpleName());
                outbox.reschedule(event);
            }
        });
    }
}
