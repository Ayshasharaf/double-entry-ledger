package com.doubleledger.ledger.exception;

/**
 * Thrown when the same idempotency key is reused with a different request payload.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
