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

    @Column(name = "allow_overdraft", nullable = false)
    private Boolean allowOverdraft = false;

    @Column(name = "overdraft_limit_minor_units", nullable = false)
    private Long overdraftLimitMinorUnits = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public void validateBalance(long balanceMinorUnits) {
        if (status == AccountStatus.frozen) {
            throw new IllegalStateException("Cannot transact on a frozen account");
        }

        if (balanceMinorUnits < 0 && !allowOverdraft) {
            throw new IllegalArgumentException("Overdraft not allowed on account: " + id);
        }
        if (balanceMinorUnits < 0 && Math.abs(balanceMinorUnits) > overdraftLimitMinorUnits) {
            throw new IllegalArgumentException("Overdraft limit exceeded on account: " + id);
        }
    }

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
    public Boolean getAllowOverdraft() { return allowOverdraft; }
    public void setAllowOverdraft(Boolean allowOverdraft) { this.allowOverdraft = allowOverdraft; }
    public Long getOverdraftLimitMinorUnits() { return overdraftLimitMinorUnits; }
    public void setOverdraftLimitMinorUnits(Long overdraftLimitMinorUnits) { this.overdraftLimitMinorUnits = overdraftLimitMinorUnits; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
