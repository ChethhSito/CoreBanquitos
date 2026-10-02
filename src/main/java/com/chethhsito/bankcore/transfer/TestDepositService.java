package com.chethhsito.bankcore.transfer;

import com.chethhsito.bankcore.account.Account;
import com.chethhsito.bankcore.account.AccountJdbcRepository;
import com.chethhsito.bankcore.audit.AuditLogJdbcRepository;
import com.chethhsito.bankcore.ledger.LedgerJdbcRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("local")
public class TestDepositService {
    private static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final AccountJdbcRepository accounts;
    private final TransferJdbcRepository transfers;
    private final LedgerJdbcRepository ledger;
    private final AuditLogJdbcRepository audit;

    public TestDepositService(AccountJdbcRepository accounts, TransferJdbcRepository transfers,
                              LedgerJdbcRepository ledger, AuditLogJdbcRepository audit) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional
    public TransferResult deposit(UUID actorId, UUID destinationId, BigDecimal amount) {
        if (destinationId == null || amount == null || amount.signum() <= 0
                || amount.scale() != 2 || amount.precision() > 19) {
            throw new TransferRejectedException("INVALID_REQUEST");
        }
        UUID firstId = SYSTEM_ACCOUNT_ID.compareTo(destinationId) < 0 ? SYSTEM_ACCOUNT_ID : destinationId;
        UUID secondId = firstId.equals(SYSTEM_ACCOUNT_ID) ? destinationId : SYSTEM_ACCOUNT_ID;
        Account first = accounts.lock(firstId)
                .orElseThrow(() -> new TransferRejectedException("ACCOUNT_NOT_FOUND"));
        Account second = accounts.lock(secondId)
                .orElseThrow(() -> new TransferRejectedException("ACCOUNT_NOT_FOUND"));
        Account system = first.id().equals(SYSTEM_ACCOUNT_ID) ? first : second;
        Account destination = first.id().equals(destinationId) ? first : second;

        if (!"SYSTEM".equals(system.type()) || !"USER".equals(destination.type())
                || !actorId.equals(destination.ownerId())) {
            throw new TransferRejectedException("FORBIDDEN");
        }
        if (!"ACTIVE".equals(system.status()) || !"ACTIVE".equals(destination.status())) {
            throw new TransferRejectedException("ACCOUNT_INACTIVE");
        }
        TransferResult result = new TransferResult(UUID.randomUUID(), SYSTEM_ACCOUNT_ID,
                destinationId, amount, "PEN", Instant.now().truncatedTo(ChronoUnit.MICROS));
        transfers.insert(result, "TEST_DEPOSIT");
        accounts.setBalance(SYSTEM_ACCOUNT_ID, system.availableBalance().subtract(amount));
        accounts.setBalance(destinationId, destination.availableBalance().add(amount));
        ledger.recordTransfer(result.id(), SYSTEM_ACCOUNT_ID, destinationId, amount);
        audit.testDepositCompleted(actorId, result.id());
        return result;
    }
}
