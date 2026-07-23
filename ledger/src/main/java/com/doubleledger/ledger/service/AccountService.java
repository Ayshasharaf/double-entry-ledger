package com.doubleledger.ledger.service;

import com.doubleledger.ledger.dto.AccountResponse;
import com.doubleledger.ledger.dto.CreateAccountRequest;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.model.AccountType;
import com.doubleledger.ledger.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountBalanceService accountBalanceService;

    public AccountService(AccountRepository accountRepository,
                          AccountBalanceService accountBalanceService) {
        this.accountRepository = accountRepository;
        this.accountBalanceService = accountBalanceService;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account();

        account.setId(request.getId() != null ? request.getId() : UUID.randomUUID());
        account.setName(request.getName());

        try {
            account.setAccountType(AccountType.valueOf(request.getAccountType().trim().toLowerCase()));
            account.setNormalBalance(parseNormalBalance(request.getNormalBalance()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid account type or normal balance format.");
        }

        if (request.getCurrency() == null || request.getCurrency().trim().length() != 3) {
            throw new IllegalArgumentException("Currency must be a valid 3-letter ISO code.");
        }
        account.setCurrency(request.getCurrency().toUpperCase());

        account.setAllowOverdraft(request.isAllowOverdraft());

        if (request.isAllowOverdraft() && request.getOverdraftLimitMinorUnits() < 0) {
            throw new IllegalArgumentException("Overdraft limit minor units cannot be negative.");
        }
        account.setOverdraftLimitMinorUnits(request.getOverdraftLimitMinorUnits());

        Account savedAccount = accountRepository.save(account);
        return accountBalanceService.toResponse(savedAccount);
    }

    @Transactional(readOnly = true)
    public Optional<AccountResponse> findById(UUID id) {
        return accountRepository.findById(id)
                .map(accountBalanceService::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> findAll() {
        return accountBalanceService.toResponses(accountRepository.findAll());
    }

    private static String parseNormalBalance(String input) {
        return switch (input.trim().toUpperCase()) {
            case "D", "DEBIT" -> "D";
            case "C", "CREDIT" -> "C";
            default -> throw new IllegalArgumentException("Normal balance must be D, C, DEBIT, or CREDIT.");
        };
    }
}
