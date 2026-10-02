package com.chethhsito.bankcore.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogJdbcRepository {
    private final JdbcTemplate jdbc;

    public AuditLogJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void userRegistered(UUID actorId) {
        insert(actorId, "USER_REGISTERED", "USER", actorId);
    }

    public void testDepositCompleted(UUID actorId, UUID transferId) {
        insert(actorId, "TEST_DEPOSIT_COMPLETED", "TRANSFER", transferId);
    }

    public void transferCompleted(UUID actorId, UUID transferId) {
        insert(actorId, "TRANSFER_COMPLETED", "TRANSFER", transferId);
    }

    private void insert(UUID actorId, String action, String resourceType, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs (id, actor_id, action, resource_type, resource_id)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actorId, action, resourceType, resourceId);
    }
}
