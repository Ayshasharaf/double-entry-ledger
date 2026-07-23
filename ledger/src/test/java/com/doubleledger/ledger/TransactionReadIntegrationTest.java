package com.doubleledger.ledger;

import com.doubleledger.ledger.dto.JournalEntryResponse;
import com.doubleledger.ledger.dto.PostTransactionRequest;
import com.doubleledger.ledger.model.Account;
import com.doubleledger.ledger.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class TransactionReadIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired
    private RestTestClient restClient;

    private Account wallet;
    private Account pool;

    @BeforeEach
    void setUpAccounts() {
        wallet = createAssetAccount("read-wallet");
        pool = createAssetAccount("read-pool");
        fundAccount(wallet, 5_000L);
    }

    @Test
    void postTransaction_persistsMetadataAndReturnsLegs() {
        UUID idempotencyKey = UUID.randomUUID();
        PostTransactionRequest request = buildTransfer(wallet.getId(), pool.getId(), 500L, idempotencyKey);
        request.setMetadata(Map.of("order_id", "order-123", "source", "integration-test"));

        JournalEntryResponse created = postTransaction(idempotencyKey, request)
                .expectStatus().isCreated()
                .expectBody(JournalEntryResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created.metadata()).containsEntry("order_id", "order-123");
        assertThat(created.entries()).hasSize(2);
        assertThat(created.entries())
                .extracting("accountId")
                .containsExactlyInAnyOrder(wallet.getId(), pool.getId());
    }

    @Test
    void getTransaction_returnsJournalEntryWithLegsAndMetadata() {
        UUID idempotencyKey = UUID.randomUUID();
        PostTransactionRequest request = buildTransfer(wallet.getId(), pool.getId(), 250L, idempotencyKey);
        request.setMetadata(Map.of("invoice_id", "inv-99"));

        JournalEntryResponse created = postTransaction(idempotencyKey, request)
                .expectStatus().isCreated()
                .expectBody(JournalEntryResponse.class)
                .returnResult()
                .getResponseBody();

        JournalEntryResponse fetched = restClient.get()
                .uri("/api/v1/transactions/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(JournalEntryResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.metadata()).containsEntry("invoice_id", "inv-99");
        assertThat(fetched.entries()).hasSize(2);
        assertThat(fetched.entries().stream().mapToLong(leg -> leg.amountMinorUnits()).sum())
                .isEqualTo(500L);
    }

    @Test
    void getTransaction_unknownId_returns404() {
        restClient.get()
                .uri("/api/v1/transactions/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    private RestTestClient.ResponseSpec postTransaction(UUID idempotencyKey, PostTransactionRequest request) {
        return restClient.post()
                .uri("/api/v1/transactions")
                .header("Idempotency-Key", idempotencyKey.toString())
                .body(request)
                .exchange();
    }
}
