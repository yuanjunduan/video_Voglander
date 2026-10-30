-- Voglander 1.0.12 cascade channel catalog meta (MySQL 8)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：上级（海康 iSecure Center）的目录项除 DeviceID/Name/ParentID 外，还按
-- Manufacturer / Model / IPAddress / Info-PTZType 区分监控点类型与点播入口，
-- Address 也会展示在上级的安装地址栏。这些值此前全部缺失或写死常量。
--
-- 本迁移给 tb_cascade_channel 补 5 个“人工覆盖值”列：
--   manufacturer / model / address / ip_address / ptz_type
-- 列可空，留空表示“按设备通道数据自动派生”（GB28181 目录项 JSON、发现类
-- extend.vendor/sourceModel/sourceIp、tb_device.ip），填了则优先生效。
--
-- 幂等：以 manufacturer 列是否存在作为整体判据；5 列在同一条 ALTER 里添加，
-- 不会出现只补一半的中间态。

-- 0) 前置检查：表不存在时后面的 ALTER 只会抛 1146
SELECT IF((SELECT COUNT(*)
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = 'tb_cascade_channel') = 1,
          '[OK] tb_cascade_channel 已存在，可继续执行',
          '[STOP] tb_cascade_channel 不存在，请先执行 1.0.11 及更早脚本') AS migration_precheck;

-- 1) 补列
SET @has_meta_col := (SELECT COUNT(*)
                      FROM information_schema.columns
                      WHERE table_schema = DATABASE()
                        AND table_name = 'tb_cascade_channel'
                        AND column_name = 'manufacturer');

SET @ddl := IF(@has_meta_col = 0,
    'ALTER TABLE `tb_cascade_channel`
        ADD COLUMN `manufacturer` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''厂商(空=自动派生)'',
        ADD COLUMN `model` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''设备型号(空=自动派生)'',
        ADD COLUMN `address` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''安装地址(空=自动派生)'',
        ADD COLUMN `ip_address` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''设备IP(空=自动派生)'',
        ADD COLUMN `ptz_type` TINYINT DEFAULT NULL COMMENT ''1球机 2半球 3枪机(空或0=自动派生)'',
        ALGORITHM = INPLACE',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 校验：5 列都必须存在
SELECT COUNT(*) AS catalog_meta_columns
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'tb_cascade_channel'
  AND column_name IN ('manufacturer', 'model', 'address', 'ip_address', 'ptz_type');
