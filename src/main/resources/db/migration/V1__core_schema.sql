CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    account_number TEXT NOT NULL UNIQUE,
    account_type TEXT NOT NULL CHECK (account_type IN ('USER', 'SYSTEM')),
    currency CHAR(3) NOT NULL CHECK (currency = 'PEN'),
    available_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_owner_type_ck CHECK (
        (account_type = 'USER' AND owner_id IS NOT NULL AND available_balance >= 0)
        OR (account_type = 'SYSTEM' AND owner_id IS NULL)
    )
);

CREATE UNIQUE INDEX accounts_one_system_per_currency_uq
    ON accounts (currency) WHERE account_type = 'SYSTEM';

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    destination_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    transfer_type TEXT NOT NULL CHECK (transfer_type IN ('INTERNAL_TRANSFER', 'TEST_DEPOSIT')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'PEN'),
    status TEXT NOT NULL CHECK (status = 'COMPLETED'),
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT transfer_distinct_accounts_ck CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX transfers_source_completed_idx ON transfers (source_account_id, completed_at DESC);
CREATE INDEX transfers_destination_completed_idx ON transfers (destination_account_id, completed_at DESC);

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL UNIQUE REFERENCES transfers(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    ledger_transaction_id UUID NOT NULL REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    signed_amount NUMERIC(19, 2) NOT NULL CHECK (signed_amount <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_account_created_idx ON ledger_entries (account_id, created_at DESC);

CREATE TABLE idempotency_keys (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status SMALLINT,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key),
    CONSTRAINT idempotency_response_pair_ck CHECK (
        (response_status IS NULL AND response_body IS NULL)
        OR (response_status IS NOT NULL AND response_body IS NOT NULL)
    )
);

INSERT INTO accounts (id, owner_id, account_number, account_type, currency, available_balance, status)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM-PEN', 'SYSTEM', 'PEN', 0, 'ACTIVE');
