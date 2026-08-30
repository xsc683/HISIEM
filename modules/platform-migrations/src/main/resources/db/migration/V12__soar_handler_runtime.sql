-- Handler-based SOAR runtime. New execution tables keep V11 history intact while
-- replacing the one-row-per-node constraint with one row per durable attempt.
ALTER TABLE soar_execution ADD COLUMN trigger_envelope TEXT;

CREATE TABLE soar_node_execution (
    id               VARCHAR(512) PRIMARY KEY,
    execution_id     VARCHAR(128) NOT NULL REFERENCES soar_execution(id) ON DELETE CASCADE,
    node_id          VARCHAR(128) NOT NULL,
    node_name        VARCHAR(256) NOT NULL,
    node_type        VARCHAR(32) NOT NULL,
    status           VARCHAR(32) NOT NULL,
    sequence_no      BIGINT NOT NULL,
    visit_no         INTEGER NOT NULL,
    attempt          INTEGER NOT NULL,
    token_id         VARCHAR(128) NOT NULL,
    idempotency_key  VARCHAR(512) NOT NULL,
    input_json       TEXT,
    output_json      TEXT,
    error            TEXT,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT soar_node_execution_status_ck CHECK (status IN
        ('running', 'success', 'failed', 'cancelled', 'waiting', 'waiting_human', 'retrying')),
    CONSTRAINT soar_node_execution_attempt_ck CHECK (visit_no > 0 AND attempt > 0),
    CONSTRAINT soar_node_execution_sequence_uq UNIQUE (execution_id, sequence_no),
    CONSTRAINT soar_node_execution_attempt_uq UNIQUE (execution_id, node_id, visit_no, attempt)
);
CREATE INDEX soar_node_execution_history_idx
    ON soar_node_execution (execution_id, sequence_no);
CREATE INDEX soar_node_execution_active_idx
    ON soar_node_execution (execution_id, node_id, status, sequence_no);

INSERT INTO soar_node_execution (
    id, execution_id, node_id, node_name, node_type, status, sequence_no,
    visit_no, attempt, token_id, idempotency_key, input_json, output_json,
    error, started_at, finished_at)
SELECT CONCAT('legacy:', execution_id, ':', node_id), execution_id, node_id,
       node_name, node_type, status,
       ROW_NUMBER() OVER (PARTITION BY execution_id ORDER BY started_at, node_id),
       1, 1, 'root', CONCAT('soar:', execution_id, ':', node_id, ':1'),
       input_json, output_json, error, started_at, finished_at
FROM soar_node_run;

CREATE TABLE soar_approval_task (
    id             VARCHAR(128) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL REFERENCES tenants(id),
    execution_id   VARCHAR(128) NOT NULL REFERENCES soar_execution(id) ON DELETE CASCADE,
    node_run_id    VARCHAR(512) NOT NULL REFERENCES soar_node_execution(id) ON DELETE CASCADE,
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
    CONSTRAINT soar_approval_task_status_ck CHECK (status IN
        ('pending', 'approved', 'rejected', 'cancelled')),
    CONSTRAINT soar_approval_task_run_uq UNIQUE (node_run_id)
);
CREATE INDEX soar_approval_task_list_idx
    ON soar_approval_task (tenant_id, status, created_at DESC);

INSERT INTO soar_approval_task (
    id, tenant_id, execution_id, node_run_id, node_id, playbook_id,
    playbook_name, object_type, object_id, prompt, status, decided_by,
    decision_note, created_at, decided_at)
SELECT approval.id, approval.tenant_id, approval.execution_id, node_run.id,
       approval.node_id, approval.playbook_id, approval.playbook_name,
       approval.object_type, approval.object_id, approval.prompt,
       approval.status, approval.decided_by, approval.decision_note,
       approval.created_at, approval.decided_at
FROM soar_approval approval
JOIN soar_node_execution node_run
  ON node_run.execution_id = approval.execution_id
 AND node_run.node_id = approval.node_id;

CREATE TABLE soar_action_receipt (
    idempotency_key VARCHAR(512) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL REFERENCES tenants(id),
    execution_id   VARCHAR(128) NOT NULL REFERENCES soar_execution(id) ON DELETE CASCADE,
    node_id        VARCHAR(128) NOT NULL,
    action_id      VARCHAR(128) NOT NULL,
    result_json    TEXT NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX soar_action_receipt_execution_idx
    ON soar_action_receipt (execution_id, node_id);
