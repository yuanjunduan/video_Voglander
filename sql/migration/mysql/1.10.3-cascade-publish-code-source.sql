-- 1.10.3 级联发布行编码来源：code_source + code_generated_time
--
-- 背景：编码规则生成上报编码后必须「生成即固化」并留下来源标记，
--   否则「人工填的码」与「规则算出来的码」无法区分，人工值保护（2.5）就无从判断。
--   本次只补这两列：device_key / last_push_time / last_push_status / last_push_message
--   与通道拆键、推送状态一起在 2.4 合入（MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS，
--   一次性脚本不能事后追加内容）。
--
-- 存量行两列都留空，表示「历史数据未标记」：读取侧照旧 publish_code ?: 本地码，行为不变。
--
-- ⚠️ 本脚本为一次性增量，由 DBA 按序手工执行，执行前先备份。
--    SQLite 本地库无需手工执行：启动期 SqliteCascadePublishCodeSourceMigrator
--    会按 pragma_table_info 幂等补列并登记 schema_migration_history。

ALTER TABLE `tb_cascade_publish`
    ADD COLUMN `code_source` VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '编码来源 AUTO-规则生成并固化 MANUAL-人工填写，空=历史数据未标记' AFTER `publish_code`,
    ADD COLUMN `code_generated_time` DATETIME DEFAULT NULL
        COMMENT '自动生成时间；人工填写为空' AFTER `code_source`;

-- 校验：应返回 2
SELECT count(*) AS code_source_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_cascade_publish'
  AND COLUMN_NAME IN ('code_source', 'code_generated_time');
