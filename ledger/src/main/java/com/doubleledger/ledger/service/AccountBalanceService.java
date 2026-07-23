package com.doubleledger.ledger.service;

import com.doubleledger.ledger.dto.AccountResponse;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.model.AccountType;
import com.doubleledger.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountBalanceService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountBalanceService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public long computeBalance(Account account) {
        return computeBalance(account.getId(), account.getAccountType());
    }

    @Transactional(readOnly = true)
    public long computeBalance(UUID accountId, AccountType accountType) {
        long signedSum = ledgerEntryRepository.sumSignedAmountMinorUnitsByAccountId(accountId);
        return toEconomicBalance(accountType, signedSum);
    }

    public long applySignedLeg(Account account, long currentBalanceMinorUnits, long signedAmountMinorUnits) {
        long delta = account.getAccountType().isDebitNormal()
                ? -signedAmountMinorUnits
                : signedAmountMinorUnits;
        return currentBalanceMinorUnits + delta;
    }

    @Transactional(readOnly = true)
    public AccountResponse toResponse(Account account) {
        return toResponse(account, computeBalance(account));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> toResponses(List<Account> accounts) {
        if (accounts.isEmpty()) {
            return List.of();
        }

        List<UUID> accountIds = accounts.stream().map(Account::getId).toList();
        Map<UUID, Long> signedSumsByAccountId = sumSignedAmountsByAccountIds(accountIds);

        return accounts.stream()
                .map(account -> toResponse(
                        account,
                        toEconomicBalance(
                                account.getAccountType(),
                                signedSumsByAccountId.getOrDefault(account.getId(), 0L))))
                .toList();
    }

    private Map<UUID, Long> sumSignedAmountsByAccountIds(List<UUID> accountIds) {
        Map<UUID, Long> signedSumsByAccountId = new HashMap<>();
        for (Object[] row : ledgerEntryRepository.sumSignedAmountMinorUnitsByAccountIds(accountIds)) {
            signedSumsByAccountId.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return signedSumsByAccountId;
    }

    private static AccountResponse toResponse(Account account, long balanceMinorUnits) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getAccountType().name(),
                account.getNormalBalance(),
                balanceMinorUnits,
                account.getCurrency(),
                account.getCreatedAt().toInstant()
        );
    }

    static long toEconomicBalance(AccountType accountType, long signedSumMinorUnits) {
        return accountType.isDebitNormal() ? -signedSumMinorUnits : signedSumMinorUnits;
    }
}
