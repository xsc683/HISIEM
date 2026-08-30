-- SOAR 控制面：Playbook 本身由 Git/YAML 管理；执行快照和每一步结果进入 PostgreSQL。
CREATE TABLE soar_executions (
    id                    VARCHAR(128) PRIMARY KEY,
    playbook_id           VARCHAR(128) NOT NULL,
    playbook_version      VARCHAR(64) NOT NULL,
    resource_type         VARCHAR(32) NOT NULL,
    resource_id           VARCHAR(256) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    actor                 VARCHAR(128) NOT NULL,
    current_step          INTEGER NOT NULL DEFAULT 0,
    playbook_snapshot_json TEXT NOT NULL,
    context_json          TEXT NOT NULL,
    approval_step_id      VARCHAR(128),
    approval_message      TEXT,
    approved_by           VARCHAR(128),
    error                 TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at           TIMESTAMP WITH TIME ZONE,
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT soar_execution_resource_ck CHECK (resource_type IN ('alert', 'case')),
    CONSTRAINT soar_execution_status_ck CHECK (status IN
        ('queued', 'running', 'waiting_approval', 'succeeded', 'failed', 'rejected', 'cancelled'))
);
CREATE INDEX soar_executions_updated_idx ON soar_executions (updated_at DESC);
CREATE INDEX soar_executions_resource_idx ON soar_executions (resource_type, resource_id, updated_at DESC);

CREATE TABLE soar_step_executions (
    execution_id    VARCHAR(128) NOT NULL REFERENCES soar_executions(id) ON DELETE CASCADE,
    step_id         VARCHAR(128) NOT NULL,
    step_index      INTEGER NOT NULL,
    step_name       VARCHAR(256) NOT NULL,
    action          VARCHAR(128) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    input_json      TEXT,
    output_json     TEXT,
    error           TEXT,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (execution_id, step_id),
    CONSTRAINT soar_step_status_ck CHECK (status IN
        ('running', 'waiting_approval', 'succeeded', 'failed', 'skipped', 'rejected'))
);
CREATE INDEX soar_step_execution_idx ON soar_step_executions (execution_id, step_index);

UPDATE roles SET permissions_json = '["alerts:read","alerts:write","soar:read","soar:execute","soar:approve"]'
WHERE name = 'analyst';
UPDATE roles SET permissions_json = '["read","soar:read"]' WHERE name = 'audit';
