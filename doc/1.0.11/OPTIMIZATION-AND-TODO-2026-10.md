# Voglander 优化点与待优化清单（2026-10-19 ~ 10-29）

> **范围**：近 10 天（2026-10-19 ~ 10-29）工作区实际改动，共 119 个文件（已剔除 `target/`、`node_modules/`、`logs/`）。
> 时间分布：10-23 **3** · 10-26 **30** · 10-27 **35** · 10-28 **30** · 10-29 **21**。
> **依据**：文件修改时间 + 同期产出的 `doc/` 文档 + 代码实测结论；不依赖 git 历史。
> **版本库状态**：工作区干净，全部内容已随唯一提交 `ba4cd91「first」`（2026-10-29 09:12，1233 个文件）入库——历史被压成单提交，无法按主题回滚或 review（见 §二 P1-3）。

---

## 一、优化点（已交付）

### 1. 本地全栈启动标准化

| 项 | 内容 |
| --- | --- |
| 问题 | 依赖 ZLM / Redis / MySQL / SIP 多进程，启动顺序错会出现「僵尸节点」，排障无共同基线 |
| 优化 | 固化端口表与启动顺序：`doc/LOCAL-STACK-RUNBOOK.md`、`doc/本地全栈服务启动清单.md`；`scripts/zlm-start.ps1` 一键起 ZLM；`logback-spring.xml` 分级日志（含 `voglander-error.log`）便于分诊 |
| 验证 | 按手册走通「注册 → 目录 → 点播」闭环 |

### 2. RTSP 设备接入闭环（探活 + 入口 + 存量 bug）

| 子项 | 内容 |
| --- | --- |
| 连通性探活 | 新增 `POST /api/v1/device/probe-rtsp`：TCP 建连 + `OPTIONS`，**同一条 socket** 内完成 Digest 挑战重发（大华把 nonce 绑在连接上，换连接必 401）；8 种结论工厂 + 中文文案；`RtspProbeClientTest` 12 用例（本地假服务，不依赖真机） |
| 判定语义 | 区分「服务可达」与「凭据通过」；URL 不带凭据时明确要求补全，不误报「密码错误」；只提示不拦截（部分设备不回 OPTIONS 但能出流） |
| 新增设备入口 | 前端抽屉 + 按钮权限码 `Device:Device:Add`（三份初始化 SQL 同步补按钮菜单） |
| 存量 bug 修复 | ①`DeviceDTO.convertDO` 整串覆盖 `extend` 导致编辑丢字段 → 新增 `mergeExtend` 逐项合并；②`expires` 基本类型拆箱 NPE |
| 验证 | 真机大华 `192.168.60.207` 四种场景（正确密码/错误密码/无凭据/ZLM 不鉴权）全部符合预期；RTSP `live/start` 1.95s、FLV 200 且首字节为 `FLV` |

### 3. NVR 多通道发现（海康 ISAPI）

| 项 | 内容 |
| --- | --- |
| 问题 | 一台 NVR 下多路通道无台账，只能当单设备播 |
| 优化 | 新增 `integration/wrapper/channel` 抽象（`ChannelDiscoverer` / `DiscoveredChannel` / `DiscoverResult` / `ChannelEndpoint` / `HttpDigestClient` / `IsapiXmlParser`）+ 海康实现 `HikvisionChannelDiscoverer`；`POST /api/v1/deviceChannel/discover/{deviceId}` 落库，`GET /deviceChannel/list/{deviceId}` 回读 |
| 语义 | 发现结果与在线状态分离（`status_source=DISCOVER`），互不覆盖 |
| 验证 | `IsapiXmlParserTest` / `ChannelEndpointTest` / `HikvisionChannelDiscovererTest` / `DeviceChannelDiscoveryServiceTest` 全部通过；真机 NVR 发现 8 路通道 |

### 4. 多编码播放（H.265 黑屏根治）

| 阶段 | 内容 |
| --- | --- |
| P0 后端 | `VideoCodec` / `StreamVideoInfo` / `StreamCodecResolver` / `ZlmStreamCodecResolver`；`LivePlayVO` 透出 `videoCodec`/`videoWidth`/`videoHeight`（10 用例） |
| P1 前端 | `playCapabilities`（运行时能力探测）+ `playStrategy`（9 条规则 × 编码/能力/播放器三重匹配）；摘掉两处硬编码 `format="httpFlv"`；21 例单测 |
| P2 WASM | 接入 jessibuca（GPL-3.0 合规：资源本地托管 + 保留 LICENSE/SOURCE.txt，未改上游字节）；`JessibucaPlayer.vue` + 错误码中文化；FLV 8s 无首帧或报错 → 自动升级 WASM 重建 |
| 硬依据 | 本机实测 MSE / 原生 video / WebRTC 对 HEVC **全部 false**，H.265 只剩 WASM 软解一条路 |
| 价值 | 消除「黑屏无解释」；编码未知时也有兜底出画路径 |

### 5. 直播会话与关流稳定性

- `LiveStreamEventListener` 修正 ready 事件时序；`MediaPlayServiceImpl` 修正关流引用计数（详情抽屉内路由跳转曾导致播放器卸载、归还引用而断流）。
- 新增 `/live/{streamId}/stats`、`/live/keepalive`、`/live/list` 与会话统计 DTO/VO。
- 验证：`LiveStreamEventListenerReadyTest`、`MediaPlayServiceCloseStreamTest`、`RtspMediaProtocolHandlerTest` 全绿。

### 6. 主子码流切换

- 新增 `StreamProfileEnum`，贯通 `LiveStartReq/DTO` → `MediaEstablishContext` → `Gb28181MediaProtocolHandler`；设备侧 `DeviceDTO`/`DeviceCreateReq`/`DeviceUpdateReq`/`DeviceVO`/`DeviceWebAssembler` 同步透出。
- 验证：`MediaPlayServiceProfileSwitchTest`、`Gb28181MediaProtocolHandlerTest`、`DeviceDTOExtendMergeTest`、`MediaPlayServiceReuseVerifyTest`。

### 7. 级联上级平台：安全与合理性加固（10-28）

| 项 | 内容 |
| --- | --- |
| 凭据 | `SensitiveUtils` 脱敏，列表/详情不再回明文密码 |
| 生命周期 | 新增 `/{id}/enable`、`/{id}/disable`、`/refresh`，update 只换任务不误注销 |
| 失败可见 | 失败原因写入 `extend.lastError` / `lastErrorAt`，前端可读 |
| 数据库 | `sql/voglander{,-postgresql,-sqlite}.sql` 三库同步 + `sql/migration/{mysql,postgresql,sqlite}/1.0.11-cascade-platform-hardening.sql` |
| 文档 | `doc/1.0.11/CASCADE-PLATFORM-SECURITY-HARDENING.md`（字段语义/调度约束/日志清单/上线步骤）、`CASCADE-PLATFORM-FULL-FLOW.md`（全景流程 + 排障索引） |

### 8. 级联可用性 7 项整改 + 框架 ACK 缺陷修复（10-29）

| # | 优化 | 落点 |
| --- | --- | --- |
| 1 | 心跳超时自愈（从未见响应只告警，避免误伤） | `CascadePeerAliveTracker`、`CascadeMessageResponseProcessor`、`CascadeClientScheduler` |
| 2 | 停用/删除发 `Expires: 0` 真注销 | `CascadeUnregisterService`、`CascadeRegisterAuthProcessor`、`CascadeClientRegisterListener` |
| 3 | 点播改为 ACK 后才 `startSendRtp`（200 OK 的 SDP 端口 = `src_port`） | `CascadeMediaInviteListener` |
| 4 | 目录 `sumNum` = 实际下发条数；超 `batch-size` 走框架分片并 `return null` 阻止重复整包 | `CascadeQueryHandler` |
| 5 | 通道状态真实化（一次 `in()` 批量取 `tb_device_channel.status`，缺失按 OFF） | `CascadeQueryHandler`、`DeviceChannelManager` |
| 6 | 目录变更推送失败重发一次（收到任意状态码即出表） | `CascadeNotifyAckTracker`、`CascadeNotifyPublisher` |
| 7 | 上级 `BasicParam` 下发解析并落库，越界不落库 | `CascadeConfigListener` |
| + | **修复框架缺陷**：`ClientAckEvent` 只由挂在「响应」表的处理器发布，而 ACK 是请求 → 事件永不触发。新增 `CascadeAckRequestProcessor` 注册到请求表 `"ACK"` 键；并给推流加 2s 幂等兜底 | `CascadeAckRequestProcessor`、`CascadeRegisterProcessorInstaller`、`CascadeMediaInviteListener` |

**验证**：`mvn -o -pl voglander-web -am "-Dtest=Cascade*Test,DeviceChannelManagerTest" test` → **134 用例 0 失败**。全过程记录见 `doc/1.0.11/CASCADE-REGISTER-FLOW-COMPARISON.md` 第 15 节（含 15.4 缺陷复盘）。

### 9. 级联媒体链路与 CSS 对齐（10-29）

对照参考实现 `D:\work\视频平台\CSS\Trunk`（Go，向上级联服务）补齐级联点播的三处不自洽，方案与差异清单见 `CASCADE-MEDIA-ALIGNMENT-WITH-CSS.md`。

| # | 优化 | 落点 | 验证 |
| --- | --- | --- | --- |
| 1 | **SSRC 兜底**：上级 INVITE 未带 ssrc 时按国标生成 10 位值（`首位 0实时/1回放 + 本域编码[4..9] + 通道号后4位`），不再回填 `"0"`；同通道并发靠进程内游标偏移防撞；`parseSsrc` 改 `long` 并保留低 32 位 | 新增 `CascadeSsrcGenerator`；`CascadeMediaInviteListener#resolveSsrc` | `CascadeSsrcGeneratorTest` 7 例；`CascadeMediaInviteListenerTest` 补 3 例 |
| 2 | **active 会话回收**：`reapStaleActiveSessions()` 以 ZLM `isMediaOnline` 为权威判活（探活异常保守保留）+ 空闲超时（600s）+ 30s 宽限期；收尾统一走幂等 `stopSession()`，等于 CSS 的「MPS 状态查询 + 600s 超时」 | `CascadeMediaInviteListener` | 补 4 例（源流消失/探活异常/空闲超时/宽限期） |
| 3 | **容器关闭全停**：`@PreDestroy` 遍历 `activeSessions` 逐个 `stopSendRtp` 并清空 pending，对齐 CSS 的 `clearSessionsAndStopPush()` | `CascadeMediaInviteListener#destroy` | 补 1 例 |
| — | 判活参数与直播链路复用同一套（`schema=rtsp` / `app=rtp` / `__defaultVhost__`），与 `LiveSessionGcService` 一致 | — | — |

**验证**：`mvn -o -pl voglander-web -am "-Dtest=Cascade*Test" test` → **140 用例 0 失败**（原 134 + 新增 13，其中生成器 7、监听器 6）。

### 10. 工程化配套

- 三套数据库 DDL（MySQL / PostgreSQL / SQLite）与 `sql/migration/` 迁移脚本保持同步。
- 测试补齐：近 10 天新增/更新测试 17 个类（探活、编解码、通道发现、码流切换、级联 7 项、ACK 处理器）。
- 文档沉淀：`LOCAL-STACK-RUNBOOK`、`本地全栈服务启动清单`、`PROTOCOL-ACCESS-BACKLOG`、`PLAYER-CODEC-STRATEGY-AND-PLAN`、`CASCADE-PLATFORM-*`、`CASCADE-REGISTER-FLOW-COMPARISON`。

---

## 二、待优化

### P0 — 阻塞业务闭环

| # | 待优化 | 现状 | 建议动作 |
| --- | --- | --- | --- |
| P0-1 | 级联通道映射为空 ✅已完成 | 2026-10-29 10:06 已落库 1 条：`hk-nvr165-ch3` / 通道 `7` / 级联 ID `34020000001320000007` / `enabled=1` | 已闭环。后续按需批量绑定（`POST /api/v1/cascade/channel/batchBind`，NVR 的 `channel_id` 是 `1..8` 非 GB 编码，必须显式给 `cascadeChannelId`） |
| P0-2 | README 能力宣称与代码不符 | `README.md` 第 5/27/36 行仍写「GB28181 / ONVIF / GT1078 全协议接入」，实际 ONVIF 只有枚举占位、GT1078 零实现 | 改为「GB28181 已支持，ONVIF/GT1078 规划中」+ 路线图（`PROTOCOL-ACCESS-BACKLOG` DOC-1，0.5 人日） |
| P0-3 | 协议验证台与级联互斥，上级看不到设备 ✅已完成 | `voglander.protocol-lab.enabled=true` 时 `CascadeQueryHandler`/`CascadeSubscribeHandler` 的 `@ConditionalOnExpression` 不成立而不注册，`QueryListener` 由 `LabQueryListener` 顶替且只认 Lab 的 `clientId`，海康的目录查询被 `return null` 静默丢弃；REGISTER/心跳不受影响，故表现为「注册成功但目录恒空」 | 已改 `application-prod.yml` 为 `false` 并补互斥说明注释。**开关语义不变**，需要协议自环时临时开启、用完关回 |
| P0-4 | 上级未带 ssrc 时填 `"0"` ✅已完成 | `CascadeMediaInviteListener` 写死 `sdp.getSsrc() != null ? ... : "0"`，`"0"` 一路进入 200 OK SDP 与 `startSendRtp`；`parseSsrc` 用 `Integer.parseInt` 越界静默回落 0 | 已新增 `CascadeSsrcGenerator` 按国标生成 10 位 ssrc（`首位 0实时/1回放 + 本域编码[4..9] + 通道号后4位`），上级有则优先用上级；`parseSsrc` 改 `long` 容错；开关 `gateway.cascade.media.ssrc-fallback-enabled` |
| P0-5 | `activeSessions` 无回收，漏收 BYE 永久占端口 ✅已完成 | `cleanExpiredPending()` 只扫 `pendingSessions`；`activeSessions` 仅靠 `onBye` 移除，上级异常断开时永不回收 → `srcPort`（30000-30999）泄漏直至「端口耗尽」告警并复用端口串流 | 已新增 `reapStaleActiveSessions()`：以 ZLM `isMediaOnline` 为权威判活（探活异常保守保留）+ 空闲超时（600s）+ 30s 宽限期回收，收尾统一走幂等 `stopSession()` |

### P1 — 质量与安全

| # | 待优化 | 现状 | 建议动作 |
| --- | --- | --- | --- |
| P1-1 | RTSP URL 凭据明文 | `rtsp://user:pass@...` 明文存 `tb_device.extend.rtspUrl` 并经 API 明文返回 | 入库前 AES 加密、`DeviceVO` 脱敏为 `rtsp://***:***@...`，仅 Handler 内部解密（RTSP-2，2 人日） |
| P1-2 | ACK 兜底推流的定性 | 已实现「ACK 可见则严格 ACK 后推流，2s 未见 ACK 则兜底推流」两套路径 | 首次点播实测：若日志只出现 `启用兜底推流`，说明该 SIP 栈不回调 ACK，应简化为直接推流并移除兜底分支，降低维护成本 |
| P1-3 | 提交历史粒度过粗 | 单提交 `first` 携带 1233 个文件，无法按主题 review / 回滚 | 后续严格按 Conventional Commits 分主题提交（直播/通道/级联/文档）；历史重建需评估风险后再做 |
| P1-4 | 临时探针文件入库 | 根目录 `_probe.txt` 已被 git 跟踪；另有若干 `logs/*.mjs`、`*.ps1` 探针脚本 | 清理或移入 `logs/` 并加入 `.gitignore` |
| P1-5 | 心跳超时阈值未按真机校准 | 当前「从未见过该平台 MESSAGE 响应则只告警不判离线」是保守策略 | 海康实测确认是否回 200，再决定是否收紧 `gateway.cascade.keepalive-timeout-count` |
| P1-6 | 进程关闭不停止级联推流 ✅已完成 | `CascadeMediaInviteListener` 无 `@PreDestroy`，重启后 ZLM 侧 `sendRtp` 发送器残留，上级持续收半死不活的流（CSS 有 `clearSessionsAndStopPush()`） | 已在 `@PreDestroy` 中遍历 pending + active 逐个 `stopSendRtp` 并清空 |
| P1-7 | 级联点播拉流链路与 CSS 不一致（**未实施**） | `ensureStreamOnline` 直接向设备发 GB28181 SIP INVITE 并把上级地址透传给设备；type=4 RTSP 设备无 SIP 栈 → 拉不到流。另 `MediaSession.stream` 自己拼 `deviceId_channelId`，与直播链路 `rtsp_live_*`/`gb_live_*` 不一致，ZLM 上查无此流 | 改造方案见 `CASCADE-MEDIA-ALIGNMENT-WITH-CSS.md` §4.4：改走 `MediaPlayService.startLive` 本地建流、streamId 用返回值、推流目标统一为 ZLM `startSendRtp`。估 0.5~1 天 + 联调 0.5 天，需另行确认 |

### P2 — 体验与稳定性

| # | 待优化 | 现状 | 建议动作 |
| --- | --- | --- | --- |
| P2-1 | 推送重试只覆盖目录变更 | 告警/位置属流式数据不重试（有意为之） | 若上级反馈告警丢失，再按「告警必达」单独设计（去重 + 幂等键） |
| P2-2 | 目录分片失败回退整包 | `sendCatalogBatched` 抛异常时回退为单条整包应答，通道多时 MESSAGE 会超长 | 失败时改为分小批重试或记错误并返回空目录，避免超长报文被上级丢弃 |
| P2-3 | 通道发现只覆盖海康 ISAPI | 仅 `HikvisionChannelDiscoverer`；大华/宇视/ONVIF 未实现 | 按 `PROTOCOL-ACCESS-BACKLOG` ONVIF-1/2 推进，或先补大华 ISAPI 变体 |
| P2-4 | 发现状态与在线状态两套来源 | `status_source=DISCOVER` 与 ZLM hook/心跳各写各的 | 前端明确标注来源与时间，避免「列表在线但播不出」的困惑 |
| P2-5 | H.265 真机与并发未验证 | WASM 每路一个解码 Worker（1080p 约 1 核/路），尚未压测 | 用真实 H.265 通道验证出画；≥4 路同屏再评估 P3 独立转码 worker |
| P2-6 | RTSP 断流不自愈 | `onStreamNotFound` 目前仅日志白名单 | RTSP-4：指数退避重连（1/2/4/8s，上限 30s，最多 5 次），失败置 `FAILED` 并推 SSE |
| P2-7 | `.gitignore` 有重复项 | `*.db` 在 44~50 行出现两次 | 顺手清理，避免后续维护误读 |

### P3 — 能力扩展（未排期）

- **ONVIF-1/2/3/4**：WS-Discovery 发现、SOAP 自动取流、PTZ、事件订阅（ONVIF 的视频流仍落在 RTSP，价值在「免手填 + 云台 + 事件」）。
- **GT1078-1**：先确认是 JT/T 1078 车载终端还是厂商私有，再评估范围。
- **RTSP-5/6**：批量导入（CSV）、定时巡检（注意「会话活跃时以 ZLM hook 为准」）。
- **P3 规模化转码**：仅在 WASM 并发成为瓶颈时启用（不用 `addFFmpegSource`，需独立 app+stream 避免自环）。

---

## 三、建议推进顺序

```
P0-1 级联通道绑定（业务闭环，需人工选通道）
   ↓
P0-2 README 对齐（0.5 人日，消除能力误判）
   ↓
P1-2 ACK 实测结论 → 决定是否简化兜底
   ↓
P1-1 RTSP 凭据加密 ＋ P1-4 探针文件清理
   ↓
P2-3/P2-4 通道发现扩展与状态口径统一
   ↓
P2-6 断流自愈 → ONVIF 系列 → GT1078 评估
```

---

## 四、验证命令速查

```powershell
# 编译（必须钉 JDK21，JDK25 会让 Lombok 静默失效）
$env:JAVA_HOME="C:\soft\jdk21"
mvn -o -q -pl voglander-integration -am "-Dgpg.skip=true" "-Dmaven.test.skip=true" compile

# 级联相关回归（140 用例）
mvn -o -pl voglander-web -am "-Dgpg.skip=true" `
  "-Dtest=Cascade*Test,DeviceChannelManagerTest" `
  "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" test

# RTSP 探活
curl -X POST http://127.0.0.1:8080/api/v1/device/probe-rtsp `
  -H "Content-Type: application/json" `
  -d '{"rtspUrl":"rtsp://admin:Admin123@192.168.60.207:554/cam/realmonitor?channel=1&subtype=1"}'

# NVR 通道发现
curl -X POST http://127.0.0.1:8080/api/v1/deviceChannel/discover/hk-nvr165-ch3
```

**级联日志关键字（重启后自检）**

| 环节 | 日志 |
| --- | --- |
| 注册接管 | `级联注册响应处理器已接管` |
| ACK 监听 | `级联 ACK 请求监听已注册: 当前 ACK 处理器 N 个` |
| 点播正常 | `级联 INVITE 已应答，等待 ACK 后推流` → `级联推流已启动`（无兜底告警） |
| 上级未带 ssrc | `级联 INVITE 未携带 ssrc，已生成: ssrc=..., localChannelId=..., playback=...`（ssrc 必须是 10 位非 0） |
| 会话回收 | `级联会话收尾: callId=..., 原因=源流已消失\|空闲超时 Ns\|收到上级 BYE\|容器关闭, ...` |
| 关闭全停 | `级联媒体监听已关闭: 停止推流 N 个, 丢弃待推流会话 M 个` |
| 走兜底 | `级联 INVITE 在 Nms 内未观测到 ACK，启用兜底推流` |
| 心跳自愈 | `级联心跳超时，判定上级失联并自愈重注册` |
| 推送重试 | `级联推送 Ns 未确认，重发一次` |
