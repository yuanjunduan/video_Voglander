-- Voglander 1.0.12 cascade channel catalog meta (SQLite)
-- Non-destructive. No DROP statements are permitted here.
--
-- 背景与字段含义同 MySQL/PostgreSQL 版：给 tb_cascade_channel 补 5 个“人工覆盖值”列
-- （manufacturer / model / address / ip_address / ptz_type），留空表示按设备通道数据自动派生。
--
-- ⚠️ SQLite 的 ALTER TABLE 不支持 IF NOT EXISTS，本脚本只能执行一次；
--    本地库无需手工执行——启动时 SqliteCascadeChannelMetaSchemaMigrator 会先查
--    pragma_table_info 再幂等补列。本文件仅供离线修库或评审留档。

ALTER TABLE tb_cascade_channel ADD COLUMN manufacturer VARCHAR(64) DEFAULT NULL;
ALTER TABLE tb_cascade_channel ADD COLUMN model VARCHAR(128) DEFAULT NULL;
ALTER TABLE tb_cascade_channel ADD COLUMN address VARCHAR(255) DEFAULT NULL;
ALTER TABLE tb_cascade_channel ADD COLUMN ip_address VARCHAR(64) DEFAULT NULL;
ALTER TABLE tb_cascade_channel ADD COLUMN ptz_type INTEGER DEFAULT NULL;

-- 校验：应返回 5
SELECT count(*) AS catalog_meta_columns
FROM pragma_table_info('tb_cascade_channel')
WHERE name IN ('manufacturer', 'model', 'address', 'ip_address', 'ptz_type');
