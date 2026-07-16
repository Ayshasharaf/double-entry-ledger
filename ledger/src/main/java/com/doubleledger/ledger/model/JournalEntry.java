package com.doubleledger.ledger.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", unique = true, nullable = false)
    private UUID idempotencyKey;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private String status = "posted"; // "posted" or "reversed"

    @Column(name = "reverses_journal_entry_id")
    private UUID reversesJournalEntryId;

    @Column(name = "posted_at", nullable = false)
    private OffsetDateTime postedAt = OffsetDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // --- Getters & Setters ---
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getReversesJournalEntryId() { return reversesJournalEntryId; }
    public void setReversesJournalEntryId(UUID reversesJournalEntryId) { this.reversesJournalEntryId = reversesJournalEntryId; }
    public OffsetDateTime getPostedAt() { return postedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}