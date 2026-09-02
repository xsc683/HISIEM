-- Durable lifecycle event delivery outbox. Kafka publication remains outside the database transaction.
CREATE TABLE lifecycle_outbox (
    message_id      VARCHAR(128) PRIMARY KEY,
    event_type      VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    object_type     VARCHAR(32) NOT NULL,
    object_id       VARCHAR(512) NOT NULL,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    message_key     VARCHAR(512) NOT NULL,
    payload_json    TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempts        INTEGER NOT NULL DEFAULT 0,
    available_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until    TIMESTAMP WITH TIME ZONE,
    lease_owner     VARCHAR(128),
    last_error      TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT lifecycle_outbox_status_ck CHECK (status IN ('pending', 'in_flight', 'succeeded', 'failed'))
);

CREATE INDEX lifecycle_outbox_ready_idx
    ON lifecycle_outbox (status, available_at, locked_until);
