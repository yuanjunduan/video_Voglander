-- 1.10.4 级联平台名称：给上级平台一个本地辨识名
--
-- 背景：列表里只有 20 位平台ID，多个平台并排时人认不出谁是谁。
-- 名称只在本地使用——GB28181 报文里用的是 platform_id，改名称不影响上报。
--
-- 存量平台补成空串（未命名）：升级不能替用户编名字，前端在空值时回落显示平台ID。
-- PostgreSQL 支持 ADD COLUMN IF NOT EXISTS，可重复执行。

ALTER TABLE tb_cascade_platform
    ADD COLUMN IF NOT EXISTS platform_name VARCHAR(128) NOT NULL DEFAULT '';

COMMENT ON COLUMN tb_cascade_platform.platform_name IS '平台名称(本地辨识用，空=未命名)';
