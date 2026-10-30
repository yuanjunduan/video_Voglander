-- Voglander 1.0.13 系统配置表 (MySQL)
--
-- 背景：gateway.cascade.catalog.* 这类参数原先只能改 application.yml 后重启，
-- 现场调分片大小/超时需要停机。本表存放「DB 覆盖值 + 代码内默认值」的配置项，
-- 键的定义（默认值/类型/说明/是否可热改）在 SysConfigCatalog 里，本表只存覆盖值。
--
-- 可重复执行：CREATE TABLE IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS `tb_sys_config`
(
    `id`           BIGINT UNSIGNED                                       NOT NULL AUTO_INCREMENT,
    `create_time`  DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `config_key`   VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '配置键',
    `config_value` VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin          DEFAULT NULL COMMENT '配置值(空=用代码内默认值)',
    `config_group` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin  NOT NULL COMMENT '配置分组',
    `value_type`   VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin  NOT NULL DEFAULT 'STRING' COMMENT 'STRING/INT/LONG/BOOLEAN',
    `description`  VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin          DEFAULT NULL COMMENT '说明',
    `editable`     TINYINT                                               NOT NULL DEFAULT 1 COMMENT '1 可热改 0 需重启',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_config_key` (`config_key`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT ='系统配置表';

-- 自查
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'tb_sys_config';
