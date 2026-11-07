# prompts.md: how I worked with AI on LedgerLine

**Tools used:** ChatGPT (setup, configuration, debugging, Git) and Claude (login/signup, ledger design, UI, history, threat model, documentation).

I read and ran what the tools gave me. Where an answer was wrong, or where I found a problem in code the AI wrote, I say so. Entries 8 to 10 are problems that are **not fixed yet**. I wrote them down in `threats.md` as Open instead of claiming they work.

---

## 1. Planning
**Prompt:** I'm a final-year B.Tech student entering a 7-day challenge called LedgerLine: money movement with a ledger you can prove. Requirements: deposits, withdrawals, transfers, a double-entry ledger, idempotency, concurrency safety, roles (admin, account holder, auditor), audit log, and tests. Any stack. Give me a day-by-day plan with the order that lowers risk the most.
**What the AI produced:** A day-by-day plan that puts the money-integrity work (schema, ledger, locking) before the UI.
**What was wrong:** Nothing I could find. It was a plan, not code.
**What I did instead:** I followed its order: schema, ledger service, auth, UI, tests. I kept the UI for later so the money rules would not be rushed.

## 2. Environment setup
**Prompt:** Create a Spring Boot project with Java 21, Maven, PostgreSQL, Flyway and Spring Security, plus a React + Vite frontend. Give me exact commands for Windows CMD.
**What the AI produced:** A pom.xml, project folders and the commands to start both projects.
**What was wrong:** The pom set Java 25 but my JDK is 21. It included a starter that Maven could not resolve. The Flyway dependency caused version problems. PostgreSQL login failed at startup.
**What I did instead:** I checked `java -version` and set Java to 21. I removed the starter because the first version did not need it. I corrected the Flyway dependencies. I reset the database user's password in psql and passed it with the `DB_PASSWORD` environment variable, so no password is in the code. I confirmed it with a Maven build.

## 3. Debugging
**Prompt:** My Maven build fails with this error: `<full error pasted>`. My JDK is 21. What is the smallest change that fixes it, and why did it happen?
**What the AI produced:** An explanation of the cause and a one-line fix.
**What was wrong:** In the earlier steps the AI had given me settings that did not match my machine (Java 25, a missing dependency). Asking for the smallest change stopped it from rewriting the whole file.
**What I did instead:** I changed only the line the error pointed to, rebuilt, and moved on once the build passed.

## 4. Schema design
**Prompt:** Design a PostgreSQL schema for a double-entry ledger. Amounts must be integers in paise, balances must never go negative for customers, and ledger rows must be append-only. Explain which rule is enforced by a constraint and which by application code.
**What the AI produced:** `V1__schema.sql` with BIGINT paise amounts, the `no_negative_user_balance` check, a `UNIQUE(reverses_txn_id)` for reversals, and triggers that block UPDATE, DELETE and TRUNCATE on `ledger_entries` and `audit_log`.
**What was wrong:** The app's database user owns the tables, so it could also drop the triggers. The triggers stop normal edits, not a privileged user.
**What I did instead:** I kept the schema and wrote the limit into `threats.md` (threat 7, Partial) instead of claiming the ledger cannot be changed. The hash chain on each entry is there so an edit that bypasses the trigger can still be detected. `ledgerEntriesAreAppendOnly` tests the trigger on `ledger_entries`. Nothing tests `audit_log` yet.

## 5. Idempotency and concurrency
**Prompt:** How do I make a transfer endpoint safe when the same request arrives twice at the same time, and when two different withdrawals race on the same account? Use plain SQL and Spring JdbcTemplate, no framework magic. Show the exact locking order.
**What the AI produced:** An approach where the idempotency key row is inserted first inside the same transaction, and accounts are locked with `SELECT ... FOR UPDATE` in ascending id order.
**What was wrong:** When a request is refused (for example insufficient funds) the whole transaction rolls back, including the idempotency row, so a refused request is not remembered. That is safe for money, but it also means refused attempts are not logged (see entry 9).
**What I did instead:** I kept the design and wrote tests for it: 20 parallel withdrawals of ₹100 against ₹500 must give exactly 5 successes, and 10 identical requests with one key must create exactly one transaction. I will only call them passing in the README after I have seen `mvnw.cmd test` pass.

## 6. Authentication and roles
**Prompt:** Add signup and login with BCrypt and JWT. Signup must never let a user choose their own role. Admin and auditor accounts are seeded. Only admin and auditor may call the audit endpoints.
**What the AI produced:** Login with seeded admin, auditor and holder users, JWT tokens that last 30 minutes, and `@PreAuthorize` on the endpoints. The first version had login only.
**What was wrong:** Signup was missing from the first version, so I asked for it separately (entry 7). Also, I asked for audit endpoints to be admin and auditor only, but `/api/audit/conservation` is open to any logged-in user because the dashboard needs it. It only returns totals.
**What I did instead:** I kept the totals-only endpoint open and restricted `verify-chain` and the audit log to ADMIN and AUDITOR. I noted the open endpoint in `threats.md`. Signup was added in entry 7 with the role fixed on the server.

## 7. Signup, history and UI round
**Prompt:** Add signup and a transaction history page to the attached zip, keep login as it is, and check that adding and withdrawing money works properly. Use a unique, professional dark theme.
**What the AI produced:** `POST /api/auth/signup` (role fixed to HOLDER, one wallet created), `GET /api/transactions`, a history page with filters and CSV export, and `MoneyFlowTest`.
**What was wrong:** Signup needs rate limiting and still tells an attacker which emails exist (the 409 `EMAIL_TAKEN` message). Neither is handled yet.
**What I did instead:** I kept login as it was and added the signup and history changes. The history query only returns the logged-in holder's own accounts and hides other holders' account names. I added the signup abuse problem to `threats.md` (threat 19, Open) and did not claim it was solved.

## 8. Authorisation and access control (attacking my own code)
**Prompt:** Here are SecurityConfig, MoneyController, AccountController and MoneyService.requireUserAccess. Pretend you are a judge. Give me exact curl requests a holder could use to read or move another holder's money, or to get more money than they should.
**What the AI produced:** A review of how each endpoint checks ownership. The ownership checks hold: another holder's account returns 404.
**What was wrong:** A holder can deposit any amount into their own account, so a user can create money for themselves. The role is read from the token and not re-checked, so a changed role is not noticed for up to 30 minutes. My authorisation tests call the service classes directly and never go through the real HTTP filter.
**What I did instead:** I have not fixed these yet. I wrote them into `threats.md` as threats 17 and 20 and in the known-gaps list, and put HTTP-level authorisation tests first in my to-do list. Before deployment I will decide whether deposit becomes admin-only or stays as a clearly labelled simulation.

## 9. Audit trail
**Prompt:** If someone tries to withdraw more than they have, or tries another person's account, where does that get written to audit_log? Show me the exact line of code. I think it might roll back with the failed transaction.
**What the AI produced:** It pointed to `MoneyService.post`, where the audit row is inserted inside the money transaction.
**What was wrong:** It is a real problem. A refused request rolls back everything, including its audit row, so refused and attacking attempts leave no record. Only successes, signups, logins and failed logins are recorded.
**What I did instead:** I have not fixed this yet. I changed `threats.md` from "Done" to **Open** for threat 8 so I am not claiming something that does not work. The planned fix is to record refusals in a separate transaction after the rollback, with a test that a refused withdrawal creates an audit row.

## 10. Deployment secrets
**Prompt:** I am about to deploy to Render and Vercel. Looking only at my repo, what could a stranger use to log in as admin, forge a token, or reach the chaos endpoint on the live site? Give me the exact settings to change.
**What the AI produced:** A list of three things in my repo.
**What was wrong:** `application.properties` has a fallback JWT secret that anyone could read. The seeder creates an admin user with the password `Passw0rd!` unless `SEED_PASSWORD` is set. The default Spring profile is `dev`, which turns the Chaos Lab endpoint on.
**What I did instead:** Nothing is deployed yet, so I wrote these as threats 15 and 18 (Open until deployed) and added a "Before deploying" checklist to `threats.md`: set `JWT_SECRET`, `SEED_PASSWORD` and `SPRING_PROFILES_ACTIVE=prod`, then check that the chaos URL returns 404 on the live site.

## 11. Review prompts I used to check the AI's work
- "List every way a logged-in user could read or move another user's money in this code."
- "Where could this code charge a customer twice?"
- "Which of these claims in my README are not yet true?"

**What the AI produced:** Lists of possible problems, including the false claims in my first `threats.md`.
**What was wrong:** My first `threats.md` said refused transactions were audited and listed constraint and endpoint names that did not exist in my code.
**What I did instead:** I corrected `threats.md` so every row matches the code and names a real test, or says it has none.
