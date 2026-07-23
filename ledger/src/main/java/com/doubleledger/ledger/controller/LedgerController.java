package com.doubleledger.ledger.controller;

import com.doubleledger.ledger.dto.AccountResponse;
import com.doubleledger.ledger.dto.CreateAccountRequest;
import com.doubleledger.ledger.dto.JournalEntryResponse;
import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.dto.ReverseTransactionRequest;
import com.doubleledger.ledger.service.AccountService;
import com.doubleledger.ledger.service.IdempotencyService;
import com.doubleledger.ledger.service.JournalEntryResponseService;
import com.doubleledger.ledger.service.LedgerPostingService;
import com.doubleledger.ledger.util.RequestHasher;
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
    private final AccountService accountService;
    private final IdempotencyService idempotencyService;
    private final RequestHasher requestHasher;
    private final JournalEntryResponseService journalEntryResponseService;

    public LedgerController(LedgerPostingService postingService,
                            AccountService accountService,
                            IdempotencyService idempotencyService,
                            RequestHasher requestHasher,
                            JournalEntryResponseService journalEntryResponseService) {
        this.postingService = postingService;
        this.accountService = accountService;
        this.idempotencyService = idempotencyService;
        this.requestHasher = requestHasher;
        this.journalEntryResponseService = journalEntryResponseService;
    }

    @PostMapping("/transactions")
    public ResponseEntity<JournalEntryResponse> postTransaction(
            @RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
            @Valid @RequestBody PostTransactionRequest request) {

        UUID domainKey = resolveDomainIdempotencyKey(idempotencyKeyHeader, request);
        request.setIdempotencyKey(domainKey);

        String requestHash = requestHasher.hashPostTransaction(request);
        IdempotencyService.IdempotentOperationOutcome outcome = idempotencyService.executePostTransaction(
                idempotencyKeyHeader,
                requestHash,
                () -> postingService.postTransaction(request));

        return idempotentResponse(outcome);
    }

    @PostMapping("/transactions/{journalEntryId}/reversals")
    public ResponseEntity<JournalEntryResponse> reverseTransaction(
            @PathVariable UUID journalEntryId,
            @RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
            @RequestBody(required = false) ReverseTransactionRequest request) {

        UUID domainKey = parseIdempotencyKeyHeader(idempotencyKeyHeader);
        ReverseTransactionRequest body = request != null ? request : new ReverseTransactionRequest();
        String requestHash = requestHasher.hashReverseTransaction(journalEntryId, body);

        IdempotencyService.IdempotentOperationOutcome outcome = idempotencyService.executeReverseTransaction(
                idempotencyKeyHeader,
                requestHash,
                () -> postingService.reverseTransaction(journalEntryId, domainKey, body));

        return idempotentResponse(outcome);
    }

    @GetMapping("/transactions/{journalEntryId}")
    @Transactional(readOnly = true)
    public ResponseEntity<JournalEntryResponse> getTransaction(@PathVariable UUID journalEntryId) {
        return journalEntryResponseService.findById(journalEntryId)
                .map(response -> new ResponseEntity<>(response, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return new ResponseEntity<>(accountService.createAccount(request), HttpStatus.CREATED);
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID id) {
        return accountService.findById(id)
                .map(response -> new ResponseEntity<>(response, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountResponse>> getAllAccounts() {
        return new ResponseEntity<>(accountService.findAll(), HttpStatus.OK);
    }

    private static ResponseEntity<JournalEntryResponse> idempotentResponse(
            IdempotencyService.IdempotentOperationOutcome outcome) {
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.response());
    }

    private static UUID resolveDomainIdempotencyKey(String header, PostTransactionRequest request) {
        UUID headerKey = parseIdempotencyKeyHeader(header);

        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().equals(headerKey)) {
            throw new IllegalArgumentException(
                    "Request body idempotencyKey must match the Idempotency-Key header.");
        }

        return headerKey;
    }

    private static UUID parseIdempotencyKeyHeader(String header) {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required.");
        }
        if (header.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters.");
        }

        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Idempotency-Key header must be a valid UUID.");
        }
    }
}
