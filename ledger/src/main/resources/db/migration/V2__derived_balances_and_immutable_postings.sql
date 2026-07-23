-- Blocker #1: balances are derived from ledger_entries, not stored on accounts.
ALTER TABLE accounts DROP COLUMN balance_minor_units;

-- Blocker #2: posted legs must never cascade-delete; immutability enforced at DB layer.
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ledger_entries_journal_entry_id_fkey;
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ledger_entries_account_id_fkey;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_journal_entry_id_fkey
        FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ledger_entries_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION prevent_ledger_entry_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_entry_mutation();

CREATE OR REPLACE FUNCTION prevent_journal_entry_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'journal_entries are immutable: deletes are not allowed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_no_delete
    BEFORE DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_journal_entry_delete();

CREATE OR REPLACE FUNCTION prevent_journal_entry_update_except_status()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.idempotency_key IS DISTINCT FROM NEW.idempotency_key
        OR OLD.description IS DISTINCT FROM NEW.description
        OR OLD.reverses_journal_entry_id IS DISTINCT FROM NEW.reverses_journal_entry_id
        OR OLD.metadata IS DISTINCT FROM NEW.metadata
        OR OLD.posted_at IS DISTINCT FROM NEW.posted_at
        OR OLD.created_at IS DISTINCT FROM NEW.created_at
    THEN
        RAISE EXCEPTION 'journal_entries are immutable except for status updates';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutable_update
    BEFORE UPDATE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_journal_entry_update_except_status();
