package com.chethhsito.bankcore.account;

import java.math.BigDecimal;
import java.util.UUID;

public record Account(
        UUID id,
        UUID ownerId,
        String type,
        String currency,
        BigDecimal availableBalance,
        String status
) {
}
