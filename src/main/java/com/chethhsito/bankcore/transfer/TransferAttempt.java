package com.chethhsito.bankcore.transfer;

public record TransferAttempt(
        int status,
        TransferResult transfer,
        String errorCode,
        String errorMessage,
        boolean replayed
) {
    public static TransferAttempt completed(TransferResult transfer, boolean replayed) {
        return new TransferAttempt(201, transfer, null, null, replayed);
    }

    public static TransferAttempt rejected(String code, String message, boolean replayed) {
        return new TransferAttempt(422, null, code, message, replayed);
    }
}
