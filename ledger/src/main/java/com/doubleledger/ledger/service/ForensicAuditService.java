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
     * Completely non-blocking. Writes directly to Standard Out (stdout)
     * as structured JSON. ZERO database connections are opened.
     */
    public void logFailedTransaction(PostTransactionRequest request, String errorMessage) {
        try {
            Map<String, Object> logPayload = new HashMap<>();
            logPayload.put("eventType", "LEDGER_POSTING_FAILED");
            logPayload.put("idempotencyKey", request.getIdempotencyKey());
            logPayload.put("description", request.getDescription());
            logPayload.put("errorMessage", errorMessage);
            logPayload.put("attemptedPayload", request); // Serialized as a nested JSON object

            // Prints clean, one-line structured JSON to stdout
            log.error(objectMapper.writeValueAsString(logPayload));

        } catch (Exception serializationError) {
            // Ultimate fallback in case JSON serialization fails
            log.error("CRITICAL: Failed to write forensic audit log. Reason: {}, Original Request Idempotency Key: {}",
                    serializationError.getMessage(), request.getIdempotencyKey());
        }
    }
}