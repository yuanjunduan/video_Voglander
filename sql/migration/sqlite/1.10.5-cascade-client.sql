-- =============================================================================
-- 1.10.5 级联「本端客户端」抽取（SQLite，离线留档）
--
-- 本文件不会被程序执行：SQLite 存量库由 voglander-repository 的
-- SqliteCascadeClientSchemaMigrator（迁移键 cascade-client-extraction）在启动时自动完成，
-- 权威建表脚本是 sql/voglander-sqlite.sql。此处只作评审与手工修库留档，两者逻辑必须一致。
--
-- 手工修库前请先停进程，否则 WAL 里可能有未落盘的事务。
-- =============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS tb_cascade_client
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_id   VARCHAR(64)                            NOT NULL,
    client_name VARCHAR(128) DEFAULT ''                NOT NULL,
    client_ip   VARCHAR(64)  DEFAULT ''                NOT NULL,
    is_default  INTEGER      DEFAULT 0                 NOT NULL,
    enabled     INTEGER      DEFAULT 1                 NOT NULL,
    remark      VARCHAR(255) DEFAULT NULL,
    extend      TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cascade_client_id ON tb_cascade_client (client_id);
CREATE INDEX IF NOT EXISTS idx_cascade_client_default ON tb_cascade_client (is_default);

-- 平台表加外键列（SQLite 的 ADD COLUMN 无 IF NOT EXISTS，重复执行会报错，属预期）
ALTER TABLE tb_cascade_platform ADD COLUMN client_ref_id INTEGER;

INSERT INTO tb_cascade_client (client_id, client_name, client_ip, is_default, enabled)
SELECT p.local_client_id, '', COALESCE(p.local_ip, ''), 0, 1
FROM tb_cascade_platform p
         JOIN (SELECT local_client_id, MIN(id) AS id
               FROM tb_cascade_platform
               WHERE local_client_id <> ''
               GROUP BY local_client_id) m ON m.id = p.id
WHERE NOT EXISTS (SELECT 1 FROM tb_cascade_client c WHERE c.client_id = p.local_client_id);

UPDATE tb_cascade_client
SET is_default = 1
WHERE id = (SELECT MIN(id) FROM tb_cascade_client)
  AND NOT EXISTS (SELECT 1 FROM tb_cascade_client WHERE is_default = 1);

UPDATE tb_cascade_platform
SET client_ref_id = (SELECT c.id FROM tb_cascade_client c WHERE c.client_id = tb_cascade_platform.local_client_id)
WHERE local_client_id <> '';

ALTER TABLE tb_cascade_platform DROP COLUMN local_client_id;
ALTER TABLE tb_cascade_platform DROP COLUMN local_ip;
ALTER TABLE tb_cascade_platform DROP COLUMN local_port;

CREATE INDEX IF NOT EXISTS idx_cascade_platform_client ON tb_cascade_platform (client_ref_id);

COMMIT;

-- 核对
-- SELECT COUNT(*) FROM tb_cascade_platform WHERE client_ref_id IS NULL;  -- 期望 0（无编码的空行除外）
