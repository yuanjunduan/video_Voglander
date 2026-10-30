-- Voglander 1.0.14 cascade org revoke policy (MySQL 8)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：级联组织「取消同步」（enabled=1 -> 0）需要让上级目录里的节点消失。完整目录快照
-- 对上级是增量 upsert 语义，只重推快照不会删除节点，必须显式推 <Event>DEL</Event>。
-- 而组织消失后其下通道有两种处置方式，协议动作完全不同：
--   CASCADE  —— 通道跟随组织一起 DEL，恢复组织时一起回来（默认）
--   REROUTE  —— 只 DEL 组织，通道改挂域节点继续上报
-- 两种策略事后无法从 enabled 反推，因此落库到 tb_cascade_org.revoke_policy。
--
-- 幂等：以 revoke_policy 列是否存在为判据。

-- 0) 前置检查：表不存在时后面的 ALTER 只会抛 1146
SELECT IF((SELECT COUNT(*)
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = 'tb_cascade_org') = 1,
          '[OK] tb_cascade_org 已存在，可继续执行',
          '[STOP] tb_cascade_org 不存在，请先执行 1.0.11 及更早脚本') AS migration_precheck;

-- 1) 补列
SET @has_revoke_col := (SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'tb_cascade_org'
                          AND column_name = 'revoke_policy');

SET @ddl := IF(@has_revoke_col = 0,
    'ALTER TABLE `tb_cascade_org`
        ADD COLUMN `revoke_policy` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT ''CASCADE'' COMMENT ''取消同步时通道处置：CASCADE连带/ REROUTE上浮''
        AFTER `enabled`,
        ALGORITHM = INPLACE',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 校验：应返回 1
SELECT COUNT(*) AS revoke_policy_columns
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'tb_cascade_org'
  AND column_name = 'revoke_policy';
