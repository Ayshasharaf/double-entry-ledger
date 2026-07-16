package com.doubleledger.ledger.dto;

import java.util.UUID;

/**
 * Represents a single transaction "leg". Every financial transaction
 * must consist of at least two legs (e.g. money leaving one account and entering another).
 */
public class TransactionLegDto {

    // The target account ID where the money is moving to or from
    private UUID accountId;

    // Unsigned amount in minor units (always positive; direction is explicit).
    private long amountMinorUnits;

    // "DEBIT" or "CREDIT" — relative to the target account's normal balance rules.
    private String direction;

    // --- Empty Constructor ---
    public TransactionLegDto() {}

    // --- Parameterized Constructor for testing/convenience ---
    public TransactionLegDto(UUID accountId, long amountMinorUnits, String direction) {
        this.accountId = accountId;
        this.amountMinorUnits = amountMinorUnits;
        this.direction = direction;
    }

    // --- Getters & Setters ---
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }

    public long getAmountMinorUnits() { return amountMinorUnits; }
    public void setAmountMinorUnits(long amountMinorUnits) { this.amountMinorUnits = amountMinorUnits; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
}