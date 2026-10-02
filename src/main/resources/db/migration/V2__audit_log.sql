CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action TEXT NOT NULL CHECK (action IN (
        'USER_REGISTERED', 'TEST_DEPOSIT_COMPLETED', 'TRANSFER_COMPLETED'
    )),
    resource_type TEXT NOT NULL CHECK (resource_type IN ('USER', 'TRANSFER')),
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_action_resource_ck CHECK (
        (action = 'USER_REGISTERED' AND resource_type = 'USER')
        OR (action IN ('TEST_DEPOSIT_COMPLETED', 'TRANSFER_COMPLETED') AND resource_type = 'TRANSFER')
    )
);

CREATE INDEX audit_logs_actor_created_idx ON audit_logs (actor_id, created_at DESC);
CREATE INDEX audit_logs_resource_idx ON audit_logs (resource_type, resource_id);

CREATE FUNCTION prevent_audit_log_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();
