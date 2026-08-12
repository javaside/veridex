-- 全量快照语义下不再使用 ROLLED_BACK：存量回滚记录归为历史版本（PUBLISHED，非当前）
UPDATE index_release SET status = 'PUBLISHED' WHERE status = 'ROLLED_BACK';
