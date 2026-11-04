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
