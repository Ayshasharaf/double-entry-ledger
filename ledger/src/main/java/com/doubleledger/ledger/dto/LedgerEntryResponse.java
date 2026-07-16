package com.doubleledger.ledger.dto;

import com.doubleledger.ledger.model.LedgerEntry;

import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID accountId,
        String direction,
        long amountMinorUnits,
        long balanceAfter
) {
    public static LedgerEntryResponse fromEntity(LedgerEntry entry) {
        long signedAmount = entry.getAmountMinorUnits();
        String direction = signedAmount < 0 ? "DEBIT" : "CREDIT";
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getAccountId(),
                direction,
                Math.abs(signedAmount),
                entry.getBalanceAfter()
        );
    }
}
