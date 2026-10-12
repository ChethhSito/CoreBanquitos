package com.chethhsito.bankcore.transfer;

import com.chethhsito.bankcore.support.PostgresIntegrationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class TransferServiceIntegrationTest extends PostgresIntegrationTest {
    private static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired TransferService service;
    @Autowired IdempotentTransferService idempotent;
    @Autowired JdbcTemplate jdbc;

    private UUID actorId;
    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void fundSourceWithBalancedDeposit() {
        actorId = UUID.randomUUID();
        sourceId = UUID.randomUUID();
        destinationId = UUID.randomUUID();
        UUID destinationOwnerId = UUID.randomUUID();
        insertUser(actorId);
        insertUser(destinationOwnerId);
        insertAccount(sourceId, actorId, new BigDecimal("100.00"));
        insertAccount(destinationId, destinationOwnerId, new BigDecimal("0.00"));
        jdbc.update("UPDATE accounts SET available_balance = available_balance - 100.00 WHERE id = ?", SYSTEM_ACCOUNT_ID);

        UUID depositId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transfers (id, source_account_id, destination_account_id, transfer_type,
                                       amount, currency, status, completed_at)
                VALUES (?, ?, ?, 'TEST_DEPOSIT', 100.00, 'PEN', 'COMPLETED', now())
                """, depositId, SYSTEM_ACCOUNT_ID, sourceId);
        jdbc.update("INSERT INTO ledger_transactions (id, transfer_id) VALUES (?, ?)", ledgerId, depositId);
        jdbc.update("INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, signed_amount) VALUES (?, ?, ?, -100.00)",
                UUID.randomUUID(), ledgerId, SYSTEM_ACCOUNT_ID);
        jdbc.update("INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, signed_amount) VALUES (?, ?, ?, 100.00)",
                UUID.randomUUID(), ledgerId, sourceId);
    }

    @Test
    void transferMovesBalanceAndWritesBalancedLedger() {
        TransferResult result = service.transfer(command("60.00"));

        assertEquals(new BigDecimal("40.00"), balance(sourceId));
        assertEquals(new BigDecimal("60.00"), balance(destinationId));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.transfer_id = ?
                """, Integer.class, result.id()));
        assertEquals(new BigDecimal("0.00"), jdbc.queryForObject("""
                SELECT sum(e.signed_amount) FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.transfer_id = ?
                """, BigDecimal.class, result.id()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE actor_id = ? AND action = 'TRANSFER_COMPLETED'
                  AND resource_type = 'TRANSFER' AND resource_id = ?
                """, Integer.class, actorId, result.id()));
        assertEquals(1, outboxCount(result.id()));
    }

    @Test
    void insufficientFundsDoesNotMoveMoney() {
        TransferRejectedException error = assertThrows(
                TransferRejectedException.class, () -> service.transfer(command("101.00")));
        assertEquals("INSUFFICIENT_FUNDS", error.code());
        assertEquals(new BigDecimal("100.00"), balance(sourceId));
        assertEquals(new BigDecimal("0.00"), balance(destinationId));
        assertEquals(0, internalTransfers());
    }

    @Test
    void exactBalanceCanBeTransferred() {
        TransferResult result = service.transfer(command("100.00"));
        assertEquals(new BigDecimal("0.00"), balance(sourceId));
        assertEquals(new BigDecimal("100.00"), balance(destinationId));
        assertEquals(1, internalTransfers());
        assertEquals(1, outboxCount(result.id()));
    }

    @Test
    void invalidRequestsAndAccountsLeaveBalancesUntouched() {
        assertRejected("INVALID_REQUEST", command("0.00"));
        assertRejected("INVALID_REQUEST", command("-1.00"));
        assertRejected("INVALID_REQUEST", command("1.001"));
        assertRejected("INVALID_REQUEST",
                new TransferCommand(actorId, sourceId, destinationId, new BigDecimal("1.00"), "USD"));
        assertRejected("SAME_SOURCE_DESTINATION",
                new TransferCommand(actorId, sourceId, sourceId, new BigDecimal("1.00"), "PEN"));
        assertRejected("ACCOUNT_NOT_FOUND",
                new TransferCommand(actorId, sourceId, UUID.randomUUID(), new BigDecimal("1.00"), "PEN"));
        assertRejected("FORBIDDEN",
                new TransferCommand(UUID.randomUUID(), sourceId, destinationId, new BigDecimal("1.00"), "PEN"));
        jdbc.update("UPDATE accounts SET status = 'BLOCKED' WHERE id = ?", destinationId);
        assertRejected("ACCOUNT_INACTIVE", command("1.00"));

        assertEquals(new BigDecimal("100.00"), balance(sourceId));
        assertEquals(new BigDecimal("0.00"), balance(destinationId));
        assertEquals(0, internalTransfers());
        assertEquals(0, transferAuditCount());
        assertEquals(0, outboxCountForSource());
    }

    @Test
    void hundredConcurrentTransfersPreserveMoneyAndLedger() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return service.transfer(command("1.00"));
                }));
            }
            start.countDown();
            for (Future<TransferResult> result : results) {
                assertEquals(new BigDecimal("1.00"), result.get(90, TimeUnit.SECONDS).amount());
            }
            assertEquals(new BigDecimal("0.00"), balance(sourceId));
            assertEquals(new BigDecimal("100.00"), balance(destinationId));
            assertEquals(100, internalTransfers());
            assertEquals(100, transferAuditCount());
            assertEquals(100, outboxCountForSource());
            assertEquals(200, jdbc.queryForObject("""
                    SELECT count(*) FROM ledger_entries e
                    JOIN ledger_transactions l ON l.id = e.ledger_transaction_id
                    JOIN transfers t ON t.id = l.transfer_id
                    WHERE t.source_account_id = ? AND t.transfer_type = 'INTERNAL_TRANSFER'
                    """, Integer.class, sourceId));
            assertEquals(new BigDecimal("0.00"), jdbc.queryForObject("""
                    SELECT sum(e.signed_amount) FROM ledger_entries e
                    JOIN ledger_transactions l ON l.id = e.ledger_transaction_id
                    JOIN transfers t ON t.id = l.transfer_id
                    WHERE t.source_account_id = ? AND t.transfer_type = 'INTERNAL_TRANSFER'
                    """, BigDecimal.class, sourceId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void crossedTransfersFinishWithoutDeadlock() throws Exception {
        jdbc.update("UPDATE accounts SET owner_id = ? WHERE id = ?", actorId, destinationId);
        service.transfer(command("50.00"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TransferResult> forward = executor.submit(() -> {
                start.await();
                return service.transfer(command("40.00"));
            });
            Future<TransferResult> backward = executor.submit(() -> {
                start.await();
                return service.transfer(new TransferCommand(actorId, destinationId, sourceId,
                        new BigDecimal("30.00"), "PEN"));
            });
            start.countDown();
            forward.get(15, TimeUnit.SECONDS);
            backward.get(15, TimeUnit.SECONDS);
            assertEquals(new BigDecimal("40.00"), balance(sourceId));
            assertEquals(new BigDecimal("60.00"), balance(destinationId));
            assertEquals(2, internalTransfers());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM transfers WHERE source_account_id = ?
                    AND destination_account_id = ? AND transfer_type = 'INTERNAL_TRANSFER'
                    """, Integer.class, destinationId, sourceId));
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertRejected(String code, TransferCommand transfer) {
        assertEquals(code, assertThrows(TransferRejectedException.class,
                () -> service.transfer(transfer)).code());
    }

    @Test
    void ledgerFailureRollsBackTransferAndBothBalances() {
        int ledgerTransactionsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions", Integer.class);
        UUID key = UUID.randomUUID();
        String suffix = sourceId.toString().replace("-", "");
        String functionName = "fail_ledger_" + suffix;
        String triggerName = "fail_ledger_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                    IF NEW.account_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'forced ledger failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(functionName, sourceId));
        jdbc.execute("CREATE TRIGGER " + triggerName
                + " BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE FUNCTION " + functionName + "()");
        try {
            assertThrows(RuntimeException.class, () -> idempotent.transfer(key, command("60.00")));
            assertEquals(new BigDecimal("100.00"), balance(sourceId));
            assertEquals(new BigDecimal("0.00"), balance(destinationId));
            assertEquals(0, internalTransfers());
            assertEquals(ledgerTransactionsBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_transactions", Integer.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND key = ?",
                    Integer.class, actorId, key));
            assertEquals(0, transferAuditCount());
            assertEquals(0, outboxCountForSource());
        } finally {
            jdbc.execute("DROP TRIGGER " + triggerName + " ON ledger_entries");
            jdbc.execute("DROP FUNCTION " + functionName + "()");
        }
    }

    @Test
    void auditFailureRollsBackTransferLedgerAndBalances() {
        String suffix = sourceId.toString().replace("-", "");
        String functionName = "fail_audit_" + suffix;
        String triggerName = "fail_audit_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                    IF NEW.actor_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'forced audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(functionName, actorId));
        jdbc.execute("CREATE TRIGGER " + triggerName
                + " BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION " + functionName + "()");
        try {
            assertThrows(RuntimeException.class, () -> idempotent.transfer(UUID.randomUUID(), command("60.00")));
            assertEquals(new BigDecimal("100.00"), balance(sourceId));
            assertEquals(new BigDecimal("0.00"), balance(destinationId));
            assertEquals(0, internalTransfers());
            assertEquals(0, transferAuditCount());
            assertEquals(0, outboxCountForSource());
        } finally {
            jdbc.execute("DROP TRIGGER " + triggerName + " ON audit_logs");
            jdbc.execute("DROP FUNCTION " + functionName + "()");
        }
    }

    @Test
    void outboxFailureRollsBackFinancialOperationAndAudit() {
        String suffix = sourceId.toString().replace("-", "");
        String functionName = "fail_outbox_" + suffix;
        String triggerName = "fail_outbox_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM transfers
                               WHERE id = NEW.aggregate_id AND source_account_id = '%s'::uuid) THEN
                        RAISE EXCEPTION 'forced outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(functionName, sourceId));
        jdbc.execute("CREATE TRIGGER " + triggerName
                + " BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION " + functionName + "()");
        try {
            assertThrows(RuntimeException.class, () -> idempotent.transfer(UUID.randomUUID(), command("60.00")));
            assertEquals(new BigDecimal("100.00"), balance(sourceId));
            assertEquals(new BigDecimal("0.00"), balance(destinationId));
            assertEquals(0, internalTransfers());
            assertEquals(0, transferAuditCount());
            assertEquals(0, outboxCountForSource());
        } finally {
            jdbc.execute("DROP TRIGGER " + triggerName + " ON outbox_events");
            jdbc.execute("DROP FUNCTION " + functionName + "()");
        }
    }

    @Test
    void auditLogCannotBeChangedOrDeleted() {
        TransferResult result = service.transfer(command("60.00"));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE audit_logs SET action = 'TEST_DEPOSIT_COMPLETED' WHERE resource_id = ?", result.id()));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "DELETE FROM audit_logs WHERE resource_id = ?", result.id()));
        assertEquals(1, transferAuditCount());
        assertEquals(1, outboxCount(result.id()));
    }

    @Test
    void concurrentTransfersCannotOverdrawSource() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> attemptAfter(start));
            Future<String> second = executor.submit(() -> attemptAfter(start));
            start.countDown();
            String firstOutcome = first.get(10, TimeUnit.SECONDS);
            String secondOutcome = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter("COMPLETED"::equals).count());
            assertEquals(1, java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter("INSUFFICIENT_FUNDS"::equals).count());
            assertEquals(new BigDecimal("20.00"), balance(sourceId));
            assertEquals(new BigDecimal("80.00"), balance(destinationId));
            assertEquals(1, internalTransfers());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedKeyReturnsOriginalResultWithoutMovingMoneyAgain() {
        UUID key = UUID.randomUUID();
        TransferAttempt first = idempotent.transfer(key, command("60.00"));
        TransferAttempt replay = idempotent.transfer(key, command("60.00"));

        assertEquals(201, first.status());
        assertEquals(false, first.replayed());
        assertEquals(true, replay.replayed());
        assertEquals(first.transfer(), replay.transfer());
        assertEquals(new BigDecimal("40.00"), balance(sourceId));
        assertEquals(new BigDecimal("60.00"), balance(destinationId));
        assertEquals(1, internalTransfers());
        assertEquals(1, transferAuditCount());
        assertEquals(1, outboxCount(first.transfer().id()));
    }

    @Test
    void sameKeyWithDifferentAmountIsConflict() {
        UUID key = UUID.randomUUID();
        idempotent.transfer(key, command("60.00"));

        assertThrows(IdempotencyConflictException.class,
                () -> idempotent.transfer(key, command("70.00")));
        assertEquals(new BigDecimal("40.00"), balance(sourceId));
        assertEquals(1, internalTransfers());
    }

    @Test
    void definitiveRejectionIsReplayed() {
        UUID key = UUID.randomUUID();
        TransferAttempt first = idempotent.transfer(key, command("101.00"));
        TransferAttempt replay = idempotent.transfer(key, command("101.00"));

        assertEquals(422, first.status());
        assertEquals("INSUFFICIENT_FUNDS", first.errorCode());
        assertEquals(false, first.replayed());
        assertEquals(true, replay.replayed());
        assertEquals(first.errorCode(), replay.errorCode());
        assertEquals(first.errorMessage(), replay.errorMessage());
        assertEquals(new BigDecimal("100.00"), balance(sourceId));
        assertEquals(0, internalTransfers());
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneTransfer() throws Exception {
        UUID key = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TransferAttempt> first = executor.submit(() -> idempotentAttemptAfter(start, key));
            Future<TransferAttempt> second = executor.submit(() -> idempotentAttemptAfter(start, key));
            start.countDown();
            TransferAttempt firstOutcome = first.get(10, TimeUnit.SECONDS);
            TransferAttempt secondOutcome = second.get(10, TimeUnit.SECONDS);
            assertEquals(firstOutcome.transfer(), secondOutcome.transfer());
            assertEquals(1, java.util.stream.Stream.of(firstOutcome, secondOutcome)
                    .filter(TransferAttempt::replayed).count());
            assertEquals(1, internalTransfers());
            assertEquals(new BigDecimal("20.00"), balance(sourceId));
            assertEquals(new BigDecimal("80.00"), balance(destinationId));
        } finally {
            executor.shutdownNow();
        }
    }

    private TransferAttempt idempotentAttemptAfter(CountDownLatch start, UUID key) throws InterruptedException {
        start.await();
        return idempotent.transfer(key, command("80.00"));
    }

    private String attemptAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            service.transfer(command("80.00"));
            return "COMPLETED";
        } catch (TransferRejectedException error) {
            return error.code();
        }
    }

    private TransferCommand command(String amount) {
        return new TransferCommand(actorId, sourceId, destinationId, new BigDecimal(amount), "PEN");
    }

    private void insertUser(UUID id) {
        jdbc.update("INSERT INTO users (id, email, password_hash, status) VALUES (?, ?, 'test-hash', 'ACTIVE')",
                id, id + "@example.test");
    }

    private void insertAccount(UUID id, UUID ownerId, BigDecimal balance) {
        jdbc.update("""
                INSERT INTO accounts (id, owner_id, account_number, account_type, currency, available_balance, status)
                VALUES (?, ?, ?, 'USER', 'PEN', ?, 'ACTIVE')
                """, id, ownerId, id.toString(), balance);
    }

    private BigDecimal balance(UUID id) {
        return jdbc.queryForObject("SELECT available_balance FROM accounts WHERE id = ?", BigDecimal.class, id);
    }

    private int internalTransfers() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transfers WHERE source_account_id = ?
                AND destination_account_id = ? AND transfer_type = 'INTERNAL_TRANSFER'
                """, Integer.class, sourceId, destinationId);
    }

    private int transferAuditCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE actor_id = ? AND action = 'TRANSFER_COMPLETED'
                """, Integer.class, actorId);
    }

    private int outboxCount(UUID transferId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, transferId);
    }

    private int outboxCountForSource() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events o
                JOIN transfers t ON t.id = o.aggregate_id
                WHERE t.source_account_id = ? AND t.transfer_type = 'INTERNAL_TRANSFER'
                """, Integer.class, sourceId);
    }
}
