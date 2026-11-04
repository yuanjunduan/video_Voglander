-- Voglander 1.10.1 cascade local asset and publish (PostgreSQL 9.6+)
--
-- 背景与字段含义同 MySQL 版：1.10.1 把级联组织从「一个上级平台一套树」改为
-- 「本地资产一套（org_code 全局唯一）+ 发布表一套可见性（tb_cascade_publish）」。
--
-- 执行顺序不可调换：platform_id 是「这个组织曾经报给谁」的唯一记录，
-- 必须在删列之前翻译成发布行，否则升级后上级目录会整体变空。
--
-- 幂等：每一步自带判据，重复执行只跳过、不报错。
-- 执行前请备份 tb_cascade_org、tb_cascade_channel、tb_device。

-- ============================================================
-- 1. 发布表
-- ============================================================
CREATE TABLE IF NOT EXISTS tb_cascade_publish
(
    id            BIGSERIAL,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    platform_id   VARCHAR(64)  NOT NULL,
    object_type   VARCHAR(16)  NOT NULL,
    object_id     BIGINT       NOT NULL,
    publish_code  VARCHAR(64)  DEFAULT NULL,
    publish_name  VARCHAR(128) DEFAULT NULL,
    enabled       SMALLINT     NOT NULL DEFAULT 1,
    sort_order    INT          DEFAULT NULL,
    manufacturer  VARCHAR(64)  DEFAULT NULL,
    model         VARCHAR(128) DEFAULT NULL,
    address       VARCHAR(255) DEFAULT NULL,
    ip_address    VARCHAR(64)  DEFAULT NULL,
    ptz_type      INT          DEFAULT NULL,
    civil_code    VARCHAR(64)  DEFAULT NULL,
    revoke_policy VARCHAR(16)  DEFAULT NULL,
    extend        TEXT,
    PRIMARY KEY (id),
    UNIQUE (platform_id, object_type, object_id),
    UNIQUE (platform_id, publish_code)
);
COMMENT ON COLUMN tb_cascade_publish.revoke_policy IS '本平台专用：该组织不上报时其下通道处置 CASCADE/REROUTE';
CREATE INDEX IF NOT EXISTS idx_publish_type ON tb_cascade_publish (platform_id, object_type, enabled);

-- ============================================================
-- 2. 组织表：去平台化（老形状才执行）
-- ============================================================
DO $$
DECLARE
    v_legacy BOOLEAN;
    v_rewritten BIGINT;
BEGIN
    SELECT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'tb_cascade_org' AND column_name = 'platform_id') INTO v_legacy;
    IF NOT v_legacy THEN
        RAISE NOTICE 'tb_cascade_org 已是去平台化的新形状，跳过';
        RETURN;
    END IF;

    -- 2.1 同一个 cascade_org_id 在多个平台各有一行：首个（id 最小）保留原值，
    --     其余拼 -platform_id 后缀保命，否则单列唯一键会撞。
    --     不能加 ON COMMIT DROP：脚本按单语句事务执行，那样建完就被删了。
    CREATE TEMP TABLE tmp_org_rewritten AS
    SELECT o.id, o.cascade_org_id AS orig_code, o.platform_id
      FROM tb_cascade_org o
      JOIN (SELECT cascade_org_id, MIN(id) AS keep_id
              FROM tb_cascade_org
             GROUP BY cascade_org_id) k
        ON k.cascade_org_id = o.cascade_org_id
     WHERE o.id <> k.keep_id;
    GET DIAGNOSTICS v_rewritten = ROW_COUNT;

    -- 2.2 旧 platform_id 翻译成发布行。这是删列前唯一能抢救「报给谁」的机会。
    INSERT INTO tb_cascade_publish (platform_id, object_type, object_id, enabled)
    SELECT platform_id, 'ORG', id, 1
      FROM tb_cascade_org
     WHERE platform_id IS NOT NULL AND platform_id <> ''
    ON CONFLICT DO NOTHING;

    -- 2.3 编码被改写的那批，用原码补成「该平台的编码覆盖」，
    --     否则上级看到的 DeviceID 整体换了名，会被当成一批新节点。
    UPDATE tb_cascade_publish p
       SET publish_code = t.orig_code
      FROM tmp_org_rewritten t
     WHERE t.id = p.object_id AND p.object_type = 'ORG';

    UPDATE tb_cascade_org o
       SET cascade_org_id = o.cascade_org_id || '-' || o.platform_id
      FROM tmp_org_rewritten t
     WHERE t.id = o.id;

    DROP TABLE tmp_org_rewritten;

    -- 2.4 索引跟着旧列名走，改列前必须先拆
    DROP INDEX IF EXISTS uk_cascade_org_code;
    DROP INDEX IF EXISTS idx_cascade_org_path;
    DROP INDEX IF EXISTS idx_cascade_org_parent;

    -- 2.5 列改名
    ALTER TABLE tb_cascade_org RENAME COLUMN cascade_org_id TO org_code;
    ALTER TABLE tb_cascade_org RENAME COLUMN parent_org_id TO parent_id;

    -- 2.6 组织表上的 revoke_policy 迁到发布表后就地删除：
    --     它表达的是「推给某个平台时组织停用后通道怎么办」，同一组织两个平台可以是两套答案。
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'tb_cascade_org' AND column_name = 'revoke_policy') THEN
        EXECUTE 'UPDATE tb_cascade_publish p
                    SET revoke_policy = o.revoke_policy
                   FROM tb_cascade_org o
                  WHERE o.id = p.object_id
                    AND o.platform_id = p.platform_id
                    AND p.object_type = ''ORG''';
        ALTER TABLE tb_cascade_org DROP COLUMN revoke_policy;
    END IF;

    -- 2.7 丢掉 platform_id：这一步之后「报给谁」只剩发布表能回答
    ALTER TABLE tb_cascade_org DROP COLUMN platform_id;

    -- 2.8 没跑过 1.0.11-cascade-org-path 的库会缺这一列，补上再建索引
    ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS org_path VARCHAR(512) NOT NULL DEFAULT '';

    -- 2.9 按新形状重建索引
    CREATE UNIQUE INDEX IF NOT EXISTS uk_cascade_org_code ON tb_cascade_org (org_code);
    CREATE INDEX IF NOT EXISTS idx_cascade_org_parent ON tb_cascade_org (parent_id);
    CREATE INDEX IF NOT EXISTS idx_cascade_org_path ON tb_cascade_org (org_path);

    RAISE NOTICE 'tb_cascade_org 去平台化完成，改写编码 % 条，发布行 % 条', v_rewritten,
        (SELECT COUNT(*) FROM tb_cascade_publish WHERE object_type = 'ORG');
END $$;

-- ============================================================
-- 3. 七段编码规则列（预留：只补列，不写值，code_rule_enabled 默认关）
-- ============================================================
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS code_province     VARCHAR(2)  DEFAULT NULL;
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS code_city         VARCHAR(2)  DEFAULT NULL;
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS code_county       VARCHAR(4)  DEFAULT NULL;
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS code_industry     VARCHAR(2)  DEFAULT NULL;
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS code_type         VARCHAR(3)  DEFAULT NULL;
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS code_network      VARCHAR(1)  DEFAULT NULL;
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS code_start_seq    INT         DEFAULT NULL;
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS code_rule_enabled SMALLINT    NOT NULL DEFAULT 0;

-- ============================================================
-- 4. 重算物化路径
-- ============================================================
-- 2.3 改写过编码，而 org_path 是「根到自身的 org_code 链」，必须整棵重算；
-- 子树的 LIKE 查询全靠它一次取全任意层级，留着旧路径会让子孙节点查不到。
WITH RECURSIVE tree AS (SELECT id, '/' || org_code || '/' AS path
                          FROM tb_cascade_org
                         WHERE parent_id IS NULL
                         UNION ALL
                        SELECT c.id, t.path || c.org_code || '/'
                          FROM tb_cascade_org c
                          JOIN tree t ON c.parent_id = t.id)
UPDATE tb_cascade_org o
   SET org_path = t.path
  FROM tree t
 WHERE t.id = o.id;

-- ============================================================
-- 5. 设备归属组织（可空：允许设备先接入、后归组织，现场调试常见）
-- ============================================================
ALTER TABLE tb_device ADD COLUMN IF NOT EXISTS org_id BIGINT DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_device_org ON tb_device (org_id);

-- ============================================================
-- 6. 校验
-- ============================================================
-- 6.1 组织表不再有平台列
SELECT column_name FROM information_schema.columns
WHERE table_name = 'tb_cascade_org'
  AND column_name IN ('platform_id', 'cascade_org_id', 'parent_org_id', 'revoke_policy');

-- 6.2 发布行按平台分布（这些就是升级后各上级能看到的组织数）
SELECT platform_id, COUNT(*) AS org_count
FROM tb_cascade_publish
WHERE object_type = 'ORG' AND enabled = 1
GROUP BY platform_id;

-- 6.3 挂在通道上、却没有发布行的组织（这些通道升级后不会出现在任何上级目录里，
--     需要人工在页面上确认：是要补发布，还是本来就不该报）
SELECT DISTINCT c.platform_id, c.cascade_org_id
FROM tb_cascade_channel c
LEFT JOIN tb_cascade_org o ON o.org_code = c.cascade_org_id
LEFT JOIN tb_cascade_publish p
       ON p.object_type = 'ORG' AND p.object_id = o.id AND p.platform_id = c.platform_id
WHERE c.cascade_org_id IS NOT NULL
  AND c.cascade_org_id <> ''
  AND p.id IS NULL;
