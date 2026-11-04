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
