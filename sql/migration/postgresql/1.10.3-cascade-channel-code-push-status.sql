-- Voglander 1.10.3 cascade channel code + publish device key / push status (PostgreSQL)
-- Non-destructive. No DROP statements are permitted here.
--
-- 背景：
--   ① 通道要能落「自己的码」：上级看到的是发布行的共享编码，本地这一层原先只有 channel_id（§6.1 ①）。
--   ③ 发布行要按设备拆键并按平台记录推送结论：object_key 是拼接键，不能拿来当查询条件（§6.3）；
--      推送结论原先只在日志里（§11.7）。
--
-- 存量行 device_key 留空即回落到「按 object_key 归组」的旧行为；本脚本按 object_key
--   的第一个分隔符回填一次，口径与 PublishKeys.channelDeviceId 完全一致。
-- device_key 为空的行（脏数据：object_key 缺少分隔符）保持为空，不猜测。

ALTER TABLE tb_device_channel ADD COLUMN IF NOT EXISTS code VARCHAR(64) DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_channel_code ON tb_device_channel (code);
COMMENT ON COLUMN tb_device_channel.code IS '自己的码（本地码）；上级看到的是发布行的 publish_code';

ALTER TABLE tb_cascade_publish ADD COLUMN IF NOT EXISTS device_key        VARCHAR(64)  DEFAULT NULL;
ALTER TABLE tb_cascade_publish ADD COLUMN IF NOT EXISTS last_push_time    TIMESTAMP    DEFAULT NULL;
ALTER TABLE tb_cascade_publish ADD COLUMN IF NOT EXISTS last_push_status  VARCHAR(16)  DEFAULT NULL;
ALTER TABLE tb_cascade_publish ADD COLUMN IF NOT EXISTS last_push_message VARCHAR(255) DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_publish_device ON tb_cascade_publish (platform_id, object_type, device_key);

COMMENT ON COLUMN tb_cascade_publish.device_key IS 'CHANNEL 行冗余设备段，用于按设备过滤（object_key 是拼接键不能当查询条件）';
COMMENT ON COLUMN tb_cascade_publish.last_push_time IS '最近一次向该平台推送的时间';
COMMENT ON COLUMN tb_cascade_publish.last_push_status IS '最近一次推送结论 OK/FAILED/TIMEOUT/SKIPPED';
COMMENT ON COLUMN tb_cascade_publish.last_push_message IS '最近一次推送失败原因';

UPDATE tb_cascade_publish
SET device_key = split_part(object_key, ':', 1)
WHERE object_type = 'CHANNEL'
  AND device_key IS NULL
  AND position(':' in object_key) > 1;

-- 校验：应返回 1
SELECT count(*) AS channel_code_columns
FROM information_schema.columns
WHERE table_name = 'tb_device_channel'
  AND column_name = 'code';

-- 校验：应返回 4
SELECT count(*) AS publish_push_columns
FROM information_schema.columns
WHERE table_name = 'tb_cascade_publish'
  AND column_name IN ('device_key', 'last_push_time', 'last_push_status', 'last_push_message');
