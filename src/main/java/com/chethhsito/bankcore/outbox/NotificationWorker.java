package com.chethhsito.bankcore.outbox;

import com.chethhsito.bankcore.observability.BankcoreMetrics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("notification-worker")
public class NotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private final ObjectMapper json;
    private final NotificationWorkerService notifications;
    private final BankcoreMetrics metrics;

    public NotificationWorker(ObjectMapper json, NotificationWorkerService notifications,
                              BankcoreMetrics metrics) {
        this.json = json;
        this.notifications = notifications;
        this.metrics = metrics;
    }

    @RabbitListener(queues = RabbitTopology.NOTIFICATIONS_QUEUE)
    public void receive(Message message) {
        TransferCompletedEvent event;
        try {
            event = json.readValue(message.getBody(), TransferCompletedEvent.class);
            if (event == null || !event.eventId().equals(UUID.fromString(
                    message.getMessageProperties().getMessageId()))) {
                throw new IllegalArgumentException("Event ID mismatch");
            }
        } catch (Exception error) {
            throw new AmqpRejectAndDontRequeueException("Invalid notification event", error);
        }
        if (notifications.process(event)) {
            metrics.notificationProcessed();
            log.info("Notification recorded for event {} transfer {}", event.eventId(), event.transferId());
        } else {
            metrics.notificationDuplicate();
            log.info("Duplicate notification event {} ignored", event.eventId());
        }
    }
}
