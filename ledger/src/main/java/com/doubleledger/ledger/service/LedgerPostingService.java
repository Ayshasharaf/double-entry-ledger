package com.doubleledger.ledger.service;

import com.doubleledger.ledger.audit.ForensicAuditService;
import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.dto.ReverseTransactionRequest;
import com.doubleledger.ledger.dto.TransactionLegDto;
import com.doubleledger.ledger.exception.IdempotencyConflictException;
import com.doubleledger.ledger.exception.JournalEntryNotFoundException;
import com.doubleledger.ledger.exception.TransactionAlreadyReversedException;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.model.JournalEntry;
import com.doubleledger.ledger.model.JournalEntryStatus;
import com.doubleledger.ledger.model.LedgerEntry;
import com.doubleledger.ledger.persistence.PostgresAdvisoryLockService;
import com.doubleledger.ledger.repository.AccountRepository;
import com.doubleledger.ledger.repository.JournalEntryRepository;
import com.doubleledger.ledger.repository.LedgerEntryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The core computational engine of the double-entry ledger system.
 * This class coordinates currency validation, atomic locks, and derived balance checks.
 */
@Service
public class LedgerPostingService {

    private static final String POSTING_FAILURE_EVENT = "LEDGER_POSTING_UNEXPECTED_FAILURE";
    private static final String REVERSAL_FAILURE_EVENT = "LEDGER_REVERSAL_UNEXPECTED_FAILURE";

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountBalanceService accountBalanceService;
    private final ForensicAuditService forensicAuditService;
    private final PostgresAdvisoryLockService advisoryLockService;

    public LedgerPostingService(AccountRepository accountRepository,
                                JournalEntryRepository journalEntryRepository,
                                LedgerEntryRepository ledgerEntryRepository,
                                AccountBalanceService accountBalanceService,
                                ForensicAuditService forensicAuditService,
                                PostgresAdvisoryLockService advisoryLockService) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountBalanceService = accountBalanceService;
        this.forensicAuditService = forensicAuditService;
        this.advisoryLockService = advisoryLockService;
    }

    @Transactional
    public JournalEntry postTransaction(PostTransactionRequest request) {
        if (request.getIdempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency key is required.");
        }

        try {
            acquireIdempotencyAdvisoryLock(request.getIdempotencyKey());

            Optional<JournalEntry> existingTx = findExistingPostingByIdempotencyKey(request.getIdempotencyKey());
            if (existingTx.isPresent()) {
                return existingTx.get();
            }

            return executePosting(request);

        } catch (DataIntegrityViolationException ex) {
            Optional<JournalEntry> existingTx = findExistingPostingByIdempotencyKey(request.getIdempotencyKey());
            if (existingTx.isPresent()) {
                return existingTx.get();
            }
            forensicAuditService.logUnexpectedFailure(
                    POSTING_FAILURE_EVENT, request.getIdempotencyKey(), request, ex);
            throw ex;
        }
    }

    @Transactional
    public JournalEntry reverseTransaction(UUID originalJournalEntryId,
                                           UUID idempotencyKey,
                                           ReverseTransactionRequest request) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("Idempotency key is required.");
        }

        try {
            acquireIdempotencyAdvisoryLock(idempotencyKey);

            Optional<JournalEntry> existingReversal =
                    findExistingReversalByIdempotencyKey(idempotencyKey, originalJournalEntryId);
            if (existingReversal.isPresent()) {
                return existingReversal.get();
            }

            return executeReversal(originalJournalEntryId, idempotencyKey, request);

        } catch (DataIntegrityViolationException ex) {
            Optional<JournalEntry> existingReversal =
                    findExistingReversalByIdempotencyKey(idempotencyKey, originalJournalEntryId);
            if (existingReversal.isPresent()) {
                return existingReversal.get();
            }
            forensicAuditService.logUnexpectedFailure(
                    REVERSAL_FAILURE_EVENT,
                    idempotencyKey,
                    Map.of("originalJournalEntryId", originalJournalEntryId, "request", request),
                    ex);
            throw ex;
        }
    }

    private JournalEntry executeReversal(UUID originalJournalEntryId,
                                         UUID idempotencyKey,
                                         ReverseTransactionRequest request) {
        acquireReversalTargetAdvisoryLock(originalJournalEntryId);

        JournalEntry original = journalEntryRepository.findById(originalJournalEntryId)
                .orElseThrow(() -> new JournalEntryNotFoundException(originalJournalEntryId));

        ensureReversible(original, originalJournalEntryId);

        List<LedgerEntry> originalLegs =
                ledgerEntryRepository.findByJournalEntryIdOrderByCreatedAtAsc(originalJournalEntryId);
        if (originalLegs.size() < 2) {
            throw new IllegalArgumentException("Original journal entry has insufficient legs to reverse.");
        }

        List<SignedLeg> reversedLegs = originalLegs.stream()
                .map(leg -> new SignedLeg(leg.getAccountId(), -leg.getAmountMinorUnits()))
                .toList();

        JournalEntry reversal = new JournalEntry();
        UUID reversalId = UUID.randomUUID();
        reversal.setId(reversalId);
        reversal.setIdempotencyKey(idempotencyKey);
        reversal.setDescription(resolveReversalDescription(request, original));
        reversal.setStatus(JournalEntryStatus.posted);
        reversal.setReversesJournalEntryId(originalJournalEntryId);
        reversal.setPostedAt(OffsetDateTime.now());
        reversal = journalEntryRepository.save(reversal);

        applySignedLegs(reversalId, reversedLegs);

        original.setStatus(JournalEntryStatus.reversed);
        journalEntryRepository.save(original);

        return reversal;
    }

    private void ensureReversible(JournalEntry original, UUID originalJournalEntryId) {
        if (original.getReversesJournalEntryId() != null) {
            throw new IllegalArgumentException("Cannot reverse a reversal entry.");
        }
        if (original.getStatus() == JournalEntryStatus.reversed) {
            throw new TransactionAlreadyReversedException("Journal entry is already reversed.");
        }
        if (journalEntryRepository.findByReversesJournalEntryId(originalJournalEntryId).isPresent()) {
            throw new TransactionAlreadyReversedException("Journal entry is already reversed.");
        }
    }

    private Optional<JournalEntry> findExistingPostingByIdempotencyKey(UUID idempotencyKey) {
        Optional<JournalEntry> existing = journalEntryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (existing.get().getReversesJournalEntryId() != null) {
            throw new IdempotencyConflictException(
                    "Idempotency key is already used by a reversal entry.");
        }
        return existing;
    }

    private Optional<JournalEntry> findExistingReversalByIdempotencyKey(UUID idempotencyKey,
                                                                        UUID originalJournalEntryId) {
        Optional<JournalEntry> existing = journalEntryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        JournalEntry entry = existing.get();
        if (entry.getReversesJournalEntryId() == null) {
            throw new IdempotencyConflictException(
                    "Idempotency key is already used by a posting entry.");
        }
        if (!originalJournalEntryId.equals(entry.getReversesJournalEntryId())) {
            throw new IdempotencyConflictException(
                    "Idempotency key is already used by a different reversal.");
        }
        return existing;
    }

    private JournalEntry executePosting(PostTransactionRequest request) {
        List<TransactionLegDto> legs = request.getLegs();

        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A transaction must have at least 2 legs (debits and credits).");
        }

        validateLegsBalanceToZero(legs);
        List<SignedLeg> signedLegs = toSignedLegs(legs);

        JournalEntry journalEntry = new JournalEntry();
        UUID journalId = UUID.randomUUID();
        journalEntry.setId(journalId);
        journalEntry.setIdempotencyKey(request.getIdempotencyKey());
        journalEntry.setDescription(request.getDescription());
        journalEntry.setStatus(JournalEntryStatus.posted);
        journalEntry.setMetadata(request.getMetadata());
        journalEntry.setPostedAt(OffsetDateTime.now());
        journalEntry = journalEntryRepository.save(journalEntry);

        applySignedLegs(journalId, signedLegs);

        return journalEntry;
    }

    private void validateLegsBalanceToZero(List<TransactionLegDto> legs) {
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
    }

    private List<SignedLeg> toSignedLegs(List<TransactionLegDto> legs) {
        List<SignedLeg> signedLegs = new ArrayList<>(legs.size());
        for (TransactionLegDto leg : legs) {
            long amount = leg.getAmountMinorUnits();
            long signedAmount = "DEBIT".equalsIgnoreCase(leg.getDirection()) ? -amount : amount;
            signedLegs.add(new SignedLeg(leg.getAccountId(), signedAmount));
        }
        return signedLegs;
    }

    private void applySignedLegs(UUID journalEntryId, List<SignedLeg> legs) {
        List<UUID> sortedAccountIds = legs.stream()
                .map(SignedLeg::accountId)
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

        // Currency check must run after FOR UPDATE — never validate balances or currency before locking.
        String baseCurrency = accounts.get(0).getCurrency();
        for (Account account : accounts) {
            if (!account.getCurrency().equalsIgnoreCase(baseCurrency)) {
                throw new IllegalArgumentException("Multi-currency transaction splits are not allowed.");
            }
        }

        Map<UUID, Long> runningBalances = new HashMap<>();
        for (UUID accountId : sortedAccountIds) {
            runningBalances.put(accountId, accountBalanceService.computeBalance(accountMap.get(accountId)));
        }

        List<LedgerEntry> ledgerEntries = new ArrayList<>(legs.size());
        for (SignedLeg leg : legs) {
            Account account = accountMap.get(leg.accountId());
            long signedAmount = leg.signedAmountMinorUnits();
            long currentBalance = runningBalances.get(leg.accountId());
            long newBalance = accountBalanceService.applySignedLeg(account, currentBalance, signedAmount);
            account.validateBalance(newBalance);

            LedgerEntry ledgerEntry = new LedgerEntry();
            ledgerEntry.setId(UUID.randomUUID());
            ledgerEntry.setJournalEntryId(journalEntryId);
            ledgerEntry.setAccountId(account.getId());
            ledgerEntry.setAmountMinorUnits(signedAmount);
            ledgerEntry.setBalanceAfter(newBalance);
            ledgerEntries.add(ledgerEntry);

            runningBalances.put(leg.accountId(), newBalance);
        }

        ledgerEntryRepository.saveAll(ledgerEntries);
    }

    private static String resolveReversalDescription(ReverseTransactionRequest request, JournalEntry original) {
        if (request != null && request.getDescription() != null && !request.getDescription().isBlank()) {
            return request.getDescription().trim();
        }
        return "Reversal of: " + original.getDescription();
    }

    private void acquireReversalTargetAdvisoryLock(UUID originalJournalEntryId) {
        advisoryLockService.acquireTransactionLock("reversal-target:" + originalJournalEntryId);
    }

    private void acquireIdempotencyAdvisoryLock(UUID idempotencyKey) {
        advisoryLockService.acquireTransactionLock(idempotencyKey.toString());
    }

    private record SignedLeg(UUID accountId, long signedAmountMinorUnits) {}
}
