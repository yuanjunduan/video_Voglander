-- Voglander 1.0.14 cascade org revoke policy (PostgreSQL)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景与字段含义同 MySQL 版：tb_cascade_org 补 revoke_policy 列，记录「取消同步」时
-- 其下通道的处置策略（CASCADE 连带剪掉 / REROUTE 上浮挂域节点）。
--
-- 幂等：ADD COLUMN IF NOT EXISTS + NOT NULL DEFAULT，历史行自动回填为 CASCADE。

ALTER TABLE tb_cascade_org
    ADD COLUMN IF NOT EXISTS revoke_policy VARCHAR(16) NOT NULL DEFAULT 'CASCADE';

COMMENT ON COLUMN tb_cascade_org.revoke_policy IS '取消同步时通道处置：CASCADE连带剪掉 / REROUTE上浮挂域节点';

-- 校验：应返回 1
SELECT count(*) AS revoke_policy_columns
FROM information_schema.columns
WHERE table_name = 'tb_cascade_org'
  AND column_name = 'revoke_policy';
