CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  email VARCHAR(120) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(10) NOT NULL CHECK (role IN ('ADMIN','HOLDER','AUDITOR')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Convention: CREDIT increases a balance, DEBIT decreases it. Sum of ALL balances is always 0.
CREATE TABLE accounts (
  id BIGSERIAL PRIMARY KEY,
  owner_user_id BIGINT REFERENCES users(id),
  name VARCHAR(60) NOT NULL,
  type VARCHAR(6) NOT NULL CHECK (type IN ('USER','SYSTEM')),
  balance_paise BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT no_negative_user_balance CHECK (type = 'SYSTEM' OR balance_paise >= 0)
);

CREATE TABLE transactions (
  id UUID PRIMARY KEY,
  type VARCHAR(12) NOT NULL CHECK (type IN ('DEPOSIT','WITHDRAWAL','TRANSFER','REVERSAL')),
  initiated_by BIGINT NOT NULL REFERENCES users(id),
  reverses_txn_id UUID UNIQUE REFERENCES transactions(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
  id BIGSERIAL PRIMARY KEY,
  txn_id UUID NOT NULL REFERENCES transactions(id),
  account_id BIGINT NOT NULL REFERENCES accounts(id),
  direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  balance_after_paise BIGINT NOT NULL,
  prev_hash VARCHAR(64) NOT NULL,
  entry_hash VARCHAR(64) NOT NULL,
  ts_ms BIGINT NOT NULL
);
CREATE INDEX idx_entries_account ON ledger_entries(account_id, id);
CREATE INDEX idx_entries_txn ON ledger_entries(txn_id);

CREATE TABLE idempotency_keys (
  user_id BIGINT NOT NULL,
  idem_key VARCHAR(100) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  response_body TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, idem_key)
);

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY,
  actor_user_id BIGINT,
  action VARCHAR(30) NOT NULL,
  entity VARCHAR(30),
  entity_id VARCHAR(60),
  detail TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only enforcement at the database level
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'Table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
