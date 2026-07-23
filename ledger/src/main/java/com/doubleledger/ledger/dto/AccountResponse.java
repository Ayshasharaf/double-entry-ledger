package com.doubleledger.ledger.dto;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String accountType,
        String normalBalance,
        long balanceMinorUnits,
        String currency,
        Instant createdAt
) {
}
