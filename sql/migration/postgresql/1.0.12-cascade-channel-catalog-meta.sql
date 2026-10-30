-- Voglander 1.0.12 cascade channel catalog meta (PostgreSQL 12+)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景与字段含义同 MySQL 版：给 tb_cascade_channel 补 5 个“人工覆盖值”列
-- （manufacturer / model / address / ip_address / ptz_type），留空表示按设备通道数据自动派生。
--
-- 幂等：逐列判断 information_schema.columns，已存在则跳过。

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'tb_cascade_channel' AND column_name = 'manufacturer') THEN
        ALTER TABLE tb_cascade_channel ADD COLUMN manufacturer VARCHAR(64) DEFAULT NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'tb_cascade_channel' AND column_name = 'model') THEN
        ALTER TABLE tb_cascade_channel ADD COLUMN model VARCHAR(128) DEFAULT NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'tb_cascade_channel' AND column_name = 'address') THEN
        ALTER TABLE tb_cascade_channel ADD COLUMN address VARCHAR(255) DEFAULT NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'tb_cascade_channel' AND column_name = 'ip_address') THEN
        ALTER TABLE tb_cascade_channel ADD COLUMN ip_address VARCHAR(64) DEFAULT NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'tb_cascade_channel' AND column_name = 'ptz_type') THEN
        ALTER TABLE tb_cascade_channel ADD COLUMN ptz_type SMALLINT DEFAULT NULL;
    END IF;
END $$;

-- 校验：5 列都必须存在
SELECT count(*) AS catalog_meta_columns
FROM information_schema.columns
WHERE table_name = 'tb_cascade_channel'
  AND column_name IN ('manufacturer', 'model', 'address', 'ip_address', 'ptz_type');
