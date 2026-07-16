package com.doubleledger.ledger.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Column(name = "normal_balance", nullable = false, length = 1)
    private String normalBalance; // "D" or "C"

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor_units", nullable = false)
    private Long balanceMinorUnits = 0L;

    @Column(name = "allow_overdraft", nullable = false)
    private Boolean allowOverdraft = false;

    @Column(name = "overdraft_limit_minor_units", nullable = false)
    private Long overdraftLimitMinorUnits = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.active;

    @Version
    @Column(nullable = false)
    private Long version = 0L; // Optimistic locking version for safety

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    // --- Helper Methods ---

    public void debit(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Debit amount must be positive");

        if (accountType == AccountType.asset || accountType == AccountType.expense) {
            this.balanceMinorUnits += amount;
        } else {
            this.balanceMinorUnits -= amount;
        }
        validateOverdraft();
    }

    public void credit(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Credit amount must be positive");

        if (accountType == AccountType.liability || accountType == AccountType.equity || accountType == AccountType.revenue) {
            this.balanceMinorUnits += amount;
        } else {
            this.balanceMinorUnits -= amount;
        }
        validateOverdraft();
    }

    private void validateOverdraft() {
        if (status == AccountStatus.frozen) {
            throw new IllegalStateException("Cannot transact on a frozen account");
        }

        // Assets are normally positive (Debits). If it falls below 0, check overdraft rules.
        if (balanceMinorUnits < 0 && !allowOverdraft) {
            throw new IllegalArgumentException("Overdraft not allowed on account: " + id);
        }
        if (balanceMinorUnits < 0 && Math.abs(balanceMinorUnits) > overdraftLimitMinorUnits) {
            throw new IllegalArgumentException("Overdraft limit exceeded on account: " + id);
        }
    }

    // --- Getters & Setters ---
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }
    public String getNormalBalance() { return normalBalance; }
    public void setNormalBalance(String normalBalance) { this.normalBalance = normalBalance; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Long getBalanceMinorUnits() { return balanceMinorUnits; }
    public void setBalanceMinorUnits(Long balanceMinorUnits) { this.balanceMinorUnits = balanceMinorUnits; }
    public Boolean getAllowOverdraft() { return allowOverdraft; }
    public void setAllowOverdraft(Boolean allowOverdraft) { this.allowOverdraft = allowOverdraft; }
    public Long getOverdraftLimitMinorUnits() { return overdraftLimitMinorUnits; }
    public void setOverdraftLimitMinorUnits(Long overdraftLimitMinorUnits) { this.overdraftLimitMinorUnits = overdraftLimitMinorUnits; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}