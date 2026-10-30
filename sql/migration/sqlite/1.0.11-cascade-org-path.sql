-- Voglander 1.0.11 cascade org materialized path (SQLite)
--
-- 背景：组织树此前只有 parent_org_id（邻接表），查"某组织下所有子组织"只能
-- 应用层递归，且「祖先是否启用」必须逐节点回表。本迁移新增 org_path 物化路径列：
--
--   org_path = 从根到自身的 cascade_org_id 链，带前导与尾随斜杠
--   根组织 A   -> /34020000002150000001/
--   A 的子 B   -> /34020000002150000001/34020000002150000002/
--
-- 存"编码"而不是自增 id，是为了和 tb_cascade_channel.cascade_org_id 直接对齐。
-- 敢用物化路径的前提：CascadeOrgManager.update() 禁止修改父节点与编码，
-- org_path 一旦写入不会失效。
--
-- 长度：20 位编码 + 斜杠 = 21 字符/层，VARCHAR(512) 约支持 24 层。
--
-- 注意：
-- 1) 执行顺序：本脚本操作的表由 1.0.11-cascade-org-tree.sql 创建，必须先跑 tree 再跑 path。
--    按文件名排序时 "-path" 排在 "-tree" 之前，直接全选执行会报 no such table。
--    （应用启动时由 SqliteCascadeOrgSchemaMigrator 自动按正确顺序执行，手工执行才需注意）
-- 2) SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报
--    "duplicate column name: org_path"，属预期，可忽略；也可先执行下面第 0 步自查。
-- 3) UPDATE ... FROM 需要 SQLite 3.33+（本项目 sqlite-jdbc 远高于此版本）。

-- 0) 前置检查：表不存在时后面的 ALTER 只会抛 no such table，这里先给出可读结论
SELECT CASE
           WHEN (SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'tb_cascade_org') = 1
               THEN '[OK] tb_cascade_org 已存在，可继续执行'
           ELSE '[STOP] tb_cascade_org 不存在，请先执行 1.0.11-cascade-org-tree.sql'
           END AS migration_precheck;

-- 0b) 自查：列是否已存在（有结果即说明已升级过，可只执行第 3 步回填）
SELECT name FROM pragma_table_info('tb_cascade_org') WHERE name = 'org_path';

-- 1) 加列
ALTER TABLE tb_cascade_org ADD COLUMN org_path VARCHAR(512) NOT NULL DEFAULT '';

-- 2) 索引：前缀匹配（LIKE '/A/%'）走 (platform_id, org_path) 组合索引
CREATE INDEX IF NOT EXISTS idx_cascade_org_path ON tb_cascade_org (platform_id, org_path);

-- 3) 存量回填：从每个根组织出发递归拼路径，一次覆盖全部层级
--    只覆盖 org_path 仍为空的记录，重复执行不会破坏已有值
WITH RECURSIVE tree AS (SELECT id, '/' || cascade_org_id || '/' AS path
                        FROM tb_cascade_org
                        WHERE parent_org_id IS NULL
                        UNION ALL
                        SELECT c.id, tree.path || c.cascade_org_id || '/'
                        FROM tb_cascade_org c
                                 JOIN tree ON c.parent_org_id = tree.id)
UPDATE tb_cascade_org
SET org_path = (SELECT path FROM tree WHERE tree.id = tb_cascade_org.id)
WHERE org_path = ''
  AND id IN (SELECT id FROM tree);

-- 校验 1：列已存在
SELECT name, type, "notnull", dflt_value FROM pragma_table_info('tb_cascade_org') WHERE name = 'org_path';

-- 校验 2：仍未回填到路径的组织（应为空；有结果说明存在环路或孤儿节点，需人工核查 parent_org_id）
SELECT id, platform_id, parent_org_id, cascade_org_id, org_name
FROM tb_cascade_org
WHERE org_path = ''
ORDER BY platform_id, id;
