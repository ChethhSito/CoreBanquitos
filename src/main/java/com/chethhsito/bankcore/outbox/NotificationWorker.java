package com.chethhsito.bankcore.outbox;

import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("notification-worker")
public class NotificationWorker {
    private final ObjectMapper json;
    private final NotificationWorkerService notifications;

    public NotificationWorker(ObjectMapper json, NotificationWorkerService notifications) {
        this.json = json;
        this.notifications = notifications;
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
        notifications.process(event);
    }
}
