-- Durable fan-out/join state. Every branch is an ordinary soar_execution so
-- leases, fencing, attempt history and action receipts remain unchanged.
ALTER TABLE soar_execution ADD COLUMN parallel_parent_id VARCHAR(128);
CREATE INDEX soar_execution_parallel_parent_idx ON soar_execution (parallel_parent_id);

CREATE TABLE soar_parallel_group (
    id                  VARCHAR(128) PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL REFERENCES tenants(id),
    parent_execution_id VARCHAR(128) NOT NULL REFERENCES soar_execution(id) ON DELETE CASCADE,
    parent_node_run_id  VARCHAR(512) NOT NULL REFERENCES soar_node_execution(id) ON DELETE CASCADE,
    join_node_id        VARCHAR(128) NOT NULL,
    expected_count      INTEGER NOT NULL,
    arrived_count       INTEGER NOT NULL DEFAULT 0,
    status              VARCHAR(16) NOT NULL DEFAULT 'waiting',
    output_json         TEXT NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT soar_parallel_group_status_ck CHECK (status IN ('waiting', 'released', 'cancelled')),
    CONSTRAINT soar_parallel_group_count_ck CHECK (expected_count > 1 AND arrived_count >= 0)
);
CREATE INDEX soar_parallel_group_parent_idx ON soar_parallel_group (parent_execution_id, status);

CREATE TABLE soar_parallel_branch (
    id                  VARCHAR(128) PRIMARY KEY,
    group_id            VARCHAR(128) NOT NULL REFERENCES soar_parallel_group(id) ON DELETE CASCADE,
    execution_id        VARCHAR(128) NOT NULL UNIQUE REFERENCES soar_execution(id) ON DELETE CASCADE,
    branch_label        VARCHAR(128) NOT NULL,
    target_node_id      VARCHAR(128) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'pending',
    arrived_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT soar_parallel_branch_status_ck CHECK (status IN ('pending', 'running', 'arrived', 'cancelled')),
    CONSTRAINT soar_parallel_branch_label_uq UNIQUE (group_id, branch_label)
);
CREATE INDEX soar_parallel_branch_group_idx ON soar_parallel_branch (group_id, status);
