package com.chethhsito.bankcore.outbox;

import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("messaging-publisher")
public class RabbitEventPublisher {
    private final RabbitTemplate rabbit;

    public RabbitEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void publish(OutboxJdbcRepository.ClaimedEvent event) throws Exception {
        CorrelationData confirmation = new CorrelationData(event.id().toString());
        rabbit.convertAndSend(RabbitTopology.EVENTS_EXCHANGE, RabbitTopology.NOTIFICATIONS_ROUTING_KEY,
                event.payload(), message -> {
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setMessageId(event.id().toString());
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                }, confirmation);
        if (!confirmation.getFuture().get(5, TimeUnit.SECONDS).ack()
                || confirmation.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ did not confirm routing for event " + event.id());
        }
    }
}
