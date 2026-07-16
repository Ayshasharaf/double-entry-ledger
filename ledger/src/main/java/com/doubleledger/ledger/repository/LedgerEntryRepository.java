package com.doubleledger.ledger.repository;

import com.doubleledger.ledger.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query(value = "SELECT * FROM ledger_entries WHERE account_id = :accountId ORDER BY created_at DESC LIMIT 1", nativeQuery = true)
    Optional<LedgerEntry> findLatestEntryForAccount(@Param("accountId") UUID accountId);
}