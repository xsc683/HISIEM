-- 调查台协作负责人；保留单一 owner 字段兼容既有客户端。
ALTER TABLE cases ADD COLUMN collaborators_json TEXT NOT NULL DEFAULT '[]';
