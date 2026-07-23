package com.doubleledger.ledger.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class AsyncForensicAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AsyncForensicAuditLogger.class);

    private final ObjectMapper objectMapper;

    public AsyncForensicAuditLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Async("forensicLogExecutor")
    public void logUnexpectedFailure(String eventType,
                                     UUID idempotencyKey,
                                     Object attemptedPayload,
                                     String errorType,
                                     String errorMessage) {
        try {
            Map<String, Object> logPayload = new HashMap<>();
            logPayload.put("eventType", eventType);
            logPayload.put("idempotencyKey", idempotencyKey);
            logPayload.put("errorType", errorType);
            logPayload.put("errorMessage", errorMessage);
            logPayload.put("attemptedPayload", attemptedPayload);

            log.error(objectMapper.writeValueAsString(logPayload));

        } catch (Exception serializationError) {
            log.error("CRITICAL: Failed to write forensic audit log. Reason: {}, Idempotency Key: {}",
                    serializationError.getMessage(), idempotencyKey);
        }
    }
}
