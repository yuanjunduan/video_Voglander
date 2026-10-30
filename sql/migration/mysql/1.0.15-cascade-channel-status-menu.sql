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