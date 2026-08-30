-- Detection controller durable reconciliation state.  Runtime status/job identity remains owned by V17 observers.
ALTER TABLE detection_job_group
    ADD COLUMN reconcile_state VARCHAR(32) NOT NULL DEFAULT 'PENDING';

ALTER TABLE detection_job_group
    ADD COLUMN reconcile_available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE detection_job_group
    ADD COLUMN controller_lease_owner VARCHAR(255);

ALTER TABLE detection_job_group
    ADD COLUMN controller_lease_until TIMESTAMP WITH TIME ZONE;

ALTER TABLE detection_job_group
    ADD COLUMN controller_fencing_token BIGINT NOT NULL DEFAULT 0;

ALTER TABLE detection_job_group
    ADD COLUMN reconcile_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE detection_job_group
    ADD COLUMN last_reconciled_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE detection_job_group
    ADD CONSTRAINT detection_job_group_reconcile_state_ck CHECK (reconcile_state IN
        ('PENDING', 'INSPECTING', 'APPLYING', 'VERIFYING', 'IDLE', 'FAILED'));
ALTER TABLE detection_job_group
    ADD CONSTRAINT detection_job_group_fencing_token_ck CHECK (controller_fencing_token >= 0);
ALTER TABLE detection_job_group
    ADD CONSTRAINT detection_job_group_reconcile_attempts_ck CHECK (reconcile_attempts >= 0);

CREATE INDEX detection_job_group_reconcile_due_idx
    ON detection_job_group(reconcile_available_at, reconcile_state, controller_lease_until);
CREATE INDEX detection_job_group_controller_lease_idx
    ON detection_job_group(controller_lease_owner, controller_fencing_token);
