
# LedgerLine
====================
# LedgerLine Proof Desk

Money movement with a ledger you can prove. Move money so the books always balance and the same request never pays twice.

**Stack:** Java 21 + Spring Boot 3.3 + PostgreSQL + Flyway (backend) / React + Vite (frontend).
**Accounts:** sign up, sign in, transaction history with filters and CSV statement.
**Signature features:** Proof Receipts (hash-chained ledger + public verify page), Chaos Lab (live concurrency demo), Books-Balanced pill (live conservation check).

## Folder layout
```
LedgerLine/
  backend/ledgerline/   Spring Boot project (pom.xml, src/)
  frontend/             React + Vite app
  docs/                 MASTER_PROMPT.md, PROMPTS.md, THREATS.md
```

## Run it (Windows CMD)

**0. PostgreSQL:** make sure `ledgerline_db` exists and your app user can create tables:
```
GRANT ALL ON SCHEMA public TO ledgerline_user;
```
(Needed on PostgreSQL 15+. Use your own user name.)

**1. Backend**
```
cd /d "%USERPROFILE%\Desktop\LegderLine\backend\ledgerline"
set "DB_PASSWORD=YOUR_DATABASE_PASSWORD"
set "DB_USER=ledgerline_user"
mvnw.cmd spring-boot:run
```
- No `mvnw.cmd` in this zip. Either copy `mvnw`, `mvnw.cmd` and the `.mvn` folder from your existing backend project into `backend/ledgerline/`, or install Maven and run `mvn spring-boot:run`.
- If you drop this into your existing project, delete your old `src/main/java` first so there is only one `@SpringBootApplication`.
- Flyway creates the tables on first start; the seeder adds demo users and accounts.

**2. Frontend** (second CMD window)
```
cd /d "%USERPROFILE%\Desktop\LegderLine\frontend"
npm install
npm run dev
```
Open http://localhost:5173 (Vite proxies `/api` to port 8080).

**3. Tests** (needs the database running and `DB_PASSWORD` set; they use the configured local DB)
```
mvnw.cmd test
```

## Demo logins (password `Passw0rd!`)
| Email | Role | Can do |
|---|---|---|
| admin@ledgerline.dev | ADMIN | everything incl. Chaos Lab, reversals, create accounts |
| auditor@ledgerline.dev | AUDITOR | read-only: audit, chain verification, ledgers |
| alice@ledgerline.dev | HOLDER | own accounts only: deposit, withdraw, transfer |
| bob@ledgerline.dev | HOLDER | own accounts only |

## Signup and history (added)
- **Signup:** open http://localhost:5173/signup. New users are always account holders and get a "Primary Wallet" with balance 0. Passwords need 8+ characters with a letter and a digit.
- **History:** the **History** page lists every ledger entry on your accounts (newest first) with direction, balance after and a receipt link. Filter by account or money in/out, and download a CSV statement. The dashboard shows the latest five.
- **API:** `POST /api/auth/signup`, `GET /api/transactions?accountId=&limit=` (holders see only their own accounts; admin and auditor see all user accounts).
- **Deposit and withdraw:** amounts go to the server as integer paise. A withdrawal larger than the balance is refused with `INSUFFICIENT_FUNDS` and leaves no ledger entry.

## 3-minute demo script
1. Log in as **alice**: show balances and the green **Books balanced** pill.
2. **Move money**: transfer to Bob's account. Open the receipt link, then **Verify** it publicly (green).
3. Log in as **admin**, open **Chaos Lab** and run all three scenarios. Say: "20 parallel withdrawals, exactly 5 succeeded, balance never negative; 10 identical requests created 1 transaction; 50 parallel transfers, total money unchanged."
4. Log in as **auditor**: run **Verify hash chain** on an account. Try to move money and show that it is blocked.
5. In psql: `UPDATE ledger_entries SET amount_paise=1;` and show that the append-only trigger rejects it.

## How the integrity rules work
- **Double entry:** every transaction posts balanced debit/credit legs in one DB transaction. Deposits and withdrawals use system clearing accounts, so the sum of all balances is always 0.
- **No double pay:** `Idempotency-Key` is required. The key row is inserted first inside the same transaction, so a concurrent identical request waits on the unique index and then replays the stored response. Same key with a different body returns 422.
- **No overdraw:** `SELECT ... FOR UPDATE` on accounts in ascending id order (no deadlocks), balance check inside the lock, plus a DB `CHECK` constraint as a backstop.
- **Tamper evidence:** append-only triggers on `ledger_entries` and `audit_log`; each entry's hash covers the previous entry's hash.
- **Access control:** JWT, `@PreAuthorize` per endpoint, plus service-level ownership checks (other people's accounts return 404).

## Honest status
| Area | Status |
|---|---|
| Frontend | `npm install` + `vite build` verified in the authoring environment; UI not click-tested against a live backend |
| Backend | Written but **not compiled or run** where this zip was generated (no Maven Central access). Expect to fix small compile or config issues on first run |
| Tests | Written, **not yet run**. Run `mvnw.cmd test` and record the real results |
| Signup, history, new tests | Written in a second pass. Frontend bundles with esbuild. Backend **not compiled or run** and `MoneyFlowTest` **not yet run**; record real results after `mvnw.cmd test` |
| Not built | login and signup rate limiting, email verification, springdoc/OpenAPI docs, deployment, refresh tokens, role re-check against DB on each request |


