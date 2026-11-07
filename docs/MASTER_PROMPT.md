# MASTER PROMPT: LedgerLine "Proof Desk"

> Paste everything below the line into Claude. Work phase by phase (Section 9). Do not let it dump the whole project in one reply. Run and verify each phase yourself before asking for the next.

---

## 1. Role

You are a senior fintech backend engineer and product designer mentoring a final-year B.Tech student. Build **LedgerLine Proof Desk**, a campus-wallet money-movement system whose main selling point is that **every rupee is provable**. Write production-style code, explain each design decision in 2-3 lines, and flag anything you are unsure about instead of guessing.

## 2. Where I am now (do not redo this)

- React + Vite frontend (running on :5173)
- Java 21, Maven, Spring Boot backend (running on :8080), `/api/health` works
- PostgreSQL `ledgerline_db`, app user configured, password via `DB_PASSWORD` env var
- Spring Security configuration started, Flyway dependency present
- Nothing about money, roles, idempotency or tests is built yet

## 3. The unique idea (what makes this different)

Most submissions will be a bank-style CRUD wallet. Mine has three signature features that directly prove the brief's requirements:

1. **Proof Receipts.** Every transaction produces a receipt with an ID and a SHA-256 hash. Each ledger entry's hash includes the previous entry's hash (a hash chain, per account). Anyone, even without logging in, can open `/verify/{receiptId}` and see "Hash matches / chain intact" or "Broken".
2. **Chaos Lab.** An admin-only page that fires real concurrent traffic at the real API and shows the ledger surviving it:
   - 20 simultaneous withdrawals of ₹100 against a ₹500 balance: exactly 5 succeed, 15 get `INSUFFICIENT_FUNDS`, final balance ₹0, never negative.
   - The same `Idempotency-Key` replayed 10 times: 1 transaction, 10 identical responses.
   - 50 random transfers between 5 accounts in parallel: total money in system unchanged.
   - Results shown live with a before/after "Conservation Meter".
3. **Books Balanced pill.** A header indicator that polls `/api/audit/conservation`. It is green if `SUM(debits) = SUM(credits)` and every account balance equals the sum of its entries. It turns red and explains why if not.

Currency: INR. Store all amounts as **BIGINT paise**. Never use float/double/BigDecimal-in-JSON for stored money.

## 4. Roles

| Role | Can do |
|---|---|
| `ADMIN` | Create accounts, assign holders, run Chaos Lab, view all, reverse a transaction (creates a compensating entry) |
| `HOLDER` | See own accounts only; deposit, withdraw, transfer from own accounts; download own statement |
| `AUDITOR` | Read-only: all accounts, ledger, audit log, conservation + chain verification. Cannot move money |

Auth: Spring Security + JWT (short-lived access token), BCrypt passwords. Seed three users, one per role, plus 5 demo accounts. Use `@PreAuthorize` and **also** a service-level ownership check (defence in depth).

## 5. Data model (Flyway migrations V1..Vn)

- `users(id, email unique, password_hash, role, created_at)`
- `accounts(id, owner_user_id nullable, name, type ['USER','SYSTEM'], balance_paise BIGINT NOT NULL CHECK (balance_paise >= 0 OR type='SYSTEM'), version, created_at)`
  - SYSTEM accounts: `CASH_IN_CLEARING`, `CASH_OUT_CLEARING`. Deposits debit/credit against these so the books always balance.
- `transactions(id UUID, type ['DEPOSIT','WITHDRAWAL','TRANSFER','REVERSAL'], status, initiated_by, reverses_txn_id nullable, created_at)`
- `ledger_entries(id, txn_id, account_id, direction ['DEBIT','CREDIT'], amount_paise BIGINT CHECK (amount_paise > 0), balance_after_paise, prev_hash, entry_hash, created_at)`
  - **Append-only**: a DB trigger that raises an exception on UPDATE or DELETE.
- `idempotency_keys(user_id, key, request_hash, response_status, response_body, txn_id, created_at, UNIQUE(user_id, key))`
- `audit_log(id, actor_user_id, action, entity, entity_id, ip, detail_json, created_at)`: append-only, same trigger.

## 6. Money-integrity rules (non-negotiable)

1. Every transaction posts **at least two entries** and `SUM(DEBIT) = SUM(CREDIT)` inside one DB transaction.
2. Locking: `SELECT ... FOR UPDATE` on all involved accounts **ordered by account id** (prevents deadlocks). Add a retry on serialization/deadlock failure (max 3).
3. Negative balances are rejected **server-side** in the service, and the DB `CHECK` is a second line of defence.
4. Idempotency: `Idempotency-Key` header is **required** on every money endpoint. Same key + same body returns the stored response. Same key + different body returns `422 IDEMPOTENCY_KEY_REUSED`. Handle the race of two identical requests arriving simultaneously (unique constraint plus a "in progress" row).
5. Amounts: reject zero, negative, over a configurable max, and values that overflow.
6. Self-transfer is rejected. Unknown or inactive account returns 404 without leaking whether it exists for other owners.
7. No edits or deletes of money records ever. Corrections are `REVERSAL` transactions.
8. Hash chain: `entry_hash = SHA256(prev_hash | txn_id | account_id | direction | amount | balance_after | created_at)`.

## 7. API (all under `/api`, JSON, consistent error shape `{code, message, traceId}`)

- `POST /auth/login`
- `GET /health` (exists)
- `POST /accounts` (ADMIN), `GET /accounts` (scoped by role), `GET /accounts/{id}`
- `POST /accounts/{id}/deposit`, `/withdraw`, `POST /transfers`
- `POST /transactions/{id}/reverse` (ADMIN)
- `GET /accounts/{id}/statement?from=&to=&format=json|csv`
- `GET /audit/conservation`, `GET /audit/verify-chain/{accountId}`, `GET /audit/log` (AUDITOR, ADMIN)
- `GET /public/receipts/{receiptId}` (no auth; returns only hash, timestamp, amount band, and chain status, with **no personal data**)
- `POST /chaos/run/{scenario}` (ADMIN, only when `spring.profiles.active=dev`)

Add springdoc OpenAPI at `/swagger-ui.html`.

## 8. Frontend: theme and screens

**Theme: "Midnight Vault"**
- Background `#05070F`, subtle radial glow in emerald `#10F5A0` and electric blue `#3B82F6`
- **Animated background**: a full-screen `<canvas>` of softly drifting hash-blocks connected by thin glowing lines (a living blockchain-like chain). Slow, GPU-friendly, pauses when the tab is hidden, respects `prefers-reduced-motion`.
- Glassmorphism cards (backdrop-blur, 1px translucent borders), rounded-2xl
- Fonts: Inter for UI, JetBrains Mono for hashes and amounts
- Amounts formatted `₹1,234.50` with tabular numerals; animated count when balances change
- Hashes shown as `a3f9…c21e` with copy button and a small "chain link" icon

**Screens**
1. Login (role badge shown after login)
2. Dashboard: account cards, Books-Balanced pill, recent activity timeline
3. Move Money: deposit / withdraw / transfer forms. The client generates the idempotency key; show it in a small "Request ID" chip. Double-click safe (disable plus in-flight guard)
4. Ledger Explorer: per-account entries with debit/credit colouring and a chain-link visual between consecutive hashes
5. Statements: date filter and CSV download
6. Auditor view: conservation report, chain verification button, audit log table
7. Chaos Lab (admin): scenario buttons, live progress, before/after Conservation Meter, result table (succeeded / rejected / duplicate-replayed)
8. Public `/verify/:receiptId` page: big green or red result, no login

Make it responsive and accessible (labels, focus states, contrast). Use React Router, a small `api.js` wrapper that attaches JWT and handles errors, and plain CSS variables or Tailwind. No heavy UI kit.

## 9. Phases (STOP after each and wait for me)

1. **Schema and seed**: Flyway migrations, append-only triggers, seed users and accounts. Give me SQL checks to verify.
2. **Auth and roles**: JWT login, `@PreAuthorize`, ownership checks, error format. Give me curl commands for each role.
3. **Ledger core**: deposit, withdraw, transfer with locking, ledger entries and hash chain.
4. **Idempotency**: including the simultaneous-duplicate race.
5. **Audit**: conservation, chain verification, audit log, statements, reversals, public receipts.
6. **Tests** (JUnit 5 + Testcontainers PostgreSQL, or a local test DB): concurrency (ExecutorService with a CountDownLatch), idempotency replay and mismatch, negative-balance, authorization matrix (every role × every endpoint), append-only trigger rejects UPDATE/DELETE, conservation after random load, chain tamper detection.
7. **Frontend**: theme and animated canvas first, then screens, then Chaos Lab.
8. **Polish**: README with honest status, API docs, seed credentials, `.env.example`, `THREATS.md`, `PROMPTS.md`, demo script (3 minutes).

## 10. Ground rules for you (Claude)

- Tell me every file path and where it goes in my existing project (`backend/ledgerline`, `frontend`). Use Windows-friendly commands (CMD).
- No secrets in code; read from environment variables.
- If something is not tested, say "untested". Do not claim success you cannot see.
- After each phase give me: (a) what to run, (b) the expected output, (c) 3 questions an interviewer might ask about that phase, with short answers.
- Prefer simple, explainable code over clever code. I must be able to defend every line.
