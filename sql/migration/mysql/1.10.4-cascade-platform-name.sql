-- 1.10.4 级联平台名称：给上级平台一个本地辨识名
--
-- 背景：列表里只有 20 位平台ID，多个平台并排时人认不出谁是谁。
-- 名称只在本地使用——GB28181 报文里用的是 platform_id，改名称不影响上报。
--
-- 存量平台一律补成空串（未命名）：升级不能替用户编名字，前端在空值时回落显示平台ID。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，本脚本只执行一次。

ALTER TABLE `tb_cascade_platform`
    ADD COLUMN `platform_name` VARCHAR(128) NOT NULL DEFAULT ''
        COMMENT '平台名称(本地辨识用，空=未命名)'
        AFTER `platform_domain`;

-- 校验：返回 1 表示列已存在
SELECT COUNT(*) AS platform_name_column
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_cascade_platform'
  AND COLUMN_NAME = 'platform_name';
