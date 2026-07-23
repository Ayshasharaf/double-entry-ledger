package com.doubleledger.ledger.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JournalEntryStatus status = JournalEntryStatus.posted;

    @Column(name = "reverses_journal_entry_id")
    private UUID reversesJournalEntryId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "posted_at", nullable = false)
    private OffsetDateTime postedAt = OffsetDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public JournalEntryStatus getStatus() { return status; }
    public void setStatus(JournalEntryStatus status) { this.status = status; }
    public UUID getReversesJournalEntryId() { return reversesJournalEntryId; }
    public void setReversesJournalEntryId(UUID reversesJournalEntryId) { this.reversesJournalEntryId = reversesJournalEntryId; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public OffsetDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(OffsetDateTime postedAt) { this.postedAt = postedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
