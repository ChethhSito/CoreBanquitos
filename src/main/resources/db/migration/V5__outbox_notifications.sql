CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    event_type TEXT NOT NULL CHECK (event_type = 'TRANSFER_COMPLETED'),
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_until TIMESTAMPTZ,
    claim_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT outbox_claim_pair_ck CHECK ((claimed_until IS NULL) = (claim_id IS NULL))
);

CREATE INDEX outbox_pending_idx ON outbox_events (available_at, created_at)
    WHERE status = 'PENDING';
CREATE INDEX outbox_expired_claim_idx ON outbox_events (claimed_until)
    WHERE status = 'IN_FLIGHT';

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification_deliveries (
    event_id UUID PRIMARY KEY REFERENCES processed_events(event_id) ON DELETE RESTRICT,
    transfer_id UUID NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
