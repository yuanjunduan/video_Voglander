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
