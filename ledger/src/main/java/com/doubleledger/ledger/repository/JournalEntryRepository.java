package com.doubleledger.ledger.repository;

import com.doubleledger.ledger.model.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    Optional<JournalEntry> findByIdempotencyKey(UUID idempotencyKey);

    Optional<JournalEntry> findByReversesJournalEntryId(UUID reversesJournalEntryId);
}