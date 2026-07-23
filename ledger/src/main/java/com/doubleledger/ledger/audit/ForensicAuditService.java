package com.doubleledger.ledger.audit;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ForensicAuditService {

    private final AsyncForensicAuditLogger asyncForensicAuditLogger;

    public ForensicAuditService(AsyncForensicAuditLogger asyncForensicAuditLogger) {
        this.asyncForensicAuditLogger = asyncForensicAuditLogger;
    }

    /**
     * Logs only unexpected infrastructure or persistence failures — not validation
     * rejections or idempotency replays, which are normal control-flow paths.
     */
    public void logUnexpectedFailure(String eventType,
                                       UUID idempotencyKey,
                                       Object attemptedPayload,
                                       Throwable error) {
        asyncForensicAuditLogger.logUnexpectedFailure(
                eventType,
                idempotencyKey,
                attemptedPayload,
                error.getClass().getName(),
                error.getMessage());
    }
}
