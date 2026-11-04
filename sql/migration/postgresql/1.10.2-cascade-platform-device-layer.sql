-- 1.10.2 级联平台画像：目录是否带设备层
--
-- 背景：「通道直挂组织」与「域/组织/设备/通道」是两种都合规的目录形态，取决于上级怎么建树。
-- 这是对端画像，不是本地结构：本地组织树只有一份，不同上级可以是不同的层级形态。
-- 因此开关挂在平台配置上，默认 0（虚拟组织），不替存量平台改结构。

ALTER TABLE tb_cascade_platform
    ADD COLUMN IF NOT EXISTS catalog_with_device_layer SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tb_cascade_platform.catalog_with_device_layer
    IS '目录是否带设备层 0=虚拟组织(通道直挂组织) 1=域/组织/设备/通道';
