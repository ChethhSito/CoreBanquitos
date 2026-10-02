package com.chethhsito.bankcore.transfer;

import com.chethhsito.bankcore.account.Account;
import com.chethhsito.bankcore.account.AccountJdbcRepository;
import com.chethhsito.bankcore.audit.AuditLogJdbcRepository;
import com.chethhsito.bankcore.ledger.LedgerJdbcRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private final AccountJdbcRepository accounts;
    private final TransferJdbcRepository transfers;
    private final LedgerJdbcRepository ledger;
    private final AuditLogJdbcRepository audit;

    public TransferService(
            AccountJdbcRepository accounts,
            TransferJdbcRepository transfers,
            LedgerJdbcRepository ledger,
            AuditLogJdbcRepository audit
    ) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional
    public TransferResult transfer(TransferCommand command) {
        return execute(command);
    }

    TransferResult execute(TransferCommand command) {
        validateRequest(command);

        UUID sourceId = command.sourceAccountId();
        UUID destinationId = command.destinationAccountId();
        UUID firstId = sourceId.compareTo(destinationId) < 0 ? sourceId : destinationId;
        UUID secondId = firstId.equals(sourceId) ? destinationId : sourceId;

        Account first = accounts.lock(firstId)
                .orElseThrow(() -> new TransferRejectedException("ACCOUNT_NOT_FOUND"));
        Account second = accounts.lock(secondId)
                .orElseThrow(() -> new TransferRejectedException("ACCOUNT_NOT_FOUND"));
        Account source = first.id().equals(sourceId) ? first : second;
        Account destination = first.id().equals(destinationId) ? first : second;

        validateAccounts(command, source, destination);

        TransferResult result = new TransferResult(
                UUID.randomUUID(), sourceId, destinationId,
                command.amount(), command.currency(), Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        transfers.insert(result);
        accounts.setBalance(sourceId, source.availableBalance().subtract(command.amount()));
        accounts.setBalance(destinationId, destination.availableBalance().add(command.amount()));
        ledger.recordTransfer(result.id(), sourceId, destinationId, command.amount());
        audit.transferCompleted(command.actorId(), result.id());
        return result;
    }

    static void validateShape(TransferCommand command) {
        if (command == null || command.actorId() == null
                || command.sourceAccountId() == null || command.destinationAccountId() == null
                || command.amount() == null || command.currency() == null) {
            throw new TransferRejectedException("INVALID_REQUEST");
        }
        BigDecimal amount = command.amount();
        if (amount.signum() <= 0 || amount.scale() != 2 || amount.precision() > 19
                || !"PEN".equals(command.currency())) {
            throw new TransferRejectedException("INVALID_REQUEST");
        }
    }

    private static void validateRequest(TransferCommand command) {
        validateShape(command);
        if (command.sourceAccountId().equals(command.destinationAccountId())) {
            throw new TransferRejectedException("SAME_SOURCE_DESTINATION");
        }
    }

    private static void validateAccounts(TransferCommand command, Account source, Account destination) {
        if (!"USER".equals(source.type()) || !command.actorId().equals(source.ownerId())) {
            throw new TransferRejectedException("FORBIDDEN");
        }
        if (!"USER".equals(destination.type())) {
            throw new TransferRejectedException("INVALID_DESTINATION");
        }
        if (!"ACTIVE".equals(source.status()) || !"ACTIVE".equals(destination.status())) {
            throw new TransferRejectedException("ACCOUNT_INACTIVE");
        }
        if (!command.currency().equals(source.currency())
                || !command.currency().equals(destination.currency())) {
            throw new TransferRejectedException("CURRENCY_MISMATCH");
        }
        if (source.availableBalance().compareTo(command.amount()) < 0) {
            throw new TransferRejectedException("INSUFFICIENT_FUNDS");
        }
    }
}
