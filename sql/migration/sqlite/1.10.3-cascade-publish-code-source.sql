-- Voglander 1.10.3 cascade publish code source (SQLite)
-- Non-destructive. No DROP statements are permitted here.
--
-- 背景：编码规则生成上报编码后必须「生成即固化」并留下来源标记，
--   否则「人工填的码」与「规则算出来的码」无法区分，2.5 的人工值保护就无从判断。
--   只补这两列：device_key / last_push_* 与通道拆键、推送状态一起在 2.4 合入。
--
-- ⚠️ SQLite 的 ALTER TABLE 不支持 IF NOT EXISTS，本脚本只能执行一次；
--    本地库无需手工执行 —— 启动期 SqliteCascadePublishCodeSourceMigrator 会先查
--    pragma_table_info 再幂等补列（迁移键 cascade-publish-code-source）。
--    本文件仅供离线修库或评审留档。

ALTER TABLE tb_cascade_publish ADD COLUMN code_source VARCHAR(8) DEFAULT NULL;
ALTER TABLE tb_cascade_publish ADD COLUMN code_generated_time DATETIME DEFAULT NULL;

-- 校验：应返回 2
SELECT count(*) AS code_source_columns
FROM pragma_table_info('tb_cascade_publish')
WHERE name IN ('code_source', 'code_generated_time');

-- 校验：存量行来源一律为空（升级不改变它们的上报编码）
SELECT count(*) AS rows_without_code_source
FROM tb_cascade_publish
WHERE code_source IS NULL AND code_generated_time IS NULL;
