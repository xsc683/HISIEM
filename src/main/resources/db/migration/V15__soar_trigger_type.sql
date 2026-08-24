ALTER TABLE soar_execution ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'KAFKA';
ALTER TABLE soar_execution ADD CONSTRAINT soar_execution_trigger_type_ck
    CHECK (trigger_type IN ('KAFKA', 'MANUAL', 'INTERNAL'));
