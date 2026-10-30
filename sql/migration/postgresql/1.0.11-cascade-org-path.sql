-- Voglander 1.0.11 cascade org materialized path (PostgreSQL)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：组织树此前只有 parent_org_id（邻接表），查"某组织下所有子组织"只能
-- 应用层递归，且「祖先是否启用」必须逐节点回表。本迁移新增 org_path 物化路径列：
--
--   org_path = 从根到自身的 cascade_org_id 链，带前导与尾随斜杠
--   根组织 A   -> /34020000002150000001/
--   A 的子 B   -> /34020000002150000001/34020000002150000002/
--
-- 存"编码"而不是自增 id，是为了和 tb_cascade_channel.cascade_org_id 直接对齐，
-- 子树查询可以 JOIN 通道表，省掉一次 id<->code 映射。
--
-- 敢用物化路径的前提：CascadeOrgManager.update() 禁止修改父节点与编码，
-- org_path 一旦写入不会失效，规避了"移动子树要重写全部子孙 path"的硬伤。
--
-- 长度：20 位编码 + 斜杠 = 21 字符/层，VARCHAR(512) 约支持 24 层。
--
-- 存量数据：org_path 默认 ''，本脚本末尾用递归 CTE 回填；回填不了的（环路脏数据）
-- 保持 ''，查询时按"无路径"处理，不影响既有上报行为。
--
-- !! 执行顺序：本脚本操作的表由 1.0.11-cascade-org-tree.sql 创建，必须先跑 tree 再跑 path。
--    注意按文件名排序时 "-path" 排在 "-tree" 之前，直接全选排序执行会报
--    ERROR: relation "tb_cascade_org" does not exist。

-- 0) 前置检查：表不存在时后面的 ALTER 只会抛 relation does not exist，这里先给出可读结论
SELECT CASE
           WHEN to_regclass('tb_cascade_org') IS NULL
               THEN '[STOP] tb_cascade_org 不存在，请先执行 1.0.11-cascade-org-tree.sql'
           ELSE '[OK] tb_cascade_org 已存在，可继续执行'
           END AS migration_precheck;

-- 1) 加列
ALTER TABLE tb_cascade_org ADD COLUMN IF NOT EXISTS org_path VARCHAR(512) NOT NULL DEFAULT '';

-- 2) 索引：前缀匹配（LIKE '/A/%'）走 (platform_id, org_path) 组合索引
CREATE INDEX IF NOT EXISTS idx_cascade_org_path ON tb_cascade_org (platform_id, org_path);

-- 3) 存量回填：从每个根组织出发递归拼路径，一次覆盖全部层级
--    只覆盖 org_path 仍为空的记录，重复执行不会破坏已有值
WITH RECURSIVE tree AS (SELECT id, '/' || cascade_org_id || '/' AS path
                        FROM tb_cascade_org
                        WHERE parent_org_id IS NULL
                        UNION ALL
                        SELECT c.id, t.path || c.cascade_org_id || '/'
                        FROM tb_cascade_org c
                                 JOIN tree t ON c.parent_org_id = t.id)
UPDATE tb_cascade_org o
SET org_path = t.path
FROM tree t
WHERE o.id = t.id
  AND o.org_path = '';

-- 校验 1：列已存在
SELECT column_name, is_nullable, data_type, column_default
FROM information_schema.columns
WHERE table_schema = current_schema() AND table_name = 'tb_cascade_org' AND column_name = 'org_path';

-- 校验 2：仍未回填到路径的组织（应为空；有结果说明存在环路或孤儿节点，需人工核查 parent_org_id）
SELECT id, platform_id, parent_org_id, cascade_org_id, org_name
FROM tb_cascade_org
WHERE org_path = ''
ORDER BY platform_id, id;
