package com.doubleledger.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a deterministic SHA-256 fingerprint of an HTTP request for idempotency checks.
 * Hashes method + path + canonical JSON body, matching common API idempotency practice.
 */
@Component
public class RequestHasher {

    private static final String TRANSACTION_POST_SCOPE = "POST:/api/v1/transactions:";

    private final ObjectMapper canonicalMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hashPostTransaction(Object requestBody) {
        try {
            String canonicalBody = canonicalMapper.writeValueAsString(requestBody);
            return sha256Hex(TRANSACTION_POST_SCOPE + canonicalBody);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to hash request body.", ex);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available.", ex);
        }
    }
}
