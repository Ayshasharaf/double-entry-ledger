package com.doubleledger.ledger.dto;

import com.doubleledger.ledger.model.JournalEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JournalEntryResponse(
        UUID id,
        String description,
        Instant timestamp,
        List<LedgerEntryResponse> entries
) {
    public static JournalEntryResponse fromEntity(JournalEntry journalEntry) {
        List<LedgerEntryResponse> entryResponses = journalEntry.getEntries()
                .stream()
                .map(LedgerEntryResponse::fromEntity)
                .toList();

        return new JournalEntryResponse(
                journalEntry.getId(),
                journalEntry.getDescription(),
                journalEntry.getTimestamp(),
                entryResponses
        );
    }
}