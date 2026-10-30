-- Voglander 1.0.13 系统配置表 (PostgreSQL)
--
-- 可重复执行：CREATE TABLE IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS tb_sys_config
(
    id           BIGSERIAL,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    config_key   VARCHAR(128) NOT NULL,
    config_value VARCHAR(512)          DEFAULT NULL,
    config_group VARCHAR(64)  NOT NULL,
    value_type   VARCHAR(16)  NOT NULL DEFAULT 'STRING',
    description  VARCHAR(255)          DEFAULT NULL,
    editable     SMALLINT     NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE (config_key)
);

-- 自查
SELECT table_name FROM information_schema.tables
WHERE table_schema = current_schema() AND table_name = 'tb_sys_config';
