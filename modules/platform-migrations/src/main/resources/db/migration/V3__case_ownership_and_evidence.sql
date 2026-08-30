-- 案件处置补充字段。证据正文保留为 JSON，便于后续扩展类型而不改表结构。
ALTER TABLE cases ADD COLUMN owner VARCHAR(128);
ALTER TABLE cases ADD COLUMN evidence_json TEXT NOT NULL DEFAULT '[]';

CREATE INDEX cases_owner_updated_at_idx ON cases (owner, updated_at DESC);
