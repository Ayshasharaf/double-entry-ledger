package com.doubleledger.ledger.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Serializes concurrent work within the current transaction using PostgreSQL advisory locks.
 */
@Component
public class PostgresAdvisoryLockService {

    private final EntityManager entityManager;

    public PostgresAdvisoryLockService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void acquireTransactionLock(String lockKey) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .setParameter("key", lockKey)
                .getSingleResult();
    }
}
