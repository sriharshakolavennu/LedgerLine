# PROMPTS.md: How I worked with AI

> **Honesty note:** Only entries I actually ran are listed. Where the AI was wrong, I say so and say what I changed. Update the "Result" lines with your real outcomes and dates before submitting.

**Tools used:** ChatGPT (setup phase), Claude (design and build phases).
**Rule I followed:** one phase at a time, run it, verify it, then continue. I never pasted code I could not explain.

---

## Phase 0: Setup (done)

### P-01: Project scaffolding
**My prompt:**
> I'm a final-year B.Tech student building a money-movement app in 7 days. I have Java 21, Maven, Node and PostgreSQL on Windows. Give me exact CMD steps to create a Spring Boot backend and a React+Vite frontend. Keep it minimal.

**What AI gave:** Initializr settings, folder layout, Vite commands.
**What was wrong:** pom.xml targeted Java 25, but my JDK is 21.
**What I did:** Checked `java -version`, changed `<java.version>` to 21, rebuilt.

### P-02: Maven errors
**My prompt:**
> `mvnw spring-boot:run` fails: "Could not resolve dependency ... governance starter". Here is the full error: [paste]. What is this and do I need it?

**What AI gave:** Explained the dependency was not on Maven Central and not needed.
**What I did:** Removed it. Build passes.

### P-03: Flyway version problem
**My prompt:**
> Flyway dependency gives a version-management error. Here is my pom.xml: [paste]. Fix it without changing the Spring Boot version.

**Result:** Corrected the dependency declaration. I confirmed by running the build.

### P-04: Postgres authentication
**My prompt:**
> "password authentication failed for user" when the backend starts. Postgres is running. How do I reset the app user's password and pass it safely?

**What I did:** Reset password in psql, set `DB_PASSWORD` in the CMD session. I did **not** commit the password.

---

## Phase 1: Design (before writing money code)

### P-05: Make the idea stand out
**My prompt:**
> Hundreds of students have the same LedgerLine brief. Everyone will build a wallet with deposit/withdraw/transfer. What unique angle can I add that still proves the brief (no double pay, no negative balance, books balance, audit)? It must be buildable in a week.

**What AI gave:** Proof Receipts (hash chain), Chaos Lab, Books-Balanced pill.
**My decision:** Kept all three. I dropped "multi-currency" as too risky for the time.

### P-06: Challenge the design
**My prompt:**
> Before I code: where could this design fail? Think about two requests at the same millisecond, a client that retries after a timeout, and an auditor who tries to move money.

**What AI gave:** Row locking order, idempotency in-progress row, role-matrix tests.
**What I did:** Wrote these into THREATS.md.

---

## Phase 2: Schema (planned, update after running)

### P-07: Schema and triggers
**My prompt:**
> Write Flyway V1 for users, accounts, transactions, ledger_entries, idempotency_keys, audit_log. Amounts are BIGINT paise. ledger_entries and audit_log must reject UPDATE and DELETE at the database level. Then give me SQL to prove the trigger works.

**How I verified:** Ran `UPDATE ledger_entries SET ...` in psql and confirmed the exception. *(fill in)*

### P-08: I disagree
**My prompt:**
> You used `NUMERIC(12,2)` for balances. I want integer paise to avoid rounding. Redo it and explain how I convert in the API.

---

## Phase 3: Ledger core

### P-09: Transfer service
**My prompt:**
> Implement transfer in Spring with @Transactional. Lock both accounts with SELECT FOR UPDATE ordered by id. Post a debit and a credit entry. Reject if the balance would go below zero. Explain why ordering by id matters.

### P-10: Prove it, don't trust it
**My prompt:**
> Write a JUnit test that fires 20 parallel withdrawals of 100 rupees from an account with 500. Expected: exactly 5 succeed and the final balance is 0. Use ExecutorService and a CountDownLatch.

**Result:** *(fill in: first run result, any bug found, fix)*

---

## Phase 4: Idempotency

### P-11: Duplicate requests
**My prompt:**
> Implement the Idempotency-Key header. Same key and same body returns the stored response. Same key and different body returns 422. What happens if two identical requests arrive at exactly the same time? Make that case safe and write a test for it.

### P-12: Reviewing AI code
**My prompt:**
> Review your own idempotency code like a hostile reviewer. What would break under crash-after-commit-before-response?

**What I changed:** *(fill in)*

---

## Phase 5: Security and audit

### P-13: Authorization matrix
**My prompt:**
> Generate a test that loops over every endpoint and every role (ADMIN, HOLDER, AUDITOR, anonymous) and asserts the expected status code. I want to see a table of the results.

### P-14: IDOR check
**My prompt:**
> As HOLDER A, try to read and spend from HOLDER B's account by changing the ID in the URL. Write tests for it. Make the error not reveal whether the account exists.

### P-15: Conservation and chain verification
**My prompt:**
> Write /audit/conservation and /audit/verify-chain/{accountId}. Then write a test that directly alters one row (bypassing the trigger in a test-only way) and shows the verification detects it.

---

## Phase 6: Frontend

### P-16: Theme
**My prompt:**
> Midnight Vault theme: dark navy, emerald and blue glow, glass cards, an animated canvas background of drifting hash blocks linked by lines. Pause when the tab is hidden and respect prefers-reduced-motion. Give me a React component.

### P-17: Double-click safety
**My prompt:**
> My Transfer button can be double-clicked. Generate the idempotency key once per form submission attempt, disable the button while in flight, and reuse the same key on retry.

### P-18: Chaos Lab
**My prompt:**
> Build the Chaos Lab page. Scenario buttons call POST /api/chaos/run/{scenario}. Show live progress, a before/after total-money meter, and a table of succeeded, rejected and replayed counts.

---

## Phase 7: Wrap-up

### P-19: Honest README
**My prompt:**
> Rewrite my README "What I built" and "What is not finished" strictly from what passes in `mvn test` and what I demoed. Do not let me claim anything untested.

### P-20: Interview prep
**My prompt:**
> Act as the interviewer. Ask me 10 hard questions about my ledger, locking, idempotency and security. After each answer, tell me what I missed.

---

## Phase 8: Signup, history and money-flow review

### P-21: Add signup and transaction history
**My prompt:**
> Add signup and a transaction history page to the attached zip, keep login as it is, and check that adding and withdrawing money works properly. Modify the zip and give it back.
**What I got:** `POST /api/auth/signup` (role fixed to HOLDER, wallet created), `GET /api/transactions`, a History page with filters and CSV export, recent activity on the dashboard, and `MoneyFlowTest`.
**What I checked:** *(fill in after running `mvnw.cmd test` and clicking through the app)*

---

## Summary of AI mistakes I caught

| # | Mistake | How I found it | Fix |
|---|---|---|---|
| 1 | Java 25 in pom.xml | Build error vs `java -version` | Set to 21 |
| 2 | Unresolvable dependency | Maven resolution failure | Removed |
| 3 | Flyway version config | Maven error | Fixed declaration |
| 4 | Float/NUMERIC money (P-08) | Code review | BIGINT paise |
| 5 | *(add your own)* | | |
