package com.chethhsito.bankcore.transfer;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("IDEMPOTENCY_CONFLICT");
    }
}
