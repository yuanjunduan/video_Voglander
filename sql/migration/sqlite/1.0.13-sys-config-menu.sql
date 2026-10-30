-- Voglander 1.0.13 system config + cascade monitor menu (SQLite)
--
-- 可重复执行：INSERT OR IGNORE + 幂等 UPDATE。

INSERT OR IGNORE INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order,
                               status, permission, meta)
VALUES
(205, 2, 'SystemConfig', 'system.config.title', 2, '/system/config', '/system/config/list', 'mdi:cog-outline', 5, 1,
 'System:Config:List',
 '{"icon": "mdi:cog-outline", "title": "system.config.title", "hideInMenu": false}'),
(604, 500, 'CascadeMonitor', 'cascade.monitor.title', 2, '/device/cascade/monitor', '/cascade/monitor/list',
 'mdi:chart-timeline-variant', 7, 1, 'Cascade:Monitor:List',
 '{"icon": "mdi:chart-timeline-variant", "title": "cascade.monitor.title", "hideInMenu": false}');

UPDATE tb_menu
SET menu_name  = 'system.config.title',
    path       = '/system/config',
    component  = '/system/config/list',
    icon       = 'mdi:cog-outline',
    sort_order = 5,
    permission = 'System:Config:List',
    meta       = '{"icon": "mdi:cog-outline", "title": "system.config.title", "hideInMenu": false}'
WHERE id = 205;

UPDATE tb_menu
SET menu_name  = 'cascade.monitor.title',
    path       = '/device/cascade/monitor',
    component  = '/cascade/monitor/list',
    icon       = 'mdi:chart-timeline-variant',
    sort_order = 7,
    permission = 'Cascade:Monitor:List',
    meta       = '{"icon": "mdi:chart-timeline-variant", "title": "cascade.monitor.title", "hideInMenu": false}'
WHERE id = 604;

-- 授权：镜像级联平台(601) 已授权的角色
INSERT OR IGNORE INTO tb_role_menu (role_id, menu_id)
SELECT rm.role_id, m.id
FROM tb_role_menu rm,
     tb_menu m
WHERE rm.menu_id = 601
  AND m.id IN (205, 604);

INSERT OR IGNORE INTO tb_role_menu (role_id, menu_id)
SELECT 1, id FROM tb_menu WHERE id IN (205, 604);

-- 校验
SELECT id, parent_id, menu_code, menu_name, path, component, sort_order, permission
FROM tb_menu WHERE id IN (205, 604) ORDER BY id;

SELECT role_id, menu_id FROM tb_role_menu WHERE menu_id IN (205, 604) ORDER BY menu_id, role_id;
