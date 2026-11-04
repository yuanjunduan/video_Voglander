-- 1.10.3 通道「自己的码」+ 发布行拆键与推送状态
--
-- 背景：
--   ① 通道要能落「自己的码」：上级看到的是发布行的共享编码，本地这一层原先只有 channel_id，
--      编码规则一旦接管，本地码就没有落库位（§6.1 ①）。
--   ③ 发布行要按设备拆键并按平台记录推送结论：object_key 是 deviceId:channelId 拼接键，
--      拿它做查询条件等于用字符串前缀推断标识（§6.3）；推送结论原先只在日志里（§11.7）。
--
-- 与 1.10.3-cascade-publish-code-source.sql 分开一支：MySQL 的 ADD COLUMN 不支持
--   IF NOT EXISTS，已执行过的脚本不能事后追加内容，混写会让老库永久缺这几列。
--
-- 存量行 device_key 留空即回落到「按 object_key 归组」的旧行为；本脚本按 object_key
--   的第一个分隔符回填一次，口径与 PublishKeys.channelDeviceId 完全一致。
--
-- ⚠️ 本脚本为一次性增量，由 DBA 按序手工执行，执行前先备份。
--    SQLite 本地库无需手工执行：启动期 SqliteCascadeChannelCodePushStatusMigrator
--    会按 pragma_table_info 幂等补列、补索引、回填 device_key 并登记 schema_migration_history。

ALTER TABLE `tb_device_channel`
    ADD COLUMN `code` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '自己的码（本地码）；上级看到的是发布行的 publish_code' AFTER `missing_count`,
    ADD KEY `idx_channel_code` (`code`) USING BTREE;

ALTER TABLE `tb_cascade_publish`
    ADD COLUMN `device_key` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT 'CHANNEL 行冗余设备段，用于按设备过滤（object_key 是拼接键不能当查询条件）' AFTER `revoke_policy`,
    ADD COLUMN `last_push_time` DATETIME DEFAULT NULL
        COMMENT '最近一次向该平台推送的时间' AFTER `device_key`,
    ADD COLUMN `last_push_status` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '最近一次推送结论 OK/FAILED/TIMEOUT/SKIPPED' AFTER `last_push_time`,
    ADD COLUMN `last_push_message` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '最近一次推送失败原因' AFTER `last_push_status`,
    ADD KEY `idx_publish_device` (`platform_id`, `object_type`, `device_key`) USING BTREE;

-- 存量通道发布行回填设备段：只认 CHANNEL，只在列为空时写，重复执行无副作用
UPDATE `tb_cascade_publish`
SET `device_key` = SUBSTRING_INDEX(`object_key`, ':', 1)
WHERE `object_type` = 'CHANNEL'
  AND `device_key` IS NULL
  AND INSTR(`object_key`, ':') > 1;

-- 校验：应返回 1
SELECT count(*) AS channel_code_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_device_channel'
  AND COLUMN_NAME = 'code';

-- 校验：应返回 4
SELECT count(*) AS publish_push_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_cascade_publish'
  AND COLUMN_NAME IN ('device_key', 'last_push_time', 'last_push_status', 'last_push_message');

-- 校验：CHANNEL 行不应再有空的 device_key（object_key 本身缺少分隔符的脏数据除外）
SELECT count(*) AS channel_rows_without_device_key
FROM `tb_cascade_publish`
WHERE `object_type` = 'CHANNEL'
  AND `device_key` IS NULL;
