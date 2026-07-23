package com.doubleledger.ledger.service;

import com.doubleledger.ledger.dto.JournalEntryResponse;
import com.doubleledger.ledger.exception.IdempotencyConflictException;
import com.doubleledger.ledger.model.IdempotencyKey;
import com.doubleledger.ledger.model.JournalEntry;
import com.doubleledger.ledger.persistence.PostgresAdvisoryLockService;
import com.doubleledger.ledger.repository.IdempotencyKeyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    public record IdempotentOperationOutcome(JournalEntryResponse response, boolean replayed) {}

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final JournalEntryResponseService journalEntryResponseService;
    private final ObjectMapper objectMapper;
    private final PostgresAdvisoryLockService advisoryLockService;

    public IdempotencyService(IdempotencyKeyRepository idempotencyKeyRepository,
                              JournalEntryResponseService journalEntryResponseService,
                              ObjectMapper objectMapper,
                              PostgresAdvisoryLockService advisoryLockService) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.journalEntryResponseService = journalEntryResponseService;
        this.objectMapper = objectMapper;
        this.advisoryLockService = advisoryLockService;
    }

    @Transactional
    public IdempotentOperationOutcome executePostTransaction(String apiIdempotencyKey,
                                                               String requestHash,
                                                               Supplier<JournalEntry> postingAction) {
        return executeIdempotent(apiIdempotencyKey, requestHash, postingAction);
    }

    @Transactional
    public IdempotentOperationOutcome executeReverseTransaction(String apiIdempotencyKey,
                                                                String requestHash,
                                                                Supplier<JournalEntry> reversalAction) {
        return executeIdempotent(apiIdempotencyKey, requestHash, reversalAction);
    }

    private IdempotentOperationOutcome executeIdempotent(String apiIdempotencyKey,
                                                         String requestHash,
                                                         Supplier<JournalEntry> action) {
        acquireAdvisoryLock(apiIdempotencyKey);

        Optional<IdempotencyKey> cached = idempotencyKeyRepository.findById(apiIdempotencyKey);
        if (cached.isPresent()) {
            return replayCached(cached.get(), requestHash);
        }

        JournalEntry entry = action.get();
        JournalEntryResponse response = journalEntryResponseService.toResponse(entry);

        try {
            persistCache(apiIdempotencyKey, requestHash, HttpStatus.CREATED.value(), response);
        } catch (DataIntegrityViolationException ex) {
            Optional<IdempotencyKey> raced = idempotencyKeyRepository.findById(apiIdempotencyKey);
            if (raced.isPresent()) {
                return replayCached(raced.get(), requestHash);
            }
            throw ex;
        }

        return new IdempotentOperationOutcome(response, false);
    }

    private IdempotentOperationOutcome replayCached(IdempotencyKey cached, String requestHash) {
        if (!cached.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key was already used with a different request body.");
        }

        try {
            JournalEntryResponse response = objectMapper.readValue(
                    cached.getResponseBody(), JournalEntryResponse.class);
            return new IdempotentOperationOutcome(response, true);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cached idempotency response is corrupt.", ex);
        }
    }

    private void persistCache(String apiIdempotencyKey,
                              String requestHash,
                              int responseStatus,
                              JournalEntryResponse response) {
        try {
            IdempotencyKey row = new IdempotencyKey();
            row.setIdempotencyKey(apiIdempotencyKey);
            row.setRequestHash(requestHash);
            row.setResponseStatus(responseStatus);
            row.setResponseBody(objectMapper.writeValueAsString(response));
            idempotencyKeyRepository.save(row);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize idempotency response.", ex);
        } catch (DataIntegrityViolationException ex) {
            throw ex;
        }
    }

    private void acquireAdvisoryLock(String idempotencyKey) {
        advisoryLockService.acquireTransactionLock("api-idempotency:" + idempotencyKey);
    }
}
