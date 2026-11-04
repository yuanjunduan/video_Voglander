-- 1.10.3 通道「自己的码」+ 发布行拆键与推送状态（SQLite）
-- Non-destructive. No DROP statements are permitted here.
--
-- 背景：
--   ① 通道要能落「自己的码」：上级看到的是发布行的共享编码，本地这一层原先只有 channel_id（§6.1 ①）。
--   ③ 发布行要按设备拆键并按平台记录推送结论：object_key 是 deviceId:channelId 拼接键，
--      拿它做查询条件等于用字符串前缀推断标识（§6.3）；推送结论原先只在日志里（§11.7）。
--
-- 与 1.10.3-cascade-publish-code-source.sql 分开一支：已执行过的脚本不能事后追加内容，
--   混写会让老库永久缺这几列。
--
-- 存量行 device_key 留空即回落到「按 object_key 归组」的旧行为；本脚本按 object_key
--   的第一个分隔符回填一次，口径与 PublishKeys.channelDeviceId 完全一致。
-- device_key 为空的行（脏数据：object_key 缺少分隔符）保持为空，不猜测。
--
-- ⚠️ SQLite 的 ALTER TABLE 不支持 IF NOT EXISTS，本脚本只能执行一次；
--    本地库无需手工执行 —— 启动期 SqliteCascadeChannelCodePushStatusMigrator 会先查
--    pragma_table_info / sqlite_master 再幂等补列、补索引、回填设备段
--    （迁移键 cascade-channel-code-push-status）。本文件仅供离线修库或评审留档。

ALTER TABLE tb_device_channel ADD COLUMN code VARCHAR(64) DEFAULT NULL;
CREATE INDEX idx_channel_code ON tb_device_channel (code);

ALTER TABLE tb_cascade_publish ADD COLUMN device_key VARCHAR(64) DEFAULT NULL;
ALTER TABLE tb_cascade_publish ADD COLUMN last_push_time DATETIME DEFAULT NULL;
ALTER TABLE tb_cascade_publish ADD COLUMN last_push_status VARCHAR(16) DEFAULT NULL;
ALTER TABLE tb_cascade_publish ADD COLUMN last_push_message VARCHAR(255) DEFAULT NULL;
CREATE INDEX idx_publish_device ON tb_cascade_publish (platform_id, object_type, device_key);

-- 存量通道发布行回填设备段：只认 CHANNEL，只在列为空时写，重复执行无副作用
UPDATE tb_cascade_publish
SET device_key = substr(object_key, 1, instr(object_key, ':') - 1)
WHERE object_type = 'CHANNEL'
  AND device_key IS NULL
  AND instr(object_key, ':') > 1;

-- 校验：应返回 1
SELECT count(*) AS channel_code_columns
FROM pragma_table_info('tb_device_channel')
WHERE name = 'code';

-- 校验：应返回 4
SELECT count(*) AS publish_push_columns
FROM pragma_table_info('tb_cascade_publish')
WHERE name IN ('device_key', 'last_push_time', 'last_push_status', 'last_push_message');

-- 校验：应返回 2
SELECT count(*) AS new_indexes
FROM sqlite_master
WHERE type = 'index' AND name IN ('idx_channel_code', 'idx_publish_device');

-- 校验：CHANNEL 行不应再有空的 device_key（object_key 本身缺少分隔符的脏数据除外）
SELECT count(*) AS channel_rows_without_device_key
FROM tb_cascade_publish
WHERE object_type = 'CHANNEL'
  AND device_key IS NULL;
