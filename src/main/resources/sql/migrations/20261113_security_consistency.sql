-- 先停止应用并备份；以下检查结果均应为空。存在历史冲突时须人工决定保留记录，不自动删除数据。
SELECT user_id, COUNT(*) FROM sys_user GROUP BY user_id HAVING COUNT(*) > 1;
SELECT username, role, COUNT(*) FROM sys_user WHERE is_deleted = 0 GROUP BY username, role HAVING COUNT(*) > 1;
SELECT email, role, COUNT(*) FROM sys_user WHERE is_deleted = 0 AND email IS NOT NULL GROUP BY email, role HAVING COUNT(*) > 1;
SELECT chat_id, COUNT(*) FROM sys_chat GROUP BY chat_id HAVING COUNT(*) > 1;

-- 既有数据库一次性迁移；不要在重复执行 init.sql 后再次执行这些 ALTER。
ALTER TABLE sys_user
    ADD COLUMN active_username VARCHAR(50) GENERATED ALWAYS AS (IF(is_deleted = 0, username, NULL)) STORED,
    ADD COLUMN active_email VARCHAR(100) GENERATED ALWAYS AS (IF(is_deleted = 0, email, NULL)) STORED,
    ADD UNIQUE KEY uk_user_id(user_id),
    ADD UNIQUE KEY uk_active_username_role(active_username, role),
    ADD UNIQUE KEY uk_active_email_role(active_email, role);
ALTER TABLE sys_chat ADD UNIQUE KEY uk_chat_id(chat_id),
    ADD COLUMN history_deleted TINYINT(1) NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS sys_qa_sync_job (
    group_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_qa_sync_due(next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 让既有有效问答在升级后重新同步，批次失败不会撤销数据库问答。
INSERT INTO sys_qa_sync_job(group_id, version)
SELECT group_id, 1 FROM sys_qa_info
ON DUPLICATE KEY UPDATE version = version + 1;
