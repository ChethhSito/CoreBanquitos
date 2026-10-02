package com.chethhsito.bankcore.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntry(UUID id, UUID actorId, String action, String resourceType,
                            UUID resourceId, Instant createdAt) {
}
