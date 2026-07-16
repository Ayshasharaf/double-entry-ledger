package com.doubleledger.ledger.dto;

import java.util.UUID;

/**
 * Represents a single transaction "leg". Every financial transaction
 * must consist of at least two legs (e.g. money leaving one account and entering another).
 */
public class TransactionLegDto {

    // The target account ID where the money is moving to or from
    private UUID accountId;

    // The signed value in minor units (e.g., -1000 means -$10.00, +1000 means +$10.00)
    // We use long to prevent decimal floating point calculation rounding errors.
    private long amountMinorUnits;

    // --- Empty Constructor ---
    public TransactionLegDto() {}

    // --- Parameterized Constructor for testing/convenience ---
    public TransactionLegDto(UUID accountId, long amountMinorUnits) {
        this.accountId = accountId;
        this.amountMinorUnits = amountMinorUnits;
    }

    // --- Getters & Setters ---
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }

    public long getAmountMinorUnits() { return amountMinorUnits; }
    public void setAmountMinorUnits(long amountMinorUnits) { this.amountMinorUnits = amountMinorUnits; }
}