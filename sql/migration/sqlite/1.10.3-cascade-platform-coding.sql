-- Voglander 1.10.3 cascade platform coding rules (SQLite)
-- Non-destructive. No DROP statements are permitted here.
--
-- 背景：同一个本地组织/通道推给不同上级，前 14 位（省/市/县/行业/网络）可以完全不同。
--   tb_cascade_org.code_* 是「这套目录长在哪个行政区」的本地事实，全局一份；
--   「推给 A 局用 340200、推给 B 局用 440300」是发布维度的选择，只能挂在平台上。
-- 平台规则全留空且 code_rule_enabled=0 时，编码解析沿用组织规则与全局兜底，与升级前等价。
--
-- 类型位（组织 215/216、设备 132、通道 131）刻意不放进平台规则：它由对象类型决定。
--
-- ⚠️ SQLite 的 ALTER TABLE 不支持 IF NOT EXISTS，本脚本只能执行一次；
--    本地库无需手工执行 —— 启动期 SqliteCascadeCodingSchemaMigrator 会先查
--    pragma_table_info 再幂等补列（迁移键 cascade-platform-coding-rules）。
--    本文件仅供离线修库或评审留档。

ALTER TABLE tb_cascade_platform ADD COLUMN code_province VARCHAR(2) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN code_city VARCHAR(2) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN code_county VARCHAR(4) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN code_industry VARCHAR(2) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN code_network VARCHAR(1) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN code_start_seq INTEGER DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN code_rule_enabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tb_cascade_platform ADD COLUMN publish_ancestors INTEGER NOT NULL DEFAULT 0;

-- 校验：应返回 8
SELECT count(*) AS coding_rule_columns
FROM pragma_table_info('tb_cascade_platform')
WHERE name IN ('code_province', 'code_city', 'code_county', 'code_industry',
               'code_network', 'code_start_seq', 'code_rule_enabled', 'publish_ancestors');

-- 校验：存量平台一律 0（升级不改变它们的上报行为）
SELECT count(*) AS platforms_with_rule_off
FROM tb_cascade_platform
WHERE code_rule_enabled = 0 AND publish_ancestors = 0;
