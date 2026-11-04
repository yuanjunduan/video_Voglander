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
