# DoubleLedger — Architecture Teaching Guide

> **How to use this doc:** Work through sections 1 → 8 in order. After each section, answer the **Check Yourself** questions before moving on. This is a teaching exercise — the goal is judgment you can reuse, not memorization.

**Companion docs:** [CODEBASE_FLOW.md](./CODEBASE_FLOW.md) (line-by-line trace), [UNDERSTANDING.md](./UNDERSTANDING.md) (accounting concepts).

---

## Table of contents

1. [High-level architecture](#1-high-level-architecture)
2. [Package structure](#2-package-structure)
3. [Domain model and OOP principles](#3-domain-model-and-oop-principles)
4. [Spring-specific mechanics](#4-spring-specific-mechanics)
5. [Core design decisions](#5-core-design-decisions)
6. [Design patterns actually present](#6-design-patterns-actually-present)
7. [Check yourself (per section)](#7-check-yourself-questions)
8. [Final synthesis — reusable checklist](#8-final-synthesis--reusable-checklist)
9. [Pattern recognition cheat sheet](#9-pattern-recognition-cheat-sheet)
10. [Coding syntax & Spring idioms in this repo](#10-coding-syntax--spring-idioms-in-this-repo)
11. [OOP principles — spot them in code](#11-oop-principles--spot-them-in-code)
12. [Side-by-side comparisons](#12-side-by-side-comparisons)
13. [How to read the codebase (learning path)](#13-how-to-read-the-codebase-learning-path)
14. [Recipes — copy these shapes for new features](#14-recipes--copy-these-shapes-for-new-features)

---

## 1. High-level architecture

### The one-sentence version

**HTTP requests enter at the top, business rules sit in the middle, and PostgreSQL is the final authority at the bottom.**

Think of it like a restaurant:

| Layer | Restaurant analogy | This codebase |
|-------|-------------------|---------------|
| **Controller** | Waiter — takes your order, brings the plate | `LedgerController` |
| **Service** | Chef — knows the recipes and rules | `LedgerPostingService`, `IdempotencyService`, etc. |
| **Repository** | Pantry clerk — fetches/stores ingredients | `AccountRepository`, `JournalEntryRepository`, … |
| **Domain model** | Recipe cards — what an Account *is* | `Account`, `JournalEntry`, `LedgerEntry` |
| **Database** | Walk-in freezer — permanent storage + hard rules | PostgreSQL + Flyway migrations + triggers |

### ASCII diagram — request flow (POST /transactions)

```
  Client (curl / mobile app)
           │
           │  HTTP JSON + Idempotency-Key header
           ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  CONTROLLER LAYER                                           │
  │  LedgerController                                           │
  │  • Parse HTTP → DTO (PostTransactionRequest)                │
  │  • Hash the request body (RequestHasher)                    │
  │  • Delegate — does NOT decide business rules                  │
  └──────────────────────────┬──────────────────────────────────┘
                             │
                             ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  API IDEMPOTENCY LAYER                                      │
  │  IdempotencyService                                         │
  │  • "Have we seen this exact HTTP call before?"              │
  │  • Caches serialized JSON response in idempotency_keys      │
  └──────────────────────────┬──────────────────────────────────┘
                             │
                             ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  DOMAIN / BUSINESS LAYER                                    │
  │  LedgerPostingService  ← the brain                          │
  │  AccountBalanceService ← balance math                       │
  │  • Validate legs sum to zero                                │
  │  • Lock accounts (SELECT FOR UPDATE)                        │
  │  • Check overdraft rules                                    │
  │  • Write journal_entries + ledger_entries                   │
  └──────────────────────────┬──────────────────────────────────┘
                             │
                             ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  REPOSITORY LAYER (Spring Data JPA interfaces)              │
  │  AccountRepository, JournalEntryRepository, LedgerEntryRepo │
  │  • SQL/JPQL queries only — no business logic                │
  └──────────────────────────┬──────────────────────────────────┘
                             │
                             ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  DOMAIN ENTITIES (JPA @Entity classes)                      │
  │  Account, JournalEntry, LedgerEntry, IdempotencyKey         │
  │  • Map Java objects ↔ table rows                            │
  │  • Small domain methods (e.g. Account.validateBalance)      │
  └──────────────────────────┬──────────────────────────────────┘
                             │
                             ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  PostgreSQL                                                 │
  │  • Tables: accounts, journal_entries, ledger_entries,     │
  │            idempotency_keys                                 │
  │  • Triggers: zero-sum check, immutability, updated_at       │
  │  • Advisory locks: pg_advisory_xact_lock                    │
  └─────────────────────────────────────────────────────────────┘
```

### Why layering exists — Separation of Concerns

Each layer has **one kind of reason to change**:

- Controller changes when the **API contract** changes (new field in JSON, new endpoint).
- Service changes when **business rules** change (new overdraft policy, new reversal rules).
- Repository changes when **how you query** changes (new index, different SQL).
- Database changes when **data integrity rules** must be enforced even if the app has bugs.

**Principle:** *Separation of Concerns* — each module handles one aspect of the system.

### What goes wrong without layers

#### ❌ Controller talks directly to Repository

Example of what this codebase *used to* do wrong (now fixed):

```java
// OLD — LedgerController.createAccount saved directly via AccountRepository
// NEW — LedgerController delegates to AccountService
AccountResponse response = accountService.createAccount(request);
```

If every endpoint talked to repositories directly:

- Business rules (overdraft validation, frozen account checks) get **copy-pasted** into controllers.
- You can't unit-test rules without spinning up HTTP.
- Switching from REST to gRPC means **rewriting all business logic**.
- One developer "fixes" a bug in one controller; another endpoint still has the old bug.

#### ❌ Service A reaches into Service B's Repository

Imagine `LedgerPostingService` calling `IdempotencyKeyRepository` directly:

- **Encapsulation breaks** — idempotency caching becomes everyone's problem.
- Two services can write conflicting cache rows.
- When idempotency logic changes, you hunt through every service.

**Rule:** Service A calls Service B's **public methods**, not B's repositories.

#### ✅ What this codebase does well

- `LedgerPostingService` owns posting logic; controllers never touch `LedgerEntryRepository`.
- `IdempotencyService` owns HTTP replay; posting service only knows domain idempotency keys.
- `AccountService` owns account creation and reads; controllers never touch `AccountRepository`.

### Two idempotency layers (important — easy to miss)

This system has **two separate idempotency mechanisms**. They solve different problems:

| Layer | Table / column | Key type | Purpose |
|-------|---------------|----------|---------|
| **API** | `idempotency_keys` table | String (header value) | "Return the same HTTP response for retries" |
| **Domain** | `journal_entries.idempotency_key` | UUID | "Never post the same economic event twice" |

Why both? A client might retry with the same header but the server crashed *after* posting but *before* caching the HTTP response. The domain key still prevents double-spend. The API key still gives fast replays without re-running business logic.

---

## 2. Package structure

### Folder map

```
com.doubleledger.ledger/
├── LedgerApplication.java      ← Spring Boot entry point
├── controller/                 ← HTTP in/out only
│   ├── LedgerController.java
│   └── GlobalExceptionHandler.java
├── dto/                        ← JSON shapes (request/response)
├── service/                    ← Business logic
├── util/                       ← Stateless helpers (RequestHasher)
├── audit/                      ← Async forensic logging
├── persistence/                ← DB-specific infrastructure (advisory locks)
├── repository/                 ← Database access (interfaces)
├── model/                      ← JPA entities + enums
├── config/                     ← Spring @Configuration beans
└── exception/                  ← Domain-specific exceptions
```

### Rule for each package — "where does new code go?"

| Package | Put here if… | Do NOT put here… |
|---------|-------------|------------------|
| `controller` | Mapping HTTP ↔ Java, status codes, headers | SQL, balance math, locking |
| `dto` | Shapes of JSON request/response bodies | JPA annotations, business rules |
| `service` | Orchestration, validation, transactions | HTTP annotations, raw SQL (usually) |
| `audit` | Cross-cutting observability (forensic logs) | Business rules, HTTP |
| `persistence` | DB-specific infrastructure (advisory locks) | Domain validation |
| `util` | Stateless helpers (hashing, formatting) | Stateful beans, DB access |
| `repository` | Queries, `@Lock`, custom JPQL | "Is this overdraft allowed?" logic |
| `model` | Entity fields, small domain methods tied to that entity | HTTP or cross-entity orchestration |
| `config` | Bean definitions (`@Bean`, thread pools) | Business logic |
| `exception` | Typed exceptions + (handled in controller advice) | Generic `RuntimeException` everywhere |

**Decision rule for new code:** Ask *"If I switched from REST to a message queue, would this code still make sense?"*

- Yes → `service` or below
- No → `controller` or `dto`

### Inconsistencies — resolved

| Issue | Resolution |
|-------|------------|
| No `AccountService` | Added `AccountService`; controller delegates all account endpoints |
| `RequestHasher` in `service/` | Moved to `util/` |
| Dead `findLatestEntryForAccount` | Removed; balances use `SUM` aggregation |
| Unused `AsyncConfig` | `ForensicAuditService` delegates to `@Async AsyncForensicAuditLogger` |
| N+1 on `GET /accounts` | `AccountBalanceService.toResponses()` batch-sums in one query |
| Forensic logging in `service/` | Moved to `audit/` package |
| `@PersistenceContext` field injection | Extracted `PostgresAdvisoryLockService` with constructor-injected `EntityManager` |

All previously flagged inconsistencies are resolved.

---

## 3. Domain model and OOP principles

> **Note:** There is no class named `Transaction`. A **JournalEntry** *is* the transaction header. **LedgerEntry** rows are the legs.

### Account

**Single Responsibility:** Holds account *metadata* and *per-account spending rules* (frozen, overdraft).

**Encapsulation:**

- No `balance` field on the entity anymore (V2 migration removed `balance_minor_units`).
- Balance is **derived** from `ledger_entries` — you cannot accidentally do `account.setBalance(999)`.
- `validateBalance(long)` is the gatekeeper: frozen check + overdraft limit. Callers must compute balance externally, then ask "is this new balance legal?"

**Inheritance / polymorphism:** **Not used.** `AccountType` is an enum with `isDebitNormal()`. Plain enum switch — no `AssetAccount extends Account`.

- **Why that's OK here:** All account types share the same fields. Only debit/credit *interpretation* differs — one boolean method on the enum is enough.
- **Open/Closed limitation:** Adding a new account type (e.g. `contra_asset`) requires editing `AccountType` enum and possibly DB CHECK constraint. **Not open for extension without modification.**

**Honest note:** `normal_balance` column duplicates information already implied by `account_type`. Client can send mismatched type/balance — the app doesn't cross-check them.

### JournalEntry

**Single Responsibility:** Represents one business event (one "transaction") — header/metadata only, not individual legs.

**Encapsulation:** Mostly a data holder (getters/setters). Status transitions (`posted` → `reversed`) are orchestrated by `LedgerPostingService`, not methods on the entity.

**Immutability:** DB trigger `trg_journal_entries_immutable_update` allows **only** `status` to change after insert. Everything else is frozen.

### LedgerEntry

**Single Responsibility:** One leg of a journal entry — signed amount + snapshot of balance after this leg.

**Encapsulation:**

- `amountMinorUnits` is signed at storage: negative = debit, positive = credit.
- **Fully immutable** at DB level — UPDATE/DELETE blocked by trigger.

**Why store `balance_after`?** Audit trail — you can prove what the balance was at each step without replaying all history. Denormalized for read speed and forensic clarity.

### IdempotencyKey

**Single Responsibility:** HTTP response cache row — not a domain concept, purely API infrastructure.

### AccountType (enum)

**Single Responsibility:** Classify accounts + answer "is debit the normal balance side?"

```java
public boolean isDebitNormal() {
    return this == asset || this == expense;
}
```

This replaces a class hierarchy. **Composition over inheritance** — the enum composes behavior via methods, not subclasses.

### Dependency Inversion — concrete example

High-level `LedgerPostingService` depends on **abstractions** (Spring Data interfaces), not concrete JDBC:

```java
public LedgerPostingService(AccountRepository accountRepository,
                            JournalEntryRepository journalEntryRepository,
                            LedgerEntryRepository ledgerEntryRepository, ...)
```

`AccountRepository` is an interface extending `JpaRepository`. Spring injects a runtime proxy. The service never knows it's Hibernate underneath.

**Principle:** *Dependency Inversion* — depend on interfaces, not implementations.

**Limitation:** There are no custom repository interfaces beyond Spring Data — you can't swap in a fake `AccountRepository` without Spring Test. For unit tests, this codebase uses integration tests against real Postgres instead.

---

## 4. Spring-specific mechanics

### Dependency Injection (DI)

**What actually happens:**

1. Spring scans `@SpringBootApplication` package at startup.
2. Classes with `@Service`, `@Repository`, `@Component`, `@RestController` become **beans**.
3. Constructor parameters are resolved automatically — Spring finds the matching bean types.
4. At runtime, you get a **singleton** instance (one shared object for the whole app).

**Why constructor injection (used everywhere here) over field `@Autowired`:**

| Constructor injection | Field injection |
|----------------------|-----------------|
| Dependencies are `final` — immutable after construction | Fields can be changed/reflection-hacked |
| Must provide all deps to create object — fails fast | Object can exist in half-initialized state |
| Easy to test: `new Service(mockRepo)` | Need Spring context or reflection |

**In this repo:** All services use constructor injection. PostgreSQL advisory locks are centralized in `PostgresAdvisoryLockService`, which receives `EntityManager` via constructor — no field injection.

### @Transactional — what it really does

Spring wraps `@Transactional` beans in a **proxy**. When you call `postingService.postTransaction()` from *another bean*, the proxy:

1. Opens a DB connection / transaction
2. Calls your real method
3. Commits on success, rolls back on unchecked exception

**Propagation (default `REQUIRED`):** When `IdempotencyService.executePostTransaction` calls `postingService.postTransaction`, both join the **same** transaction — one commit for the whole operation.

**Isolation:** Not explicitly set → database default (PostgreSQL `READ COMMITTED`). Combined with `SELECT FOR UPDATE`, this is correct for financial locking.

**Self-invocation pitfall:**

```java
@Transactional
public void outer() {
    inner();  // ← Does NOT go through proxy! No transaction boundary.
}

@Transactional
public void inner() { ... }
```

**Does this codebase fall in?** Mostly **no** — private helpers (`executePosting`, `applySignedLegs`) are called from public `@Transactional` methods in the same class. That's fine: they inherit the outer transaction. The pitfall would be if a *public* `@Transactional` method called another *public* `@Transactional` method on `this`.

**Nested services:** Controller → `IdempotencyService` → `LedgerPostingService` — all cross-bean calls, proxies work correctly.

**Controller `@Transactional(readOnly = true)`** on GET endpoints: hints Hibernate to skip flush, can optimize read paths. Slightly unusual on controllers (often on service), but harmless here.

### JPA / Hibernate specifics

**Entity mapping:** `@Entity` + `@Table(name = "...")` + `@Column` map Java ↔ SQL. Enums use `@Enumerated(EnumType.STRING)` — stored as readable text, not opaque integers.

**Relationships:** Intentionally **absent**. No `@ManyToOne` from `LedgerEntry` to `Account`. Only UUID foreign keys. Why?

- Avoids accidental lazy-loading and N+1 in wrong places
- Keeps entities simple
- Trade-off: no navigational queries like `ledgerEntry.getAccount().getName()`

**Lazy vs eager:** Not really used (no relationship annotations). Every fetch is explicit via repository methods.

**N+1 on list accounts — fixed:** `AccountBalanceService.toResponses()` loads all signed sums in one grouped query.

**Locking:**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
List<Account> findAllByIdsForUpdate(...)
```

Generates `SELECT ... FOR UPDATE`. Accounts are locked **in sorted UUID order** in `applySignedLegs` to prevent deadlocks — tested in `LedgerPostingConcurrencyIntegrationTest`.

### Bean scope and shared state

All beans are **singletons** (default). Checked for request-scoped state bugs:

| Bean | Holds mutable state? | Safe? |
|------|---------------------|-------|
| `LedgerPostingService` | No instance fields except deps | ✅ |
| `RequestHasher` | Immutable `canonicalMapper` | ✅ |
| `ForensicAuditService` | Only `ObjectMapper` | ✅ |
| `EntityManager` (injected) | Thread-local via Spring | ✅ |

**No request-scoped state leaked into singletons.** Good.

---

## 5. Core design decisions

### 5.1 Idempotency key strategy

**Problem:** Network retries can double-charge a customer.

**Naive alternative:** App-only `if (exists) return` check before insert.

**Why rejected:** Two concurrent requests both pass the check → two inserts → double spend. Classic race condition.

**Chosen approach — defense in depth:**

1. **PostgreSQL advisory lock** (`pg_advisory_xact_lock`) — serializes concurrent requests with same key within transaction
2. **UNIQUE constraint** on `journal_entries.idempotency_key` — DB rejects duplicate even if app logic fails
3. **API cache table** — fast replay of exact HTTP response + request hash mismatch → 409

**Migration away:** Would need distributed idempotency store (Redis) if you go multi-region without shared Postgres advisory locks.

### 5.2 Locking strategy

| Option | Pros | Cons | Used here? |
|--------|------|------|------------|
| **SELECT FOR UPDATE** | Simple, strong consistency | Blocks readers/writers on hot accounts | ✅ Yes |
| **Optimistic locking (@Version)** | No locks, fast reads | Retries under contention; bad UX for money | ❌ |
| **Advisory locks** | App-level mutex without row lock | Doesn't protect balance by itself | ✅ For idempotency + reversal target |
| **Serializable isolation** | DB guarantees no anomalies | Performance, deadlocks | ❌ |

**Why FOR UPDATE for accounts:** Posting must read current balance, validate, write legs — all atomically. Optimistic would mean many retries when 10 threads hit the same wallet (see concurrency test).

**Deadlock prevention:** Lock accounts in **sorted UUID order** before touching them.

### 5.3 Balance representation

**V1:** Stored `balance_minor_units` on `accounts` — fast reads, risk of drift from ledger.

**V2 (current):** **Derived** — `SUM(ledger_entries.amount_minor_units)` per account, converted to "economic balance" via `AccountType.isDebitNormal()`.

**Risk if mismatched:** If you stored balance *and* derived it, they could diverge after bugs. V2 correctly picked one source of truth: **the ledger entries**.

**Performance trade-off:** Every balance read hits aggregation query. `balance_after` on each leg helps audit but isn't used for current balance computation (uses full SUM).

**Snapshot field:** `balance_after` on each leg is a **point-in-time snapshot**, not the live balance source.

### 5.4 Reversal design

**Problem:** Undo a posted transaction.

**Naive alternatives:**

- Delete the original legs → **breaks audit trail**, triggers block it anyway
- Mutate amounts on existing rows → **immutable ledger violated**

**Chosen:** Create a **new** `JournalEntry` with negated leg amounts. Mark original `status = reversed`.

**Principle:** *Append-only event log* — never erase history, only add compensating events.

### 5.5 Zero-sum enforcement

**Problem:** Legs must sum to zero (double-entry invariant).

**App check:** `validateLegsBalanceToZero` in `LedgerPostingService` — fast fail before DB.

**DB trigger:** `trg_check_journal_entry_balances` — DEFERRED constraint trigger fires at COMMIT.

**Why DB trigger, not app only?**

- Another client, admin script, or future bug could insert legs bypassing Java
- Legs are inserted one row at a time — an AFTER ROW trigger on first insert would see partial sum; **DEFERRABLE INITIALLY DEFERRED** waits until commit
- **Principle:** *Defense in depth* — business rules at app layer, invariants at DB layer

---

## 6. Design patterns actually present

| Pattern | Where | Problem it solves |
|---------|-------|-------------------|
| **Repository** | `AccountRepository`, etc. | Hide SQL/persistence from business logic |
| **DTO** | `PostTransactionRequest`, `AccountResponse` | Decouple API contract from DB schema |
| **Template Method-ish** | `IdempotencyService.executeIdempotent` | Shared idempotency flow for post vs reverse |
| **Strategy-ish** | `AccountType.isDebitNormal()` + `toEconomicBalance` | Different debit/credit interpretation without subclass explosion |
| **Record (Java)** | `JournalEntryResponse`, `SignedLeg` | Immutable data carriers |

**NOT present (don't claim these):**

- **Factory** — no `AccountFactory`; controllers construct `Account` with `new`
- **Builder** — no builder classes
- **Observer/Event sourcing** — no domain events published
- **Clean Strategy interface** — enum method isn't a Strategy pattern in the GoF sense

**Half-implemented:** None — async forensic logging is wired via `AsyncForensicAuditLogger` + `forensicLogExecutor`.

---

## 7. Check yourself questions

### After Section 1 — Architecture

1. A teammate wants to add overdraft validation inside `LedgerController` "because it's only one `if` statement." What layering principle does that violate, and what concrete bug could appear six months later?

2. This app has two idempotency layers. A retry arrives after the journal entry was saved but before the API cache row was written. Which layer prevents double-spend, and which layer returns the cached HTTP response on the *next* retry?

3. Draw the path of `POST /api/v1/transactions` from HTTP to PostgreSQL without looking at the code. Name every class in order.

### After Section 2 — Packages

1. You need to add `PATCH /accounts/{id}/freeze`. Which package gets the new code, and why shouldn't the rule live in the controller?

2. Name two inconsistencies in this repo's package boundaries. Are they harmless today or real tech debt?

3. What's the test for "does this class belong in `dto` or `model`?"

### After Section 3 — Domain model

1. Why was `balance_minor_units` removed from `accounts` in V2? What bug class does a stored balance enable?

2. `Account` has no `debit()`/`credit()` methods anymore. Where does balance mutation logic live now, and why?

3. Is this codebase Open/Closed for new account types? What would you change to add `contra_asset`?

### After Section 4 — Spring

1. Explain why `IdempotencyService` calling `postingService.postTransaction()` participates in one transaction, but `this.postTransaction()` calling `this.executePosting()` would NOT create a new transaction even if `executePosting` were `@Transactional`.

2. Where is the N+1 query problem in this codebase — and how was it fixed?

3. Why was advisory lock SQL extracted into `PostgresAdvisoryLockService` instead of field-injecting `EntityManager` in multiple services?

### After Section 5 — Design decisions

1. Why sort account UUIDs before `SELECT FOR UPDATE`? What test proves this matters?

2. Why is the zero-sum trigger DEFERRABLE instead of firing immediately on each INSERT?

3. Why negate leg amounts for reversal instead of deleting the original `ledger_entries`?

### After Section 6 — Patterns

1. Point to the Repository pattern — what would `LedgerPostingService` look like if it used JDBC directly?

2. What's the difference between the DTO `TransactionLegDto` (DEBIT/CREDIT strings) and the entity `LedgerEntry` (signed long)? Why both?

---

## 8. Final synthesis — reusable checklist

When starting a **new** Spring Boot service, make these decisions **in this order** (mirroring how this repo evolved):

### Step 1 — Define your core invariant

- **This repo:** "Money is conserved — debits equal credits; ledger is append-only."
- **Your question:** What is the one rule that must *never* break, even if the app has bugs?

### Step 2 — Schema first (Flyway)

- **This repo:** Tables + CHECK constraints + DEFERRED triggers before Java logic.
- **Your question:** Which invariants belong in the DB because they're safety-critical?

### Step 3 — Draw layer boundaries

- Controller = HTTP only
- Service = orchestration + rules
- Repository = queries
- **Your question:** Can I swap the delivery mechanism (REST → queue) without rewriting rules?

### Step 4 — Choose write path concurrency

- **This repo:** Pessimistic row locks + sorted lock order + advisory locks for idempotency.
- **Your question:** Read-modify-write on shared rows? → pessimistic or optimistic with retry policy.

### Step 5 — Choose idempotency model

- **This repo:** API cache + domain unique key + advisory lock + UNIQUE constraint.
- **Your question:** What happens on retry at each layer (client, load balancer, worker)?

### Step 6 — Choose source of truth for derived state

- **This repo:** Ledger entries sum to balance; no stored balance column.
- **Your question:** Can my derived state drift? If yes, pick one writer.

### Step 7 — Choose mutation model

- **This repo:** Append-only + compensating entries for reversal.
- **Your question:** Do auditors need to replay history? → avoid UPDATE/DELETE on financial rows.

### Step 8 — Entity design

- **This repo:** Flat entities, UUID FKs, no JPA relationships, enums for types.
- **Your question:** Will `@ManyToOne` lazy loading help or hurt my locking/read paths?

### Step 9 — Spring wiring

- Constructor injection, `@Transactional` on service entry points, `open-in-view=false`.
- **Your question:** Where is my transaction boundary? (Should be service layer, not controller — mostly true here.)

### Step 10 — Test the scary paths

- **This repo:** Concurrency integration tests, idempotency race tests, Testcontainers Postgres.
- **Your question:** What race condition would cost money? Write that test first.

### Step 11 — Honest limitations doc

- **This repo should admit:** No auth, no multi-currency FX.
- **Your question:** What did we defer, and what's the failure mode?

---

## Quick reference — API endpoints

| Method | Path | Service path |
|--------|------|--------------|
| POST | `/api/v1/accounts` | Controller → AccountService |
| GET | `/api/v1/accounts/{id}` | Controller → AccountService |
| GET | `/api/v1/accounts` | Controller → AccountService (batch balance query) |
| POST | `/api/v1/transactions` | Controller → IdempotencyService → LedgerPostingService |
| GET | `/api/v1/transactions/{id}` | Controller → JournalEntryResponseService |
| POST | `/api/v1/transactions/{id}/reversals` | Controller → IdempotencyService → LedgerPostingService |

---

## 9. Pattern recognition cheat sheet

Use this when you open any file and ask: **"What kind of thing is this?"**

### When you see… → it means…

| You see this | Layer / role | Real example | OOP / design idea |
|--------------|--------------|--------------|-------------------|
| `@RestController` + `@GetMapping` / `@PostMapping` | HTTP adapter | `LedgerController` | **Adapter** — translates outside world ↔ inside app |
| `ResponseEntity<SomeDto>` | HTTP adapter | `createAccount` returns 201 + body | Controller chooses status code, not business rules |
| `class FooRequest` / `FooResponse` in `dto/` | API contract | `PostTransactionRequest` | **DTO** — shape of JSON, not DB |
| `record FooResponse(...)` + `fromEntity()` | Immutable output DTO | `JournalEntryResponse` | **Factory method** on the DTO itself |
| `@Entity` + `@Table` | Persistence model | `Account`, `LedgerEntry` | Maps row ↔ object; not the public API |
| `interface XxxRepository extends JpaRepository` | Data access | `AccountRepository` | **Repository** — "give me rows" |
| `@Lock(PESSIMISTIC_WRITE)` on repository method | Concurrency at DB | `findAllByIdsForUpdate` | "I need exclusive access before I read-modify-write" |
| `@Service` + `@Transactional` on public method | Business orchestration | `LedgerPostingService.postTransaction` | **Transaction boundary** + rules |
| `@Transactional(readOnly = true)` | Read-only query path | `AccountService.findAll` | Hint: no writes, safer for reads |
| `private static` helper in service | Pure logic, no deps | `parseNormalBalance`, `validateLegsBalanceToZero` | **SRP** — keep method small; easy to test mentally |
| `private record SignedLeg(...)` inside service | Internal value object | `LedgerPostingService` | Not exposed outside; **information hiding** |
| `Supplier<T>` passed into a method | Callback / deferred work | `IdempotencyService` + `() -> postingService.postTransaction(...)` | **Inversion of control** — caller defines *when* work runs |
| `@ControllerAdvice` + `@ExceptionHandler` | Cross-cutting HTTP errors | `GlobalExceptionHandler` | One place maps exceptions → status codes |
| Custom exception extending `RuntimeException` | Domain error signal | `IdempotencyConflictException` | Typed failures; not generic strings |
| `@Component` in `util/` or `persistence/` | Infrastructure helper | `RequestHasher`, `PostgresAdvisoryLockService` | Not business logic |
| `@Async("beanName")` on another class's method | Background work | `AsyncForensicAuditLogger` | Must call **through Spring proxy** (another bean) |
| Flyway `V1__`, `V2__` SQL + triggers | Source of truth | `check_journal_entry_balances` | **Defense in depth** — DB enforces invariants |
| `Optional<T>` return from service | "Might not exist" | `AccountService.findById` | Avoid null; map to 404 in controller |
| `catch (DataIntegrityViolationException)` then re-query | Race-tolerant idempotency | `LedgerPostingService`, `IdempotencyService` | **At-least-once** delivery handled safely |

### The 5 shapes that repeat everywhere

```
Shape A — HTTP in
  Controller → parse header/body → call one service method → ResponseEntity

Shape B — HTTP + idempotency
  Controller → hash request → IdempotencyService.execute*(key, hash, () -> work)

Shape C — Business write
  @Transactional service → validate → lock → mutate → save → return entity

Shape D — Entity → API
  Service loads @Entity → DTO.fromEntity() or balanceService.toResponse()

Shape E — Fail gracefully
  throw IllegalArgumentException (400) | custom XxxException (404/409) | GlobalExceptionHandler
```

---

## 10. Coding syntax & Spring idioms in this repo

### Spring annotations — what to memorize

| Annotation | On what | Mechanism in one line |
|------------|---------|------------------------|
| `@SpringBootApplication` | `LedgerApplication` | Starts component scan + auto-config |
| `@RestController` | Controller | `@Controller` + JSON body serialization |
| `@RequestMapping("/api/v1")` | Controller class | Prefix for all routes in that class |
| `@PostMapping` / `@GetMapping` | Controller method | Maps HTTP verb + path |
| `@RequestHeader("Idempotency-Key")` | Parameter | Pulls header string |
| `@PathVariable UUID id` | Parameter | Path segment → typed variable |
| `@RequestBody` | Parameter | JSON → Java object (Jackson) |
| `@Valid` | Parameter | Run Bean Validation (when annotations exist on DTO) |
| `@Service` | Class | Register as singleton bean |
| `@Repository` | Interface | Spring Data JPA generates implementation |
| `@Component` | Class | Generic bean (`util`, `audit`, `persistence`) |
| `@Configuration` + `@Bean` | Config class | Manual bean definition (`forensicLogExecutor`) |
| `@Transactional` | Service method | Proxy wraps method in DB transaction |
| `@ControllerAdvice` | Class | Global exception → HTTP mapping |
| `@EnableAsync` | Application | Enables `@Async` proxy processing |

### Java syntax patterns used here (learn to recognize)

**1. Constructor injection (preferred everywhere)**

```java
public AccountService(AccountRepository accountRepository,
                      AccountBalanceService accountBalanceService) {
    this.accountRepository = accountRepository;
    this.accountBalanceService = accountBalanceService;
}
```

→ Dependencies are `final`, required at construction, easy to test.

**2. Method reference in streams**

```java
accountRepository.findById(id).map(accountBalanceService::toResponse);
```

→ Same as `account -> accountBalanceService.toResponse(account)`.

**3. Lambda as callback (Supplier)**

```java
idempotencyService.executePostTransaction(key, hash,
    () -> postingService.postTransaction(request));
```

→ `Supplier<JournalEntry>` = "run this later, inside my idempotency wrapper."

**4. Java `record` for immutable bundles**

```java
public record IdempotentOperationOutcome(JournalEntryResponse response, boolean replayed) {}
```

→ Data carrier + auto `equals`/`hashCode`/`toString`.

**5. `switch` expression (Java 21)**

```java
return switch (input.trim().toUpperCase()) {
    case "D", "DEBIT" -> "D";
    case "C", "CREDIT" -> "C";
    default -> throw new IllegalArgumentException("...");
};
```

→ Expression returns a value; `default` must cover all cases or throw.

**6. `.stream().map().toList()`**

```java
return accounts.stream()
    .map(account -> toResponse(account, balance))
    .toList();
```

→ Transform a list without manual loops (used for responses, legs, account IDs).

**7. `Optional` chain for HTTP 404**

```java
return accountService.findById(id)
    .map(response -> new ResponseEntity<>(response, HttpStatus.OK))
    .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
```

→ If empty, 404; if present, 200 + body.

**8. Static factory on DTO**

```java
public static JournalEntryResponse fromEntity(JournalEntry journalEntry, List<LedgerEntryResponse> entries)
```

→ Conversion logic lives on the **output** type, not scattered in controllers.

### Naming conventions in this project

| Pattern | Meaning | Example |
|---------|---------|---------|
| `*Request` | Incoming JSON | `CreateAccountRequest` |
| `*Response` | Outgoing JSON | `AccountResponse` |
| `*Service` | Business logic | `LedgerPostingService` |
| `*Repository` | DB access | `JournalEntryRepository` |
| `*Dto` | Nested leg in request | `TransactionLegDto` |
| `execute*` | Orchestration entry | `executePostTransaction` |
| `find*` / `get*` | Read paths | `findById`, `findAll` |
| `toResponse` / `toResponses` | Entity → DTO | `AccountBalanceService` |
| `validate*` / `ensure*` | Guard clauses | `validateBalance`, `ensureReversible` |
| `apply*` | Apply domain change | `applySignedLegs` |
| `acquire*Lock` | Concurrency | `acquireTransactionLock` |

---

## 11. OOP principles — spot them in code

Don't memorize definitions. **Find the principle in a file.**

### Single Responsibility Principle (SRP)

> One class, one reason to change.

| Class | Its one job |
|-------|-------------|
| `LedgerPostingService` | Post/reverse journal entries with correct locking |
| `AccountBalanceService` | Balance math + account DTO assembly |
| `IdempotencyService` | HTTP idempotency cache only |
| `RequestHasher` | Fingerprint requests for idempotency |
| `PostgresAdvisoryLockService` | PostgreSQL advisory locks only |
| `GlobalExceptionHandler` | Map exceptions → HTTP only |

**Anti-pattern avoided:** `LedgerPostingService` does NOT build JSON responses — that's `JournalEntryResponseService`.

### Encapsulation

> Hide internal state; expose behavior through methods.

| Good | Why |
|------|-----|
| `Account.validateBalance(long)` | You can't skip overdraft/frozen checks |
| No public balance setter on `Account` | Balance is derived, not stored |
| `SignedLeg` is `private record` inside service | Internal posting representation hidden |
| `executePosting` is `private` | Public API is `postTransaction` only |

**What's NOT encapsulated (honest):** Entities use public getters/setters (JavaBean style for JPA). That's normal for Spring entities — rich behavior lives in services instead.

### Composition over inheritance

> No `AssetAccount extends Account`. Behavior comes from **having** an `AccountType`, not **being** a subclass.

```java
// Composition: Account HAS-A AccountType
account.getAccountType().isDebitNormal()

// NOT inheritance:
// class AssetAccount extends Account { ... }  ← not used
```

### Dependency Inversion Principle (DIP)

> High-level code depends on abstractions.

```java
// LedgerPostingService depends on interfaces, not JDBC
private final AccountRepository accountRepository;  // interface
private final ForensicAuditService forensicAuditService;  // component API
```

Spring injects implementations at runtime. You don't `new AccountRepositoryImpl()`.

### Open/Closed Principle (OCP) — limitation here

> Open for extension, closed for modification.

**Partially true:** New posting flows can use `IdempotencyService.execute*` without changing the wrapper.

**Not true for account types:** Adding `contra_asset` requires editing `AccountType` enum + DB CHECK constraint. No plugin model.

### Polymorphism — where it actually appears

| Location | Form | What varies |
|----------|------|-------------|
| `AccountType.isDebitNormal()` | Enum method | How signed amounts affect economic balance |
| `GlobalExceptionHandler` | Multiple `@ExceptionHandler` methods | Exception type → HTTP response |
| Spring Data repositories | Interface → generated impl | Storage backend (Hibernate) |

**No interface hierarchy** for accounts or transactions — enums + services instead.

### Tell don't ask (light version)

Services **tell** entities to validate, not inspect every field:

```java
// Tell:
account.validateBalance(newBalance);

// Not ask-then-act scattered everywhere:
// if (account.getStatus() == frozen && newBalance < 0 && !account.getAllowOverdraft()) ...
```

---

## 12. Side-by-side comparisons

These tables stop you from confusing similar-looking types.

### `model` vs `dto`

| | `model` (entity) | `dto` (request/response) |
|--|------------------|---------------------------|
| **Purpose** | Mirror DB tables | Mirror JSON API |
| **Annotations** | `@Entity`, `@Column` | Plain class or `record` |
| **Who uses it** | Repositories, services | Controllers, Jackson |
| **Example** | `LedgerEntry` | `LedgerEntryResponse` |
| **Can change when** | Schema migration | API version change |
| **Rule** | Never return entity from controller | Never `@Entity` on request body |

### Request leg vs stored leg

| | `TransactionLegDto` | `LedgerEntry` (entity) |
|--|----------------------|-------------------------|
| **Direction** | `"DEBIT"` / `"CREDIT"` strings | Signed `long` (+ credit, − debit) |
| **Amount** | Always positive | Signed (never zero) |
| **When exists** | HTTP request only | After posting, forever |
| **Conversion** | `toSignedLegs()` in service | Written by `applySignedLegs` |

### Two idempotency keys

| | API layer | Domain layer |
|--|-----------|--------------|
| **Storage** | `idempotency_keys` table | `journal_entries.idempotency_key` |
| **Key type** | `String` (header as-is) | `UUID` |
| **Prevents** | Duplicate HTTP processing | Duplicate economic event |
| **Class** | `IdempotencyService` | `LedgerPostingService` |
| **Replay** | Cached JSON body | Return existing `JournalEntry` |

### Class vs `record` vs `enum`

| Use | Syntax | Example in repo |
|-----|--------|-----------------|
| Mutable entity (JPA) | `class` + getters/setters | `Account`, `JournalEntry` |
| Immutable API output | `record` | `AccountResponse`, `JournalEntryResponse` |
| Fixed set of types | `enum` + methods | `AccountType`, `JournalEntryStatus` |
| Internal tuple | `private record` in service | `SignedLeg` |

### `@Component` vs `@Service` vs `@Repository`

| Annotation | Typical package | Meaning |
|------------|-----------------|---------|
| `@Repository` | `repository/` | Data access (JPA interface) |
| `@Service` | `service/` | Business orchestration |
| `@Component` | `util/`, `audit/`, `persistence/` | Infrastructure / helpers |

All are Spring beans. The stereotype is **documentation for humans**, not different runtime behavior.

---

## 13. How to read the codebase (learning path)

### Pass 1 — Skeleton (30 min)

Open in this order; don't read every line:

1. `V1__initial_ledger_schema.sql` — what tables exist
2. `V2__derived_balances_and_immutable_postings.sql` — what changed
3. `LedgerController.java` — all endpoints in one file
4. Package tree under `src/main/java/.../ledger/`

**Goal:** Name the 4 tables and 6 API routes.

### Pass 2 — One happy path (45 min)

Follow `POST /transactions` only:

1. `LedgerController.postTransaction`
2. `IdempotencyService.executeIdempotent`
3. `LedgerPostingService.postTransaction` → `executePosting` → `applySignedLegs`
4. `AccountRepository.findAllByIdsForUpdate`
5. `AccountBalanceService.computeBalance` / `applySignedLeg`
6. Back up to `JournalEntryResponseService.toResponse`

**Goal:** Explain locking order and where zero-sum is checked (app + DB).

### Pass 3 — OOP & patterns (45 min)

| File | Look for |
|------|----------|
| `Account.java` | Encapsulation — `validateBalance`, no balance field |
| `AccountType.java` | Enum polymorphism — `isDebitNormal()` |
| `IdempotencyService.java` | `Supplier` callback pattern |
| `JournalEntryResponse.java` | `record` + `fromEntity` factory |
| `GlobalExceptionHandler.java` | Exception → HTTP mapping |
| `LedgerPostingConcurrencyIntegrationTest.java` | Why sorting UUIDs matters |

### Pass 4 — Scary paths (30 min)

| Test file | What it proves |
|-----------|----------------|
| `LedgerPostingConcurrencyIntegrationTest` | Locks prevent double-spend / deadlock |
| `IdempotencyIntegrationTest` | 50 concurrent same-key → 1 create, 49 replay |
| `ReversalIntegrationTest` | Append-only reversal, no double reverse |

### Mnemonic: **CLASP** for any new endpoint

| Letter | Question |
|--------|----------|
| **C** | **Controller** — HTTP only? |
| **L** | **Layer** — which service owns the rule? |
| **A** | **Atomic** — does it need `@Transactional` + locks? |
| **S** | **Shape** — Request DTO in, Response DTO out? |
| **P** | **Persist** — which repository/tables? |

---

## 14. Recipes — copy these shapes for new features

When you add something new, **copy an existing shape** — don't invent structure.

### Recipe: new read endpoint (e.g. `GET /accounts/{id}/entries`)

```java
// 1. controller/LedgerController.java
@GetMapping("/accounts/{id}/entries")
public ResponseEntity<List<LedgerEntryResponse>> getAccountEntries(@PathVariable UUID id) {
    return accountEntryService.findByAccountId(id)
        .map(entries -> new ResponseEntity<>(entries, HttpStatus.OK))
        .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
}

// 2. service/AccountEntryService.java
@Service
public class AccountEntryService {
    @Transactional(readOnly = true)
    public Optional<List<LedgerEntryResponse>> findByAccountId(UUID id) { ... }
}

// 3. repository — add query if needed
// 4. dto — reuse LedgerEntryResponse or add new record
```

### Recipe: new write endpoint with idempotency

Copy `postTransaction` exactly:

```java
String hash = requestHasher.hashSomething(request);
var outcome = idempotencyService.executeSomething(
    headerKey, hash, () -> someService.doWork(request));
return idempotentResponse(outcome);
```

### Recipe: new domain rule on existing entity

```java
// Prefer method on entity (if rule is about that entity's state):
public void validateSomething() { ... }

// Prefer private method on service (if rule spans multiple entities):
private void ensureSomething(JournalEntry a, Account b) { ... }

// Prefer DB constraint (if invariant must never break):
-- Flyway migration + CHECK or trigger
```

### Recipe: new exception → HTTP status

```java
// 1. exception/MyNewException.java
public class MyNewException extends RuntimeException { ... }

// 2. controller/GlobalExceptionHandler.java
@ExceptionHandler(MyNewException.class)
public ResponseEntity<Map<String, Object>> handle(MyNewException ex) {
    return buildBody(HttpStatus.CONFLICT, ex.getMessage());
}

// 3. service — throw it where rule fails
throw new MyNewException("human-readable message");
```

### Recipe: new Spring bean

| Bean type | Annotation | Package |
|-----------|------------|---------|
| Business logic | `@Service` | `service/` |
| DB queries | `@Repository` interface | `repository/` |
| Hashing, pure utils | `@Component` | `util/` |
| Logging, async | `@Component` + `@Async` | `audit/` |
| Postgres-specific | `@Component` | `persistence/` |
| Thread pool / config | `@Configuration` + `@Bean` | `config/` |

---

*Last updated to match V2 migration (derived balances, immutable postings). If CODEBASE_FLOW.md still shows `Account.debit()`/`credit()`, that doc is stale — trust the Java source and this guide.*
