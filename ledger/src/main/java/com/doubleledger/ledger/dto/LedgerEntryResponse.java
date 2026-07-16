package com.doubleledger.ledger.dto;

import com.doubleledger.ledger.model.LedgerEntry;
import java.math.BigDecimal;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID accountId,
        String direction, // "DEBIT" or "CREDIT"
        BigDecimal amount,
        String currency
) {
    public static LedgerEntryResponse fromEntity(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getAccount().getId(),
                entry.getDirection().name(),
                entry.getAmount(),
                entry.getCurrency()
        );
    }
}