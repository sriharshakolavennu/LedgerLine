# threats.md: LedgerLine threat model

**Status key:** **Done** = implemented and a named automated test covers it (tests are written; run `mvnw.cmd test` to confirm). **Implemented, no test** = code exists, checked by reading or by hand only. **Partial** = reduced, not closed. **Open** = not handled yet.

| # | Threat | How it could happen | Defence in this repo | Test | Status |
|---|--------|--------------------|----------------------|------|--------|
| 1 | Duplicate payment | Double click, retry, replay | `Idempotency-Key` required; primary key `(user_id, idem_key)`; same key with a different body returns 422 | `sameKeyTenTimesCreatesOneTransaction`, `retryWithSameKeyIsAppliedOnce`, `reusingKeyWithDifferentAmountIsRejected` | Done |
| 2 | Simultaneous overdraw | Parallel withdrawals read the same balance | `SELECT ... FOR UPDATE` on the accounts in ascending id order, check, update, all in one transaction | `twentyParallelWithdrawalsNeverOverdraw` (20 x ₹100 on ₹500: exactly 5 succeed) | Done |
| 3 | Negative balance | Bug or crafted request | Server check inside the lock plus DB constraint `no_negative_user_balance` | `overdrawIsRejectedAndBalanceIsUnchanged`, `withdrawingExactBalanceLeavesZero` (the DB constraint itself is not tested) | Done |
| 4 | Money created or destroyed | Half-written transaction | Balanced debit/credit legs in one DB transaction; `/api/audit/conservation` checks all balances sum to 0, debits equal credits, each balance equals its entries | `booksBalanceAfterActivity` (passing case only) | Done |
| 5 | Access to another user's account (IDOR) | Changing the account id in the URL | The account id comes from the **URL path**, not the token; the service checks ownership and returns 404 for accounts that are not yours; history is filtered by `owner_user_id` | `holderCannotTouchSomeoneElsesAccount`, `transferShowsOnBothSidesAndHidesOtherAccountNames` (service level; not tested over HTTP) | Partial |
| 6 | Privilege escalation | `role: ADMIN` at signup; holder calls admin API | Signup has no role field and inserts `HOLDER` only; `@PreAuthorize` per endpoint (`/api/audit/verify-chain` and `/api/audit/log` for ADMIN and AUDITOR only; `/api/audit/conservation` is open to any logged-in user and returns totals only) | `signupCreatesHolderWithOneWallet` (the role-per-endpoint check has no test) | Partial |
| 7 | Ledger tampering | Editing or deleting history in SQL | Triggers reject UPDATE, DELETE and TRUNCATE on `ledger_entries` and `audit_log`; each entry hash covers the previous hash, so edits that bypass the trigger are detectable | `ledgerEntriesAreAppendOnly` (audit_log and hash-chain tamper are not tested) | Partial: the app DB user owns the tables and could drop the triggers |
| 8 | Missing accountability | Action with no record | Audit rows for signup, login, failed login (email only) and every **successful** transaction | none | **Open for refused attempts**: the audit row is inside the transaction and rolls back with a refusal |
| 9 | SQL injection | Malicious input | Parameterised queries only; dynamic SQL is built from fixed text and placeholder counts | none | Implemented, no test |
| 10 | Amount abuse | `0`, `-5`, huge values, decimals | Integer paise; 1 paise to 1,000,000,000 paise (₹10,000,000.00); Jackson rejects decimals for integers | `invalidAmountsAreRejected` (0 and -5 only) | Partial |
| 11 | Password guessing | Credential stuffing on login | BCrypt, generic error message, failed logins logged | none | Partial: no rate limit or lockout |
| 12 | Token theft | XSS reads the token | **30-minute** expiry, React escapes output, token in `sessionStorage` | none | Partial: no refresh tokens, no httpOnly cookie, no revocation |
| 13 | CSRF | Cross-site request | Bearer token in a header, no cookies; CORS limited to the configured frontend origin | none | Implemented, no test |
| 14 | Deadlock | Opposite transfers | Locks always in ascending account id | none | Implemented, no test |
| 15 | Secrets in Git | DB password or JWT secret committed | `DB_PASSWORD`, `JWT_SECRET`, `SEED_PASSWORD` read from the environment | none | **Open until deployed**: `application.properties` has a fallback JWT secret and the seeder uses `Passw0rd!` unless overridden |
| 16 | Error leakage | Stack traces | Central handler returns `{code, message, traceId}`; stack traces off | none | Implemented, no test |
| 17 | Holder creates money for themselves | Holder deposits ₹1 crore into their own account, repeatedly | Per-operation cap only | none | **Open (design decision)**: restrict deposit to ADMIN, or state that deposit is a simulation |
| 18 | Chaos Lab reachable in production | Admin token on `/api/chaos/**` | Endpoint exists only under the `dev` profile | none | **Open until deployed**: the repo default profile is `dev`; set `SPRING_PROFILES_ACTIVE=prod` |
| 19 | Signup abuse, email enumeration | Bots; `EMAIL_TAKEN` reveals existing emails | Duplicate email refused with 409 | `signupRejectsDuplicateEmailAndWeakPassword` | Open: no rate limit, no email verification |
| 20 | Role change not noticed | Role is read from the token | 30-minute expiry | none | Open: no DB re-check |
| 21 | Reversal abuse | Reverse the same transaction twice | Admin only; one reversal per transaction (check plus `UNIQUE(reverses_txn_id)`) | none | Implemented, no test |
| 22 | Public receipt leaks data, or "verifies" a missing receipt | Guess or enumerate receipt ids | Public response has only type, time, entry count, hash result and hashes; unknown or malformed id returns 404 | none | Implemented, no test |
| 23 | CSV formula injection | Account name starting with `=` | Cells starting with `= + - @` are prefixed with `'` | none | Implemented, no test |

## Known gaps
Rate limiting and lockout; audit records for refused attempts; refresh tokens and revocation; HTTP-level authorisation tests (JWT filter and `@PreAuthorize`); hash-chain tamper test and `audit_log` append-only test; opposite-direction parallel transfer test; HTTPS and security headers on the frontend host; a DB role without trigger-drop rights; no dependency vulnerability scan.

## Before deploying
Set `JWT_SECRET`, `SEED_PASSWORD`, `SPRING_PROFILES_ACTIVE=prod`, a strong `DB_PASSWORD`, and the real frontend origin in `ledgerline.cors.origin`. Then confirm `/api/chaos/run/overdraw` returns 404 on the live site.
