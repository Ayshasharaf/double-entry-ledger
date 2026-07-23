package com.doubleledger.ledger;

import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.service.LedgerPostingService;
import com.doubleledger.ledger.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerPostingConcurrencyIntegrationTest extends PostgresIntegrationTestSupport {

    private static final int THREAD_COUNT = 10;
    private static final long WITHDRAWAL_AMOUNT = 1_000L;

    @Autowired
    private LedgerPostingService postingService;

    @Test
    void concurrentWithdrawals_exhaustBalanceExactlyOneFails() throws Exception {
        Account wallet = createAssetAccount("wallet");
        Account pool = createAssetAccount("pool");

        fundAccount(wallet, (THREAD_COUNT - 1) * WITHDRAWAL_AMOUNT);

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Throwable> unexpectedErrors = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREAD_COUNT; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        PostTransactionRequest request = buildTransfer(
                                wallet.getId(),
                                pool.getId(),
                                WITHDRAWAL_AMOUNT,
                                UUID.randomUUID());
                        postingService.postTransaction(request);
                        successes.incrementAndGet();
                    } catch (IllegalArgumentException ex) {
                        failures.incrementAndGet();
                    } catch (Throwable ex) {
                        unexpectedErrors.add(ex);
                    }
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(unexpectedErrors).isEmpty();
        assertThat(successes.get()).isEqualTo(THREAD_COUNT - 1);
        assertThat(failures.get()).isEqualTo(1);

        assertThat(balanceOf(wallet)).isZero();
    }

    @Test
    void crossAccountTransfers_doNotDeadlock() throws Exception {
        Account accountA = createAssetAccount("account-a");
        Account accountB = createAssetAccount("account-b");

        fundAccount(accountA, 50_000L);
        fundAccount(accountB, 50_000L);

        int rounds = 20;
        CountDownLatch ready = new CountDownLatch(rounds * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(rounds * 2)) {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < rounds; i++) {
                futures.add(submitTransfer(executor, ready, start, errors, accountA, accountB));
                futures.add(submitTransfer(executor, ready, start, errors, accountB, accountA));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(errors).isEmpty();
    }

    private Future<?> submitTransfer(ExecutorService executor,
                                     CountDownLatch ready,
                                     CountDownLatch start,
                                     List<Throwable> errors,
                                     Account from,
                                     Account to) {
        return executor.submit(() -> {
            ready.countDown();
            try {
                start.await(10, TimeUnit.SECONDS);
                postingService.postTransaction(buildTransfer(
                        from.getId(),
                        to.getId(),
                        100L,
                        UUID.randomUUID()));
            } catch (Throwable ex) {
                synchronized (errors) {
                    errors.add(ex);
                }
            }
            return null;
        });
    }
}
