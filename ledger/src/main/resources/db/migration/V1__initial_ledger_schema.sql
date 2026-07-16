

-- 1. Accounts
CREATE TABLE accounts (
                          id UUID PRIMARY KEY,
                          name VARCHAR(255) NOT NULL,

                          account_type VARCHAR(20) NOT NULL
                              CHECK (account_type IN ('asset', 'liability', 'equity', 'revenue', 'expense')),
                          normal_balance CHAR(1) NOT NULL
                              CHECK (normal_balance IN ('D', 'C')),

                          currency CHAR(3) NOT NULL, -- ISO 4217
                          balance_minor_units BIGINT NOT NULL DEFAULT 0,

                          allow_overdraft BOOLEAN NOT NULL DEFAULT FALSE,
                          overdraft_limit_minor_units BIGINT NOT NULL DEFAULT 0
                              CHECK (overdraft_limit_minor_units >= 0),

                          status VARCHAR(20) NOT NULL DEFAULT 'active'
                              CHECK (status IN ('active', 'frozen', 'closed')),

                          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_currency ON accounts(currency);
CREATE INDEX idx_accounts_type ON accounts(account_type);

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- 2. Journal Entries (transaction header)
CREATE TABLE journal_entries (
                                 id UUID PRIMARY KEY,

    -- Domain-level idempotency: don't post the same business event twice,
    -- regardless of API path. Distinct from idempotency_keys below, which
    -- caches HTTP responses at the API layer.
                                 idempotency_key UUID UNIQUE NOT NULL,

                                 description VARCHAR(500) NOT NULL,
                                 status VARCHAR(50) NOT NULL DEFAULT 'posted'
                                     CHECK (status IN ('posted', 'reversed')),

    -- Points to the journal entry that reversed this one (set on the
    -- original once its reversal is posted), or the entry this one
    -- reverses (set on the reversal itself). Only one of these should
    -- be non-null per row in practice; enforce in application logic.
                                 reverses_journal_entry_id UUID REFERENCES journal_entries(id),

                                 metadata JSONB,
                                 posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_journal_entries_status ON journal_entries(status);
CREATE INDEX idx_journal_entries_posted_at ON journal_entries(posted_at);
CREATE INDEX idx_journal_entries_metadata ON journal_entries USING gin (metadata);
CREATE INDEX idx_journal_entries_reverses ON journal_entries(reverses_journal_entry_id);


-- 3. Ledger Entries (transaction legs / postings)
CREATE TABLE ledger_entries (
                                id UUID PRIMARY KEY,
                                journal_entry_id UUID NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
                                account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,

                                amount_minor_units BIGINT NOT NULL
                                    CHECK (amount_minor_units <> 0), -- signed: negative = debit, positive = credit; zero-value legs are meaningless
                                balance_after BIGINT NOT NULL,      -- account's running balance immediately after this leg

                                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_entries_account_created ON ledger_entries(account_id, created_at DESC);
CREATE INDEX idx_ledger_entries_journal_account ON ledger_entries(journal_entry_id, account_id);

-- Invariant: within a journal entry, all legs must share one currency,
-- and their signed amounts must sum to zero. This is checked at COMMIT
-- time (DEFERRED) because legs are inserted one row at a time within
-- the same transaction. v1 scope: single-currency journal entries only.
-- Multi-currency (FX) transactions are a v2 feature via explicit
-- exchange/clearing accounts, not silently allowed here.
CREATE OR REPLACE FUNCTION check_journal_entry_balances() RETURNS TRIGGER AS $$
DECLARE
v_journal_entry_id UUID;
    v_currency_count INT;
    v_sum BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_journal_entry_id := OLD.journal_entry_id;
ELSE
        v_journal_entry_id := NEW.journal_entry_id;
END IF;

SELECT COUNT(DISTINCT a.currency), COALESCE(SUM(le.amount_minor_units), 0)
INTO v_currency_count, v_sum
FROM ledger_entries le
         JOIN accounts a ON a.id = le.account_id
WHERE le.journal_entry_id = v_journal_entry_id;

IF v_currency_count > 1 THEN
        RAISE EXCEPTION 'journal_entry % mixes currencies across legs', v_journal_entry_id;
END IF;

    IF v_sum <> 0 THEN
        RAISE EXCEPTION 'journal_entry % legs do not sum to zero (got %)', v_journal_entry_id, v_sum;
END IF;

RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_check_journal_entry_balances
    AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balances();


-- 4. Idempotency Keys (API-layer response cache — any endpoint)
CREATE TABLE idempotency_keys (
                                  idempotency_key TEXT PRIMARY KEY,
                                  request_hash TEXT NOT NULL,
                                  response_status INTEGER NOT NULL,
                                  response_body TEXT NOT NULL,
                                  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


