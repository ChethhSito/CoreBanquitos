package com.chethhsito.bankcore.transfer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {
    private final IdempotentTransferService idempotent;
    private final TransferJdbcRepository transfers;

    public TransferController(IdempotentTransferService idempotent, TransferJdbcRepository transfers) {
        this.idempotent = idempotent;
        this.transfers = transfers;
    }

    @PostMapping
    @Operation(summary = "Transferir dinero entre cuentas en PEN",
            description = "Reutiliza la misma Idempotency-Key al reintentar la misma intención.")
    public ResponseEntity<?> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody TransferRequest request
    ) {
        TransferAttempt attempt = idempotent.transfer(key, new TransferCommand(
                UUID.fromString(jwt.getSubject()), request.sourceAccountId(),
                request.destinationAccountId(), new BigDecimal(request.amount()), request.currency()));
        if (attempt.status() == 422) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ApiExceptionHandler.ErrorResponse(attempt.errorCode(), attempt.errorMessage()));
        }
        TransferResponse body = TransferResponse.from(attempt.transfer());
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar una transferencia visible para el usuario")
    public TransferResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return transfers.findVisible(id, UUID.fromString(jwt.getSubject()))
                .map(TransferResponse::from)
                .orElseThrow(() -> new TransferRejectedException("TRANSFER_NOT_FOUND"));
    }

    public record TransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @NotNull @Pattern(regexp = "^(?!0\\.00$)(0|[1-9][0-9]{0,16})\\.[0-9]{2}$") String amount,
            @NotNull @Pattern(regexp = "PEN") String currency
    ) {
    }

    public record TransferResponse(
            UUID id, UUID sourceAccountId, UUID destinationAccountId,
            String amount, String currency, String status, Instant completedAt
    ) {
        public static TransferResponse from(TransferResult transfer) {
            return new TransferResponse(transfer.id(), transfer.sourceAccountId(),
                    transfer.destinationAccountId(), transfer.amount().toPlainString(),
                    transfer.currency(), "COMPLETED", transfer.completedAt());
        }
    }
}
