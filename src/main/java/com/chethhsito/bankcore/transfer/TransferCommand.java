package com.chethhsito.bankcore.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferCommand(
        UUID actorId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency
) {
}
