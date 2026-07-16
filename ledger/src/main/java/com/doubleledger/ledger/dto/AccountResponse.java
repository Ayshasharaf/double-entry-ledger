package com.doubleledger.ledger.dto;

import com.doubleledger.ledger.model.Account;

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
    public static AccountResponse fromEntity(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getAccountType().name(),
                account.getNormalBalance(),
                account.getBalanceMinorUnits(),
                account.getCurrency(),
                account.getCreatedAt().toInstant()
        );
    }
}
