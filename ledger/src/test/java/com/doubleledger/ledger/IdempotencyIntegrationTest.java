package com.doubleledger.ledger;

import com.doubleledger.ledger.dto.JournalEntryResponse;
import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.repository.IdempotencyKeyRepository;
import com.doubleledger.ledger.repository.JournalEntryRepository;
import com.doubleledger.ledger.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class IdempotencyIntegrationTest extends PostgresIntegrationTestSupport {

    private static final int CONCURRENT_REPLAY_THREADS = 50;

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private Account wallet;
    private Account pool;

    @BeforeEach
    void setUpAccounts() {
        wallet = createAssetAccount("idempotency-wallet");
        pool = createAssetAccount("idempotency-pool");
        fundAccount(wallet, 1_000_000L);
    }

    @Test
    void sameIdempotencyKey_concurrentPosts_createSingleJournalEntry() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        PostTransactionRequest request = buildTransfer(wallet.getId(), pool.getId(), 500L, idempotencyKey);

        CountDownLatch ready = new CountDownLatch(CONCURRENT_REPLAY_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        List<JournalEntryResponse> responses = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REPLAY_THREADS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REPLAY_THREADS; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        var result = postTransaction(idempotencyKey, request)
                                .expectBody(JournalEntryResponse.class)
                                .returnResult();
                        HttpStatusCode status = result.getStatus();
                        JournalEntryResponse body = result.getResponseBody();

                        synchronized (responses) {
                            responses.add(body);
                        }
                        if (status.value() == 201) {
                            created.incrementAndGet();
                        } else if (status.value() == 200) {
                            replayed.incrementAndGet();
                        }
                    } catch (Throwable ex) {
                        synchronized (errors) {
                            errors.add(ex);
                        }
                    }
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(errors).isEmpty();
        assertThat(created.get()).isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(CONCURRENT_REPLAY_THREADS - 1);
        assertThat(journalEntryRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
        assertThat(idempotencyKeyRepository.findById(idempotencyKey.toString())).isPresent();

        UUID expectedJournalId = responses.getFirst().id();
        assertThat(responses).allSatisfy(response ->
                assertThat(response.id()).isEqualTo(expectedJournalId));
    }

    @Test
    void sameIdempotencyKey_differentPayload_returns409() {
        UUID idempotencyKey = UUID.randomUUID();

        PostTransactionRequest first = buildTransfer(wallet.getId(), pool.getId(), 500L, idempotencyKey);
        PostTransactionRequest second = buildTransfer(wallet.getId(), pool.getId(), 750L, idempotencyKey);

        postTransaction(idempotencyKey, first).expectStatus().isCreated();
        postTransaction(idempotencyKey, second).expectStatus().isEqualTo(409);

        assertThat(journalEntryRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
    }

    @Test
    void sequentialReplay_returns200WithReplayHeader() {
        UUID idempotencyKey = UUID.randomUUID();
        PostTransactionRequest request = buildTransfer(wallet.getId(), pool.getId(), 250L, idempotencyKey);

        JournalEntryResponse created = postTransaction(idempotencyKey, request)
                .expectStatus().isCreated()
                .expectBody(JournalEntryResponse.class)
                .returnResult()
                .getResponseBody();

        var replayedResult = postTransaction(idempotencyKey, request)
                .expectStatus().isOk()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody(JournalEntryResponse.class)
                .returnResult();

        JournalEntryResponse replayedBody = replayedResult.getResponseBody();

        assertThat(replayedBody.id()).isEqualTo(created.id());
    }

    private RestTestClient.ResponseSpec postTransaction(UUID idempotencyKey,
                                                        PostTransactionRequest request) {
        return restClient.post()
                .uri("/api/v1/transactions")
                .header("Idempotency-Key", idempotencyKey.toString())
                .body(request)
                .exchange();
    }
}
