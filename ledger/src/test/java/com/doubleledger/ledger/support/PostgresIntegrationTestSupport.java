package com.doubleledger.ledger.support;

import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.dto.TransactionLegDto;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.model.AccountType;
import com.doubleledger.ledger.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

@SpringBootTest
public abstract class PostgresIntegrationTestSupport {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        if (useTestcontainers()) {
            POSTGRES.start();
        }
    }

    @Autowired
    protected AccountRepository accountRepository;

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        if (useTestcontainers()) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            return;
        }

        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/ledger_db");
        registry.add("spring.datasource.username", () -> "ledger_admin");
        registry.add("spring.datasource.password", () -> "ledger1234");
    }

    private static boolean useTestcontainers() {
        if ("true".equalsIgnoreCase(System.getProperty("test.useLocalPostgres"))) {
            return false;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    protected Account createAssetAccount(String name) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName(name);
        account.setAccountType(AccountType.asset);
        account.setNormalBalance("D");
        account.setCurrency("USD");
        account.setBalanceMinorUnits(0L);
        account.setAllowOverdraft(false);
        account.setOverdraftLimitMinorUnits(0L);
        return accountRepository.save(account);
    }

    protected PostTransactionRequest buildTransfer(UUID fromAccountId,
                                                   UUID toAccountId,
                                                   long amountMinorUnits,
                                                   UUID idempotencyKey) {
        PostTransactionRequest request = new PostTransactionRequest();
        request.setIdempotencyKey(idempotencyKey);
        request.setDescription("integration-test transfer");
        request.setLegs(List.of(
                new TransactionLegDto(fromAccountId, amountMinorUnits, "CREDIT"),
                new TransactionLegDto(toAccountId, amountMinorUnits, "DEBIT")
        ));
        return request;
    }
}
