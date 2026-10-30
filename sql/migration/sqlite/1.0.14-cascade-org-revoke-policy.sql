-- Voglander 1.0.14 cascade org revoke policy (SQLite)
-- Non-destructive. No DROP statements are permitted here.
--
-- 背景：级联组织「取消同步」（enabled=1 -> 0）需要让上级目录里的节点消失。完整目录快照
-- 对上级是增量 upsert 语义，只重推快照不会删除节点，必须显式推 <Event>DEL</Event>。
-- 而组织消失后其下通道有两种处置方式，协议动作完全不同：
--   CASCADE  —— 通道跟随组织一起 DEL，恢复组织时一起回来（默认）
--   REROUTE  —— 只 DEL 组织，通道改挂域节点继续上报（发 UPDATE 改 ParentID）
-- 两种策略事后无法从 enabled 反推，必须落库区分。
--
-- ⚠️ SQLite 的 ALTER TABLE 不支持 IF NOT EXISTS，本脚本只能执行一次；
--    本地库无需手工执行 —— 启动时 SqliteCascadeOrgSchemaMigrator 会先查
--    pragma_table_info 再幂等补列。本文件仅供离线修库或评审留档。

ALTER TABLE tb_cascade_org ADD COLUMN revoke_policy VARCHAR(16) DEFAULT 'CASCADE';

-- 校验：应返回 1
SELECT count(*) AS revoke_policy_columns
FROM pragma_table_info('tb_cascade_org')
WHERE name = 'revoke_policy';
