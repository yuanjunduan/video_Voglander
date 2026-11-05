-- =============================================================================
-- Voglander 1.10.0 级联升级合并脚本（MySQL 8）
-- 由 1.0.9 基线一次性升到 1.10.5
-- =============================================================================
--
-- 本文件由以下 21 支增量脚本按依赖拓扑序合并而成，取代它们（原文件已删除，
-- 需要追溯逐次演进请看 git 历史）：
--
--   1.0.10-menu-restructure
--   1.0.11-cascade-platform-hardening / cascade-org-tree / cascade-org-path
--                               / cascade-org-menu
--   1.0.12-cascade-channel-catalog-meta
--   1.0.13-sys-config / sys-config-menu
--   1.0.14-cascade-org-revoke-policy
--   1.0.15-cascade-channel-status-menu
--   1.10.1-cascade-local-asset-and-publish
--   1.10.2-cascade-platform-device-layer / cascade-local-asset
--                               / cascade-publish-object-key
--                               / cascade-channel-into-publish / cascade-channel-drop
--   1.10.3-cascade-platform-coding / cascade-publish-code-source
--                               / cascade-channel-code-push-status
--   1.10.4-cascade-platform-name
--   1.10.5-cascade-client
--
-- 1.0.9 的三支脚本（durable-business-task-engine、其 validation、image-asset-collection）
-- 不在本文件范围内，仍按各自文件单独执行。
--
-- =============================================================================
-- 适用前提（缺一不可）
-- =============================================================================
--   1) 目标库结构处于 1.0.9 基线。可据此自检：
--      * tb_cascade_platform 仍带 local_client_id / local_ip / local_port；
--      * 没有 tb_cascade_org / tb_cascade_publish / tb_sys_config / tb_cascade_client；
--      * 菜单里还没有 601 / 602 / 603 / 604 / 605。
--   2) 执行前已备份：tb_cascade_platform、tb_cascade_org（若有）、tb_cascade_channel、
--      tb_device、tb_device_channel、tb_menu、tb_role_menu。
--      物理备份示例（表名按日期自定）：
--      CREATE TABLE bak_YYYYMMDD_cascade_platform AS SELECT * FROM tb_cascade_platform;
--
-- =============================================================================
-- 一次性语义（重要）
-- =============================================================================
--   MySQL 8 既不支持 ADD COLUMN IF NOT EXISTS，也不能重复 DROP COLUMN/INDEX。
--   因此本文件只能对 1.0.9 基线执行一次。对已经在 1.10.x 的库重跑会报
--   duplicate column / unknown column —— 那说明库已经升级过，不是脚本坏了。
--
--   容错：多数步骤自带 information_schema 判据（见各段判重逻辑），中途失败可按
--   步骤编号续跑——已完成的步骤判据不成立，会被自动跳过。
--
--   建议逐阶段执行并核对检查点，不要盲目一次跑到底。若使用 GUI 客户端，
--   请用「执行脚本」模式（DBeaver 是 Alt+X），逐条单跑会丢失会话变量
--   和 DELIMITER 状态。
--
-- =============================================================================
-- 步骤检查点（21 步 / 9 个阶段）
-- =============================================================================
--   阶段一 菜单结构      步骤 1               601/602 挂到 500 下、任务管理改用 800 系列
--   阶段二 组织与通道元数据 步骤 2-6            tb_cascade_org、org_path、5 个目录元数据列
--   阶段三 系统配置与状态页 步骤 7-8            tb_sys_config、菜单 205 / 604
--   阶段四 取消同步与启停码 步骤 9-10           org.revoke_policy、菜单 60204
--   阶段五 组织本地资产化 步骤 11               tb_cascade_publish、org 去 platform_id
--   阶段六 设备与发布层    步骤 12-16          object_key/parent_key、CHANNEL 发布行、
--                                             tb_cascade_channel 退场
--   阶段七 编码与推送状态  步骤 17-19          platform.code_*、publish.code_source/
--                                             device_key/last_push_*、device_channel.code
--   阶段八 平台名称       步骤 20               platform.platform_name
--   阶段九 本端客户端     步骤 21               tb_cascade_client、platform.client_ref_id、
--                                             local_* 三列退场
--
--   终态自检：执行完毕后，级联/设备/图像/系统配置相关表应与 sql/voglander.sql
--   逐列一致（tb_menu / tb_role / tb_user 等基础表本就一致，可一并核对）。
--
-- =============================================================================
-- 回滚
-- =============================================================================
--   各步骤保留了原脚本的回滚说明；1.10.5 的回滚段在文件末尾（注释状态）。
--   注意：步骤 11、15、21 发生了数据搬迁（组织去平台化、通道转发布行、本端身份收敛），
--   无法靠 DDL 反向恢复，只能依赖执行前的物理备份。
-- =============================================================================

-- =============================================================================
-- 阶段一 菜单结构 · 步骤 1/21：级联菜单改挂设备管理，任务管理迁到 800 系列
--   原文件：1.0.10-menu-restructure.sql
--   601/602 原先挂在 id=600 的目录下，与任务管理 ID 冲突；这里删旧记录、改挂
--   设备管理 500，并把任务管理整体挪到 800 系列。
--   必须最先执行：后续多个脚本以「601/602 已存在」为前提做授权镜像。
-- =============================================================================

-- ============================================================================================
-- 1.0.10 菜单结构调整：修复ID冲突，将级联通道管理移至设备管理下
-- ============================================================================================

-- 步骤1：删除旧的级联管理目录（ID=600会与任务管理冲突）
DELETE FROM tb_role_menu WHERE menu_id IN (600, 601, 602, 60101, 60102, 60103, 60104, 60201, 60202, 60203);
DELETE FROM tb_menu WHERE id IN (60203, 60202, 60201, 60104, 60103, 60102, 60101, 602, 601, 600);

-- 步骤2：将级联平台管理（601）和级联通道管理（602）作为设备管理的子菜单插入
INSERT INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order, status,
                     permission, meta)
VALUES
(601, 500, 'CascadePlatform', 'cascade.platform.title', 2, '/device/cascade/platform', '/cascade/platform/list',
 'mdi:server-network-outline', 3, 1, 'Cascade:Platform:List',
 JSON_OBJECT('icon', 'mdi:server-network-outline', 'title', 'cascade.platform.title', 'hideInMenu', false)),
(602, 500, 'CascadeChannel', 'cascade.channel.title', 2, '/device/cascade/channel', '/cascade/channel/list',
 'mdi:swap-horizontal', 4, 1, 'Cascade:Channel:List',
 JSON_OBJECT('icon', 'mdi:swap-horizontal', 'title', 'cascade.channel.title', 'hideInMenu', false));

-- 步骤3：插入级联平台和通道的按钮权限
INSERT INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order, status,
                     permission, meta)
VALUES
(60101, 601, 'CascadePlatformCreate', 'cascade.platform.create', 3, null, null, '', 1, 1, 'Cascade:Platform:Create',
 JSON_OBJECT('title', 'cascade.platform.create', 'hideInMenu', true)),
(60102, 601, 'CascadePlatformEdit', 'cascade.platform.edit', 3, null, null, '', 2, 1, 'Cascade:Platform:Edit',
 JSON_OBJECT('title', 'cascade.platform.edit', 'hideInMenu', true)),
(60103, 601, 'CascadePlatformDelete', 'cascade.platform.delete', 3, null, null, '', 3, 1, 'Cascade:Platform:Delete',
 JSON_OBJECT('title', 'cascade.platform.delete', 'hideInMenu', true)),
(60104, 601, 'CascadePlatformStatus', 'cascade.platform.status', 3, null, null, '', 4, 1, 'Cascade:Platform:Status',
 JSON_OBJECT('title', 'cascade.platform.status', 'hideInMenu', true)),
(60201, 602, 'CascadeChannelCreate', 'cascade.channel.create', 3, null, null, '', 1, 1, 'Cascade:Channel:Create',
 JSON_OBJECT('title', 'cascade.channel.create', 'hideInMenu', true)),
(60202, 602, 'CascadeChannelEdit', 'cascade.channel.edit', 3, null, null, '', 2, 1, 'Cascade:Channel:Edit',
 JSON_OBJECT('title', 'cascade.channel.edit', 'hideInMenu', true)),
(60203, 602, 'CascadeChannelDelete', 'cascade.channel.delete', 3, null, null, '', 3, 1, 'Cascade:Channel:Delete',
 JSON_OBJECT('title', 'cascade.channel.delete', 'hideInMenu', true));

-- 步骤4：删除旧的任务管理菜单（如果存在ID=600/601冲突）
DELETE FROM tb_role_menu WHERE menu_id IN (600, 601, 60101, 60102) AND menu_id IN (SELECT id FROM tb_menu WHERE menu_code = 'TaskManagement' OR parent_id IN (SELECT id FROM tb_menu WHERE menu_code = 'TaskManagement'));
-- 注：原文这两句是「DELETE 目标表 + 子查询同表」，MySQL 8 会报
--     ERROR 1093 (You can't specify target table 'tb_menu' for update in FROM clause)，
--     合并成 1.10.0 时改成等价的自连接写法。
DELETE t FROM tb_menu t
    JOIN tb_menu c ON c.id = t.parent_id AND c.menu_code = 'TaskCenter'
    JOIN tb_menu m ON m.id = c.parent_id AND m.menu_code = 'TaskManagement'
WHERE t.menu_code IN ('TaskQuery', 'TaskControl');
DELETE c FROM tb_menu c
    JOIN tb_menu m ON m.id = c.parent_id AND m.menu_code = 'TaskManagement'
WHERE c.menu_code = 'TaskCenter';
DELETE FROM tb_menu WHERE menu_code = 'TaskManagement';

-- 步骤5：插入任务管理菜单（使用新ID 800系列）
INSERT INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order, status,
                     permission, meta)
VALUES
(800, 0, 'TaskManagement', 'task.management.title', 1, '/task', 'BasicLayout', 'lucide:list-checks', 6, 1, '',
 JSON_OBJECT('icon', 'lucide:list-checks', 'title', 'task.management.title', 'order', 6)),
(801, 800, 'TaskCenter', 'task.center.title', 2, '/task/center', '/task/center/list', 'lucide:activity', 1, 1,
 'Task:Query', JSON_OBJECT('icon', 'lucide:activity', 'title', 'task.center.title')),
(80101, 801, 'TaskQuery', 'task.action.query', 3, NULL, NULL, '', 1, 1, 'Task:Query',
 JSON_OBJECT('title', 'task.action.query', 'hideInMenu', true)),
(80102, 801, 'TaskControl', 'task.action.control', 3, NULL, NULL, '', 2, 1, 'Task:Control',
 JSON_OBJECT('title', 'task.action.control', 'hideInMenu', true));

-- 步骤6：为管理员角色分配所有新菜单权限
INSERT IGNORE INTO tb_role_menu (role_id, menu_id)
SELECT 1, id FROM tb_menu
WHERE id IN (601, 602, 60101, 60102, 60103, 60104, 60201, 60202, 60203, 800, 801, 80101, 80102);

-- =============================================================================
-- 阶段二 组织与通道元数据 · 步骤 2/21：本端 SIP 端口默认值收敛到 5061
--   原文件：1.0.11-cascade-platform-hardening.sql
--   local_port 的历史默认值 5070 与本机 SIP 客户端监听端口 5061 不一致，会把
--   Contact 通告成不存在的端口。这里只收敛默认值；存量 5070 记录只给查询，不自动改。
-- =============================================================================

-- Voglander 1.0.11 cascade platform hardening (MySQL 8)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：tb_cascade_platform.local_port 的历史默认值 5070 与本机 SIP 客户端监听端口 5061 不一致。
-- 平台记录留空本端端口时会落 5070，导致 Contact 通告成不存在的端口，上级回包无法到达。
-- 本次只收敛默认值；存量记录需人工确认后再改（见文末检查语句）。

ALTER TABLE `tb_cascade_platform` ALTER COLUMN `local_port` SET DEFAULT 5061;

-- 校验 1：默认值是否已生效（COLUMN_DEFAULT 应为 5061）
SELECT column_name, column_default FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_platform' AND column_name = 'local_port';

-- 校验 2：存量 5070 记录——确认这些平台确实应使用 5061 后再执行 UPDATE
SELECT id, platform_id, local_ip, local_port, enabled FROM tb_cascade_platform WHERE local_port = 5070;
-- UPDATE tb_cascade_platform SET local_port = 5061 WHERE local_port = 5070;

-- 校验 3：最近一次注册失败原因（应用写入 extend，无需新增列）
SELECT id, platform_id, register_status, extend FROM tb_cascade_platform WHERE extend LIKE '%lastError%';

-- =============================================================================
-- 阶段二 组织与通道元数据 · 步骤 3/21：建级联组织树表
--   原文件：1.0.11-cascade-org-tree.sql
--   上级按目录项 ParentID 建树，只发通道不发组织时通道是「无父节点」的孤儿：
--   SIP 回 200 OK，但上级界面里不出现。
--   必须先于步骤 4（org_path 依赖本步骤建的表）。
-- =============================================================================

-- Voglander 1.0.11 cascade org tree (MySQL 8)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：上级平台（海康 iSecure Center 等）按 Catalog Item 的 ParentID 建目录树。
-- 只下发通道、没有组织节点时，通道是"无父节点"的孤儿：SIP 层回 200 OK，
-- 但上级界面里不出现。本迁移新增级联组织树表，并给通道表补组织归属列。
--
-- 兼容性：cascade_org_id 允许为空，表示"通道直接挂平台根节点"，
-- 存量数据无需回填，行为与升级前一致。

CREATE TABLE IF NOT EXISTS `tb_cascade_org`
(
    `id`             BIGINT UNSIGNED                                       NOT NULL AUTO_INCREMENT,
    `create_time`    DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `platform_id`    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '上级平台ID',
    `parent_org_id`  BIGINT UNSIGNED                                                DEFAULT NULL COMMENT '父组织(tb_cascade_org.id)，空=根组织挂平台节点',
    `cascade_org_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '上报给上级的组织编码(20位)',
    `org_name`       VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '上报名称',
    `sort_order`     INT                                                   NOT NULL DEFAULT 0 COMMENT '同级排序',
    `enabled`        TINYINT                                               NOT NULL DEFAULT 1 COMMENT '1上报 0不上报(整棵子树剪掉)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cascade_org_code` (`platform_id`, `cascade_org_id`) USING BTREE,
    KEY `idx_cascade_org_parent` (`parent_org_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT ='级联组织树表';

-- 通道表补组织归属列（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判重）
SET @has_org_col := (SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'tb_cascade_channel'
                       AND column_name = 'cascade_org_id');
SET @ddl := IF(@has_org_col = 0,
    'ALTER TABLE `tb_cascade_channel` ADD COLUMN `cascade_org_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''所属上级组织编码(空=挂平台根节点)'' AFTER `cascade_name`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_org_idx := (SELECT COUNT(*)
                     FROM information_schema.statistics
                     WHERE table_schema = DATABASE()
                       AND table_name = 'tb_cascade_channel'
                       AND index_name = 'idx_cascade_channel_org');
SET @ddl := IF(@has_org_idx = 0,
    'CREATE INDEX `idx_cascade_channel_org` ON `tb_cascade_channel` (`platform_id`, `cascade_org_id`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验 1：组织树表已建立
SELECT table_name, table_comment FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_org';

-- 校验 2：通道表的组织归属列已存在
SELECT column_name, is_nullable, column_default FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'tb_cascade_channel' AND column_name = 'cascade_org_id';

-- 校验 3：仍未归属任何组织的通道（升级后这些通道直接挂在平台节点下，不会消失）
SELECT id, platform_id, local_device_id, local_channel_id, cascade_channel_id
FROM tb_cascade_channel
WHERE cascade_org_id IS NULL;

-- =============================================================================
-- 阶段二 组织与通道元数据 · 步骤 4/21：组织物化路径 org_path
--   原文件：1.0.11-cascade-org-path.sql
--   !! 顺序坑：按文件名排序时 -path 排在 -tree 之前，直接全选排序执行会报
--      ERROR 1146 Table 'tb_cascade_org' doesn't exist。此处已固定为 tree 之后。
-- =============================================================================

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

-- =============================================================================
-- 阶段二 组织与通道元数据 · 步骤 5/21：级联组织树页面与按钮码
--   原文件：1.0.11-cascade-org-menu.sql
--   新增菜单 603 + 按钮 60301-60304，并把设备管理下顺序调成
--   平台(3) → 组织(4) → 通道(5) → 实时事件(6)。
-- =============================================================================

-- Voglander 1.0.11 cascade org menu (MySQL)
--
-- 背景：级联组织树是通道挂到上级目录节点（Catalog ParentID）的前置步骤，
-- 需要一个可访问的页面入口。本迁移新增「级联组织树」菜单（603）与 4 个按钮权限码，
-- 并把设备管理下的级联菜单顺序调整为 平台(3) → 组织(4) → 通道(5)，
-- 「实时事件」(503) 顺延为 6。
--
-- 说明：所有语句均可重复执行（INSERT IGNORE + 幂等 UPDATE），无需先判断是否已升级。

-- 0) 自查：已有组织菜单会返回记录，此时下面的 INSERT 会被忽略，UPDATE 仍会纠正顺序
SELECT id, menu_code, sort_order FROM tb_menu WHERE id IN (603, 60301, 60302, 60303, 60304);

-- 1) 级联组织树菜单（挂在设备管理 500 下）
INSERT IGNORE INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order,
                            status, permission, meta)
VALUES
(603, 500, 'CascadeOrg', 'cascade.org.title', 2, '/device/cascade/org', '/cascade/org/list',
 'mdi:file-tree', 4, 1, 'Cascade:Org:List',
 JSON_OBJECT('icon', 'mdi:file-tree', 'title', 'cascade.org.title', 'hideInMenu', false));

-- 2) 按钮权限：新增 / 编辑 / 删除 / 启停上报
INSERT IGNORE INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order,
                            status, permission, meta)
VALUES
(60301, 603, 'CascadeOrgCreate', 'cascade.org.create', 3, null, null, '', 1, 1, 'Cascade:Org:Create',
 JSON_OBJECT('title', 'cascade.org.create', 'hideInMenu', true)),
(60302, 603, 'CascadeOrgEdit', 'cascade.org.edit', 3, null, null, '', 2, 1, 'Cascade:Org:Edit',
 JSON_OBJECT('title', 'cascade.org.edit', 'hideInMenu', true)),
(60303, 603, 'CascadeOrgDelete', 'cascade.org.delete', 3, null, null, '', 3, 1, 'Cascade:Org:Delete',
 JSON_OBJECT('title', 'cascade.org.delete', 'hideInMenu', true)),
(60304, 603, 'CascadeOrgStatus', 'cascade.org.status', 3, null, null, '', 4, 1, 'Cascade:Org:Status',
 JSON_OBJECT('title', 'cascade.org.status', 'hideInMenu', true));

-- 3) 菜单顺序：平台 → 组织 → 通道 → 实时事件
UPDATE tb_menu SET sort_order = 4 WHERE id = 603;
UPDATE tb_menu SET sort_order = 5 WHERE id = 602;
UPDATE tb_menu SET sort_order = 6 WHERE id = 503;

-- 4) 权限分配：镜像「级联平台」（601）已有的授权
--    不按角色名匹配：存量库的角色名可能是历史转码残留，且角色名本身可被改名。
--    能看级联平台的角色，本来就该能看组织树。
INSERT IGNORE INTO tb_role_menu (role_id, menu_id)
SELECT rm.role_id, m.id
FROM tb_role_menu rm,
     tb_menu m
WHERE rm.menu_id = 601
  AND m.id IN (603, 60301, 60302, 60303, 60304);

-- 校验：设备管理下菜单顺序应为 设备列表(1) → 设备通道(2) → 级联平台(3) → 级联组织(4) → 级联通道(5) → 实时事件(6)
SELECT id, parent_id, menu_code, menu_name, sort_order, permission
FROM tb_menu
WHERE parent_id IN (500, 603)
ORDER BY parent_id, sort_order;

-- =============================================================================
-- 阶段二 组织与通道元数据 · 步骤 6/21：通道目录元数据五列
--   原文件：1.0.12-cascade-channel-catalog-meta.sql
--   manufacturer / model / address / ip_address / ptz_type，留空表示按设备通道
--   数据自动派生，填了则优先生效。
-- =============================================================================

-- Voglander 1.0.12 cascade channel catalog meta (MySQL 8)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：上级（海康 iSecure Center）的目录项除 DeviceID/Name/ParentID 外，还按
-- Manufacturer / Model / IPAddress / Info-PTZType 区分监控点类型与点播入口，
-- Address 也会展示在上级的安装地址栏。这些值此前全部缺失或写死常量。
--
-- 本迁移给 tb_cascade_channel 补 5 个“人工覆盖值”列：
--   manufacturer / model / address / ip_address / ptz_type
-- 列可空，留空表示“按设备通道数据自动派生”（GB28181 目录项 JSON、发现类
-- extend.vendor/sourceModel/sourceIp、tb_device.ip），填了则优先生效。
--
-- 幂等：以 manufacturer 列是否存在作为整体判据；5 列在同一条 ALTER 里添加，
-- 不会出现只补一半的中间态。

-- 0) 前置检查：表不存在时后面的 ALTER 只会抛 1146
SELECT IF((SELECT COUNT(*)
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = 'tb_cascade_channel') = 1,
          '[OK] tb_cascade_channel 已存在，可继续执行',
          '[STOP] tb_cascade_channel 不存在，请先执行 1.0.11 及更早脚本') AS migration_precheck;

-- 1) 补列
SET @has_meta_col := (SELECT COUNT(*)
                      FROM information_schema.columns
                      WHERE table_schema = DATABASE()
                        AND table_name = 'tb_cascade_channel'
                        AND column_name = 'manufacturer');

SET @ddl := IF(@has_meta_col = 0,
    'ALTER TABLE `tb_cascade_channel`
        ADD COLUMN `manufacturer` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''厂商(空=自动派生)'',
        ADD COLUMN `model` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''设备型号(空=自动派生)'',
        ADD COLUMN `address` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''安装地址(空=自动派生)'',
        ADD COLUMN `ip_address` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''设备IP(空=自动派生)'',
        ADD COLUMN `ptz_type` TINYINT DEFAULT NULL COMMENT ''1球机 2半球 3枪机(空或0=自动派生)'',
        ALGORITHM = INPLACE',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 校验：5 列都必须存在
SELECT COUNT(*) AS catalog_meta_columns
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'tb_cascade_channel'
  AND column_name IN ('manufacturer', 'model', 'address', 'ip_address', 'ptz_type');

-- =============================================================================
-- 阶段三 系统配置与状态页 · 步骤 7/21：系统配置表 tb_sys_config
--   原文件：1.0.13-sys-config.sql
--   键的默认值/类型/说明定义在 SysConfigCatalog，本表只存覆盖值。
-- =============================================================================

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

-- =============================================================================
-- 阶段三 系统配置与状态页 · 步骤 8/21：系统配置页 205 与级联运行状态页 604
--   原文件：1.0.13-sys-config-menu.sql
-- =============================================================================

-- Voglander 1.0.13 system config + cascade monitor menu (MySQL)
--
-- 新增两个页面：
--   205 系统配置（/system/config）      —— 级联分片等可热改参数
--   604 级联运行状态（/device/cascade/monitor）—— 目录分片/媒体会话/待确认推送
--
-- 可重复执行：INSERT ... ON DUPLICATE KEY UPDATE + INSERT IGNORE 授权。

INSERT INTO `tb_menu` (`id`, `parent_id`, `menu_code`, `menu_name`, `menu_type`, `path`, `component`, `icon`,
                       `sort_order`, `status`, `permission`, `meta`)
VALUES
(205, 2, 'SystemConfig', 'system.config.title', 2, '/system/config', '/system/config/list', 'mdi:cog-outline', 5, 1,
 'System:Config:List',
 JSON_OBJECT('icon', 'mdi:cog-outline', 'title', 'system.config.title', 'hideInMenu', false)),
(604, 500, 'CascadeMonitor', 'cascade.monitor.title', 2, '/device/cascade/monitor', '/cascade/monitor/list',
 'mdi:chart-timeline-variant', 7, 1, 'Cascade:Monitor:List',
 JSON_OBJECT('icon', 'mdi:chart-timeline-variant', 'title', 'cascade.monitor.title', 'hideInMenu', false))
ON DUPLICATE KEY UPDATE `menu_name`  = VALUES(`menu_name`),
                        `path`       = VALUES(`path`),
                        `component`  = VALUES(`component`),
                        `icon`       = VALUES(`icon`),
                        `sort_order` = VALUES(`sort_order`),
                        `permission` = VALUES(`permission`),
                        `meta`       = VALUES(`meta`);

-- 授权：镜像「级联平台」(601) 的角色，不按角色名匹配
INSERT IGNORE INTO `tb_role_menu` (`role_id`, `menu_id`)
SELECT `rm`.`role_id`, `m`.`id`
FROM `tb_role_menu` `rm`,
     `tb_menu` `m`
WHERE `rm`.`menu_id` = 601
  AND `m`.`id` IN (205, 604);

INSERT IGNORE INTO `tb_role_menu` (`role_id`, `menu_id`)
SELECT 1, `id`
FROM `tb_menu`
WHERE `id` IN (205, 604);

-- 校验
SELECT id, parent_id, menu_code, menu_name, path, component, sort_order, permission
FROM `tb_menu`
WHERE `id` IN (205, 604)
ORDER BY id;

SELECT role_id, menu_id FROM `tb_role_menu` WHERE `menu_id` IN (205, 604) ORDER BY menu_id, role_id;

-- =============================================================================
-- 阶段四 取消同步与启停码 · 步骤 9/21：组织取消同步时通道的处置策略
--   原文件：1.0.14-cascade-org-revoke-policy.sql
--   revoke_policy：CASCADE 通道跟随组织一起 DEL / REROUTE 只 DEL 组织、通道上浮。
-- =============================================================================

-- Voglander 1.0.14 cascade org revoke policy (MySQL 8)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：级联组织「取消同步」（enabled=1 -> 0）需要让上级目录里的节点消失。完整目录快照
-- 对上级是增量 upsert 语义，只重推快照不会删除节点，必须显式推 <Event>DEL</Event>。
-- 而组织消失后其下通道有两种处置方式，协议动作完全不同：
--   CASCADE  —— 通道跟随组织一起 DEL，恢复组织时一起回来（默认）
--   REROUTE  —— 只 DEL 组织，通道改挂域节点继续上报
-- 两种策略事后无法从 enabled 反推，因此落库到 tb_cascade_org.revoke_policy。
--
-- 幂等：以 revoke_policy 列是否存在为判据。

-- 0) 前置检查：表不存在时后面的 ALTER 只会抛 1146
SELECT IF((SELECT COUNT(*)
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = 'tb_cascade_org') = 1,
          '[OK] tb_cascade_org 已存在，可继续执行',
          '[STOP] tb_cascade_org 不存在，请先执行 1.0.11 及更早脚本') AS migration_precheck;

-- 1) 补列
SET @has_revoke_col := (SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'tb_cascade_org'
                          AND column_name = 'revoke_policy');

SET @ddl := IF(@has_revoke_col = 0,
    'ALTER TABLE `tb_cascade_org`
        ADD COLUMN `revoke_policy` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT ''CASCADE'' COMMENT ''取消同步时通道处置：CASCADE连带/ REROUTE上浮''
        AFTER `enabled`,
        ALGORITHM = INPLACE',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 校验：应返回 1
SELECT COUNT(*) AS revoke_policy_columns
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'tb_cascade_org'
  AND column_name = 'revoke_policy';

-- =============================================================================
-- 阶段四 取消同步与启停码 · 步骤 10/21：级联通道启停权限码
--   原文件：1.0.15-cascade-channel-status-menu.sql
--   只补按钮码 60204（Cascade:Channel:Status），不新增页面、不改菜单顺序。
-- =============================================================================

-- Voglander 1.0.15 cascade channel status menu (MySQL)
--
-- 背景：级联通道页要支持「取消同步 / 恢复同步」（后端 POST /cascade/channel/{id}/revoke|publish），
-- 但通道菜单（602）下只有 新增/编辑/删除 三个按钮权限码，前端无码可判——
-- 开关要么对所有人可见，要么永远藏起来。本迁移只补一个按钮权限码 Cascade:Channel:Status，
-- 不新增页面，也不改菜单顺序。
--
-- 说明：语句可重复执行（INSERT IGNORE + 幂等授权），无需先判断是否已升级。

-- 0) 自查：已存在时会返回记录，下面的 INSERT 会被忽略
SELECT id, menu_code, permission FROM tb_menu WHERE id = 60204;

-- 1) 按钮权限：级联通道启停上报
INSERT IGNORE INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order,
                            status, permission, meta)
VALUES
(60204, 602, 'CascadeChannelStatus', 'cascade.channel.status', 3, null, null, '', 4, 1, 'Cascade:Channel:Status',
 JSON_OBJECT('title', 'cascade.channel.status', 'hideInMenu', true));

-- 2) 权限分配：镜像「编辑通道」（60202）已有的授权
--    不按角色名匹配：存量库的角色名可能是历史转码残留，且角色名本身可被改名。
--    能改通道映射的角色，本来就该能决定它要不要上报。
INSERT IGNORE INTO tb_role_menu (role_id, menu_id)
SELECT rm.role_id, m.id
FROM tb_role_menu rm,
     tb_menu m
WHERE rm.menu_id = 60202
  AND m.id = 60204;

-- 校验：通道页应出现 4 个按钮权限码，sort_order 1..4
SELECT id, parent_id, menu_code, menu_name, sort_order, permission
FROM tb_menu
WHERE parent_id = 602
ORDER BY sort_order;

-- =============================================================================
-- 阶段五 组织本地资产化 · 步骤 11/21：组织去平台化 + 发布层建表
--   原文件：1.10.1-cascade-local-asset-and-publish.sql
--   组织从「每个上级平台一套」改为全局一套（org_code 唯一），
--   「推给哪个平台」搬到 tb_cascade_publish。
--   !! 段内顺序不可调换：platform_id 必须在删列之前翻译成发布行，
--      否则升级后上级目录会整体变空。
--   注意：本段含 DELIMITER $$ 存储过程，需要「执行脚本」模式。
-- =============================================================================

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

-- =============================================================================
-- 阶段六 设备与发布层 · 步骤 12/21：平台画像「目录是否带设备层」
--   原文件：1.10.2-cascade-platform-device-layer.sql
--   !! 步骤 17 的平台编码列用 AFTER catalog_with_device_layer 定位，故本步必须先执行。
-- =============================================================================

-- 1.10.2 级联平台画像：目录是否带设备层
--
-- 背景：「通道直挂组织」与「域/组织/设备/通道」是两种都合规的目录形态，取决于上级怎么建树。
-- 这是对端画像，不是本地结构：本地组织树只有一份，不同上级可以是不同的层级形态。
-- 因此开关挂在平台配置上，默认 0（虚拟组织），不替存量平台改结构。

ALTER TABLE `tb_cascade_platform`
    ADD COLUMN `catalog_with_device_layer` TINYINT NOT NULL DEFAULT 0
        COMMENT '目录是否带设备层 0=虚拟组织(通道直挂组织) 1=域/组织/设备/通道'
        AFTER `transport`;

-- =============================================================================
-- 阶段六 设备与发布层 · 步骤 13/21：设备与通道的本地启停 + 通道组织归属
--   原文件：1.10.2-cascade-local-asset.sql
--   必须在步骤 15 之前：后者要读 tb_device_channel.org_id。
-- =============================================================================

-- 1.10.2 级联本地资产层：设备与通道的本地启停 + 通道的组织归属
--
-- 背景：1.10.1 把组织改成本地资产（去 platform_id），但设备与通道仍缺两样东西：
--   1) 本地启停 enabled —— 与在线状态 status 语义不同。status=0 是"掉线"，目录里仍应出现
--      （Status 字段报 OFF）；enabled=0 是"不想让它上报了"，应从所有上级平台消失。
--      没有本地启停时这个动作只能逐平台关发布行，"本地下线一次"变成"按平台关 N 次"。
--   2) 通道的组织归属 org_id —— 为空表示跟随设备归属。GB28181 的业务分组/虚拟组织允许
--      摄像机直接挂在组织结构上，通道自带归属才能表达"设备在 A 组织、某路通道归 B 业务分组"。
--
-- 两列都可空/带默认值，对既有数据零影响。

ALTER TABLE `tb_device`
    ADD COLUMN `enabled` tinyint(1) NOT NULL DEFAULT '1'
        COMMENT '本地启用 1启用 0停用；停用后从所有上级平台消失，与在线状态 status 无关';

ALTER TABLE `tb_device_channel`
    ADD COLUMN `org_id` bigint unsigned DEFAULT NULL
        COMMENT '所属级联组织(tb_cascade_org.id)，空=跟随设备归属';

ALTER TABLE `tb_device_channel`
    ADD COLUMN `enabled` tinyint(1) NOT NULL DEFAULT '1'
        COMMENT '本地启用 1启用 0停用；停用后从所有上级平台消失';

ALTER TABLE `tb_device_channel`
    ADD KEY `idx_channel_org` (`org_id`) USING BTREE;

-- =============================================================================
-- 阶段六 设备与发布层 · 步骤 14/21：发布行改用业务键 object_key + 父节点覆盖
--   原文件：1.10.2-cascade-publish-object-key.sql
--   必须在步骤 15 之前：后者写入 object_key / parent_key。
-- =============================================================================

-- 1.10.2 级联发布层：对象定位从自增主键改成业务键 + 父节点覆盖列
--
-- 背景：发布关系原来按本地对象自增主键定位，而 id 是存储细节不是身份：
--   1) 设备与通道会被目录同步物理删后重建，id 一变发布关系就成了指不到对象的孤儿；
--   2) 上级点播反查本地通道时，只有业务键能让一次查询直接拿到 device_id / channel_id；
--   3) 「这个对象推给了哪些平台」与「这个平台推了哪些对象」是同一张表的两个方向，
--      后者的自然维度正是业务键。
-- 因此改为：ORG=org_code、DEVICE=device_id、CHANNEL=device_id:channel_id。
--
-- parent_key：同一对象在不同上级挂不同父节点时的唯一覆盖位。留空=按本地结构推导，
-- 只存差异，本地组织树仍然只有一份。

-- 唯一键要先摘掉：它建在 object_id 上，回填期间会与新的对象键判重无关地互相干扰
ALTER TABLE `tb_cascade_publish`
    DROP INDEX `uk_publish_object`;

ALTER TABLE `tb_cascade_publish`
    ADD COLUMN `object_key` VARCHAR(128) NOT NULL DEFAULT ''
        COMMENT '对象业务键：ORG=org_code / DEVICE=device_id / CHANNEL=device_id:channel_id' AFTER `object_type`,
    ADD COLUMN `parent_key` VARCHAR(128) DEFAULT NULL
        COMMENT '该平台下的父节点业务键，空=按本地结构推导' AFTER `object_key`;

-- 回填：按对象类型 join 回各自的业务键
UPDATE `tb_cascade_publish` p
    JOIN `tb_cascade_org` o ON p.object_id = o.id
   SET p.object_key = o.org_code
 WHERE p.object_type = 'ORG';

UPDATE `tb_cascade_publish` p
    JOIN `tb_device` d ON p.object_id = d.id
   SET p.object_key = d.device_id
 WHERE p.object_type = 'DEVICE';

UPDATE `tb_cascade_publish` p
    JOIN `tb_device_channel` c ON p.object_id = c.id
   SET p.object_key = CONCAT(c.device_id, ':', c.channel_id)
 WHERE p.object_type = 'CHANNEL';

-- 对象已被删除的历史行无法翻译成业务键：留着会让唯一键被空串卡死
DELETE FROM `tb_cascade_publish` WHERE `object_key` = '';

-- 回填完成后去掉临时默认值，让"漏填对象键"在写入时直接失败
ALTER TABLE `tb_cascade_publish`
    ALTER COLUMN `object_key` DROP DEFAULT;

ALTER TABLE `tb_cascade_publish`
    ADD UNIQUE KEY `uk_publish_object` (`platform_id`, `object_type`, `object_key`) USING BTREE;

ALTER TABLE `tb_cascade_publish`
    DROP COLUMN `object_id`;

-- =============================================================================
-- 阶段六 设备与发布层 · 步骤 15/21：老通道表存量翻成 CHANNEL 发布行
--   原文件：1.10.2-cascade-channel-into-publish.sql
--   只有「所有平台都指向同一组织」时才回填本地 org_id，其余差异逐行写 parent_key。
-- =============================================================================

-- 1.10.2 级联通道进入发布层：目录来源切到 tb_cascade_publish（expand 阶段）
--
-- 背景：tb_cascade_channel 是「每平台一行」，通道因此带上了平台维度：
--   同一路摄像机推给 N 个上级就有 N 行，本地换组织要改 N 次，
--   也无法回答「这路通道推给了哪些平台」这种反向问题。
-- 终态是：组织/设备/通道各自是本地资产（不带 platform_id），「推给谁」只由 tb_cascade_publish 表达。
-- 本脚本把老表存量翻译成 CHANNEL 发布行，让目录组装改读发布层后不丢节点。
--
-- 翻译规则（与 SqliteCascadeChannelPublishMigrator 严格一致）：
--   1) 某通道在所有平台的 cascade_org_id 一致 → 回填 tb_device_channel.org_id，
--      发布行 parent_key 留空：以后在本地换组织，上级目录自动跟着动。
--   2) 各平台归属不一致、或用户已在本地配过归属 → 本地列不动，
--      差异逐行写 parent_key，上报行为与迁移前完全一致。
--   3) 本地通道已不存在的老行丢弃：留下来就是挂不住的孤儿节点。
--   4) 已存在的发布行不覆盖：新接口配过的编码/启停是用户后来的选择。
--
-- 老表本阶段不删，R4 阶段确认线上目录一致后才退场。

-- 一、归属回填：只有「所有平台都指向同一个组织」时才敢写本地列，本地列只存得下一份
UPDATE `tb_device_channel` c
    JOIN (SELECT ch.`local_device_id`,
                 ch.`local_channel_id`,
                 MIN(o.`id`) AS `org_id`
            FROM `tb_cascade_channel` ch
            JOIN `tb_cascade_org` o ON o.`org_code` = ch.`cascade_org_id`
           WHERE ch.`cascade_org_id` IS NOT NULL
             AND ch.`cascade_org_id` <> ''
           GROUP BY ch.`local_device_id`, ch.`local_channel_id`
          HAVING COUNT(DISTINCT ch.`cascade_org_id`) = 1) t
    ON t.`local_device_id` = c.`device_id`
   AND t.`local_channel_id` = c.`channel_id`
   SET c.`org_id` = t.`org_id`
 WHERE c.`org_id` IS NULL;

-- 二、逐平台翻成 CHANNEL 发布行；object_key = device_id:channel_id
INSERT IGNORE INTO `tb_cascade_publish`
    (`platform_id`, `object_type`, `object_key`, `parent_key`, `publish_code`, `publish_name`,
     `enabled`, `manufacturer`, `model`, `address`, `ip_address`, `ptz_type`)
SELECT ch.`platform_id`,
       'CHANNEL',
       CONCAT(ch.`local_device_id`, ':', ch.`local_channel_id`),
       -- 本地归属已经等于该平台归属时不需要覆盖，留空才能跟随本地组织调整
       CASE WHEN c.`org_id` IS NOT NULL AND c.`org_id` = o.`id` THEN NULL
            ELSE ch.`cascade_org_id` END,
       ch.`cascade_channel_id`,
       ch.`cascade_name`,
       ch.`enabled`,
       ch.`manufacturer`,
       ch.`model`,
       ch.`address`,
       ch.`ip_address`,
       ch.`ptz_type`
  FROM `tb_cascade_channel` ch
  JOIN `tb_device_channel` c
    ON c.`device_id` = ch.`local_device_id`
   AND c.`channel_id` = ch.`local_channel_id`
  LEFT JOIN `tb_cascade_org` o ON o.`org_code` = ch.`cascade_org_id`
 WHERE NOT EXISTS (SELECT 1
                     FROM `tb_cascade_publish` p
                    WHERE p.`platform_id` = ch.`platform_id`
                      AND p.`object_type` = 'CHANNEL'
                      AND p.`object_key` = CONCAT(ch.`local_device_id`, ':', ch.`local_channel_id`));

-- 三、执行后核对（期望：没被翻译的老行数 = 发布行数，差额只能是本地已删除的通道）
-- SELECT ch.platform_id, COUNT(*) AS legacy_rows
--   FROM tb_cascade_channel ch
--   LEFT JOIN tb_device_channel c
--     ON c.device_id = ch.local_device_id AND c.channel_id = ch.local_channel_id
--  WHERE c.id IS NULL
--  GROUP BY ch.platform_id;

-- =============================================================================
-- 阶段六 设备与发布层 · 步骤 16/21：老表 tb_cascade_channel 退场
--   原文件：1.10.2-cascade-channel-drop.sql
--   !! 执行本步前请先跑段内的自查查询，确认它返回 0 行——即不存在
--      「本地通道还在、却没生成对应发布行」的老配置。合并后不再有单独的
--      「搬迁/删表」两步之间的暂停点，这一步的自查就是那个暂停点。
-- =============================================================================

-- 1.10.2 级联通道老表退场：DROP TABLE tb_cascade_channel（contract 阶段）
--
-- 前置条件（缺一不可）：
--   1) 已执行 1.10.2-cascade-channel-into-publish.sql，把存量翻译成 CHANNEL 发布行；
--   2) 新版本已上线并确认上级目录与本平台页面一致（目录唯一来源已是 tb_cascade_publish）。
--
-- 为什么要单独一个脚本而不是并进上一步：
--   搬迁与删表分开，搬迁失败只需重跑上一步，老表还在原地可核对；
--   合在一起的话，一旦翻译有误就没有回头路。
--
-- 删表前必须自查：老表里凡是「本地通道已删」的孤儿行本就不会进发布层，
-- 因此判据不是行数相等，而是下面这条查询返回 0 行——即不存在「本地通道还在、却没生成
-- 对应发布行」的老配置：
--
-- SELECT ch.platform_id, ch.local_channel_id
--   FROM `tb_cascade_channel` ch
--   JOIN `tb_device_channel` c
--     ON c.`device_id` = ch.`local_device_id` AND c.`channel_id` = ch.`local_channel_id`
--  WHERE NOT EXISTS (SELECT 1 FROM `tb_cascade_publish` p
--                     WHERE p.`platform_id` = ch.`platform_id`
--                       AND p.`object_type` = 'CHANNEL'
--                       AND p.`object_key` = CONCAT(ch.`local_device_id`, ':', ch.`local_channel_id`));
--
-- 回滚：老表数据已 1:1 保存在 tb_cascade_publish（object_type='CHANNEL'）与
--       tb_device_channel.org_id 中，需要恢复时按反向规则重建即可，不依赖本表的物理存在。

DROP TABLE IF EXISTS `tb_cascade_channel`;

-- =============================================================================
-- 阶段七 编码与推送状态 · 步骤 17/21：平台级七段编码规则
--   原文件：1.10.3-cascade-platform-coding.sql
--   两个开关默认 0，不替存量平台换码。依赖步骤 12 的 catalog_with_device_layer 列。
-- =============================================================================

-- 1.10.3 级联平台编码规则：平台级七段前 14 位 + 起始序号 + 启用开关 + 父链推送策略
--
-- 背景：同一个本地组织/通道推给不同上级，前 14 位（省/市/县/行业/网络）可以完全不同。
--   `tb_cascade_org.code_*` 是「这套目录长在哪个行政区」的本地事实，全局一份；
--   「推给 A 局用 340200、推给 B 局用 440300」是发布维度的选择，只能挂在平台上。
-- 平台规则全留空且 code_rule_enabled=0 时，编码解析沿用组织规则与全局兜底，与升级前等价。
--
-- 类型位（组织 215/216、设备 132、通道 131）刻意不放进平台规则：它由对象类型决定，
-- 允许平台填会生成出「通道码以 216 开头」这类非法编码。
--
-- 两个开关都默认 0：
--   code_rule_enabled=0 —— 不替存量平台换码，上级侧不会把老对象当成新设备；
--   publish_ancestors=0 —— 勾选组织时不顺带补推祖先链，缺祖先直接报错（阶段一行为）。
--
-- ⚠️ 本脚本为一次性增量（MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS），由 DBA 按序手工执行，
--    执行前先备份。SQLite 本地库无需手工执行：启动期 SqliteCascadeCodingSchemaMigrator
--    会按 pragma_table_info 幂等补列并登记 schema_migration_history。

ALTER TABLE `tb_cascade_platform`
    ADD COLUMN `code_province` VARCHAR(2) DEFAULT NULL
        COMMENT '平台编码规则-省编码(2位)' AFTER `catalog_with_device_layer`,
    ADD COLUMN `code_city` VARCHAR(2) DEFAULT NULL
        COMMENT '平台编码规则-市编码(2位)' AFTER `code_province`,
    ADD COLUMN `code_county` VARCHAR(4) DEFAULT NULL
        COMMENT '平台编码规则-县区编码(4位)' AFTER `code_city`,
    ADD COLUMN `code_industry` VARCHAR(2) DEFAULT NULL
        COMMENT '平台编码规则-行业编码(2位)' AFTER `code_county`,
    ADD COLUMN `code_network` VARCHAR(1) DEFAULT NULL
        COMMENT '平台编码规则-网络标识(1位)' AFTER `code_industry`,
    ADD COLUMN `code_start_seq` INT DEFAULT NULL
        COMMENT '平台编码规则-起始序号(6位)' AFTER `code_network`,
    ADD COLUMN `code_rule_enabled` TINYINT NOT NULL DEFAULT 0
        COMMENT '平台编码规则 0=未启用(沿用本地组织码与全局兜底) 1=按平台七段规则生成' AFTER `code_start_seq`,
    ADD COLUMN `publish_ancestors` TINYINT NOT NULL DEFAULT 0
        COMMENT '发布策略 0=勾选组织时缺祖先即报错 1=自动补推缺失的祖先链' AFTER `code_rule_enabled`;

-- 校验：应返回 8
SELECT count(*) AS coding_rule_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_cascade_platform'
  AND COLUMN_NAME IN ('code_province', 'code_city', 'code_county', 'code_industry',
                      'code_network', 'code_start_seq', 'code_rule_enabled', 'publish_ancestors');

-- 校验：存量平台一律 0（升级不改变它们的上报行为）
SELECT count(*) AS platforms_with_rule_off
FROM `tb_cascade_platform`
WHERE `code_rule_enabled` = 0 AND `publish_ancestors` = 0;

-- =============================================================================
-- 阶段七 编码与推送状态 · 步骤 18/21：发布行编码来源标记
--   原文件：1.10.3-cascade-publish-code-source.sql
-- =============================================================================

-- 1.10.3 级联发布行编码来源：code_source + code_generated_time
--
-- 背景：编码规则生成上报编码后必须「生成即固化」并留下来源标记，
--   否则「人工填的码」与「规则算出来的码」无法区分，人工值保护（2.5）就无从判断。
--   本次只补这两列：device_key / last_push_time / last_push_status / last_push_message
--   与通道拆键、推送状态一起在 2.4 合入（MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS，
--   一次性脚本不能事后追加内容）。
--
-- 存量行两列都留空，表示「历史数据未标记」：读取侧照旧 publish_code ?: 本地码，行为不变。
--
-- ⚠️ 本脚本为一次性增量，由 DBA 按序手工执行，执行前先备份。
--    SQLite 本地库无需手工执行：启动期 SqliteCascadePublishCodeSourceMigrator
--    会按 pragma_table_info 幂等补列并登记 schema_migration_history。

ALTER TABLE `tb_cascade_publish`
    ADD COLUMN `code_source` VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '编码来源 AUTO-规则生成并固化 MANUAL-人工填写，空=历史数据未标记' AFTER `publish_code`,
    ADD COLUMN `code_generated_time` DATETIME DEFAULT NULL
        COMMENT '自动生成时间；人工填写为空' AFTER `code_source`;

-- 校验：应返回 2
SELECT count(*) AS code_source_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_cascade_publish'
  AND COLUMN_NAME IN ('code_source', 'code_generated_time');

-- =============================================================================
-- 阶段七 编码与推送状态 · 步骤 19/21：通道自己的码 + 发布行拆键与推送结论
--   原文件：1.10.3-cascade-channel-code-push-status.sql
-- =============================================================================

-- 1.10.3 通道「自己的码」+ 发布行拆键与推送状态
--
-- 背景：
--   ① 通道要能落「自己的码」：上级看到的是发布行的共享编码，本地这一层原先只有 channel_id，
--      编码规则一旦接管，本地码就没有落库位（§6.1 ①）。
--   ③ 发布行要按设备拆键并按平台记录推送结论：object_key 是 deviceId:channelId 拼接键，
--      拿它做查询条件等于用字符串前缀推断标识（§6.3）；推送结论原先只在日志里（§11.7）。
--
-- 与 1.10.3-cascade-publish-code-source.sql 分开一支：MySQL 的 ADD COLUMN 不支持
--   IF NOT EXISTS，已执行过的脚本不能事后追加内容，混写会让老库永久缺这几列。
--
-- 存量行 device_key 留空即回落到「按 object_key 归组」的旧行为；本脚本按 object_key
--   的第一个分隔符回填一次，口径与 PublishKeys.channelDeviceId 完全一致。
--
-- ⚠️ 本脚本为一次性增量，由 DBA 按序手工执行，执行前先备份。
--    SQLite 本地库无需手工执行：启动期 SqliteCascadeChannelCodePushStatusMigrator
--    会按 pragma_table_info 幂等补列、补索引、回填 device_key 并登记 schema_migration_history。

ALTER TABLE `tb_device_channel`
    ADD COLUMN `code` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '自己的码（本地码）；上级看到的是发布行的 publish_code' AFTER `missing_count`,
    ADD KEY `idx_channel_code` (`code`) USING BTREE;

ALTER TABLE `tb_cascade_publish`
    ADD COLUMN `device_key` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT 'CHANNEL 行冗余设备段，用于按设备过滤（object_key 是拼接键不能当查询条件）' AFTER `revoke_policy`,
    ADD COLUMN `last_push_time` DATETIME DEFAULT NULL
        COMMENT '最近一次向该平台推送的时间' AFTER `device_key`,
    ADD COLUMN `last_push_status` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '最近一次推送结论 OK/FAILED/TIMEOUT/SKIPPED' AFTER `last_push_time`,
    ADD COLUMN `last_push_message` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '最近一次推送失败原因' AFTER `last_push_status`,
    ADD KEY `idx_publish_device` (`platform_id`, `object_type`, `device_key`) USING BTREE;

-- 存量通道发布行回填设备段：只认 CHANNEL，只在列为空时写，重复执行无副作用
UPDATE `tb_cascade_publish`
SET `device_key` = SUBSTRING_INDEX(`object_key`, ':', 1)
WHERE `object_type` = 'CHANNEL'
  AND `device_key` IS NULL
  AND INSTR(`object_key`, ':') > 1;

-- 校验：应返回 1
SELECT count(*) AS channel_code_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_device_channel'
  AND COLUMN_NAME = 'code';

-- 校验：应返回 4
SELECT count(*) AS publish_push_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_cascade_publish'
  AND COLUMN_NAME IN ('device_key', 'last_push_time', 'last_push_status', 'last_push_message');

-- 校验：CHANNEL 行不应再有空的 device_key（object_key 本身缺少分隔符的脏数据除外）
SELECT count(*) AS channel_rows_without_device_key
FROM `tb_cascade_publish`
WHERE `object_type` = 'CHANNEL'
  AND `device_key` IS NULL;

-- =============================================================================
-- 阶段八 平台名称 · 步骤 20/21：上级平台本地辨识名
--   原文件：1.10.4-cascade-platform-name.sql
--   名称只在本地使用，GB28181 报文里仍是 platform_id。
-- =============================================================================

-- 1.10.4 级联平台名称：给上级平台一个本地辨识名
--
-- 背景：列表里只有 20 位平台ID，多个平台并排时人认不出谁是谁。
-- 名称只在本地使用——GB28181 报文里用的是 platform_id，改名称不影响上报。
--
-- 存量平台一律补成空串（未命名）：升级不能替用户编名字，前端在空值时回落显示平台ID。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，本脚本只执行一次。

ALTER TABLE `tb_cascade_platform`
    ADD COLUMN `platform_name` VARCHAR(128) NOT NULL DEFAULT ''
        COMMENT '平台名称(本地辨识用，空=未命名)'
        AFTER `platform_domain`;

-- 校验：返回 1 表示列已存在
SELECT COUNT(*) AS platform_name_column
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'tb_cascade_platform'
  AND COLUMN_NAME = 'platform_name';

-- =============================================================================
-- 阶段九 本端客户端 · 步骤 21/21：本端注册身份拆为独立客户端表
--   原文件：1.10.5-cascade-client.sql
--   !! 必须最后执行：要读 local_client_id 才能收敛客户端行并回填 client_ref_id。
--   !! 本步 DROP 掉 platform 的 local_client_id / local_ip / local_port 三列，
--      执行后无法靠 DDL 还原，只能靠执行前的物理备份。
-- =============================================================================

-- =============================================================================
-- 1.10.5 级联「本端客户端」抽取（MySQL）
--
-- 背景：tb_cascade_platform 原本每行冗余一份 local_client_id / local_ip / local_port，
--       新增第二个上级平台要再抄一遍同样的值；抄错的本端 IP 会让上级把 INVITE 打到
--       不可达地址，而页面仍显示「在线」（注册是主动外发的，对端收得到 UDP 就算成功）。
--
-- 本脚本做的事：
--   1) 建 tb_cascade_client（本端平台身份：编码 + 对外通告 IP；端口不落库）
--   2) 平台表加 client_ref_id
--   3) 按 local_client_id 收敛出客户端行（同一身份只留一条，IP 取 id 最小那行的值）
--   4) 首选身份置为默认行（新增平台时预选）
--   5) 回填 client_ref_id
--   6) 删除三个 local_* 列
--
-- 注意：
--   * 本脚本只执行一次（ADD/DROP COLUMN 不是幂等语句）；
--   * 执行前务必备份 tb_cascade_platform（回滚只能靠备份或文末的回滚段）；
--   * SQLite 不需要本脚本：SqliteCascadeClientSchemaMigrator 会在启动时自动完成同样的动作；
--   * 本端端口一律由运行时配置 sip.client.port 提供，表里不再保存。
-- =============================================================================

-- 1) 本端客户端表
CREATE TABLE IF NOT EXISTS `tb_cascade_client`
(
    `id`          BIGINT UNSIGNED                                       NOT NULL AUTO_INCREMENT,
    `create_time` DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME                                              NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `client_id`   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '本端平台编码(20位)，创建后不可改',
    `client_name` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '本地辨识名，空=未命名',
    `client_ip`   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '对外通告IP(Contact/From用)',
    `is_default`  TINYINT                                               NOT NULL DEFAULT 0 COMMENT '新增上级平台时默认预选 0否 1是(全局唯一)',
    `enabled`     TINYINT                                               NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    `remark`      VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin         DEFAULT NULL COMMENT '备注',
    `extend`      TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT '扩展字段(FastJSON2)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cascade_client_id` (`client_id`) USING BTREE,
    KEY `idx_cascade_client_default` (`is_default`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT ='级联本端客户端表';

-- 2) 平台表加外键列
ALTER TABLE `tb_cascade_platform`
    ADD COLUMN `client_ref_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '本端客户端(tb_cascade_client.id)，应用层必填';

-- 3) 收敛客户端行：同一 local_client_id 只留一条，IP 取 id 最小那行
INSERT INTO `tb_cascade_client` (`client_id`, `client_name`, `client_ip`, `is_default`, `enabled`)
SELECT p.`local_client_id`, '', COALESCE(p.`local_ip`, ''), 0, 1
FROM `tb_cascade_platform` p
         JOIN (SELECT `local_client_id`, MIN(`id`) AS `id`
               FROM `tb_cascade_platform`
               WHERE `local_client_id` <> ''
               GROUP BY `local_client_id`) m ON m.`id` = p.`id`
WHERE NOT EXISTS (SELECT 1 FROM `tb_cascade_client` c WHERE c.`client_id` = p.`local_client_id`);

-- 4) 首选身份置为默认行（已有默认行时不改写）
SET @has_default = (SELECT COUNT(*) FROM `tb_cascade_client` WHERE `is_default` = 1);
SET @min_id = (SELECT MIN(`id`) FROM `tb_cascade_client`);
UPDATE `tb_cascade_client` SET `is_default` = 1 WHERE `id` = @min_id AND @has_default = 0;

-- 5) 回填引用
UPDATE `tb_cascade_platform` p
    JOIN `tb_cascade_client` c ON c.`client_id` = p.`local_client_id`
SET p.`client_ref_id` = c.`id`
WHERE p.`local_client_id` <> '';

-- 6) 删除冗余列（顺序在回填之后，否则无从按旧值回填）
ALTER TABLE `tb_cascade_platform`
    DROP COLUMN `local_client_id`,
    DROP COLUMN `local_ip`,
    DROP COLUMN `local_port`;

ALTER TABLE `tb_cascade_platform`
    ADD KEY `idx_cascade_platform_client` (`client_ref_id`) USING BTREE;

-- ============================================================
-- 7) 菜单：页面 605 + 按钮 60501-60504
--    原 1.10.5 脚本只做表结构，菜单只写在三份初始化 SQL 里（voglander.sql /
--    voglander-postgresql.sql / voglander-sqlite.sql），按脚本升级的库因此
--    升完没有页面入口。合并成 1.10.0 时补上这一段，与初始化 SQL 取值一致。
-- ============================================================
INSERT IGNORE INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon,
                            sort_order, status, permission, meta)
VALUES
(605, 500, 'CascadeClient', 'cascade.client.title', 2, '/device/cascade/client', '/cascade/client/list',
 'mdi:account-network-outline', 2, 1, 'Cascade:Client:List',
 JSON_OBJECT('icon', 'mdi:account-network-outline', 'title', 'cascade.client.title', 'hideInMenu', false));

INSERT IGNORE INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon,
                            sort_order, status, permission, meta)
VALUES
(60501, 605, 'CascadeClientCreate', 'cascade.client.create', 3, null, null, '', 1, 1,
 'Cascade:Client:Create', JSON_OBJECT('title', 'cascade.client.create', 'hideInMenu', true)),
(60502, 605, 'CascadeClientEdit', 'cascade.client.edit', 3, null, null, '', 2, 1,
 'Cascade:Client:Edit', JSON_OBJECT('title', 'cascade.client.edit', 'hideInMenu', true)),
(60503, 605, 'CascadeClientDelete', 'cascade.client.delete', 3, null, null, '', 3, 1,
 'Cascade:Client:Delete', JSON_OBJECT('title', 'cascade.client.delete', 'hideInMenu', true)),
(60504, 605, 'CascadeClientDefault', 'cascade.client.setDefault', 3, null, null, '', 4, 1,
 'Cascade:Client:SetDefault', JSON_OBJECT('title', 'cascade.client.setDefault', 'hideInMenu', true));

-- 授权：镜像「设备管理」(500) 与「级联平台」(601) 的角色，不按角色名匹配
INSERT IGNORE INTO tb_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, m.id
FROM tb_role_menu rm,
     tb_menu m
WHERE rm.menu_id IN (500, 601)
  AND m.id IN (605, 60501, 60502, 60503, 60504);

-- 校验：页面 605 应 1 行，按钮 60501-60504 应 4 行
SELECT id, parent_id, menu_code, path, component, permission
FROM tb_menu
WHERE id IN (605, 60501, 60502, 60503, 60504)
ORDER BY id;

-- 核对：应为 0
-- SELECT COUNT(*) FROM `tb_cascade_platform` WHERE `local_client_id` IS NOT NULL; -- 列已删，此句仅作心理核对
-- SELECT COUNT(*) FROM `tb_cascade_platform` WHERE `client_ref_id` IS NULL;        -- 期望 0（无编码的空行除外）

-- =============================================================================
-- 回滚（仅在没有写入新数据的前提下可用；执行前请确认）
-- =============================================================================
-- ALTER TABLE `tb_cascade_platform`
--     ADD COLUMN `local_client_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
--     ADD COLUMN `local_ip`        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
--     ADD COLUMN `local_port`      INT NOT NULL DEFAULT 5061;
-- UPDATE `tb_cascade_platform` p
--     JOIN `tb_cascade_client` c ON c.`id` = p.`client_ref_id`
-- SET p.`local_client_id` = c.`client_id`,
--     p.`local_ip`        = c.`client_ip`,
--     p.`local_port`      = 5061;
-- ALTER TABLE `tb_cascade_platform` DROP KEY `idx_cascade_platform_client`, DROP COLUMN `client_ref_id`;
-- DROP TABLE IF EXISTS `tb_cascade_client`;

