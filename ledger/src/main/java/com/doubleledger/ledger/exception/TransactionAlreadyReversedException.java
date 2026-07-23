package com.doubleledger.ledger.exception;

public class TransactionAlreadyReversedException extends RuntimeException {

    public TransactionAlreadyReversedException(String message) {
        super(message);
    }
}
