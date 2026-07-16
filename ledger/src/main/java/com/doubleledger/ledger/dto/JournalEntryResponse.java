package com.doubleledger.ledger.dto;

import com.doubleledger.ledger.model.JournalEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JournalEntryResponse(
        UUID id,
        UUID idempotencyKey,
        String description,
        Instant postedAt,
        List<LedgerEntryResponse> entries
) {
    public static JournalEntryResponse fromEntity(JournalEntry journalEntry) {
        return new JournalEntryResponse(
                journalEntry.getId(),
                journalEntry.getIdempotencyKey(),
                journalEntry.getDescription(),
                journalEntry.getPostedAt().toInstant(),
                List.of()
        );
    }
}
