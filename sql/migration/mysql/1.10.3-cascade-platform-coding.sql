-- 1.10.3 级联平台编码规则：平台级七段前 14 位 + 起始序号 + 启用开关 + 父链推送策略
--
-- 背景：同一个本地组织/通道推给不同上级，前 14 位（省/市/县/行业/网络）可以完全不同。
--   `tb_cascade_org.code_*` 是「这套目录长在哪个行政区」的本地事实，全局一份；
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
-- ⚠️ 本脚本为一次性增量（MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS），由 DBA 按序手工执行，
--    执行前先备份。SQLite 本地库无需手工执行：启动期 SqliteCascadeCodingSchemaMigrator
--    会按 pragma_table_info 幂等补列并登记 schema_migration_history。

ALTER TABLE `tb_cascade_platform`
    ADD COLUMN `code_province` VARCHAR(2) DEFAULT NULL
        COMMENT '平台编码规则-省编码(2位)' AFTER `catalog_with_device_layer`,
    ADD COLUMN `code_city` VARCHAR(2) DEFAULT NULL
        COMMENT '平台编码规则-市编码(2位)' AFTER `code_province`,
    ADD COLUMN `code_county` VARCHAR(4) DEFAULT NULL
        COMMENT '平台编码规则-县区编码(4位)' AFTER `code_city`,
    ADD COLUMN `code_industry` VARCHAR(2) DEFAULT NULL
        COMMENT '平台编码规则-行业编码(2位)' AFTER `code_county`,
    ADD COLUMN `code_network` VARCHAR(1) DEFAULT NULL
        COMMENT '平台编码规则-网络标识(1位)' AFTER `code_industry`,
    ADD COLUMN `code_start_seq` INT DEFAULT NULL
        COMMENT '平台编码规则-起始序号(6位)' AFTER `code_network`,
    ADD COLUMN `code_rule_enabled` TINYINT NOT NULL DEFAULT 0
        COMMENT '平台编码规则 0=未启用(沿用本地组织码与全局兜底) 1=按平台七段规则生成' AFTER `code_start_seq`,
    ADD COLUMN `publish_ancestors` TINYINT NOT NULL DEFAULT 0
        COMMENT '发布策略 0=勾选组织时缺祖先即报错 1=自动补推缺失的祖先链' AFTER `code_rule_enabled`;

-- 校验：应返回 8
SELECT count(*) AS coding_rule_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_cascade_platform'
  AND COLUMN_NAME IN ('code_province', 'code_city', 'code_county', 'code_industry',
                      'code_network', 'code_start_seq', 'code_rule_enabled', 'publish_ancestors');

-- 校验：存量平台一律 0（升级不改变它们的上报行为）
SELECT count(*) AS platforms_with_rule_off
FROM `tb_cascade_platform`
WHERE `code_rule_enabled` = 0 AND `publish_ancestors` = 0;
