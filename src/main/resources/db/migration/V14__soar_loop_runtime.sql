CREATE TABLE soar_loop_state (
    id                  VARCHAR(128) PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL REFERENCES tenants(id),
    parent_execution_id VARCHAR(128) NOT NULL REFERENCES soar_execution(id) ON DELETE CASCADE,
    parent_node_run_id  VARCHAR(512) NOT NULL REFERENCES soar_node_execution(id) ON DELETE CASCADE,
    child_execution_id  VARCHAR(128) NOT NULL UNIQUE REFERENCES soar_execution(id) ON DELETE CASCADE,
    body_start_node_id  VARCHAR(128) NOT NULL,
    body_end_node_id    VARCHAR(128) NOT NULL,
    items_json          TEXT NOT NULL,
    iteration_index     INTEGER NOT NULL DEFAULT 0,
    max_iterations      INTEGER NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'running',
    output_json         TEXT NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT soar_loop_state_status_ck CHECK (status IN ('running', 'success', 'failed', 'cancelled')),
    CONSTRAINT soar_loop_state_bounds_ck CHECK (iteration_index >= 0 AND max_iterations > 0)
);
CREATE INDEX soar_loop_state_parent_idx ON soar_loop_state (parent_execution_id, status);
