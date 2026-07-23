package com.doubleledger.ledger.exception;

import java.util.UUID;

public class JournalEntryNotFoundException extends RuntimeException {

    public JournalEntryNotFoundException(UUID journalEntryId) {
        super("Journal entry not found: " + journalEntryId);
    }
}
