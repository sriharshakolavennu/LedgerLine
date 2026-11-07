# LedgerLine Proof Desk

**Every rupee. Every movement. Every proof.**
Money movement with a ledger you can prove: the books always balance and the same request never pays twice.

**Live site:** not deployed yet *(replace with the URL once it is live)*

**Stack:** Java 21, Spring Boot 3.3, PostgreSQL, Flyway (backend); React + Vite (frontend).

**What it does**
- Sign up and sign in with three roles: account holder, auditor, admin.
- Deposit, withdraw and transfer money. Amounts are integer paise.
- Double-entry ledger, append-only, with a SHA-256 hash chain per account.
- Transaction history with filters and a CSV statement.
- Proof Receipts: a public page that re-checks a stored transaction and its hash chain.
- Books-Balanced indicator: a live check that all balances sum to 0 and debits equal credits.
- Chaos Lab (development only): fires real parallel requests at the real ledger.

## Documents
- [API_DOCUMENTATION.md](API_DOCUMENTATION.md): every endpoint, roles, errors, curl examples
- [THREATS.md](THREATS.md): threat model, what is tested, what is open
- [PROMPTS.md](PROMPTS.md): how I used AI, what it got wrong, what I did instead

## Run it locally (Windows CMD)

Prerequisites: Java 21, Maven, Node.js, npm, PostgreSQL.

**1. Database.** Make sure `ledgerline_db` exists and the app user can create tables:
```
GRANT ALL ON SCHEMA public TO ledgerline_user;
```
(Needed on PostgreSQL 15+. Use your own user name.)

**2. Backend**
```
cd /d "<your folder>\LedgerLine\backend\ledgerline"
set "DB_PASSWORD=YOUR_DATABASE_PASSWORD"
set "DB_USER=ledgerline_user"
mvnw.cmd spring-boot:run
```
Backend: http://localhost:8080 . Health check: http://localhost:8080/api/health . Flyway creates the tables on first start and the seeder adds demo users and accounts.

**3. Frontend** (second CMD window)
```
cd /d "<your folder>\LedgerLine\frontend"
npm install
npm run dev
```
Open http://localhost:5173 (Vite forwards `/api` to port 8080).

## Environment variables

| Variable | Purpose | Local default | Deployment |
|---|---|---|---|
| `DB_PASSWORD` | Database password | none (required) | Set a strong one |
| `DB_USER`, `DB_URL` | Database user and URL | `ledgerline_user`, local `ledgerline_db` | Set to the hosted database |
| `JWT_SECRET` | Signs login tokens | a public development value | **Must be a long random secret** |
| `SEED_PASSWORD` | Password of the seeded demo users | `Passw0rd!` | **Must be changed** |
| `SPRING_PROFILES_ACTIVE` | `dev` turns Chaos Lab on | `dev` | **Set to `prod`** |

The development defaults are in the public repository, so they must be overridden on any live deployment. See THREATS.md rows 15 and 18.

## Demo logins (local development only)
Password `Passw0rd!` unless `SEED_PASSWORD` is set.

| Email | Role | Can do |
|---|---|---|
| admin@ledgerline.dev | ADMIN | create accounts, reverse transactions, Chaos Lab (dev), operate on any user account |
| auditor@ledgerline.dev | AUDITOR | read-only: ledger, audit log, chain verification |
| alice@ledgerline.dev | HOLDER | own accounts only |
| bob@ledgerline.dev | HOLDER | own accounts only |

New users: http://localhost:5173/signup . They are always account holders and get a "Primary Wallet" with balance 0. Passwords need 8 or more characters with a letter and a digit.

## Using it
- **Move money:** deposit, withdraw or transfer. The browser sends an `Idempotency-Key`; retrying the same request never pays twice.
- **History:** every ledger entry on your accounts, newest first, with direction, balance after and a receipt link. Filter by account or money in/out and download a CSV statement. Holders see only their own accounts; admin and auditor see all user accounts.
- **Verify receipt:** open `/verify`, paste a receipt id. A missing or malformed id shows "not found", never a success.

## Demo script (local, about 5 minutes)
1. Sign in as **alice**: balances and the green **Books balanced** indicator.
2. **Move money**: transfer to Bob's account. Open the receipt link and verify it.
3. Sign in as **admin**, open **Chaos Lab**, run the three scenarios (20 parallel withdrawals of ₹100 on ₹500 give exactly 5 successes; 10 identical requests give 1 transaction; 50 parallel transfers keep the total unchanged). This only works with the `dev` profile.
4. Sign in as **auditor**: verify a hash chain. Try to move money and see it blocked.
5. In psql: `UPDATE ledger_entries SET amount_paise=1;` is rejected by the append-only trigger.

## How the integrity rules work
- **Double entry:** every transaction posts balanced debit and credit legs in one database transaction. Deposits and withdrawals use system clearing accounts, so all balances always sum to 0.
- **No double pay:** `Idempotency-Key` is required on money endpoints. The key row is inserted first inside the same transaction, so a concurrent identical request waits and then replays the stored response. The same key with a different request returns 422.
- **No overdraw:** `SELECT ... FOR UPDATE` on the accounts in ascending id order, balance check inside the lock, plus a database CHECK constraint on user accounts.
- **Tamper evidence:** triggers reject UPDATE, DELETE and TRUNCATE on `ledger_entries` and `audit_log`; each entry's hash covers the previous entry's hash.
- **Access control:** JWT tokens (30 minutes), `@PreAuthorize` per endpoint, and ownership checks in the service. Another holder's account returns 404.

## Tests
```
mvnw.cmd test
```
15 tests are written (`LedgerIntegrityTest` 8, `MoneyFlowTest` 7). They need the database running and `DB_PASSWORD` set, use the configured local database, and create extra accounts there.
They cover: 20 parallel withdrawals, 10 parallel identical requests, key reuse with a different amount, missing key, invalid amounts, spending from another holder's account (service level), append-only `ledger_entries`, conservation, signup role and password rules, deposit/withdraw/history, overdraw refusal, and history hiding other holders' names.
**Last full run:** *(write the date and the real pass/fail result here after running)*

## What I verified, and what I did not
| Area | Status |
|---|---|
| Local run | The app runs locally: sign in, dashboard balances, Move money form and Verify page load (checked in the browser) |
| Automated tests | Written. I will only list them as passing after I have seen `mvnw.cmd test` pass (see above) |
| Authorisation over HTTP | **Not tested.** Existing tests call the service classes, not the JWT filter and `@PreAuthorize` |
| Hash-chain tamper detection | Implemented, **no automated test** |
| Deployment | **Not deployed yet** |

## Known gaps (full list in THREATS.md)
- Refused and attacking attempts are **not written to the audit log** (the audit row rolls back with the refused request).
- A holder can deposit any amount into their own account (deposit is a simulation of cash-in, not checked against real cash).
- No rate limiting or lockout on login and signup; signup reveals which emails exist.
- Role is read from the token and not re-checked; no token revocation.
- No email verification, no refresh tokens, no OpenAPI page, no dependency scan.
- The database user owns the tables, so a privileged user could drop the triggers (the hash chain would detect the edit afterwards).

## AI tools I used
ChatGPT for setup, configuration, debugging and Git. Claude for the ledger design, backend, frontend, signup and history, threat model and documentation. I reviewed and ran the output; PROMPTS.md lists what each tool got wrong and what I changed. Examples of AI mistakes I fixed: Java 25 in the pom (my JDK is 21), an unresolvable dependency, a Flyway version problem, and a false claim in my first threat model that refused transactions were audited.

## Folder layout
```
LedgerLine/
  README.md
  API_DOCUMENTATION.md
  THREATS.md
  PROMPTS.md
  backend/ledgerline/   Spring Boot project (pom.xml, src/)
  frontend/             React + Vite app
```
