package com.doubleledger.ledger.repository;

import com.doubleledger.ledger.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByJournalEntryIdOrderByCreatedAtAsc(UUID journalEntryId);

    @Query("SELECT COALESCE(SUM(le.amountMinorUnits), 0) FROM LedgerEntry le WHERE le.accountId = :accountId")
    long sumSignedAmountMinorUnitsByAccountId(@Param("accountId") UUID accountId);

    @Query("""
            SELECT le.accountId, COALESCE(SUM(le.amountMinorUnits), 0)
            FROM LedgerEntry le
            WHERE le.accountId IN :accountIds
            GROUP BY le.accountId
            """)
    List<Object[]> sumSignedAmountMinorUnitsByAccountIds(@Param("accountIds") Collection<UUID> accountIds);
}