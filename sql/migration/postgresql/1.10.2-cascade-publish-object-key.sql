-- 1.10.2 级联发布层：对象定位从自增主键改成业务键 + 父节点覆盖列 (PostgreSQL 9.6+)
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

-- 1.10.1 的建表语句用匿名 UNIQUE 声明，PostgreSQL 会自动命名；也兼容后来改过名的库
DO $$
BEGIN
    ALTER TABLE tb_cascade_publish
        DROP CONSTRAINT IF EXISTS tb_cascade_publish_platform_id_object_type_object_id_key;
    ALTER TABLE tb_cascade_publish
        DROP CONSTRAINT IF EXISTS uk_publish_object;
END $$;
DROP INDEX IF EXISTS uk_publish_object;

-- 先允许为空：有数据时无法直接加 NOT NULL 列，回填后再收紧
ALTER TABLE tb_cascade_publish ADD COLUMN IF NOT EXISTS object_key VARCHAR(128);
ALTER TABLE tb_cascade_publish ADD COLUMN IF NOT EXISTS parent_key VARCHAR(128) DEFAULT NULL;

-- 回填：按对象类型 join 回各自的业务键
UPDATE tb_cascade_publish p
   SET object_key = o.org_code
  FROM tb_cascade_org o
 WHERE p.object_id = o.id AND p.object_type = 'ORG';

UPDATE tb_cascade_publish p
   SET object_key = d.device_id
  FROM tb_device d
 WHERE p.object_id = d.id AND p.object_type = 'DEVICE';

UPDATE tb_cascade_publish p
   SET object_key = c.device_id || ':' || c.channel_id
  FROM tb_device_channel c
 WHERE p.object_id = c.id AND p.object_type = 'CHANNEL';

-- 对象已被删除的历史行无法翻译成业务键：留着会让唯一键被空值卡死
DELETE FROM tb_cascade_publish WHERE object_key IS NULL OR object_key = '';

ALTER TABLE tb_cascade_publish ALTER COLUMN object_key SET NOT NULL;

CREATE UNIQUE INDEX uk_publish_object ON tb_cascade_publish (platform_id, object_type, object_key);

ALTER TABLE tb_cascade_publish DROP COLUMN IF EXISTS object_id;

COMMENT ON COLUMN tb_cascade_publish.object_key IS
    '对象业务键：ORG=org_code / DEVICE=device_id / CHANNEL=device_id:channel_id';
COMMENT ON COLUMN tb_cascade_publish.parent_key IS
    '该平台下的父节点业务键，空=按本地结构推导';
