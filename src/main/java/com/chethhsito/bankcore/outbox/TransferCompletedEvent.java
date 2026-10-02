package com.chethhsito.bankcore.outbox;

import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(UUID eventId, UUID transferId, UUID recipientUserId,
                                     String amount, String currency, Instant occurredAt) {
}
