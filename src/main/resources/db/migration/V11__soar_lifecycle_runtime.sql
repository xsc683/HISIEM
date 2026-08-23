-- SOAR V4: lifecycle-driven MVP runtime. The singular table names deliberately
-- isolate this implementation from the retired YAML/connector runtime tables.
CREATE TABLE soar_playbook (
    id               VARCHAR(128) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL REFERENCES tenants(id),
    name             VARCHAR(256) NOT NULL,
    description      TEXT NOT NULL DEFAULT '',
    status           VARCHAR(16) NOT NULL DEFAULT 'draft',
    enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    entry_type       VARCHAR(16) NOT NULL,
    event_types_json TEXT NOT NULL,
    graph_json       TEXT NOT NULL,
    revision         BIGINT NOT NULL DEFAULT 1,
    created_by       VARCHAR(128) NOT NULL,
    updated_by       VARCHAR(128) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at     TIMESTAMP WITH TIME ZONE,
    deleted_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT soar_playbook_status_ck CHECK (status IN ('draft', 'published', 'disabled')),
    CONSTRAINT soar_playbook_entry_ck CHECK (entry_type IN ('alert', 'case')),
    CONSTRAINT soar_playbook_enabled_ck CHECK (enabled = FALSE OR status = 'published')
);
CREATE INDEX soar_playbook_list_idx
    ON soar_playbook (tenant_id, deleted_at, updated_at DESC);
CREATE INDEX soar_playbook_match_idx
    ON soar_playbook (tenant_id, entry_type, status, enabled);

CREATE TABLE soar_execution (
    id                  VARCHAR(128) PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL REFERENCES tenants(id),
    playbook_id         VARCHAR(128) NOT NULL REFERENCES soar_playbook(id),
    playbook_name       VARCHAR(256) NOT NULL,
    playbook_revision   BIGINT NOT NULL,
    graph_snapshot      TEXT NOT NULL,
    object_type         VARCHAR(16) NOT NULL,
    object_id           VARCHAR(256) NOT NULL,
    event_type          VARCHAR(64) NOT NULL,
    trigger_message_id  VARCHAR(128) NOT NULL,
    payload_snapshot    TEXT NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'pending',
    current_node_id     VARCHAR(128),
    next_run_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error               TEXT,
    actor               VARCHAR(128) NOT NULL,
    cancel_requested    BOOLEAN NOT NULL DEFAULT FALSE,
    lease_owner         VARCHAR(128),
    lease_expires_at    TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          TIMESTAMP WITH TIME ZONE,
    finished_at         TIMESTAMP WITH TIME ZONE,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT soar_execution_object_ck CHECK (object_type IN ('alert', 'case')),
    CONSTRAINT soar_execution_status_v4_ck CHECK (status IN
        ('pending', 'running', 'success', 'failed', 'cancelled', 'waiting', 'waiting_human')),
    CONSTRAINT soar_execution_message_uq UNIQUE (tenant_id, playbook_id, trigger_message_id)
);
CREATE INDEX soar_execution_list_idx
    ON soar_execution (tenant_id, updated_at DESC);
CREATE INDEX soar_execution_claim_idx
    ON soar_execution (status, next_run_at, lease_expires_at);
CREATE INDEX soar_execution_object_idx
    ON soar_execution (tenant_id, object_type, object_id, updated_at DESC);

CREATE TABLE soar_node_run (
    execution_id VARCHAR(128) NOT NULL REFERENCES soar_execution(id) ON DELETE CASCADE,
    node_id      VARCHAR(128) NOT NULL,
    node_name    VARCHAR(256) NOT NULL,
    node_type    VARCHAR(32) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    input_json   TEXT,
    output_json  TEXT,
    error        TEXT,
    started_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (execution_id, node_id),
    CONSTRAINT soar_node_run_status_ck CHECK (status IN
        ('pending', 'running', 'success', 'failed', 'cancelled', 'waiting', 'waiting_human'))
);
CREATE INDEX soar_node_run_execution_idx ON soar_node_run (execution_id, started_at);

CREATE TABLE soar_approval (
    id             VARCHAR(128) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL REFERENCES tenants(id),
    execution_id   VARCHAR(128) NOT NULL REFERENCES soar_execution(id) ON DELETE CASCADE,
    node_id        VARCHAR(128) NOT NULL,
    playbook_id    VARCHAR(128) NOT NULL,
    playbook_name  VARCHAR(256) NOT NULL,
    object_type    VARCHAR(16) NOT NULL,
    object_id      VARCHAR(256) NOT NULL,
    prompt         TEXT NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'pending',
    decided_by     VARCHAR(128),
    decision_note  TEXT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT soar_approval_status_ck CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled')),
    CONSTRAINT soar_approval_execution_node_uq UNIQUE (execution_id, node_id)
);
CREATE INDEX soar_approval_list_idx
    ON soar_approval (tenant_id, status, created_at DESC);
