-- Voglander 1.0.13 系统配置表 (SQLite)
--
-- 可重复执行：新增表与索引都用 IF NOT EXISTS。
-- 说明：SQLite 侧同时由 SqliteSysConfigSchemaMigrator 在启动时兜底建表，
-- 本脚本用于手工升级已有 app.db / voglander-web/app.db。

CREATE TABLE IF NOT EXISTS tb_sys_config
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    config_key   VARCHAR(128)                            NOT NULL,
    config_value VARCHAR(512) DEFAULT NULL,
    config_group VARCHAR(64)                             NOT NULL,
    value_type   VARCHAR(16)  DEFAULT 'STRING'           NOT NULL,
    description  VARCHAR(255) DEFAULT NULL,
    editable     INTEGER      DEFAULT 1                  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_config_key ON tb_sys_config (config_key);

-- 自查
SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'tb_sys_config';
