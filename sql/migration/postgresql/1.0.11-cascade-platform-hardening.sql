-- Voglander 1.0.11 cascade platform hardening (PostgreSQL)
-- Non-destructive and repeatable. No DROP statements are permitted here.
--
-- 背景：tb_cascade_platform.local_port 的历史默认值 5070 与本机 SIP 客户端监听端口 5061 不一致。
-- 平台记录留空本端端口时会落 5070，导致 Contact 通告成不存在的端口，上级回包无法到达。

ALTER TABLE tb_cascade_platform ALTER COLUMN local_port SET DEFAULT 5061;

-- 校验 1：默认值是否已生效（column_default 应为 5061）
SELECT column_name, column_default FROM information_schema.columns
WHERE table_name = 'tb_cascade_platform' AND column_name = 'local_port';

-- 校验 2：存量 5070 记录——确认这些平台确实应使用 5061 后再执行 UPDATE
SELECT id, platform_id, local_ip, local_port, enabled FROM tb_cascade_platform WHERE local_port = 5070;
-- UPDATE tb_cascade_platform SET local_port = 5061 WHERE local_port = 5070;

-- 校验 3：最近一次注册失败原因（应用写入 extend，无需新增列）
SELECT id, platform_id, register_status, extend FROM tb_cascade_platform WHERE extend LIKE '%lastError%';