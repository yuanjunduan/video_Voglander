-- Voglander 1.0.13 system config + cascade monitor menu (PostgreSQL)
--
-- 可重复执行：ON CONFLICT DO UPDATE + ON CONFLICT DO NOTHING。

INSERT INTO tb_menu (id, parent_id, menu_code, menu_name, menu_type, path, component, icon,
                     sort_order, status, permission, meta)
VALUES
(205, 2, 'SystemConfig', 'system.config.title', 2, '/system/config', '/system/config/list', 'mdi:cog-outline', 5, 1,
 'System:Config:List',
 '{"icon": "mdi:cog-outline", "title": "system.config.title", "hideInMenu": false}'),
(604, 500, 'CascadeMonitor', 'cascade.monitor.title', 2, '/device/cascade/monitor', '/cascade/monitor/list',
 'mdi:chart-timeline-variant', 7, 1, 'Cascade:Monitor:List',
 '{"icon": "mdi:chart-timeline-variant", "title": "cascade.monitor.title", "hideInMenu": false}')
ON CONFLICT (id) DO UPDATE
SET parent_id  = EXCLUDED.parent_id,
    menu_name  = EXCLUDED.menu_name,
    path       = EXCLUDED.path,
    component  = EXCLUDED.component,
    icon       = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    permission = EXCLUDED.permission,
    meta       = EXCLUDED.meta;

INSERT INTO tb_role_menu (role_id, menu_id)
SELECT rm.role_id, m.id
FROM tb_role_menu rm,
     tb_menu m
WHERE rm.menu_id = 601
  AND m.id IN (205, 604)
ON CONFLICT DO NOTHING;

INSERT INTO tb_role_menu (role_id, menu_id)
SELECT 1, id FROM tb_menu WHERE id IN (205, 604)
ON CONFLICT DO NOTHING;

-- 校验
SELECT id, parent_id, menu_code, menu_name, path, component, sort_order, permission
FROM tb_menu WHERE id IN (205, 604) ORDER BY id;

SELECT role_id, menu_id FROM tb_role_menu WHERE menu_id IN (205, 604) ORDER BY menu_id, role_id;
