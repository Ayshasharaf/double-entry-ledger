package com.doubleledger.ledger;

import com.doubleledger.ledger.dto.JournalEntryResponse;
import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.dto.ReverseTransactionRequest;
import com.doubleledger.ledger.exception.IdempotencyConflictException;
import com.doubleledger.ledger.exception.TransactionAlreadyReversedException;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.model.JournalEntry;
import com.doubleledger.ledger.model.JournalEntryStatus;
import com.doubleledger.ledger.repository.JournalEntryRepository;
import com.doubleledger.ledger.service.LedgerPostingService;
import com.doubleledger.ledger.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class ReversalIntegrationTest extends PostgresIntegrationTestSupport {

    private static final long TRANSFER_AMOUNT = 500L;
    private static final long INITIAL_WALLET_BALANCE = 10_000L;

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    private Account wallet;
    private Account pool;

    @BeforeEach
    void setUpAccounts() {
        wallet = createAssetAccount("reversal-wallet");
        pool = createAssetAccount("reversal-pool");
        fundAccount(wallet, INITIAL_WALLET_BALANCE);
    }

    @Test
    void reverseTransaction_restoresBalancesAndMarksOriginalReversed() {
        UUID postKey = UUID.randomUUID();
        PostTransactionRequest transfer = buildTransfer(wallet.getId(), pool.getId(), TRANSFER_AMOUNT, postKey);
        JournalEntry original = postingService.postTransaction(transfer);

        long walletAfterTransfer = balanceOf(wallet);
        long poolAfterTransfer = balanceOf(pool);
        assertThat(walletAfterTransfer).isEqualTo(INITIAL_WALLET_BALANCE - TRANSFER_AMOUNT);
        assertThat(poolAfterTransfer).isEqualTo(TRANSFER_AMOUNT);

        UUID reversalKey = UUID.randomUUID();
        JournalEntry reversal = postingService.reverseTransaction(
                original.getId(),
                reversalKey,
                new ReverseTransactionRequest());

        JournalEntry reloadedOriginal = journalEntryRepository.findById(original.getId()).orElseThrow();
        assertThat(reloadedOriginal.getStatus()).isEqualTo(JournalEntryStatus.reversed);
        assertThat(reversal.getReversesJournalEntryId()).isEqualTo(original.getId());
        assertThat(reversal.getStatus()).isEqualTo(JournalEntryStatus.posted);
        assertThat(reversal.getDescription()).isEqualTo("Reversal of: " + original.getDescription());

        assertThat(balanceOf(wallet)).isEqualTo(INITIAL_WALLET_BALANCE);
        assertThat(balanceOf(pool)).isZero();
        assertThat(journalEntryRepository.findByIdempotencyKey(reversalKey)).isPresent();
    }

    @Test
    void reverseTransaction_idempotentRetry_returns200WithReplayHeader() {
        UUID postKey = UUID.randomUUID();
        JournalEntry original = postingService.postTransaction(
                buildTransfer(wallet.getId(), pool.getId(), TRANSFER_AMOUNT, postKey));

        UUID reversalKey = UUID.randomUUID();
        ReverseTransactionRequest request = new ReverseTransactionRequest();

        JournalEntryResponse created = reverseTransaction(original.getId(), reversalKey, request)
                .expectStatus().isCreated()
                .expectBody(JournalEntryResponse.class)
                .returnResult()
                .getResponseBody();

        var replayedResult = reverseTransaction(original.getId(), reversalKey, request)
                .expectStatus().isOk()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody(JournalEntryResponse.class)
                .returnResult();

        assertThat(replayedResult.getResponseBody().id()).isEqualTo(created.id());
        assertThat(replayedResult.getResponseBody().reversesJournalEntryId()).isEqualTo(original.getId());
    }

    @Test
    void reverseTransaction_secondAttemptWithNewKey_returns409() {
        UUID postKey = UUID.randomUUID();
        JournalEntry original = postingService.postTransaction(
                buildTransfer(wallet.getId(), pool.getId(), TRANSFER_AMOUNT, postKey));

        reverseTransaction(original.getId(), UUID.randomUUID(), new ReverseTransactionRequest())
                .expectStatus().isCreated();

        reverseTransaction(original.getId(), UUID.randomUUID(), new ReverseTransactionRequest())
                .expectStatus().isEqualTo(409);
    }

    @Test
    void reverseTransaction_unknownJournalEntry_returns404() {
        reverseTransaction(UUID.randomUUID(), UUID.randomUUID(), new ReverseTransactionRequest())
                .expectStatus().isNotFound();
    }

    @Test
    void reverseTransaction_cannotReverseAReversalEntry() {
        UUID postKey = UUID.randomUUID();
        JournalEntry original = postingService.postTransaction(
                buildTransfer(wallet.getId(), pool.getId(), TRANSFER_AMOUNT, postKey));
        JournalEntry reversal = postingService.reverseTransaction(
                original.getId(),
                UUID.randomUUID(),
                new ReverseTransactionRequest());

        assertThatThrownBy(() -> postingService.reverseTransaction(
                reversal.getId(),
                UUID.randomUUID(),
                new ReverseTransactionRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot reverse a reversal entry");
    }

    @Test
    void reverseTransaction_serviceLevelDuplicateKey_returnsSameReversal() {
        UUID postKey = UUID.randomUUID();
        JournalEntry original = postingService.postTransaction(
                buildTransfer(wallet.getId(), pool.getId(), TRANSFER_AMOUNT, postKey));

        UUID reversalKey = UUID.randomUUID();
        JournalEntry first = postingService.reverseTransaction(
                original.getId(), reversalKey, new ReverseTransactionRequest());
        JournalEntry second = postingService.reverseTransaction(
                original.getId(), reversalKey, new ReverseTransactionRequest());

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void reverseTransaction_alreadyReversed_throwsConflict() {
        UUID postKey = UUID.randomUUID();
        JournalEntry original = postingService.postTransaction(
                buildTransfer(wallet.getId(), pool.getId(), TRANSFER_AMOUNT, postKey));
        postingService.reverseTransaction(original.getId(), UUID.randomUUID(), new ReverseTransactionRequest());

        assertThatThrownBy(() -> postingService.reverseTransaction(
                original.getId(),
                UUID.randomUUID(),
                new ReverseTransactionRequest()))
                .isInstanceOf(TransactionAlreadyReversedException.class);
    }

    @Test
    void reverseTransaction_reusingPostIdempotencyKey_throwsConflict() {
        UUID sharedKey = UUID.randomUUID();
        JournalEntry original = postingService.postTransaction(
                buildTransfer(wallet.getId(), pool.getId(), TRANSFER_AMOUNT, sharedKey));

        assertThatThrownBy(() -> postingService.reverseTransaction(
                original.getId(),
                sharedKey,
                new ReverseTransactionRequest()))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("posting entry");
    }

    @Test
    void concurrentReversalsWithDifferentKeys_exactlyOneSucceeds() throws Exception {
        UUID postKey = UUID.randomUUID();
        JournalEntry original = postingService.postTransaction(
                buildTransfer(wallet.getId(), pool.getId(), TRANSFER_AMOUNT, postKey));

        int threadCount = 10;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpectedErrors = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        postingService.reverseTransaction(
                                original.getId(),
                                UUID.randomUUID(),
                                new ReverseTransactionRequest());
                        successes.incrementAndGet();
                    } catch (TransactionAlreadyReversedException ex) {
                        conflicts.incrementAndGet();
                    } catch (Throwable ex) {
                        synchronized (unexpectedErrors) {
                            unexpectedErrors.add(ex);
                        }
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
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threadCount - 1);
        assertThat(journalEntryRepository.findById(original.getId()).orElseThrow().getStatus())
                .isEqualTo(JournalEntryStatus.reversed);
        assertThat(balanceOf(wallet)).isEqualTo(INITIAL_WALLET_BALANCE);
    }

    private RestTestClient.ResponseSpec reverseTransaction(UUID journalEntryId,
                                                           UUID idempotencyKey,
                                                           ReverseTransactionRequest request) {
        return restClient.post()
                .uri("/api/v1/transactions/{journalEntryId}/reversals", journalEntryId)
                .header("Idempotency-Key", idempotencyKey.toString())
                .body(request)
                .exchange();
    }
}
