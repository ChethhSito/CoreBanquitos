package com.chethhsito.bankcore.outbox;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"messaging-publisher", "notification-worker"})
public class RabbitTopology {
    public static final String EVENTS_EXCHANGE = "bankcore.events";
    public static final String NOTIFICATIONS_QUEUE = "bankcore.notifications";
    public static final String NOTIFICATIONS_ROUTING_KEY = "transfer.completed";
    public static final String DEAD_LETTER_EXCHANGE = "bankcore.dlx";
    public static final String DEAD_LETTER_QUEUE = "bankcore.notifications.dlq";

    @Bean
    DirectExchange eventsExchange() {
        return new DirectExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue notificationsQueue() {
        return QueueBuilder.durable(NOTIFICATIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "notifications.failed")
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding notificationsBinding(Queue notificationsQueue, DirectExchange eventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(eventsExchange).with(NOTIFICATIONS_ROUTING_KEY);
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("notifications.failed");
    }
}
