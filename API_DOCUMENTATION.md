# LedgerLine API Documentation

Base URL (local): `http://localhost:8080`  &nbsp;|&nbsp; Live: `https://YOUR-BACKEND-URL` *(fill in after deploying)*
All endpoints are under `/api`. Requests and responses are JSON. There is no OpenAPI/Swagger page yet; this file is the reference.

## 1. Conventions

**Money is always an integer number of paise.** ₹250.50 is `25050`. Never send decimals. The server rejects amounts below 1 paise or above 1,000,000,000 paise (₹1 crore, shown as ₹10,000,000.00) per operation.

**Authentication.** Log in or sign up to get a token, then send `Authorization: Bearer <token>`. Tokens last 30 minutes.

**Idempotency.** Every money endpoint (`deposit`, `withdraw`, `transfer`, `reverse`) **requires** an `Idempotency-Key` header: any unique string up to 100 characters (a UUID is ideal).
- Same key + same request: the original result is returned again with `"replayed": true`. Money moves once.
- Same key + a different request: `422 IDEMPOTENCY_KEY_REUSED`.
- No key: `400 IDEMPOTENCY_KEY_REQUIRED`.
- Keys are private to each user.
- A request that was refused (for example `INSUFFICIENT_FUNDS`) is not remembered; the key can be used again.

**Roles**

| Role | Meaning |
|---|---|
| `ADMIN` | Creates accounts, reverses transactions, can operate on any user account, runs Chaos Lab (dev only) |
| `HOLDER` | Account-holder. Sees and moves money in their own accounts only |
| `AUDITOR` | Read-only. Sees all user accounts, ledger, audit log and integrity checks. Cannot move money |

**Error format** (all errors except a missing or invalid token):
```json
{ "code": "INSUFFICIENT_FUNDS", "message": "Insufficient funds", "traceId": "3fa91c2b" }
```
A missing, expired or invalid token returns `401` with an empty body.

## 2. Access matrix

| Endpoint | Anonymous | HOLDER | AUDITOR | ADMIN |
|---|---|---|---|---|
| `GET /api/health` | yes | yes | yes | yes |
| `POST /api/auth/signup`, `/login` | yes | yes | yes | yes |
| `GET /api/public/receipts/{id}` | yes | yes | yes | yes |
| `GET /api/accounts` | 401 | own only | all | all |
| `POST /api/accounts` | 401 | 403 | 403 | yes |
| `GET /api/accounts/{id}/entries` | 401 | own only (else 404) | yes | yes |
| `POST /api/accounts/{id}/deposit` / `withdraw` / `transfer` | 401 | own account only (else 404) | 403 | yes |
| `POST /api/transactions/{id}/reverse` | 401 | 403 | 403 | yes |
| `GET /api/transactions` | 401 | own only | all user accounts | all user accounts |
| `GET /api/audit/conservation` | 401 | yes | yes | yes |
| `GET /api/audit/verify-chain/{accountId}` | 401 | 403 | yes | yes |
| `GET /api/audit/log` | 401 | 403 | yes | yes |
| `POST /api/chaos/run/{scenario}` | 401 | 403 | 403 | yes, **dev profile only** (404 otherwise) |

Accessing another holder's account returns **404** (the same as a missing account), so account ids cannot be probed.

---

## 3. Auth

### `GET /api/health`
`200` `{ "status": "UP", "service": "ledgerline" }`

### `POST /api/auth/signup`
Creates an account-holder with one wallet named "Primary Wallet". The role is always `HOLDER`; the request has no role field.

Request
```json
{ "email": "newuser@example.com", "password": "Passw0rd123" }
```
Rules: valid email (max 120), password 8 to 72 characters with at least one letter and one digit.

Response `200`
```json
{ "token": "eyJhbGciOi...", "email": "newuser@example.com", "role": "HOLDER", "id": 7 }
```
Errors: `400 VALIDATION_FAILED`, `400 WEAK_PASSWORD`, `409 EMAIL_TAKEN`.

### `POST /api/auth/login`
Request `{ "email": "alice@ledgerline.dev", "password": "..." }`
Response `200` `{ "token": "...", "email": "...", "role": "HOLDER", "id": 3 }`
Errors: `401 BAD_CREDENTIALS` (same message for unknown email and wrong password), `400 VALIDATION_FAILED`.

---

## 4. Accounts

### `GET /api/accounts`
Holders get their own accounts. Admin and auditor get all, including the two system clearing accounts.
```json
[ { "id": 3, "name": "Alice Wallet", "type": "USER", "balancePaise": 450000, "ownerEmail": "alice@ledgerline.dev" } ]
```

### `POST /api/accounts` (ADMIN)
Request `{ "ownerEmail": "bob@ledgerline.dev", "name": "Bob Savings" }` (name max 60)
Response `200` `{ "id": 9 }`. Errors: `404 USER_NOT_FOUND` (the email must belong to a holder), `403 FORBIDDEN`.

### `GET /api/accounts/{id}/entries`
Latest 200 ledger entries for one account, newest first. Holders: own accounts only (else `404 ACCOUNT_NOT_FOUND`).
```json
[ { "id": 41, "txnId": "6b9c...", "direction": "DEBIT", "amountPaise": 50000,
    "balanceAfterPaise": 450000, "prevHash": "a1...", "entryHash": "9f...", "tsMs": 1762500000000 } ]
```
`direction` is `CREDIT` (balance up) or `DEBIT` (balance down). `tsMs` is milliseconds since the epoch.

---

## 5. Moving money

All four endpoints need `Authorization` and `Idempotency-Key`. All run in one database transaction with row locks.

### `POST /api/accounts/{id}/deposit`
Request `{ "amountPaise": 25000 }`

### `POST /api/accounts/{id}/withdraw`
Request `{ "amountPaise": 10000 }`

### `POST /api/accounts/{id}/transfer`
`{id}` is the **source** account. Request `{ "toAccountId": 5, "amountPaise": 10000 }`. The destination must be a user account.

### `POST /api/transactions/{txnId}/reverse` (ADMIN)
No body. Posts the opposite entries (the original is never changed). Fails with `INSUFFICIENT_FUNDS` if the money has already been spent.

**Success response `200`** (all four)
```json
{ "receiptId": "6b9c2d3e-....", "type": "WITHDRAWAL", "status": "POSTED",
  "accountId": 3, "balanceAfterPaise": 440000, "replayed": false }
```
`receiptId` is the transaction id; use it on the public verify endpoint. For a repeat of the same request, `replayed` is `true` and the other fields are identical.

**Errors**

| Status | Code | When |
|---|---|---|
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | Header missing or longer than 100 characters |
| 400 | `INVALID_AMOUNT` | Amount outside the allowed range |
| 400 | `VALIDATION_FAILED` | Missing or non-integer amount, missing `toAccountId` |
| 400 | `SAME_ACCOUNT` | Transfer to the same account |
| 403 | `FORBIDDEN` | Role not allowed (auditor, or holder on reverse) |
| 404 | `ACCOUNT_NOT_FOUND` | Account missing, a system account, or not yours |
| 404 | `TXN_NOT_FOUND` | Reverse of an unknown transaction |
| 409 | `INSUFFICIENT_FUNDS` | Would make a user balance negative |
| 409 | `ALREADY_REVERSED` | Transaction already has a reversal |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same key used with a different request |

---

## 6. History

### `GET /api/transactions`
Your transaction history, newest first, one row per ledger entry on a user account.

| Query | Default | Notes |
|---|---|---|
| `accountId` | all | Filter to one account. Holders still only see their own |
| `limit` | 100 | Clamped to 1 to 500 |

```json
[ { "entryId": 41, "receiptId": "6b9c...", "type": "TRANSFER", "direction": "DEBIT",
    "amountPaise": 50000, "balanceAfterPaise": 450000, "tsMs": 1762500000000,
    "accountId": 3, "accountName": "Alice Wallet", "ownerEmail": "alice@ledgerline.dev",
    "description": "Sent to account #5" } ]
```
For holders, other people's account names are never returned (`account #5`, not the name). Descriptions: "Money added", "Money withdrawn", "Sent to …", "Received from …", "Reversal …".

---

## 7. Audit and verification

### `GET /api/audit/conservation` (any logged-in user)
Aggregates only. One SQL statement, so one consistent snapshot.
```json
{ "balanced": true, "totalBalancePaise": 0, "debitsPaise": 1250000, "creditsPaise": 1250000, "mismatchedAccounts": 0 }
```
`balanced` is true only if all balances sum to 0, total debits equal total credits, and every account balance equals the sum of its entries.

### `GET /api/audit/verify-chain/{accountId}` (ADMIN, AUDITOR)
Recomputes each entry's SHA-256 hash and checks the link to the previous entry.
```json
{ "accountId": 3, "entries": 12, "intact": true, "brokenAtEntryId": null }
```
If `intact` is `false`, `brokenAtEntryId` is the first entry that does not match.

### `GET /api/audit/log` (ADMIN, AUDITOR)
Latest 100 audit rows.
```json
[ { "id": 88, "actorUserId": 3, "action": "WITHDRAWAL", "entity": "TRANSACTION", "entityId": "6b9c...", "createdAt": "2026-11-07T08:12:44.120Z" } ]
```
Actions recorded today: `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `REVERSAL`, `LOGIN`, `LOGIN_FAILED`, `SIGNUP`. **Refused attempts are not recorded yet** (see THREATS.md T-1).

### `GET /api/public/receipts/{id}` (no login)
Checks the stored transaction and its hash chain. Returns only non-private data.
```json
{ "receiptId": "6b9c...", "type": "TRANSFER", "createdAt": "2026-11-07 08:12:44.12+00",
  "entryCount": 2, "intact": true, "entryHashes": ["9f...", "c4..."] }
```
`intact` is `false` if any entry's hash or its link to the previous entry does not match. An unknown id or a malformed id returns `404 RECEIPT_NOT_FOUND`, never a success.

---

## 8. Chaos Lab (dev profile, ADMIN)

### `POST /api/chaos/run/{scenario}`
Runs real concurrent requests through the real service. Scenarios: `overdraw`, `duplicate`, `storm`. Not available when the `prod` profile is active (`404`). Unknown scenario: `404 UNKNOWN_SCENARIO`.
```json
{ "scenario": "overdraw",
  "expectation": "20 parallel withdrawals of Rs100 from Rs500: exactly 5 succeed, balance ends at Rs0, never negative",
  "beforePaise": 50000, "afterPaise": 0,
  "outcomes": { "REJECTED_INSUFFICIENT_FUNDS": 15, "SUCCEEDED": 5 },
  "ok": true, "conservation": { "balanced": true, "...": "..." } }
```
`duplicate` also returns `transactionsCreated` (expected 1). `storm` checks the five accounts' total is unchanged.

---

## 9. Quick test with curl (Windows CMD)

Use `curl.exe` and double quotes with escaped inner quotes.

```
:: 1. log in as the demo holder and copy the token
curl.exe -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d "{\"email\":\"alice@ledgerline.dev\",\"password\":\"YOUR_SEED_PASSWORD\"}"

:: 2. withdraw 100 rupees (10000 paise) from account 3
curl.exe -s -X POST http://localhost:8080/api/accounts/3/withdraw -H "Authorization: Bearer TOKEN" -H "Idempotency-Key: demo-0001" -H "Content-Type: application/json" -d "{\"amountPaise\":10000}"

:: 3. send the exact same request again: replayed is true, balance unchanged
curl.exe -s -X POST http://localhost:8080/api/accounts/3/withdraw -H "Authorization: Bearer TOKEN" -H "Idempotency-Key: demo-0001" -H "Content-Type: application/json" -d "{\"amountPaise\":10000}"

:: 4. same key, different amount: 422 IDEMPOTENCY_KEY_REUSED
curl.exe -s -X POST http://localhost:8080/api/accounts/3/withdraw -H "Authorization: Bearer TOKEN" -H "Idempotency-Key: demo-0001" -H "Content-Type: application/json" -d "{\"amountPaise\":99999}"

:: 5. verify a receipt without logging in (use the receiptId from step 2)
curl.exe -s http://localhost:8080/api/public/receipts/RECEIPT_ID

:: 6. try another holder's account (Bob's is 5): 404
curl.exe -s http://localhost:8080/api/accounts/5/entries -H "Authorization: Bearer TOKEN"
```

## 10. Not available yet

OpenAPI/Swagger page, CSV statements from the API (the UI builds CSV from `GET /api/transactions`), rate limiting, refresh tokens, and audit records for refused attempts.
