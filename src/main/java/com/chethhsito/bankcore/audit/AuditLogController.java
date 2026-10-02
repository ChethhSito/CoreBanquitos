package com.chethhsito.bankcore.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/audit-logs")
@SecurityRequirement(name = "bearerAuth")
public class AuditLogController {
    private final AuditLogJdbcRepository auditLogs;

    public AuditLogController(AuditLogJdbcRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @GetMapping
    @Operation(summary = "Consultar acciones auditadas (solo rol AUDITOR)")
    public List<AuditLogEntry> list(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return auditLogs.findLatest(limit);
    }
}
