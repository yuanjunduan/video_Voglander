-- Voglander 1.0.11 cascade org tree (SQLite)
--
-- 背景：上级平台按 Catalog Item 的 ParentID 建目录树。只下发通道、没有组织节点时，
-- 通道是"无父节点"的孤儿：SIP 层回 200 OK，但上级界面里不出现。
-- 本迁移新增级联组织树表，并给通道表补组织归属列。
--
-- 注意：
-- 1) SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报
--    "duplicate column name: cascade_org_id"，属预期，可忽略；也可先执行下面第 0 步自查。
-- 2) 存量数据无需回填：cascade_org_id 为空表示"通道直接挂平台根节点"，行为与升级前一致。

-- 0) 自查：列是否已存在（有结果即说明已升级过，可跳过第 2 步）
SELECT name FROM pragma_table_info('tb_cascade_channel') WHERE name = 'cascade_org_id';

-- 1) 组织树表
CREATE TABLE IF NOT EXISTS tb_cascade_org
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    platform_id    VARCHAR(64)                            NOT NULL,
    parent_org_id  INTEGER      DEFAULT NULL,
    cascade_org_id VARCHAR(64)                            NOT NULL,
    org_name       VARCHAR(255)                           NOT NULL,
    sort_order     INTEGER      DEFAULT 0                 NOT NULL,
    enabled        INTEGER      DEFAULT 1                 NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cascade_org_code ON tb_cascade_org (platform_id, cascade_org_id);
CREATE INDEX IF NOT EXISTS idx_cascade_org_parent ON tb_cascade_org (parent_org_id);

-- 2) 通道表补组织归属列
ALTER TABLE tb_cascade_channel ADD COLUMN cascade_org_id VARCHAR(64) DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_cascade_channel_org ON tb_cascade_channel (platform_id, cascade_org_id);

-- 校验：仍未归属任何组织的通道（升级后这些通道直接挂在平台节点下，不会消失）
SELECT id, platform_id, local_device_id, local_channel_id, cascade_channel_id
FROM tb_cascade_channel
WHERE cascade_org_id IS NULL;
