-- Voglander 1.0.11 cascade org tree (MySQL 8)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：上级平台（海康 iSecure Center 等）按 Catalog Item 的 ParentID 建目录树。
-- 只下发通道、没有组织节点时，通道是"无父节点"的孤儿：SIP 层回 200 OK，
-- 但上级界面里不出现。本迁移新增级联组织树表，并给通道表补组织归属列。
--
-- 兼容性：cascade_org_id 允许为空，表示"通道直接挂平台根节点"，
-- 存量数据无需回填，行为与升级前一致。

CREATE TABLE IF NOT EXISTS `tb_cascade_org`
(
    `id`             BIGINT UNSIGNED                                       NOT NULL AUTO_INCREMENT,
    `create_time`    DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `platform_id`    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '上级平台ID',
    `parent_org_id`  BIGINT UNSIGNED                                                DEFAULT NULL COMMENT '父组织(tb_cascade_org.id)，空=根组织挂平台节点',
    `cascade_org_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '上报给上级的组织编码(20位)',
    `org_name`       VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '上报名称',
    `sort_order`     INT                                                   NOT NULL DEFAULT 0 COMMENT '同级排序',
    `enabled`        TINYINT                                               NOT NULL DEFAULT 1 COMMENT '1上报 0不上报(整棵子树剪掉)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cascade_org_code` (`platform_id`, `cascade_org_id`) USING BTREE,
    KEY `idx_cascade_org_parent` (`parent_org_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT ='级联组织树表';

-- 通道表补组织归属列（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判重）
SET @has_org_col := (SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'tb_cascade_channel'
                       AND column_name = 'cascade_org_id');
SET @ddl := IF(@has_org_col = 0,
    'ALTER TABLE `tb_cascade_channel` ADD COLUMN `cascade_org_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''所属上级组织编码(空=挂平台根节点)'' AFTER `cascade_name`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_org_idx := (SELECT COUNT(*)
                     FROM information_schema.statistics
                     WHERE table_schema = DATABASE()
                       AND table_name = 'tb_cascade_channel'
                       AND index_name = 'idx_cascade_channel_org');
SET @ddl := IF(@has_org_idx = 0,
    'CREATE INDEX `idx_cascade_channel_org` ON `tb_cascade_channel` (`platform_id`, `cascade_org_id`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验 1：组织树表已建立
SELECT table_name, table_comment FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org';

-- 校验 2：通道表的组织归属列已存在
SELECT column_name, is_nullable, column_default FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_channel' AND column_name = 'cascade_org_id';

-- 校验 3：仍未归属任何组织的通道（升级后这些通道直接挂在平台节点下，不会消失）
SELECT id, platform_id, local_device_id, local_channel_id, cascade_channel_id
FROM tb_cascade_channel
WHERE cascade_org_id IS NULL;
