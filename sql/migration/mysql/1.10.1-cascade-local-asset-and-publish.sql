-- Voglander 1.10.1 cascade local asset and publish (MySQL 8)
--
-- 背景：1.10.1 之前是「一个上级平台一套组织树」——tb_cascade_org 带 platform_id，
-- 同一份组织结构在 N 个平台里录了 N 遍，改一次名要改 N 次，还经常改漏。
-- 1.10.1 把组织改成本地资产（全局一套、org_code 唯一），
-- 「推给哪个平台」搬到新的 tb_cascade_publish：结构一份，可见性 N 份。
--
-- 执行顺序不可调换：platform_id 是「这个组织曾经报给谁」的唯一记录，
-- 必须在删列之前翻译成发布行，否则升级后上级目录会整体变空。
--
-- 幂等：每一步都以 information_schema 判据自守，重复执行只跳过、不报错。
-- 中途失败按步骤编号续跑即可——已完成的步骤判据不成立，会被跳过。
-- 执行前请备份 tb_cascade_org、tb_cascade_channel、tb_device。

-- ============================================================
-- 1. 发布表：每个上级平台一套可见性视图
-- ============================================================
CREATE TABLE IF NOT EXISTS `tb_cascade_publish`
(
    `id`            BIGINT UNSIGNED                                       NOT NULL AUTO_INCREMENT,
    `create_time`   DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `platform_id`   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '上级平台ID',
    `object_type`   VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ORG/DEVICE/CHANNEL',
    `object_id`     BIGINT UNSIGNED                                       NOT NULL COMMENT '本地对象ID',
    `publish_code`  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '上报编码(该平台)，空=沿用本地编码',
    `publish_name`  VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '上报名称，空=用本地名称',
    `enabled`       TINYINT                                               NOT NULL DEFAULT 1 COMMENT '1上报 0不报',
    `sort_order`    INT                                                            DEFAULT NULL COMMENT '同级排序，空=用本地排序',
    `manufacturer`  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '目录元数据覆盖-厂商',
    `model`         VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '目录元数据覆盖-型号',
    `address`       VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '目录元数据覆盖-地址',
    `ip_address`    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '目录元数据覆盖-IP',
    `ptz_type`      INT                                                   DEFAULT NULL COMMENT '目录元数据覆盖-摄像机类型',
    `civil_code`    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '目录元数据覆盖-行政区划码',
    `revoke_policy` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '本平台专用：该组织不上报时其下通道处置 CASCADE/REROUTE',
    `extend`        TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT '扩展字段(FastJSON2)',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT ='级联发布配置';

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_publish'
                   AND index_name = 'uk_publish_object');
SET @ddl := IF(@has_idx = 0,
    'CREATE UNIQUE INDEX `uk_publish_object` ON `tb_cascade_publish` (`platform_id`, `object_type`, `object_id`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 跨对象类型防重码：上级目录按 DeviceID 建索引，组织码与通道码撞了同样是丢节点。
-- publish_code 为空时不参与唯一约束（MySQL 允许多个 NULL），所以没被覆盖的对象不占编码空间。
SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_publish'
                   AND index_name = 'uk_publish_code');
SET @ddl := IF(@has_idx = 0,
    'CREATE UNIQUE INDEX `uk_publish_code` ON `tb_cascade_publish` (`platform_id`, `publish_code`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_publish'
                   AND index_name = 'idx_publish_type');
SET @ddl := IF(@has_idx = 0,
    'CREATE INDEX `idx_publish_type` ON `tb_cascade_publish` (`platform_id`, `object_type`, `enabled`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 2. 组织表：去平台化（老形状才执行）
-- ============================================================
DELIMITER $$

DROP PROCEDURE IF EXISTS `voglander_1_10_1_cascade_org` $$
CREATE PROCEDURE `voglander_1_10_1_cascade_org`()
BEGIN
    DECLARE v_legacy INT DEFAULT 0;
    DECLARE v_has_revoke INT DEFAULT 0;

    SELECT COUNT(*) INTO v_legacy FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org' AND column_name = 'platform_id';
    IF v_legacy = 0 THEN
        SELECT 'tb_cascade_org 已是去平台化的新形状，跳过' AS result;
    ELSE
        -- 2.1 同一个 cascade_org_id 在多个平台各有一行：先把「非首个」的 id 和原编码记下来，
        --     首个（id 最小）保留原值，其余拼 -platform_id 后缀保命。不这么做，单列唯一键会撞。
        DROP TEMPORARY TABLE IF EXISTS tmp_org_rewritten;
        CREATE TEMPORARY TABLE tmp_org_rewritten
        (
            id        BIGINT UNSIGNED NOT NULL PRIMARY KEY,
            orig_code VARCHAR(64)     NOT NULL
        );
        INSERT INTO tmp_org_rewritten (id, orig_code)
        SELECT o.id, o.cascade_org_id
          FROM tb_cascade_org o
          JOIN (SELECT cascade_org_id, MIN(id) AS keep_id
                  FROM tb_cascade_org
                 GROUP BY cascade_org_id) k
            ON k.cascade_org_id = o.cascade_org_id
         WHERE o.id <> k.keep_id;

        -- 2.2 旧 platform_id 翻译成发布行。这是删列前唯一能抢救「报给谁」的机会。
        INSERT IGNORE INTO `tb_cascade_publish` (`platform_id`, `object_type`, `object_id`, `enabled`)
        SELECT `platform_id`, 'ORG', `id`, 1
          FROM `tb_cascade_org`
         WHERE `platform_id` IS NOT NULL AND `platform_id` <> '';

        -- 2.3 编码被改写的那批，用原码补成「该平台的编码覆盖」，
        --     否则上级看到的 DeviceID 整体换了名，会被当成一批新节点。
        UPDATE `tb_cascade_publish` p
          JOIN tmp_org_rewritten t ON t.id = p.object_id
           SET p.publish_code = t.orig_code
         WHERE p.object_type = 'ORG';

        UPDATE `tb_cascade_org` o
          JOIN tmp_org_rewritten t ON t.id = o.id
           SET o.cascade_org_id = CONCAT(o.cascade_org_id, '-', o.platform_id);

        DROP TEMPORARY TABLE IF EXISTS tmp_org_rewritten;

        -- 2.4 索引跟着旧列名走，改列前必须先拆
        IF EXISTS (SELECT 1 FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                      AND index_name = 'uk_cascade_org_code') THEN
            ALTER TABLE `tb_cascade_org` DROP INDEX `uk_cascade_org_code`;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                      AND index_name = 'idx_cascade_org_path') THEN
            ALTER TABLE `tb_cascade_org` DROP INDEX `idx_cascade_org_path`;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                      AND index_name = 'idx_cascade_org_parent') THEN
            ALTER TABLE `tb_cascade_org` DROP INDEX `idx_cascade_org_parent`;
        END IF;

        -- 2.5 列改名
        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                      AND column_name = 'cascade_org_id') THEN
            ALTER TABLE `tb_cascade_org`
                CHANGE COLUMN `cascade_org_id` `org_code` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
                    NOT NULL COMMENT '组织编码(20位，全局唯一)';
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                      AND column_name = 'parent_org_id') THEN
            ALTER TABLE `tb_cascade_org`
                CHANGE COLUMN `parent_org_id` `parent_id` BIGINT UNSIGNED
                    DEFAULT NULL COMMENT '父组织(tb_cascade_org.id)，空=根组织挂域节点';
        END IF;

        -- 2.6 组织表上的 revoke_policy 迁到发布表后就地删除；
        --     它表达的是「推给某个平台时组织停用后通道怎么办」，同一组织两个平台可以是两套答案。
        SELECT COUNT(*) INTO v_has_revoke FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org' AND column_name = 'revoke_policy';
        IF v_has_revoke > 0 THEN
            -- 旧列值先按平台搬进发布行（行可能已在 2.2 建好）
            UPDATE `tb_cascade_publish` p
              JOIN `tb_cascade_org` o ON o.id = p.object_id AND o.platform_id = p.platform_id
               SET p.revoke_policy = o.revoke_policy
             WHERE p.object_type = 'ORG';
            ALTER TABLE `tb_cascade_org` DROP COLUMN `revoke_policy`;
        END IF;

        -- 2.7 丢掉 platform_id：这一步之后「报给谁」只剩发布表能回答
        ALTER TABLE `tb_cascade_org` DROP COLUMN `platform_id`;

        -- 2.8 没跑过 1.0.11-cascade-org-path 的库会缺这一列，补上再建索引
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                          AND column_name = 'org_path') THEN
            ALTER TABLE `tb_cascade_org`
                ADD COLUMN `org_path` VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
                    NOT NULL DEFAULT '' COMMENT '物化路径：根到自身的 org_code 链，形如 /A/B/';
        END IF;

        -- 2.9 按新形状重建索引
        ALTER TABLE `tb_cascade_org` ADD UNIQUE KEY `uk_cascade_org_code` (`org_code`) USING BTREE;
        ALTER TABLE `tb_cascade_org` ADD KEY `idx_cascade_org_parent` (`parent_id`) USING BTREE;
        ALTER TABLE `tb_cascade_org` ADD KEY `idx_cascade_org_path` (`org_path`) USING BTREE;

        SELECT CONCAT('tb_cascade_org 去平台化完成，改写编码 ',
                      (SELECT COUNT(*) FROM tb_cascade_publish WHERE object_type = 'ORG'), ' 条发布行') AS result;
    END IF;
END $$

DELIMITER ;

CALL `voglander_1_10_1_cascade_org`();
DROP PROCEDURE `voglander_1_10_1_cascade_org`;

-- ============================================================
-- 3. 七段编码规则列（预留：只补列，不写值，code_rule_enabled 默认关）
-- ============================================================
-- 用来生成组织编码：prefix(10位) + 类型(3位,215/216) + 网络(1位) + 序号(6位)。
-- 四段一直有值（前缀、类型、网络、序号），县区四段只在需要细分时用，故拆成多列便于逐段推导。
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                   AND column_name = 'code_province') = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `code_province` VARCHAR(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''编码规则-省编码(2位)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                   AND column_name = 'code_city') = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `code_city` VARCHAR(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''编码规则-市编码(2位)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                   AND column_name = 'code_county') = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `code_county` VARCHAR(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''编码规则-县区编码(4位)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                   AND column_name = 'code_industry') = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `code_industry` VARCHAR(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''编码规则-行业编码(2位)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                   AND column_name = 'code_type') = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `code_type` VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''编码规则-类型编码(3位 215/216)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                   AND column_name = 'code_network') = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `code_network` VARCHAR(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''编码规则-网络标识(1位)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                   AND column_name = 'code_start_seq') = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `code_start_seq` INT DEFAULT NULL COMMENT ''编码规则-起始序号(6位)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
                   AND column_name = 'code_rule_enabled') = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `code_rule_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT ''0=未启用(后端默认生成) 1=按七段规则生成''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 4. 重算物化路径
-- ============================================================
-- 2.3 改写过编码，而 org_path 是「根到自身的 org_code 链」，必须整棵重算；
-- 子树的 LIKE 查询全靠它一次取全任意层级，留着旧路径会让子孙节点查不到。
UPDATE `tb_cascade_org` o
    JOIN (WITH RECURSIVE tree AS (SELECT id, CONCAT('/', org_code, '/') AS path
                                    FROM tb_cascade_org
                                   WHERE parent_id IS NULL
                                   UNION ALL
                                  SELECT c.id, CONCAT(t.path, c.org_code, '/')
                                    FROM tb_cascade_org c
                                    JOIN tree t ON c.parent_id = t.id)
          SELECT id, path FROM tree) t ON t.id = o.id
   SET o.org_path = t.path;

-- ============================================================
-- 5. 设备归属组织（可空：允许设备先接入、后归组织，现场调试常见）
-- ============================================================
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tb_device'
                   AND column_name = 'org_id') = 0,
    'ALTER TABLE `tb_device` ADD COLUMN `org_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''所属组织(tb_cascade_org.id)，空=未归属''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'tb_device' AND index_name = 'idx_device_org');
SET @ddl := IF(@has_idx = 0, 'CREATE INDEX `idx_device_org` ON `tb_device` (`org_id`)', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 6. 校验
-- ============================================================
-- 6.1 组织表不再有平台列
SELECT column_name FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org'
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
