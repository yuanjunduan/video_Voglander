# 级联上级平台：安全与合理性整改（1.0.11）

> 版本：1.0.11
>
> 日期：2026-10-28
>
> 状态：P0 + P1 已落地（编译 + 单测通过），待运行态验证
>
> 触发问题：海康网关级联记录已启用但注册状态长期"离线"（`register_status=0`），且配置面存在密码回传、无入参校验、更新不生效等隐患

---

## 1. 问题清单与处置结论

| 级别 | 问题 | 处置 |
| --- | --- | --- |
| P0 | 新增平台后不启动调度：记录 enabled=1 但从未发起 REGISTER | `add` 成功后按库中最新记录启动调度 |
| P0 | 更新平台后仍用旧配置注册：调度任务持有的是 DTO 快照 | `update` 成功后先停旧任务再按最新记录重建 |
| P0 | 删除平台后任务残留、幽灵注册不止息 | `delete` 必须先 `stopPlatform` 再删记录（顺序不可颠倒） |
| P0 | 平台密码明文回传前端 | VO 的 `password` 加 `@JsonIgnore`，接口不再返回；更新时留空表示"保持原值" |
| P0 | 重复平台ID 触发唯一索引异常，原始 SQL 回传前端 | 新增前先查重给友好提示；全局异常处理器吞掉 `DataIntegrityViolationException` 原始信息 |
| P1 | 入参无任何服务端校验（20 位 ID、端口、IP、协议可随意填） | `CreateReq/UpdateReq` 增加 jakarta validation，Controller 加 `@Valid` |
| P1 | `platform_domain` 库中 NOT NULL 而前端标可选 | 后端兜底取平台ID前 10 位 |
| P1 | `local_port` 默认 5070 与 SIP 客户端 5061 不一致 | 三套建表 SQL 默认值改 5061，补迁移脚本 |
| P1 | 失败后无原因、且要等 2/3 注册周期（默认 40 分钟）才重试 | 失败原因写 `extend.lastError`；按 30s/60s/120s/300s 快速退避重试 |
| P2（未做） | 密码明文存库、接口无细粒度授权、SSE 免鉴权 | 记录为后续专项，需产品决策与前端配合 |

---

## 2. 调度生命周期（必须遵守的约束）

```
新增 add     → manager.add → 回查最新记录 → enabled=1 时 startPlatform
更新 update  → 停旧任务 → 更新记录 → 回查最新记录 → enabled=1 时 startPlatform
删除 delete  → stopPlatform（先）→ manager.delete（后）
启用 enable  → enablePlatform → startPlatform
停用 disable → stopPlatform → disablePlatform
```

- `stopPlatform(id)` 内部先 `getById(id)`，查不到直接返回且不取消任务；因此**删除必须先停后删**，否则任务泄漏。
- 调度任务（`scheduleWithFixedDelay`）的 lambda 捕获的是启动时的 DTO 快照，**不重建就不会读到新配置**。
- 启动时只会对"任务表中不存在"的 platformId 启动；运行期新增靠 `add` 主动启动，不依赖定时扫描。

---

## 3. 字段语义（前端表单口径）

| 字段 | 含义 | 取值来源 |
| --- | --- | --- |
| `platformId` | 上级平台国标 ID（20 位） | 上级登记的本平台 ID，REGISTER 的 To |
| `platformIp` / `platformPort` | 上级 SIP 信令地址 | 上级给的信令地址与端口（海康常见 7099） |
| `platformDomain` | 上级 SIP 域 | 留空取 `platformId` 前 10 位 |
| `localClientId` | 本端客户端国标 ID（20 位） | **必须与上级登记的下级域编码完全一致**，摘要鉴权 username 就是它 |
| `localIp` / `localPort` | 本端对外通告地址 | 留空取 `local.sip.client.domain` / `port`（当前 192.168.118.7:5061） |
| `username` | 仅存库展示 | SIP 摘要不读取该字段 |
| `password` | 摘要鉴权口令 | 上级为本端配置的口令；接口不回显，留空=不修改 |
| `transport` | UDP / TCP | 与上级监听一致（海康常见 UDP） |
| `registerExpires` | 注册有效期（秒） | 默认 3600；续期周期 = 2/3，最小 60s |
| `keepaliveInterval` | 心跳间隔（秒） | 默认 60，仅在 ONLINE 时发送 |

> **上级"媒体服务端口/TCP-UDP 端口段/websocket 端口"无需填写**。级联点播方向是上级 INVITE 本端，媒体收流地址由本端在 SDP 中给出，再经 ZLM `startSendRtp` 推给上级；这些端口属于"上级主动访问下级媒体"的场景，不是本端注册参数。

---

## 4. 失败可见性与排查

失败原因写入 `tb_cascade_platform.extend`：

```json
{"lastError":"REGISTER 失败 statusCode=401（请核对本端客户端ID是否已在上级登记、密码是否正确）","lastErrorAt":1761619200000}
```

- 注册成功时 `lastError` 置空串（`lastErrorAt` 更新为恢复时间）。
- 接口 `GET /api/v1/cascade/platform/{id}` 与列表响应新增只读字段 `lastError` / `lastErrorAt`。
- `extend` 若为历史脏数据（非 JSON），写入前会重置为空对象，不影响调度。

状态码对照：

| statusCode | 含义 | 处理方向 |
| --- | --- | --- |
| 401 / 403 | 鉴权失败 | 核对 `localClientId` 是否与上级登记一致、密码是否正确 |
| 404 | 上级不认识该平台ID | 核对 `platformId` / `platformDomain` |
| 408 | 响应超时 | 核对网络、防火墙、UDP/TCP 是否与上级一致 |
| 无响应（30s 转 FAILED） | 报文未到达或被静默丢弃 | 先确认到上级 IP:PORT 的连通性；海康网关对未登记来源会静默丢包 |

快速重试：第 1~4 次失败分别延迟 30s / 60s / 120s / 300s 重发 REGISTER，超过 4 次回落到常规续期周期；重试前重新读库，平台已停用/删除则放弃。

---

## 5. 日志清单（新增）

- 新增/更新/删除/启用/停用/刷新：操作审计日志，密码统一经 `SensitiveUtils.mask()` 脱敏为 `***`。
- 注册发起：`localClientId@localIp:localPort -> sip:platformId@platformIp:platformPort`，附带 `transport` / `charset` / `expires` / 连续失败次数。
- REGISTER 已发送：`callId` + 耗时（ms）。
- 注册成功：`peerProtocolVersion`、目标地址、本端 ID。
- 注册失败：`statusCode` + 可操作提示 + 目标地址。
- 注册超时：`callId` + `timeout`。
- 调度启停：目标地址、本端地址；刷新完成打印"启用平台 / 注册任务 / 保活任务"数量。
- 心跳失败：目标地址。

---

## 6. 数据库变更

| 脚本 | 内容 |
| --- | --- |
| `sql/voglander.sql`、`sql/voglander-postgresql.sql`、`sql/voglander-sqlite.sql` | `tb_cascade_platform.local_port` 默认值 5070 → 5061 |
| `sql/migration/mysql/1.0.11-cascade-platform-hardening.sql` | `ALTER COLUMN SET DEFAULT 5061` + 存量 5070 检查 + 失败原因查询 |
| `sql/migration/postgresql/1.0.11-cascade-platform-hardening.sql` | 同上 |
| `sql/migration/sqlite/1.0.11-cascade-platform-hardening.sql` | SQLite 不支持改列默认值，仅提供检查语句；新建库已生效，应用层已兜底回填 |

存量 `local_port=5070` 的记录需人工确认后再改（脚本内 UPDATE 默认注释掉）。

---

## 7. 上线步骤

1. `mvn -o -B -pl voglander-common,voglander-client,voglander-repository,voglander-manager,voglander-integration,voglander-service install -DskipTests -Dgpg.skip=true`（JDK 17）。
2. 执行对应数据库的 1.0.11 迁移脚本（至少确认默认值，存量记录按需修正）。
3. 重启后端（IDE 停止后重新 Debug 启动，确保加载新 class 与 yml）。
4. 管理端点一次"刷新调度"，或把目标平台的启用开关关一次再开。
5. 验证：
   - `GET /api/v1/cascade/platform/{id}` 的 `registerStatus` 是否从 0 → 2 → 1；
   - 失败时看 `lastError` 是否给出原因；
   - 日志检索 `级联` / `REGISTER` 关键字。

---

## 8. 前端配套（待办）

- 平台列表/详情不再拿到 `password`，编辑弹窗的密码框需改为"留空表示不修改"，并去掉密码必填校验。
- 建议在列表增加"最近失败原因"列或 Tooltip，直接展示 `lastError`。
- `platformDomain` 可从"可选"改为"可选，提示：留空自动取平台ID前 10 位"。

---

## 9. 测试证据

| 测试类 | 覆盖点 | 结果 |
| --- | --- | --- |
| `CascadePlatformControllerTest` | 新增启动调度 / 停用不启动 / 重复ID 友好提示 / 默认值回填 / 更新留空保留密码 / 更新重启调度 / 删除顺序 / 密码不外泄 | 14 通过 |
| `CascadePlatformReqValidationTest` | 20 位 ID、IPv4、端口范围、协议枚举、编码大小写、更新局部提交 | 12 通过 |
| `CascadeClientSchedulerTest` | 退避序列、失败原因合并进 extend、成功后清空、脏 JSON 容错、平台已删除 | 8 通过（另保留原 3 项启动容错/超时用例） |
| `CascadeClientRegisterListenerTest` | 成功/失败事件驱动调度器失败可见性 | 2 通过 |

命令：

```bash
mvn -o -B -pl voglander-web -am test \
  -Dtest=CascadePlatformControllerTest,CascadePlatformReqValidationTest,CascadeClientSchedulerTest,CascadeClientRegisterListenerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```