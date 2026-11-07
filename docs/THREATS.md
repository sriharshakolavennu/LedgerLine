# THREATS.md: LedgerLine Threat Model

> **How to use:** Status column must be honest: `Planned`, `Implemented`, or `Tested`. Change it only when the code exists and a test passes. Reviewers value an honest "Planned" more than a false "Tested".

**Assets:** account balances, ledger entries, idempotency records, user credentials, audit log.
**Actors:** anonymous attacker, malicious account-holder, curious holder (snooping on others), compromised or over-curious auditor, impatient user (double-click), flaky network client, insider with DB access.
**Method:** STRIDE, mapped to the brief's five failure types: duplicate requests, simultaneous withdrawals, unauthorised transactions, wrong balances, untraceable changes.

---

## 1. Threat table

| ID | Threat | Example attack | Mitigation | Where | Test | Status |
|---|---|---|---|---|---|---|
| T-01 | **Duplicate payment** (retry, double-click, replay) | Client times out and resends the same transfer | Required `Idempotency-Key`; unique `(user_id, key)`; stored response replay | `IdempotencyService`, `idempotency_keys` | Same key 10x gives 1 txn | Planned |
| T-02 | **Idempotency race** | Two identical requests at the same millisecond | Insert in-progress row first; the unique constraint makes the loser wait or replay | `IdempotencyService` | Parallel identical requests | Planned |
| T-03 | **Key reuse with different body** | Attacker reuses a key to alter an amount | Store `request_hash`; mismatch returns 422 | `IdempotencyService` | Mismatch test | Planned |
| T-04 | **Simultaneous withdrawals overdraw** | 20 parallel withdrawals vs ₹500 | `SELECT FOR UPDATE`, single DB transaction, balance check inside the lock | `LedgerService` | 20x ₹100 gives exactly 5 pass | Planned |
| T-05 | **Deadlock between opposite transfers** | A to B and B to A at once | Lock accounts in ascending id order; bounded retry | `LedgerService` | Opposing parallel transfers | Planned |
| T-06 | **Negative balance** | Race, bug or crafted amount | Service validation plus DB `CHECK (balance_paise >= 0)` for user accounts | Service, Flyway V1 | Direct SQL violation test | Planned |
| T-07 | **Invalid amounts** | 0, negative, huge, overflow, decimals | Integer paise, `> 0`, max limit, strict JSON parsing | DTO validation | Boundary tests | Planned |
| T-08 | **Money created or destroyed** | Bug posts one-sided entry | Double-entry (every txn balanced), conservation endpoint, commit-time check | `LedgerService`, `/audit/conservation` | Random load, then SUM check | Planned |
| T-09 | **Stored balance drifts from ledger** | Manual DB edit or bug | Verify `balance == SUM(entries)` per account in the audit | `AuditService` | Tamper test | Planned |
| T-10 | **Ledger tampering** (edit/delete history) | Insider runs UPDATE/DELETE | Append-only trigger; hash chain detects tampering that bypasses the trigger | Flyway trigger, `verify-chain` | Trigger rejection, chain break detected | Planned |
| T-11 | **Audit log tampering** | Delete evidence | Append-only trigger on `audit_log` | Flyway | Trigger test | Planned |
| T-12 | **Broken authentication** | Brute force, weak passwords, stolen token | BCrypt, short-lived JWT, login rate limit, generic login errors | Security config | Wrong password, expired token | Planned |
| T-13 | **IDOR / horizontal privilege escalation** | HOLDER A uses `/accounts/{B}` | Service-level ownership check; 404 (not 403) for others' accounts | `AccountService` | Cross-user tests | Planned |
| T-14 | **Vertical privilege escalation** | HOLDER calls admin APIs; AUDITOR moves money | `@PreAuthorize` per endpoint plus deny-by-default | Controllers | Role x endpoint matrix | Planned |
| T-15 | **Auditor abuse** | Auditor tries a write | Auditor role has read-only authorities; writes are denied | Security config | Matrix | Planned |
| T-16 | **JWT tampering / alg confusion** | Forged role claim | Verify signature with a fixed algorithm and a server-only secret; role re-read from DB for money actions | JWT filter | Forged-token test | Planned |
| T-17 | **SQL injection** | Malicious statement filter | Parameterized queries only (JPA / `?` binding) | Repositories | Injection string tests | Planned |
| T-18 | **Mass assignment** | Client sends `role` or `balance` in JSON | Dedicated request DTOs; no entity binding | DTOs | Extra-field test | Planned |
| T-19 | **Information leakage in errors** | Stack traces, "account exists" hints | Global exception handler; uniform `{code, message, traceId}` | `@ControllerAdvice` | Error-shape test | Planned |
| T-20 | **Secrets in repo** | DB password committed | Env vars, `.env.example`, `.gitignore` | Config | Repo scan | Implemented (DB_PASSWORD env) |
| T-21 | **CSRF / CORS misconfig** | Malicious site calls the API | Stateless JWT in Authorization header (not cookies); CORS limited to the frontend origin | Security config | CORS test | Planned |
| T-22 | **Chaos endpoints in production** | Attacker triggers load tests | Only under `dev` profile and ADMIN | `ChaosController` | Prod-profile 404 test | Planned |
| T-23 | **Public receipt leaks data** | Enumerating receipt IDs | Random UUID IDs; only hash, time, status exposed; no names or balances; rate limited | `PublicReceiptController` | Response-shape test | Planned |
| T-24 | **Crash between commit and response** | Client never learns the outcome | Idempotency replay returns the stored result on retry | `IdempotencyService` | Simulated crash test | Planned |
| T-25 | **Client-side only validation** | Bypass UI with curl | All rules enforced server-side; UI is convenience only | Whole API | curl tests | Planned |
| T-26 | **Reversal abuse** | Reverse the same txn twice | `reverses_txn_id` unique; only ADMIN; the original is never modified | `ReversalService` | Double-reverse test | Planned |
| T-27 | **Denial of service by flooding** | Spam transfers | Per-user rate limit; max request size | Filter | Rate limit test | Planned |
| T-28 | **Dependency vulnerabilities** | Vulnerable library | Keep Spring Boot current; run `mvn dependency-check` / `npm audit` | Build | Report | Planned |

---
| S-1 | Role escalation at signup | `POST /api/auth/signup` with `"role":"ADMIN"` | Request has no role field; server inserts `HOLDER` only | `AuthController.signup` | `MoneyFlowTest.signupCreatesHolderWithOneWallet` | Implemented (test written, not yet run) |
| S-2 | Duplicate or weak signups | Same email twice, password `aaaaaaaa` | Unique email index, 409 `EMAIL_TAKEN`; min 8 chars with letter and digit; BCrypt | `AuthController.signup` | `MoneyFlowTest.signupRejectsDuplicateEmailAndWeakPassword` | Implemented (test written, not yet run) |
| S-3 | Signup abuse and account enumeration | Bot creates thousands of accounts; 409 reveals which emails exist | None yet | | | Planned: rate limiting, email verification |
| H-1 | Reading another person's history | `GET /api/transactions?accountId=<someone else's>` | Holder queries always add `owner_user_id = me`; counterparty names of other holders are hidden | `HistoryController` | `MoneyFlowTest.transferShowsOnBothSidesAndHidesOtherAccountNames` | Implemented (test written, not yet run) |
| H-2 | Spreadsheet formula injection in CSV export | Account name starting with `=` | Cells starting with `= + - @` are prefixed with `'` | `pages.jsx` `csvCell` | Manual | Implemented |
| M-1 | Edited request reusing an old Idempotency-Key | Change the amount, resend with the same key (gets 422) | UI issues a new key whenever the account, destination, amount or tab changes | `MovePage` | Manual | Implemented |

## 2. Trust boundaries

```
Browser (untrusted)  --HTTPS+JWT-->  Spring Boot API (enforces everything)  --parameterised SQL-->  PostgreSQL (last line of defence: CHECKs, triggers)
```

The browser is never trusted. The database is the last line of defence, so integrity rules live **both** in code and in the schema.

## 3. Residual risks (stated honestly)

- A superuser with raw database access can drop triggers. The hash chain **detects** this but cannot **prevent** it. Production would add WORM storage or external anchoring of the chain head.
- Single-node PostgreSQL: no replication or failover in this project.
- No real KYC, payment rails, or regulated-money handling. This is a demonstration ledger.
- JWT revocation is limited to short expiry. A production system would add a deny-list or rotating refresh tokens.
- Rate limiting is in-memory (per instance).

## 4. Out of scope

Multi-currency, interest, card/UPI integration, MFA, mobile app.

## 5. Evidence checklist (fill as you go)

- [ ] `mvn test` output screenshot showing concurrency + idempotency tests passing
- [ ] Role x endpoint matrix output
- [ ] Chaos Lab screenshot (before/after meter)
- [ ] `/verify/{receiptId}` green and red screenshots
- [ ] psql screenshot of trigger rejecting UPDATE
