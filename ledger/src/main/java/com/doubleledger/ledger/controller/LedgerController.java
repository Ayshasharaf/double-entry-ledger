package com.doubleledger.ledger.controller;

import com.doubleledger.ledger.dto.AccountResponse;
import com.doubleledger.ledger.dto.CreateAccountRequest;
import com.doubleledger.ledger.dto.JournalEntryResponse;
import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.model.AccountType;
import com.doubleledger.ledger.repository.AccountRepository;
import com.doubleledger.ledger.service.IdempotencyService;
import com.doubleledger.ledger.service.LedgerPostingService;
import com.doubleledger.ledger.service.RequestHasher;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class LedgerController {

    private final LedgerPostingService postingService;
    private final AccountRepository accountRepository;
    private final IdempotencyService idempotencyService;
    private final RequestHasher requestHasher;

    public LedgerController(LedgerPostingService postingService,
                            AccountRepository accountRepository,
                            IdempotencyService idempotencyService,
                            RequestHasher requestHasher) {
        this.postingService = postingService;
        this.accountRepository = accountRepository;
        this.idempotencyService = idempotencyService;
        this.requestHasher = requestHasher;
    }

    /**
     * Posts a multi-legged transaction with full idempotency checks.
     */
    @PostMapping("/transactions")
    public ResponseEntity<JournalEntryResponse> postTransaction(
            @RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
            @Valid @RequestBody PostTransactionRequest request) {

        UUID domainKey = resolveDomainIdempotencyKey(idempotencyKeyHeader, request);
        request.setIdempotencyKey(domainKey);

        String requestHash = requestHasher.hashPostTransaction(request);
        IdempotencyService.PostTransactionOutcome outcome = idempotencyService.executePostTransaction(
                idempotencyKeyHeader,
                requestHash,
                () -> postingService.postTransaction(request));

        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.response());
    }

    /**
     * Provisions a new account in the ledger using the secure CreateAccountRequest DTO.
     * Prevents Mass Assignment Vulnerabilities.
     */
    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = new Account();

        account.setId(request.getId() != null ? request.getId() : UUID.randomUUID());
        account.setName(request.getName());

        try {
            account.setAccountType(AccountType.valueOf(request.getAccountType().trim().toLowerCase()));
            account.setNormalBalance(parseNormalBalance(request.getNormalBalance()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid account type or normal balance format.");
        }

        if (request.getCurrency() == null || request.getCurrency().trim().length() != 3) {
            throw new IllegalArgumentException("Currency must be a valid 3-letter ISO code.");
        }
        account.setCurrency(request.getCurrency().toUpperCase());

        account.setBalanceMinorUnits(0L);
        account.setAllowOverdraft(request.isAllowOverdraft());

        if (request.isAllowOverdraft() && request.getOverdraftLimitMinorUnits() < 0) {
            throw new IllegalArgumentException("Overdraft limit minor units cannot be negative.");
        }
        account.setOverdraftLimitMinorUnits(request.getOverdraftLimitMinorUnits());

        Account savedAccount = accountRepository.save(account);

        return new ResponseEntity<>(AccountResponse.fromEntity(savedAccount), HttpStatus.CREATED);
    }

    /**
     * Retrieves account balance securely and read-only.
     */
    @GetMapping("/accounts/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID id) {
        return accountRepository.findById(id)
                .map(AccountResponse::fromEntity)
                .map(dto -> new ResponseEntity<>(dto, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    /**
     * Highly optimized batch retrieval of accounts.
     */
    @GetMapping("/accounts")
    @Transactional(readOnly = true)
    public ResponseEntity<List<AccountResponse>> getAllAccounts() {
        List<AccountResponse> accounts = accountRepository.findAll()
                .stream()
                .map(AccountResponse::fromEntity)
                .toList();
        return new ResponseEntity<>(accounts, HttpStatus.OK);
    }

    private static UUID resolveDomainIdempotencyKey(String header, PostTransactionRequest request) {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required.");
        }
        if (header.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters.");
        }

        UUID headerKey;
        try {
            headerKey = UUID.fromString(header.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Idempotency-Key header must be a valid UUID.");
        }

        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().equals(headerKey)) {
            throw new IllegalArgumentException(
                    "Request body idempotencyKey must match the Idempotency-Key header.");
        }

        return headerKey;
    }

    private static String parseNormalBalance(String input) {
        return switch (input.trim().toUpperCase()) {
            case "D", "DEBIT" -> "D";
            case "C", "CREDIT" -> "C";
            default -> throw new IllegalArgumentException("Normal balance must be D, C, DEBIT, or CREDIT.");
        };
    }
}
