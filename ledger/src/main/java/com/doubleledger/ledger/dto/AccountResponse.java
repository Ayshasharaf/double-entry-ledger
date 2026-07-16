package com.doubleledger.ledger.dto;

import com.doubleledger.ledger.model.Account;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String type,          // e.g., "ASSET", "LIABILITY"
        String normalBalance, // e.g., "DEBIT", "CREDIT"
        BigDecimal balance,
        String currency,
        Instant createdAt
) {
    // Elegant static factory method to map from Entity to DTO
    public static AccountResponse fromEntity(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType().name(),
                account.getNormalBalance().name(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt()
        );
    }
}