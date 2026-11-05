-- 1.10.4 级联平台名称：给上级平台一个本地辨识名
--
-- 注意：SQLite 部署由代码里的 SqliteCascadePlatformNameMigrator 在启动时自动补列
-- （pragma_table_info 判列 + 单事务 + 登记 schema_migration_history），本文件仅供
-- 离线修库与评审留档，不会被执行器自动执行。重复执行会报 duplicate column。
--
-- 存量平台补成空串（未命名）：升级不能替用户编名字，前端在空值时回落显示平台ID。

ALTER TABLE tb_cascade_platform
    ADD COLUMN platform_name VARCHAR(128) NOT NULL DEFAULT '';

-- 校验：应返回 1
SELECT COUNT(*) AS platform_name_column
FROM pragma_table_info('tb_cascade_platform')
WHERE name = 'platform_name';
