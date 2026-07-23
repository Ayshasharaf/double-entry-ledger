package com.doubleledger.ledger.dto;

import com.doubleledger.ledger.model.JournalEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JournalEntryResponse(
        UUID id,
        UUID idempotencyKey,
        String description,
        String status,
        UUID reversesJournalEntryId,
        Map<String, Object> metadata,
        Instant postedAt,
        List<LedgerEntryResponse> entries
) {
    public static JournalEntryResponse fromEntity(JournalEntry journalEntry, List<LedgerEntryResponse> entries) {
        return new JournalEntryResponse(
                journalEntry.getId(),
                journalEntry.getIdempotencyKey(),
                journalEntry.getDescription(),
                journalEntry.getStatus().name(),
                journalEntry.getReversesJournalEntryId(),
                journalEntry.getMetadata(),
                journalEntry.getPostedAt().toInstant(),
                entries
        );
    }
}
