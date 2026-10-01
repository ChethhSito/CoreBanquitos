package com.chethhsito.bankcore.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotentTransferService {
    private final IdempotencyJdbcRepository idempotency;
    private final TransferService transfers;

    public IdempotentTransferService(IdempotencyJdbcRepository idempotency, TransferService transfers) {
        this.idempotency = idempotency;
        this.transfers = transfers;
    }

    @Transactional
    public TransferAttempt transfer(UUID key, TransferCommand command) {
        if (key == null) {
            throw new TransferRejectedException("INVALID_REQUEST");
        }
        TransferService.validateShape(command);
        UUID userId = command.actorId();
        String requestHash = hash(command);

        if (!idempotency.reserve(userId, key, requestHash)) {
            IdempotencyJdbcRepository.StoredAttempt stored = idempotency.find(userId, key);
            if (!requestHash.equals(stored.requestHash())) {
                throw new IdempotencyConflictException();
            }
            return stored.attempt();
        }

        try {
            TransferResult result = transfers.execute(command);
            idempotency.complete(userId, key, result);
            return TransferAttempt.completed(result, false);
        } catch (TransferRejectedException error) {
            if (!isDefinitiveBusinessRejection(error.code())) {
                throw error;
            }
            String message = messageFor(error.code());
            idempotency.reject(userId, key, error.code(), message);
            return TransferAttempt.rejected(error.code(), message, false);
        }
    }

    private static boolean isDefinitiveBusinessRejection(String code) {
        return switch (code) {
            case "SAME_SOURCE_DESTINATION", "INVALID_DESTINATION", "ACCOUNT_INACTIVE",
                    "CURRENCY_MISMATCH", "INSUFFICIENT_FUNDS" -> true;
            default -> false;
        };
    }

    private static String messageFor(String code) {
        return switch (code) {
            case "SAME_SOURCE_DESTINATION" -> "Las cuentas de origen y destino deben ser distintas.";
            case "INVALID_DESTINATION" -> "La cuenta de destino no admite transferencias.";
            case "ACCOUNT_INACTIVE" -> "Una de las cuentas no esta activa.";
            case "CURRENCY_MISMATCH" -> "La moneda de las cuentas no coincide.";
            case "INSUFFICIENT_FUNDS" -> "Saldo insuficiente.";
            default -> throw new IllegalArgumentException("Unknown business rejection: " + code);
        };
    }

    private static String hash(TransferCommand command) {
        String canonical = String.join("\n",
                command.actorId().toString(),
                command.sourceAccountId().toString(),
                command.destinationAccountId().toString(),
                command.amount().toPlainString(),
                command.currency());
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
