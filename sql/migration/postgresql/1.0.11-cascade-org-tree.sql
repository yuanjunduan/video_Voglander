-- Voglander 1.0.11 cascade org tree (PostgreSQL)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：上级平台按 Catalog Item 的 ParentID 建目录树。只下发通道、没有组织节点时，
-- 通道是"无父节点"的孤儿：SIP 层回 200 OK，但上级界面里不出现。
-- 本迁移新增级联组织树表，并给通道表补组织归属列。
--
-- 兼容性：cascade_org_id 允许为空，表示"通道直接挂平台根节点"，存量数据无需回填。

CREATE TABLE IF NOT EXISTS tb_cascade_org
(
    id             BIGSERIAL,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    platform_id    VARCHAR(64)  NOT NULL,
    parent_org_id  BIGINT       DEFAULT NULL,
    cascade_org_id VARCHAR(64)  NOT NULL,
    org_name       VARCHAR(255) NOT NULL,
    sort_order     INT          NOT NULL DEFAULT 0,
    enabled        SMALLINT     NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE (platform_id, cascade_org_id)
);

CREATE INDEX IF NOT EXISTS idx_cascade_org_parent ON tb_cascade_org (parent_org_id);

ALTER TABLE tb_cascade_channel ADD COLUMN IF NOT EXISTS cascade_org_id VARCHAR(64) DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_cascade_channel_org ON tb_cascade_channel (platform_id, cascade_org_id);

-- 校验 1：组织树表已建立
SELECT table_name FROM information_schema.tables
WHERE table_schema = current_schema() AND table_name = 'tb_cascade_org';

-- 校验 2：通道表的组织归属列已存在
SELECT column_name, is_nullable, data_type FROM information_schema.columns
WHERE table_schema = current_schema() AND table_name = 'tb_cascade_channel' AND column_name = 'cascade_org_id';

-- 校验 3：仍未归属任何组织的通道（升级后这些通道直接挂在平台节点下，不会消失）
SELECT id, platform_id, local_device_id, local_channel_id, cascade_channel_id
FROM tb_cascade_channel
WHERE cascade_org_id IS NULL;
