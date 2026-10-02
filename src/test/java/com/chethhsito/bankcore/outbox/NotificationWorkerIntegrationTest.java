package com.chethhsito.bankcore.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.chethhsito.bankcore.transfer.TransferResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("notification-worker")
@EnabledIfEnvironmentVariable(named = "BANKCORE_TEST_DB_URL", matches = ".+")
class NotificationWorkerIntegrationTest {
    private static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BANKCORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("BANKCORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("BANKCORE_TEST_DB_PASSWORD"));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxJdbcRepository outbox;
    @Autowired NotificationWorker worker;

    @Test
    void duplicateMessageCreatesOneSimulatedNotification() {
        UUID eventId = createEvent();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE id = ? AND status = 'PENDING'",
                Integer.class, eventId));

        OutboxJdbcRepository.ClaimedEvent claimed = outbox.claimNext().orElseThrow();
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(claimed.id().toString());
        Message message = new Message(claimed.payload().getBytes(StandardCharsets.UTF_8), properties);
        worker.receive(message);
        worker.receive(message);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, claimed.id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE event_id = ?",
                Integer.class, claimed.id()));

        properties.setMessageId(UUID.randomUUID().toString());
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> worker.receive(message));
        outbox.markPublished(claimed);
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?",
                String.class, claimed.id()));
    }

    @Test
    void brokerFailureReschedulesClaimedEvent() throws Exception {
        createEvent();
        RabbitEventPublisher failingBroker = mock(RabbitEventPublisher.class);
        doThrow(new IllegalStateException("broker unavailable")).when(failingBroker).publish(any());
        new OutboxPublisher(outbox, failingBroker).dispatch();

        ArgumentCaptor<OutboxJdbcRepository.ClaimedEvent> sent =
                ArgumentCaptor.forClass(OutboxJdbcRepository.ClaimedEvent.class);
        verify(failingBroker).publish(sent.capture());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?",
                String.class, sent.getValue().id()));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?",
                Integer.class, sent.getValue().id()));
    }

    private UUID createEvent() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, status) VALUES (?, ?, 'test', 'ACTIVE')",
                userId, userId + "@example.test");
        jdbc.update("""
                INSERT INTO accounts (id, owner_id, account_number, account_type, currency, available_balance, status)
                VALUES (?, ?, ?, 'USER', 'PEN', 0, 'ACTIVE')
                """, accountId, userId, accountId.toString());
        jdbc.update("""
                INSERT INTO transfers (id, source_account_id, destination_account_id, transfer_type,
                                       amount, currency, status, completed_at)
                VALUES (?, ?, ?, 'TEST_DEPOSIT', 1.00, 'PEN', 'COMPLETED', now())
                """, transferId, SYSTEM_ACCOUNT_ID, accountId);
        return outbox.recordTransferCompleted(new TransferResult(transferId, SYSTEM_ACCOUNT_ID,
                accountId, new BigDecimal("1.00"), "PEN", Instant.now()), userId);
    }
}
