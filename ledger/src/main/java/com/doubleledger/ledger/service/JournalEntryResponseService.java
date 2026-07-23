package com.doubleledger.ledger.service;

import com.doubleledger.ledger.dto.JournalEntryResponse;
import com.doubleledger.ledger.dto.LedgerEntryResponse;
import com.doubleledger.ledger.model.JournalEntry;
import com.doubleledger.ledger.model.LedgerEntry;
import com.doubleledger.ledger.repository.JournalEntryRepository;
import com.doubleledger.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JournalEntryResponseService {

    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public JournalEntryResponseService(JournalEntryRepository journalEntryRepository,
                                       LedgerEntryRepository ledgerEntryRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public Optional<JournalEntryResponse> findById(UUID journalEntryId) {
        return journalEntryRepository.findById(journalEntryId)
                .map(this::toResponse);
    }

    public JournalEntryResponse toResponse(JournalEntry journalEntry) {
        List<LedgerEntry> legs = ledgerEntryRepository.findByJournalEntryIdOrderByCreatedAtAsc(journalEntry.getId());
        List<LedgerEntryResponse> legResponses = legs.stream()
                .map(LedgerEntryResponse::fromEntity)
                .toList();
        return JournalEntryResponse.fromEntity(journalEntry, legResponses);
    }
}
