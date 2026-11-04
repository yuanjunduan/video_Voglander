-- 1.10.3 级联平台编码规则：平台级七段前 14 位 + 起始序号 + 启用开关 + 父链推送策略
--
-- 背景：同一个本地组织/通道推给不同上级，前 14 位（省/市/县/行业/网络）可以完全不同。
--   tb_cascade_org.code_* 是「这套目录长在哪个行政区」的本地事实，全局一份；
--   「推给 A 局用 340200、推给 B 局用 440300」是发布维度的选择，只能挂在平台上。
-- 平台规则全留空且 code_rule_enabled=0 时，编码解析沿用组织规则与全局兜底，与升级前等价。
--
-- 类型位（组织 215/216、设备 132、通道 131）刻意不放进平台规则：它由对象类型决定，
-- 允许平台填会生成出「通道码以 216 开头」这类非法编码。
--
-- 两个开关都默认 0：
--   code_rule_enabled=0 —— 不替存量平台换码，上级侧不会把老对象当成新设备；
--   publish_ancestors=0 —— 勾选组织时不顺带补推祖先链，缺祖先直接报错（阶段一行为）。
--
-- 幂等：PostgreSQL 支持 ADD COLUMN IF NOT EXISTS，本脚本可重复执行。
-- SQLite 本地库无需手工执行：启动期 SqliteCascadeCodingSchemaMigrator 会幂等补列
-- 并登记 schema_migration_history。

ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS code_province VARCHAR(2) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS code_city VARCHAR(2) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS code_county VARCHAR(4) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS code_industry VARCHAR(2) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS code_network VARCHAR(1) DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS code_start_seq INT DEFAULT NULL;
ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS code_rule_enabled SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS publish_ancestors SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tb_cascade_platform.code_province IS '平台编码规则-省编码(2位)';
COMMENT ON COLUMN tb_cascade_platform.code_city IS '平台编码规则-市编码(2位)';
COMMENT ON COLUMN tb_cascade_platform.code_county IS '平台编码规则-县区编码(4位)';
COMMENT ON COLUMN tb_cascade_platform.code_industry IS '平台编码规则-行业编码(2位)';
COMMENT ON COLUMN tb_cascade_platform.code_network IS '平台编码规则-网络标识(1位)';
COMMENT ON COLUMN tb_cascade_platform.code_start_seq IS '平台编码规则-起始序号(6位)';
COMMENT ON COLUMN tb_cascade_platform.code_rule_enabled IS '平台编码规则 0=未启用(沿用本地组织码与全局兜底) 1=按平台七段规则生成';
COMMENT ON COLUMN tb_cascade_platform.publish_ancestors IS '发布策略 0=勾选组织时缺祖先即报错 1=自动补推缺失的祖先链';

-- 校验：应返回 8
SELECT count(*) AS coding_rule_columns
FROM information_schema.columns
WHERE table_name = 'tb_cascade_platform'
  AND column_name IN ('code_province', 'code_city', 'code_county', 'code_industry',
                      'code_network', 'code_start_seq', 'code_rule_enabled', 'publish_ancestors');

-- 校验：存量平台一律 0（升级不改变它们的上报行为）
SELECT count(*) AS platforms_with_rule_off
FROM tb_cascade_platform
WHERE code_rule_enabled = 0 AND publish_ancestors = 0;
