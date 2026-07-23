package com.doubleledger.ledger.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents the request payload to post a complete multi-legged ledger transaction.
 */
public class PostTransactionRequest {

    // Domain-level idempotency key to prevent processing the same economic event twice
    private UUID idempotencyKey;

    // A description of the transfer (e.g., "Subscription payment for invoice_923")
    private String description;

    // The individual splits of this transaction. They must sum to zero!
    private List<TransactionLegDto> legs;

    // Custom metadata map allowing clients to store arbitrary info, mimicking Stripe’s "metadata" field
    private Map<String, Object> metadata;

    // --- Empty Constructor ---
    public PostTransactionRequest() {}

    // --- Getters & Setters ---
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<TransactionLegDto> getLegs() { return legs; }
    public void setLegs(List<TransactionLegDto> legs) { this.legs = legs; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}