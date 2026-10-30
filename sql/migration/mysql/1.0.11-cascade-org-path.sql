-- Voglander 1.0.11 cascade org materialized path (MySQL 8)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：组织树此前只有 parent_org_id（邻接表），查"某组织下所有子组织"只能
-- 应用层递归，且「祖先是否启用」必须逐节点回表。本迁移新增 org_path 物化路径列：
--
--   org_path = 从根到自身的 cascade_org_id 链，带前导与尾随斜杠
--   根组织 A   -> /34020000002150000001/
--   A 的子 B   -> /34020000002150000001/34020000002150000002/
--
-- 之所以存"编码"而不是自增 id：tb_cascade_channel.cascade_org_id 也是编码，
-- 子树查询可以直接 JOIN 通道表，不需要再做一次 id<->code 映射。
--
-- 之所以敢用物化路径：CascadeOrgManager.update() 明确禁止修改父节点与编码
-- （改父节点等于在上级侧删旧建新），因此 org_path 一旦写入不会失效，
-- 规避了路径枚举最大的缺点——移动子树要重写全部子孙 path。
--
-- 长度：20 位编码 + 斜杠 = 21 字符/层，VARCHAR(512) 约支持 24 层。
-- 若确有更深的层级，把列扩到 TEXT 并放弃 idx_cascade_org_path 即可（查询退化为平台内扫描）。
--
-- 存量数据：org_path 默认 ''，本脚本末尾用递归 CTE 回填；回填不了的（环路脏数据）
-- 保持 ''，查询时按"无路径"处理，不影响既有上报行为。
--
-- !! 执行顺序：本脚本操作的表由 1.0.11-cascade-org-tree.sql 创建，必须先跑 tree 再跑 path。
--    注意按文件名排序时 "-path" 排在 "-tree" 之前，直接全选排序执行会报
--    ERROR 1146 Table 'xxx.tb_cascade_org' doesn't exist。

-- 0) 前置检查：表不存在时后面的 ALTER 只会抛 1146，这里先给出可读结论
SELECT IF((SELECT COUNT(*)
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = 'tb_cascade_org') = 1,
          '[OK] tb_cascade_org 已存在，可继续执行',
          '[STOP] tb_cascade_org 不存在，请先执行 1.0.11-cascade-org-tree.sql') AS migration_precheck;

-- 1) 加列（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判重）
SET @has_path_col := (SELECT COUNT(*)
                      FROM information_schema.columns
                      WHERE table_schema = DATABASE()
                        AND table_name = 'tb_cascade_org'
                        AND column_name = 'org_path');
SET @ddl := IF(@has_path_col = 0,
    'ALTER TABLE `tb_cascade_org` ADD COLUMN `org_path` VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '''' COMMENT ''物化路径：根到自身的 cascade_org_id 链，形如 /A/B/'' AFTER `parent_org_id`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 索引：前缀匹配（LIKE '/A/%'）走 (platform_id, org_path) 组合索引
SET @has_path_idx := (SELECT COUNT(*)
                      FROM information_schema.statistics
                      WHERE table_schema = DATABASE()
                        AND table_name = 'tb_cascade_org'
                        AND index_name = 'idx_cascade_org_path');
SET @ddl := IF(@has_path_idx = 0,
    'CREATE INDEX `idx_cascade_org_path` ON `tb_cascade_org` (`platform_id`, `org_path`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 存量回填：从每个根组织出发递归拼路径，一次覆盖全部层级
--    只覆盖 org_path 仍为空的记录，重复执行不会破坏已有值
UPDATE tb_cascade_org o
    JOIN (WITH RECURSIVE tree AS (SELECT id,
                                         CONCAT('/', cascade_org_id, '/') AS path
                                  FROM tb_cascade_org
                                  WHERE parent_org_id IS NULL
                                  UNION ALL
                                  SELECT c.id, CONCAT(t.path, c.cascade_org_id, '/')
                                  FROM tb_cascade_org c
                                           JOIN tree t ON c.parent_org_id = t.id)
          SELECT id, path FROM tree) x ON x.id = o.id
SET o.org_path = x.path
WHERE o.org_path = '';

-- 校验 1：列与索引已存在
SELECT column_name, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org' AND column_name = 'org_path';

-- 校验 2：仍未回填到路径的组织（应为空；有结果说明存在环路或孤儿节点，需人工核查 parent_org_id）
SELECT id, platform_id, parent_org_id, cascade_org_id, org_name
FROM tb_cascade_org
WHERE org_path = ''
ORDER BY platform_id, id;
