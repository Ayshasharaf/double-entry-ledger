# ledger-core — Design Document

A portfolio project to learn and demonstrate the core primitives behind
fintech backends: double-entry accounting, ACID transactions, concurrency
control, and idempotency. Built in Java 21 + Spring Boot + PostgreSQL.

---

## 1. What This Project Is, and Why

### What is a double-entry ledger?

A double-entry ledger is a bookkeeping system built around one rule:
**money never appears or disappears — it only moves.** Every financial
event is recorded as two or more balanced entries: value leaving one
account is always matched, exactly, by value arriving in another. If the
entries for a transaction don't sum to zero, the transaction is invalid
by definition — not a business error, a structural one.

This is the model used underneath real payment systems — Stripe, Wise,
Adyen, and every retail bank — as the **single source of truth** for
balances. It doesn't talk to card networks or banking rails directly; it
sits behind the business logic layer and records the internal movement
of money that those systems trigger.

```
Payment Gateway / API  →  Business Application Layer  →  ledger-core (this service)
```

### Why I'm building this

I'm a CS grad targeting backend/fintech roles, and a ledger is a good
vehicle for that because it forces you to actually use — not just know
about — the things these companies interview on:

- ACID transaction boundaries
- Row-level locking and race conditions under concurrent writes
- Idempotency (safe retries over an unreliable network)
- Exact-precision arithmetic (no floats near money)
- Designing a schema whose *constraints*, not just its application code,
  prevent corruption

This is **Block 1** of a larger system. Later blocks — an outbox/event
layer, webhook delivery, a fraud/rules engine — will consume events from
this ledger, so Block 1 is scoped to have clean boundaries even though
none of that is built yet.

### Core concepts I'm learning from it

- **The double-entry invariant** — why "debit" and "credit" are relative
  to account type, not fixed meanings of "money in/out."
- **Denormalized vs. derived state** — caching a running balance for
  read speed, and what that costs you in write-side complexity (locking).
- **Pessimistic concurrency control** — `SELECT ... FOR UPDATE` and why
  it's the simpler, more defensible choice here over optimistic
  versioning or retry loops.
- **Defense in depth** — enforcing the same invariant at both the
  application layer (fast, friendly errors) and the database layer
  (the actual backstop that can't be bypassed by a bug or a stray
  `psql` session).
- **Idempotency at two different layers** — API-response caching vs.
  domain-event deduplication, and why those are two separate problems.
- **Immutability as an audit strategy** — corrections are new entries,
  never edits or deletes.

---

## 2. Learning From How Others Build This

Before finalizing the schema, I looked at how three different classes of
systems solve the same problem, to understand which parts of my design
are "the standard shape" versus decisions I'm actually making myself.

**TigerBeetle** — a purpose-built financial database (written in Zig,
using the LMAX Disruptor pattern) that does nothing but ledger
operations, at very high throughput. It models a `Transfer` as movement
between exactly two accounts, and multi-leg transactions are composed
from several *linked* transfers rather than one wide record. It also
expresses "which side increases this account" as account-level flags
rather than a stored label.
*What I took from it:* the idea of validating a debit/credit rule
structurally rather than trusting application code, and using flexible
`user_data` fields to link back to external entities without the ledger
needing to understand them.

**Formance Ledger** — an open-source, Postgres-backed, event-sourced
ledger that uses a DSL (Numscript) to declare transactions as a set of
postings, where each posting is asset-aware. A single transaction can
fan out across many accounts and assets in one atomic operation.
*What I took from it:* the "one transaction, many legs" shape (header +
line items), which is more natural for fee-splitting or multi-party
payouts than TigerBeetle's two-account transfer model — this is the
shape I chose.

**Medici** (Node.js double-entry library) — organizes itself the same
way: a journal has child transactions/legs, and won't write anything
unless the legs net to zero. It also handles corrections by "voiding" —
posting an equal-and-opposite entry rather than touching history.
*What I took from it:* the reversal pattern (`reverses_journal_entry_id`)
instead of deletion or mutation.

**Stripe-style implementations** (various engineering write-ups on
building Stripe-like ledgers in Laravel/Rails) — consistently reinforced
three things regardless of language: store money as integers, treat a
transaction as atomic-or-nothing, and never delete — reverse.

Net result: my schema follows the **Formance/Medici shape** (one
`journal_entries` header + N `ledger_entries` legs) rather than
TigerBeetle's two-account-per-transfer shape, specifically because it
makes a payment that splits into a merchant payout + platform fee + tax
a single journal entry instead of several linked records.

---

## 3. How I'm Building It (Phases)

I'm going step by step and not skipping ahead, so each layer is
understood before the next one depends on it:

```
Phase 1 — Schema & Docker         Postgres + Flyway migrations, no app code yet
Phase 2 — Core domain models      Java entities, zero-sum validation logic
Phase 3 — Idempotency layer       Key validation + cached responses
Phase 4 — Concurrency testing     Multi-threaded tests, prove the locking works
Phase 5 — API layer               Controllers, DTOs, endpoints
Phase 6 — Docs & CI               README, DESIGN.md, GitHub Actions
```

**Where I am right now:** Phase 1 is done (migration below) and Phase 2
is in progress — the JPA entities exist (`Account`, `JournalEntry`,
`LedgerEntry`, `IdempotencyKey`, `FailedEntry`, plus the `AccountType` /
`AccountStatus` enums), but there's no repository, service, or
controller code yet, and no tests. See §6 for exactly what's built vs.
pending.

---

## 4. Terminology — Demystifying the Accounting Language

The schema borrows vocabulary from real accounting, which is worth
translating up front:

| Term | Plain-English meaning |
|---|---|
| **Asset** | What the business/user owns (cash, balances). |
| **Liability** | What the business owes to someone else. A user's balance is technically a liability *to the bank* — money owed to the user on demand. |
| **Equity** | Net worth: assets minus liabilities. |
| **Revenue / Expense** | Money earned from operating the business / money spent running it. |
| **Debit (D) / Credit (C)** | Not "money out/in." These are just **left/right**, positional labels. What they *do* to a balance depends on account type. |
| **Normal balance** | Which side (D or C) *increases* a given account type. Assets & expenses increase on debit; liabilities, equity & revenue increase on credit. |
| **Trial balance** | The audit check that total debits equal total credits system-wide. My `check_journal_entry_balances()` trigger is a real-time version of this, scoped to one transaction. |
| **Running balance (`balance_after`)** | A snapshot of the account's balance immediately after one specific entry — lets you answer "what was the balance on date X" without replaying all history. |

The practical payoff of the D/C convention: because every account type
has a fixed normal balance, a debit and a credit of the same amount on
two different account types can be represented as a single **signed
integer** (negative for debit, positive for credit). That collapses the
zero-sum check into `SUM(amount) = 0` instead of branching enum logic.

---

## 5. The Schema — What, Why, and Where the Idea Came From

The schema splits every financial event into a **header** and its
**legs**, which is the standard shape described in §2, not something
specific to this project.

### `accounts`

| Column | Why it exists |
|---|---|
| `account_type` | Lets the system eventually produce a trial balance / balance sheet, and stops the app from crediting a revenue account by mistake. |
| `normal_balance` | Explicit statement of which side increases the account. TigerBeetle achieves the same concept structurally, via account flags instead of a stored label — either is valid, the concept just has to live *somewhere*. |
| `balance_minor_units` | A cached, denormalized balance — chosen over summing `ledger_entries` on every read for performance. The tradeoff: every posting has to lock and update this row, which is your throughput ceiling on "hot" accounts. This exact bottleneck is the problem TigerBeetle was purpose-built to solve at scale. |
| `currency` | ISO 4217. Each account holds exactly one currency — multi-currency movement goes through explicit legs and clearing accounts, not a mixed-currency balance. |
| `allow_overdraft` / `overdraft_limit_minor_units` | Business-rule fields, not accounting-model fields. |
| `status` | Lets an account be frozen or closed without deleting its history. |

**Dropped from the original draft:** an optimistic-locking `version`
column running *alongside* `SELECT ... FOR UPDATE`. Running both
pessimistic and optimistic concurrency control at once doesn't add
safety — it adds a second failure mode to reason about. I picked
pessimistic locking as the single mechanism (see §1 and Postgres docs on
[`SELECT ... FOR UPDATE`](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)).

### `journal_entries` (the header)

| Column | Why it exists |
|---|---|
| `idempotency_key` | **Domain-level** dedup — don't post the same business event twice, no matter which code path it arrived through. Distinct from the separate `idempotency_keys` table below (API-layer dedup). |
| `status` + `reverses_journal_entry_id` | Corrections are handled the way Medici "voids" a journal: post an equal-and-opposite entry against the original, rather than editing or deleting it. |
| `metadata` (JSONB) | Arbitrary external references (order IDs, payment intents) — the same role TigerBeetle's `user_data` fields play, so the ledger can link outward without needing to understand the thing it's linking to. |

### `ledger_entries` (the legs) and the zero-sum invariant

Each row is one signed leg against one account, plus `balance_after` —
the running balance right after that leg. The actual "double-entry"
guarantee is enforced by a `DEFERRABLE INITIALLY DEFERRED` constraint
trigger (`check_journal_entry_balances()`) that checks, **at commit
time**, that all legs belonging to one journal entry are in a single
currency and sum to exactly zero. It's deferred because legs are
inserted one row at a time within the same transaction — checking after
each row would fail on the first insert.

This is deliberately scoped to **single-currency journal entries** for
v1. A cross-currency transaction isn't silently allowed with mismatched
amounts; it has to be expressed as matched same-currency legs on each
side through an explicit FX/clearing account — which mirrors how
Formance (asset-aware postings) and TigerBeetle (ledger-scoped accounts,
chained same-ledger transfers) both handle it.

### `idempotency_keys` vs. `journal_entries.idempotency_key`

These look redundant but guard different failure points:

- `idempotency_keys` — **API layer.** Any endpoint. If a client retries
  an HTTP call after a dropped response, this replays the exact same
  response instead of re-executing anything.
- `journal_entries.idempotency_key` — **domain layer.** Guarantees one
  business event (e.g. "charge order #123") is posted once, even if it
  somehow reaches the ledger through two different code paths.

### `failed_entries` (the forensic log)

When a submission fails a business-rule check (not a DB error — a
rejection, like breaking the zero-sum invariant or an overdraft limit),
most simple systems just return an error and the attempt is gone. This
table keeps the raw payload and the reason it failed, because in
finance, "someone attempted this and here's exactly why it didn't go
through" matters to auditors and support — silence doesn't.

---

## 6. The Model Layer — What I Built and Why

The schema above is relational; Java speaks in objects. JPA/Hibernate is
the translator between the two — annotating a class tells Hibernate how
to map it onto a table, row by row, column by column (e.g. `UUID id` ↔
`id UUID PRIMARY KEY`, `Long balanceMinorUnits` ↔ `BIGINT`, so money
never touches a floating-point type end to end).

What exists so far, one entity per table:

- **`Account`** — mirrors the `accounts` table, and also owns the
  `debit()` / `credit()` helper methods plus `validateOverdraft()`. This
  is deliberate: the *type-dependent* meaning of debit/credit (does it
  increase or decrease this account?) is domain logic, not persistence
  logic, so it lives on the entity rather than scattered through a
  service class.
- **`JournalEntry`** — the header row: idempotency key, description,
  status, and the reversal pointer.
- **`LedgerEntry`** — one leg: signed `amountMinorUnits`, plus
  `balanceAfter` as a point-in-time snapshot.
- **`IdempotencyKey`** — the API-layer response cache row.
- **`FailedEntry`** — the forensic log row (JSONB payload currently
  mapped as a plain `String`; a proper JSON type converter is still
  pending).
- **`AccountType`** / **`AccountStatus`** — enums backing the checked
  columns, mapped with `@Enumerated(EnumType.STRING)` so the database
  stores readable text (`'asset'`, `'frozen'`) instead of ordinals.

**Why validate in both Java *and* SQL, when they check the same thing?**
This is intentional defense in depth: the Java-side checks
(`validateOverdraft()`, and eventually the zero-sum check before insert)
give fast, user-facing error messages without wasting a database
round-trip. The SQL-side constraint trigger is the actual backstop —
even a bug in the Java layer, or someone hitting the database directly,
cannot leave the ledger unbalanced, because the database itself refuses
to commit a broken transaction.

---

## 7. Current Status & What's Left

**Repo layout right now:**

```
ledger/
├── src/main/java/.../ledger/
│   ├── LedgerApplication.java
│   ├── controller/        (empty — not started)
│   ├── dto/                (empty — not started)
│   ├── model/               ✅ done
│   │   ├── Account.java
│   │   ├── AccountStatus.java
│   │   ├── AccountType.java
│   │   ├── FailedEntry.java
│   │   ├── IdempotencyKey.java
│   │   ├── JournalEntry.java
│   │   └── LedgerEntry.java
│   ├── repository/         (empty — not started)
│   └── service/             (empty — not started)
└── src/main/resources/db/migration/
    └── V1__initial_ledger_schema.sql   ✅ done
```

**Done:**
- [x] Schema + Flyway migration (`V1__initial_ledger_schema.sql`)
- [x] JPA entity mappings for all five tables
- [x] `AccountType` / `AccountStatus` enums
- [x] Overdraft + debit/credit logic on `Account`

**Not started yet — space reserved for the rest of the build:**
- [ ] **Repositories** — Spring Data JPA interfaces, including the
  pessimistic write lock (`@Lock(LockModeType.PESSIMISTIC_WRITE)`)
  on account lookups.
- [ ] **Service layer** — transaction posting orchestration: open a DB
  transaction, lock accounts, apply legs, validate zero-sum,
  commit or roll back.
- [ ] **Idempotency interceptor** — reads the `Idempotency-Key` header,
  checks `idempotency_keys`, replays or rejects on hash mismatch.
- [ ] **DTOs + Controllers** — the REST surface (`POST /accounts`,
  `POST /transactions`, `POST /transactions/{id}/reversals`, etc.)
- [ ] **Unit tests** — unbalanced-transaction rejection, overdraft
  rules, idempotency replay vs. conflict.
- [ ] **Integration tests (Testcontainers)** — real Postgres, full
  posting flow, rollback behavior.
- [ ] **Concurrency test** — the centerpiece: N simultaneous
  withdrawals against a balance that can only cover one; prove a
  naive version fails, then prove the locked version passes.
- [ ] **Docker Compose + CI** — one-command local run; GitHub Actions
  for lint/unit/integration/concurrency on every push.
- [ ] **README + this doc's remaining sections** — API contract with
  curl examples, and the open design questions from the original
  brief (isolation level choice, idempotency key retention policy)
  still need to be answered and written up here.

This document will keep growing as each phase lands — the next section
to fill in is the concurrency design (§8, not yet written), once the
repository/service layer exists to test against.