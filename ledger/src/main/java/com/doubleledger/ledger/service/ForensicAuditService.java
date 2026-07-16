package com.doubleledger.ledger.service;

import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class ForensicAuditService {

    private static final Logger log = LoggerFactory.getLogger(ForensicAuditService.class);
    private final ObjectMapper objectMapper;

    public ForensicAuditService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Logs only unexpected infrastructure or persistence failures — not validation
     * rejections or idempotency replays, which are normal control-flow paths.
     */
    public void logUnexpectedFailure(PostTransactionRequest request, Throwable error) {
        try {
            Map<String, Object> logPayload = new HashMap<>();
            logPayload.put("eventType", "LEDGER_POSTING_UNEXPECTED_FAILURE");
            logPayload.put("idempotencyKey", request.getIdempotencyKey());
            logPayload.put("description", request.getDescription());
            logPayload.put("errorType", error.getClass().getName());
            logPayload.put("errorMessage", error.getMessage());
            logPayload.put("attemptedPayload", request);

            log.error(objectMapper.writeValueAsString(logPayload));

        } catch (Exception serializationError) {
            log.error("CRITICAL: Failed to write forensic audit log. Reason: {}, Original Request Idempotency Key: {}",
                    serializationError.getMessage(), request.getIdempotencyKey());
        }
    }
}