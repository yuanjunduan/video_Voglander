-- =============================================================================
-- 1.10.5 级联「本端客户端」抽取（MySQL）
--
-- 背景：tb_cascade_platform 原本每行冗余一份 local_client_id / local_ip / local_port，
--       新增第二个上级平台要再抄一遍同样的值；抄错的本端 IP 会让上级把 INVITE 打到
--       不可达地址，而页面仍显示「在线」（注册是主动外发的，对端收得到 UDP 就算成功）。
--
-- 本脚本做的事：
--   1) 建 tb_cascade_client（本端平台身份：编码 + 对外通告 IP；端口不落库）
--   2) 平台表加 client_ref_id
--   3) 按 local_client_id 收敛出客户端行（同一身份只留一条，IP 取 id 最小那行的值）
--   4) 首选身份置为默认行（新增平台时预选）
--   5) 回填 client_ref_id
--   6) 删除三个 local_* 列
--
-- 注意：
--   * 本脚本只执行一次（ADD/DROP COLUMN 不是幂等语句）；
--   * 执行前务必备份 tb_cascade_platform（回滚只能靠备份或文末的回滚段）；
--   * SQLite 不需要本脚本：SqliteCascadeClientSchemaMigrator 会在启动时自动完成同样的动作；
--   * 本端端口一律由运行时配置 sip.client.port 提供，表里不再保存。
-- =============================================================================

-- 1) 本端客户端表
CREATE TABLE IF NOT EXISTS `tb_cascade_client`
(
    `id`          BIGINT UNSIGNED                                       NOT NULL AUTO_INCREMENT,
    `create_time` DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `client_id`   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '本端平台编码(20位)，创建后不可改',
    `client_name` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '本地辨识名，空=未命名',
    `client_ip`   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '对外通告IP(Contact/From用)',
    `is_default`  TINYINT                                               NOT NULL DEFAULT 0 COMMENT '新增上级平台时默认预选 0否 1是(全局唯一)',
    `enabled`     TINYINT                                               NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    `remark`      VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin         DEFAULT NULL COMMENT '备注',
    `extend`      TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT '扩展字段(FastJSON2)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cascade_client_id` (`client_id`) USING BTREE,
    KEY `idx_cascade_client_default` (`is_default`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT ='级联本端客户端表';

-- 2) 平台表加外键列
ALTER TABLE `tb_cascade_platform`
    ADD COLUMN `client_ref_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '本端客户端(tb_cascade_client.id)，应用层必填';

-- 3) 收敛客户端行：同一 local_client_id 只留一条，IP 取 id 最小那行
INSERT INTO `tb_cascade_client` (`client_id`, `client_name`, `client_ip`, `is_default`, `enabled`)
SELECT p.`local_client_id`, '', COALESCE(p.`local_ip`, ''), 0, 1
FROM `tb_cascade_platform` p
         JOIN (SELECT `local_client_id`, MIN(`id`) AS `id`
               FROM `tb_cascade_platform`
               WHERE `local_client_id` <> ''
               GROUP BY `local_client_id`) m ON m.`id` = p.`id`
WHERE NOT EXISTS (SELECT 1 FROM `tb_cascade_client` c WHERE c.`client_id` = p.`local_client_id`);

-- 4) 首选身份置为默认行（已有默认行时不改写）
SET @has_default = (SELECT COUNT(*) FROM `tb_cascade_client` WHERE `is_default` = 1);
SET @min_id = (SELECT MIN(`id`) FROM `tb_cascade_client`);
UPDATE `tb_cascade_client` SET `is_default` = 1 WHERE `id` = @min_id AND @has_default = 0;

-- 5) 回填引用
UPDATE `tb_cascade_platform` p
    JOIN `tb_cascade_client` c ON c.`client_id` = p.`local_client_id`
SET p.`client_ref_id` = c.`id`
WHERE p.`local_client_id` <> '';

-- 6) 删除冗余列（顺序在回填之后，否则无从按旧值回填）
ALTER TABLE `tb_cascade_platform`
    DROP COLUMN `local_client_id`,
    DROP COLUMN `local_ip`,
    DROP COLUMN `local_port`;

ALTER TABLE `tb_cascade_platform`
    ADD KEY `idx_cascade_platform_client` (`client_ref_id`) USING BTREE;

-- 核对：应为 0
-- SELECT COUNT(*) FROM `tb_cascade_platform` WHERE `local_client_id` IS NOT NULL; -- 列已删，此句仅作心理核对
-- SELECT COUNT(*) FROM `tb_cascade_platform` WHERE `client_ref_id` IS NULL;        -- 期望 0（无编码的空行除外）

-- =============================================================================
-- 回滚（仅在没有写入新数据的前提下可用；执行前请确认）
-- =============================================================================
-- ALTER TABLE `tb_cascade_platform`
--     ADD COLUMN `local_client_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
--     ADD COLUMN `local_ip`        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
--     ADD COLUMN `local_port`      INT NOT NULL DEFAULT 5061;
-- UPDATE `tb_cascade_platform` p
--     JOIN `tb_cascade_client` c ON c.`id` = p.`client_ref_id`
-- SET p.`local_client_id` = c.`client_id`,
--     p.`local_ip`        = c.`client_ip`,
--     p.`local_port`      = 5061;
-- ALTER TABLE `tb_cascade_platform` DROP KEY `idx_cascade_platform_client`, DROP COLUMN `client_ref_id`;
-- DROP TABLE IF EXISTS `tb_cascade_client`;
