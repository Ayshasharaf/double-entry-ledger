package com.doubleledger.ledger.service;

import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.dto.TransactionLegDto;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.model.JournalEntry;
import com.doubleledger.ledger.model.LedgerEntry;
import com.doubleledger.ledger.repository.AccountRepository;
import com.doubleledger.ledger.repository.JournalEntryRepository;
import com.doubleledger.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The core computational engine of the double-entry ledger system.
 * This class coordinates currency validation, atomic locks, and balance arithmetic.
 */
@Service
public class LedgerPostingService {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ForensicAuditService forensicAuditService; // Injecting our brand-new isolated logger service

    public LedgerPostingService(AccountRepository accountRepository,
                                JournalEntryRepository journalEntryRepository,
                                LedgerEntryRepository ledgerEntryRepository,
                                ForensicAuditService forensicAuditService) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.forensicAuditService = forensicAuditService;
    }

    /**
     * Executes an atomic, double-entry financial transaction.
     */
    @Transactional // Rolls back everything inside this method if an exception is thrown
    public JournalEntry postTransaction(PostTransactionRequest request) {
        try {
            // 1. Prevent double processing of requests (Idempotency check)
            Optional<JournalEntry> existingTx = journalEntryRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existingTx.isPresent()) {
                return existingTx.get();
            }

            List<TransactionLegDto> legs = request.getLegs();

            // 2. Validate that there are at least two legs (Double-entry rules)
            if (legs == null || legs.size() < 2) {
                throw new IllegalArgumentException("A transaction must have at least 2 legs (debits and credits).");
            }

            // 3. Zero-Sum Equation Check (Total Debits MUST equal Total Credits)
            long sum = 0;
            for (TransactionLegDto leg : legs) {
                if ("DEBIT".equalsIgnoreCase(leg.getDirection())) {
                    sum -= leg.getAmountMinorUnits();
                } else if ("CREDIT".equalsIgnoreCase(leg.getDirection())) {
                    sum += leg.getAmountMinorUnits();
                } else {
                    throw new IllegalArgumentException("Invalid leg direction. Must be 'DEBIT' or 'CREDIT'.");
                }
            }
            if (sum != 0) {
                throw new IllegalArgumentException("Unbalanced Transaction: Total DEBITS must equal Total CREDITS.");
            }

            // 4. DEADLOCK PREVENTION - Sort Account IDs before querying them
            List<UUID> sortedAccountIds = legs.stream()
                    .map(TransactionLegDto::getAccountId)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            // 5. Acquire PESSIMISTIC locks on all involved accounts in a single query
            List<Account> accounts = accountRepository.findAllByIdsForUpdate(sortedAccountIds);
            Map<UUID, Account> accountMap = accounts.stream()
                    .collect(Collectors.toMap(Account::getId, a -> a));

            // 6. Validate that all requested account IDs exist in the database
            for (UUID id : sortedAccountIds) {
                if (!accountMap.containsKey(id)) {
                    throw new IllegalArgumentException("Account not found: " + id);
                }
            }

            // 7. Check currency compatibility
            String baseCurrency = accounts.get(0).getCurrency();
            for (Account account : accounts) {
                if (!account.getCurrency().equalsIgnoreCase(baseCurrency)) {
                    throw new IllegalArgumentException("Multi-currency transaction splits are not allowed.");
                }
            }

            // 8. Create and Save the Journal Entry Header
            JournalEntry journalEntry = new JournalEntry();
            UUID journalId = UUID.randomUUID();
            journalEntry.setId(journalId);
            journalEntry.setIdempotencyKey(request.getIdempotencyKey());
            journalEntry.setDescription(request.getDescription());
            journalEntry.setStatus("posted");
            journalEntry.setPostedAt(OffsetDateTime.now());
            journalEntry = journalEntryRepository.save(journalEntry);

            // 9. Mutate balance and persist historical Ledger Entries
            for (TransactionLegDto leg : legs) {
                Account account = accountMap.get(leg.getAccountId());
                long amount = leg.getAmountMinorUnits();

                if ("DEBIT".equalsIgnoreCase(leg.getDirection())) {
                    account.debit(amount);
                } else {
                    account.credit(amount);
                }

                accountRepository.save(account);

                LedgerEntry ledgerEntry = new LedgerEntry();
                ledgerEntry.setId(UUID.randomUUID());
                ledgerEntry.setJournalEntryId(journalId);
                ledgerEntry.setAccountId(account.getId());

                long signedAmount = "DEBIT".equalsIgnoreCase(leg.getDirection()) ? -amount : amount;
                ledgerEntry.setAmountMinorUnits(signedAmount);
                ledgerEntry.setBalanceAfter(account.getBalanceMinorUnits());

                ledgerEntryRepository.save(ledgerEntry);
            }

            return journalEntry;

        } catch (Exception ex) {
            // Write the structured failure JSON directly to stdout.
            // NO database connection is requested, keeping the pool safe!
            forensicAuditService.logFailedTransaction(request, ex.getMessage());

            // Rethrow so Spring's @Transactional triggers a clean rollback of SQL data
            throw ex;
        }
    }
}