package com.doubleledger.ledger.service;

import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.dto.TransactionLegDto;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.model.JournalEntry;
import com.doubleledger.ledger.model.LedgerEntry;
import com.doubleledger.ledger.repository.AccountRepository;
import com.doubleledger.ledger.repository.JournalEntryRepository;
import com.doubleledger.ledger.repository.LedgerEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final ForensicAuditService forensicAuditService;

    @PersistenceContext
    private EntityManager entityManager;

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
    @Transactional
    public JournalEntry postTransaction(PostTransactionRequest request) {
        if (request.getIdempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency key is required.");
        }

        try {
            acquireIdempotencyAdvisoryLock(request.getIdempotencyKey());

            Optional<JournalEntry> existingTx = journalEntryRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existingTx.isPresent()) {
                return existingTx.get();
            }

            return executePosting(request);

        } catch (DataIntegrityViolationException ex) {
            Optional<JournalEntry> existingTx = journalEntryRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existingTx.isPresent()) {
                return existingTx.get();
            }
            forensicAuditService.logFailedTransaction(request, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            forensicAuditService.logFailedTransaction(request, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Serializes concurrent requests sharing the same idempotency key for the
     * duration of this transaction. Released automatically on commit/rollback.
     */
    private void acquireIdempotencyAdvisoryLock(UUID idempotencyKey) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .setParameter("key", idempotencyKey.toString())
                .getSingleResult();
    }

    private JournalEntry executePosting(PostTransactionRequest request) {
        List<TransactionLegDto> legs = request.getLegs();

        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A transaction must have at least 2 legs (debits and credits).");
        }

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

        List<UUID> sortedAccountIds = legs.stream()
                .map(TransactionLegDto::getAccountId)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<Account> accounts = accountRepository.findAllByIdsForUpdate(sortedAccountIds);
        Map<UUID, Account> accountMap = accounts.stream()
                .collect(Collectors.toMap(Account::getId, a -> a));

        for (UUID id : sortedAccountIds) {
            if (!accountMap.containsKey(id)) {
                throw new IllegalArgumentException("Account not found: " + id);
            }
        }

        String baseCurrency = accounts.get(0).getCurrency();
        for (Account account : accounts) {
            if (!account.getCurrency().equalsIgnoreCase(baseCurrency)) {
                throw new IllegalArgumentException("Multi-currency transaction splits are not allowed.");
            }
        }

        JournalEntry journalEntry = new JournalEntry();
        UUID journalId = UUID.randomUUID();
        journalEntry.setId(journalId);
        journalEntry.setIdempotencyKey(request.getIdempotencyKey());
        journalEntry.setDescription(request.getDescription());
        journalEntry.setStatus("posted");
        journalEntry.setPostedAt(OffsetDateTime.now());
        journalEntry = journalEntryRepository.save(journalEntry);

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
    }
}
