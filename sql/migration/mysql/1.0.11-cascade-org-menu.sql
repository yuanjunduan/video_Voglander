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
