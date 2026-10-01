package com.chethhsito.bankcore.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "BANKCORE_TEST_DB_URL", matches = ".+")
class TransferServiceIntegrationTest {
    private static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BANKCORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("BANKCORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("BANKCORE_TEST_DB_PASSWORD"));
    }

    @Autowired TransferService service;
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
    void ledgerFailureRollsBackTransferAndBothBalances() {
        int ledgerTransactionsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions", Integer.class);
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
            assertThrows(RuntimeException.class, () -> service.transfer(command("60.00")));
            assertEquals(new BigDecimal("100.00"), balance(sourceId));
            assertEquals(new BigDecimal("0.00"), balance(destinationId));
            assertEquals(0, internalTransfers());
            assertEquals(ledgerTransactionsBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_transactions", Integer.class));
        } finally {
            jdbc.execute("DROP TRIGGER " + triggerName + " ON ledger_entries");
            jdbc.execute("DROP FUNCTION " + functionName + "()");
        }
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
}
