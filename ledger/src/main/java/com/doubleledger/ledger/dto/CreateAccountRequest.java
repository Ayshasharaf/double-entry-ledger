package com.doubleledger.ledger.dto;

import java.util.UUID;

/**
 * This class represents the exact JSON payload sent by a client
 * to create a new financial ledger account.
 */
public class CreateAccountRequest {

    // Unique ID provided by the client (allows safe client-side ID generation)
    private UUID id;

    // The human-readable name of the account (e.g., "Customer A's Checking")
    private String name;

    // Must match our database types: asset, liability, equity, revenue, expense
    private String accountType;

    // 'D' (Debit) or 'C' (Credit) representing how this account normally holds balance
    private String normalBalance;

    // ISO 4217 Currency code (e.g., "USD", "EUR")
    private String currency;

    // Flag indicating if this account is allowed to drop below 0
    private boolean allowOverdraft;

    // The maximum overdraft limit. We use 'long' primitive to default to 0 and avoid nulls!
    private long overdraftLimitMinorUnits;

    // --- Empty Constructor (Required by Jackson for JSON parsing) ---
    public CreateAccountRequest() {}

    // --- Getters & Setters ---
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }

    public String getNormalBalance() { return normalBalance; }
    public void setNormalBalance(String normalBalance) { this.normalBalance = normalBalance; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public boolean isAllowOverdraft() { return allowOverdraft; }
    public void setAllowOverdraft(boolean allowOverdraft) { this.allowOverdraft = allowOverdraft; }

    public long getOverdraftLimitMinorUnits() { return overdraftLimitMinorUnits; }
    public void setOverdraftLimitMinorUnits(long overdraftLimitMinorUnits) { this.overdraftLimitMinorUnits = overdraftLimitMinorUnits; }
}