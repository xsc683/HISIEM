-- 通知扫描依赖的时间检索索引；删除操作只针对已读历史数据。
CREATE INDEX notifications_read_created_at_idx
    ON notifications (is_read, created_at);
