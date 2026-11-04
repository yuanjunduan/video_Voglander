-- 1.10.3 级联发布行编码来源：code_source + code_generated_time
--
-- 背景：编码规则生成上报编码后必须「生成即固化」并留下来源标记，
--   否则「人工填的码」与「规则算出来的码」无法区分，人工值保护（2.5）就无从判断。
--   本次只补这两列：device_key / last_push_* 与通道拆键、推送状态一起在 2.4 合入。
--
-- 存量行两列都留空，表示「历史数据未标记」：读取侧照旧 publish_code ?: 本地码，行为不变。
--
-- PostgreSQL 支持 ADD COLUMN IF NOT EXISTS，本脚本可重复执行。

ALTER TABLE tb_cascade_publish ADD COLUMN IF NOT EXISTS code_source VARCHAR(8) DEFAULT NULL;
ALTER TABLE tb_cascade_publish ADD COLUMN IF NOT EXISTS code_generated_time TIMESTAMP DEFAULT NULL;

COMMENT ON COLUMN tb_cascade_publish.code_source IS '编码来源 AUTO-规则生成并固化 MANUAL-人工填写，空=历史数据未标记';
COMMENT ON COLUMN tb_cascade_publish.code_generated_time IS '自动生成时间；人工填写为空';

-- 校验：应返回 2
SELECT count(*) AS code_source_columns
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name = 'tb_cascade_publish'
  AND column_name IN ('code_source', 'code_generated_time');
