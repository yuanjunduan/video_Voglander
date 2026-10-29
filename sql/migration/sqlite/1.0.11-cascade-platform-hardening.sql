-- Voglander 1.0.11 cascade platform hardening (SQLite)
-- SQLite 不支持 ALTER COLUMN ... SET DEFAULT，默认值只能随建表语句生效。
--
-- 说明：
-- 1) sql/voglander-sqlite.sql 的 tb_cascade_platform.local_port 默认值已改为 5061，新建库直接生效；
-- 2) 已有 SQLite 库需要整表重建才能改默认值，本地调试库不建议重建，交由应用层兜底：
--    CascadePlatformController 在新增/启用时用 sip.client.port（默认 5061）回填 local_port；
-- 3) 存量记录检查（确认这些平台确实应使用 5061 后再执行 UPDATE）。

SELECT id, platform_id, local_ip, local_port, enabled FROM tb_cascade_platform WHERE local_port = 5070;
-- UPDATE tb_cascade_platform SET local_port = 5061 WHERE local_port = 5070 AND local_ip IS NOT NULL;

-- 最近一次注册失败原因（应用写入 extend，无需新增列）
SELECT id, platform_id, register_status, extend FROM tb_cascade_platform WHERE extend LIKE '%lastError%';