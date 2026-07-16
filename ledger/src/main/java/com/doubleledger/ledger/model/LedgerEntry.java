package com.doubleledger.ledger.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor_units", nullable = false)
    private Long amountMinorUnits; // Signed: Negative = Debit, Positive = Credit

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // --- Getters & Setters ---
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public Long getAmountMinorUnits() { return amountMinorUnits; }
    public void setAmountMinorUnits(Long amountMinorUnits) { this.amountMinorUnits = amountMinorUnits; }
    public Long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Long balanceAfter) { this.balanceAfter = balanceAfter; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}