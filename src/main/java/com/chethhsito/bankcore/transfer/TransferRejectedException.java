package com.chethhsito.bankcore.transfer;

public class TransferRejectedException extends RuntimeException {
    private final String code;

    public TransferRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
