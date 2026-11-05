-- =============================================================================
-- 1.10.5 级联「本端客户端」抽取（PostgreSQL）
--
-- 与 mysql/1.10.5-cascade-client.sql 等价，用 IF [NOT] EXISTS 保证可重复执行。
-- SQLite 不需要本脚本：SqliteCascadeClientSchemaMigrator 启动时自动完成。
--
-- 执行前请备份 tb_cascade_platform。
-- =============================================================================

BEGIN;

-- 1) 本端客户端表
CREATE TABLE IF NOT EXISTS tb_cascade_client
(
    id          BIGSERIAL,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_id   VARCHAR(64)  NOT NULL,
    client_name VARCHAR(128) NOT NULL DEFAULT '',
    client_ip   VARCHAR(64)  NOT NULL DEFAULT '',
    is_default  SMALLINT     NOT NULL DEFAULT 0,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(255) DEFAULT NULL,
    extend      TEXT,
    PRIMARY KEY (id),
    UNIQUE (client_id)
);
COMMENT ON COLUMN tb_cascade_client.client_id IS '本端平台编码(20位)，创建后不可改';
COMMENT ON COLUMN tb_cascade_client.client_ip IS '对外通告IP(Contact/From用)，必须是上级可回包地址';
COMMENT ON COLUMN tb_cascade_client.is_default IS '新增上级平台时默认预选 0否 1是(全局唯一)';
CREATE INDEX IF NOT EXISTS idx_cascade_client_default ON tb_cascade_client (is_default);

-- 2) 平台表加外键列
ALTER TABLE tb_cascade_platform ADD COLUMN IF NOT EXISTS client_ref_id BIGINT DEFAULT NULL;
COMMENT ON COLUMN tb_cascade_platform.client_ref_id IS '本端客户端(tb_cascade_client.id)，应用层必填';

-- 3) 收敛客户端行：同一 local_client_id 只留一条，IP 取 id 最小那行
INSERT INTO tb_cascade_client (client_id, client_name, client_ip, is_default, enabled)
SELECT p.local_client_id, '', COALESCE(p.local_ip, ''), 0, 1
FROM tb_cascade_platform p
         JOIN (SELECT local_client_id, MIN(id) AS id
               FROM tb_cascade_platform
               WHERE local_client_id <> ''
               GROUP BY local_client_id) m ON m.id = p.id
WHERE NOT EXISTS (SELECT 1 FROM tb_cascade_client c WHERE c.client_id = p.local_client_id);

-- 4) 首选身份置为默认行（已有默认行时不改写）
UPDATE tb_cascade_client
SET is_default = 1
WHERE id = (SELECT MIN(id) FROM tb_cascade_client)
  AND NOT EXISTS (SELECT 1 FROM tb_cascade_client WHERE is_default = 1);

-- 5) 回填引用
UPDATE tb_cascade_platform p
SET client_ref_id = c.id
FROM tb_cascade_client c
WHERE c.client_id = p.local_client_id
  AND p.client_ref_id IS NULL;

-- 6) 删除冗余列
ALTER TABLE tb_cascade_platform
    DROP COLUMN IF EXISTS local_client_id,
    DROP COLUMN IF EXISTS local_ip,
    DROP COLUMN IF EXISTS local_port;

CREATE INDEX IF NOT EXISTS idx_cascade_platform_client ON tb_cascade_platform (client_ref_id);

COMMIT;

-- 核对
-- SELECT COUNT(*) FROM tb_cascade_platform WHERE client_ref_id IS NULL;  -- 期望 0（无编码的空行除外）

-- =============================================================================
-- 回滚（仅在未写入新数据时可用于救急）
-- =============================================================================
-- BEGIN;
-- ALTER TABLE tb_cascade_platform
--     ADD COLUMN IF NOT EXISTS local_client_id VARCHAR(64) NOT NULL DEFAULT '',
--     ADD COLUMN IF NOT EXISTS local_ip        VARCHAR(64) DEFAULT NULL,
--     ADD COLUMN IF NOT EXISTS local_port      INT NOT NULL DEFAULT 5061;
-- UPDATE tb_cascade_platform p
-- SET local_client_id = c.client_id,
--     local_ip        = c.client_ip,
--     local_port      = 5061
-- FROM tb_cascade_client c
-- WHERE c.id = p.client_ref_id;
-- DROP INDEX IF EXISTS idx_cascade_platform_client;
-- ALTER TABLE tb_cascade_platform DROP COLUMN IF EXISTS client_ref_id;
-- DROP TABLE IF EXISTS tb_cascade_client;
-- COMMIT;
