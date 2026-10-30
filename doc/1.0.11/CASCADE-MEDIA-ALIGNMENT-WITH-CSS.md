# 级联媒体链路与 CSS 参考实现对齐方案

> 版本：v1.0 ｜ 日期：2026-10-29
> 关联：[CASCADE-REGISTER-FLOW-COMPARISON.md](CASCADE-REGISTER-FLOW-COMPARISON.md)、[OPTIMIZATION-AND-TODO-2026-10.md](OPTIMIZATION-AND-TODO-2026-10.md)
> 参考实现：`D:\work\视频平台\CSS\Trunk`（Go，向上级联服务，以下称 CSS）

---

## 1. 背景

### 1.1 本轮已定位并修复的问题（配置层）

「海康已注册成功但看不到任何设备」的根因是 **协议验证台与级联互斥**：

| 环节 | 事实 |
|---|---|
| 配置 | `application-prod.yml` 的 `voglander.protocol-lab.enabled` 为 `true` |
| 装配条件 | `CascadeQueryHandler` / `CascadeSubscribeHandler` 带 `"${sip.client.enabled} and not ${voglander.protocol-lab.enabled}"`，条件不满足 → **不注册** |
| 顶替者 | `LabQueryListener` 成为唯一 `QueryListener`，它只认 Lab 自己的 `clientId`（`34020000001320000011`）；海康查目录时 `DeviceID` 是平台域 `34020000002000000001`，被 `isLabDevice()` 判否后 `return null`，**连错误都不回** |
| 表象 | REGISTER / 心跳照常成功（走 `CascadeClientScheduler`，不受该条件限制），但日志无「响应目录查询」，上级目录恒空 |

**处置**：`enabled` 改为 `false`，并在该配置项上方补充了互斥说明（含现象特征与临时开启方式）。此项已落盘，重启生效。

### 1.2 本文要解决的问题

配置修好后上级能"看到"目录，但**点播能不能出画面、会话会不会泄漏**还有两处与 CSS 的实质差异。本文给出这两项的实施方案，并把媒体链路的结构性问题单列，供后续决策。

---

## 2. CSS 的处理方式

### 2.1 三层分工

```
上级平台(海康) ──SIP──▶ CSS(级联服务/信令) ──HTTP──▶ CMS(目录+地址服务)
                            │
                            └──HTTP──▶ MPS(媒体推流服务) ──RTP/PS──▶ 上级平台
```

CSS 的 SIP 层**不理解媒体**：只把"拉流地址"交给 MPS、把"MPS 返回的推流端口"写进 SDP。

### 2.2 点播时序（`service/gb28181/gb28181_invite.go`）

| 步骤 | 方法 | 动作 |
|---|---|---|
| 1 | `parseOfferSdp` | 解析上级 offer：推流 IP/端口、传输方式、ssrc、deviceSn（Subject → URI → Origin 三级回退） |
| 2 | `getPullAddrFromCms` | 向 CMS 要可拉取地址（`getLiveAddr`，`protocol=sstp`，主码流） |
| 3 | `GetOptimalMps` | 按 deviceSn 选最近的媒体节点 |
| 4 | `mps.PrePush` | 提交 `pullAddr + 上级IP:Port + transType + ssrc` → 返回 `sessionId / pushLocalIP / pushLocalPort` |
| 5 | `createAnswerSdp` + `sendSipInviteResponse` | **200 OK 的 `m=` 端口 = MPS 分配的 `pushLocalPort`** |
| 6 | `ackResponse` | 收到 ACK 才 `mps.StartPush(sessionId)` |
| 7 | `byeResponse` | 收到 BYE 则 `mps.StopPush(sessionId)` + 删 dialog |

### 2.3 会话保活（`periodicUpdateSessions`）

```go
for { // 每 MoreDelayTime 秒
    sessionsStatus := p.getSessionStatus()   // 批量向各 MPS 查会话状态
    for _, sess := range sessionsStatus {
        if sess.Status == 0 { p.removeSession(sess.Id) } else { p.sessions[sess.Id].updateTime = now }
    }
    p.removeTimeoutSessions(now)             // 600s 无更新 → 移除
}
p.clearSessionsAndStopPush()                 // 平台销毁时全部 StopPush
```

三个兜底层次：**MPS 状态查询 → 本地 600s 超时 → 销毁时全停**。

### 2.4 ssrc 缺失兜底（`gb28181_utils.go:generateSsrc`）

```go
ssrcSuffix := fmt.Sprintf("%04d", sn%10000)
if playMode == "Play" || playMode == "Talk" {
    return fmt.Sprintf("0%s%s", sipId[4:9], ssrcSuffix)   // 首位 0 = 实时
}
return fmt.Sprintf("1%s%s", sipId[4:9], ssrcSuffix)       // 首位 1 = 回放
```

代码注释写得很直白：「**海康平台下发 Invite 竟然不带 ssrc**」。10 位规则为 `首位(0实时/1回放) + 本域编码第5~9位 + 序号后4位`。

---

## 3. 差异清单

| 编号 | 维度 | CSS | Voglander 现状 | 影响 | 优先级 |
|---|---|---|---|---|---|
| **D1** | ssrc 缺失 | 按国标规则生成 10 位 | `sdp.getSsrc() != null ? ... : "0"`，一路传 `"0"` 到 SDP 与 ZLM | 上级按 ssrc 匹配/分发 RTP 时取不到流，表现为有 RTP 无画面 | **P0** |
| **D2** | active 会话回收 | MPS 状态查询 + 600s 超时 + 销毁全停 | `cleanExpiredPending()` 只扫 `pendingSessions`；`activeSessions` 仅 `onBye` 移除 | 漏收 BYE 的会话永久占用 `srcPort`（30000-30999），最终触发「端口耗尽」告警并复用端口 → 串流/花屏 | **P0** |
| **D3** | 容器关闭 | `clearSessionsAndStopPush()` | `CascadeMediaInviteListener` 无 `@PreDestroy` | 进程重启后 ZLM 侧 sendRtp 残留，上级持续收半死不活的流 | P1 |
| **D4** | 拉流方式 | 地址交给 MPS，协议无关 | `ensureStreamOnline` 直接给设备发 **SIP INVITE**（`inviteRealTimePlay`） | RTSP(type=4) 设备无 SIP 栈 → 拉不到流；GB28181 设备则被要求直推上级，与本地 ZLM 转发路径冲突 | P1（见 §4.4） |
| **D5** | 流身份 | `sessionId`（媒体服务分配） | 自己拼 `localDeviceId + "_" + localChannelId` | 与直播链路 `rtsp_live_<deviceId>[_<channelId>]` 不一致，ZLM 上找不到该流 | P1（与 D4 同批） |

> D1/D2/D3 是"不依赖设备类型、纯级联侧自洽"的修复，风险低、可独立验收，纳入本次实施。
> D4/D5 要动媒体编排，属独立批次，需另行确认后开工。

---

## 4. 实施计划

> **实施状态（2026-10-29）**：批次一 ~ 批次三 **已全部落盘**，回归 140 用例 0 失败。
> 批次四（D4/D5）未实施，仍待确认。交付明细见 §8。

### 4.1 批次一：SSRC 兜底（D1）— ✅ 已完成

**改动文件**

| 文件 | 变更 |
|---|---|
| `voglander-integration/.../cascade/CascadeSsrcGenerator.java` | **新增**：按国标规则生成 10 位 ssrc |
| `.../cascade/CascadeMediaInviteListener.java` | INVITE 解析处改为"上级有则用、无则生成"；`parseSsrc` 改容错 |
| `.../CascadeSsrcGeneratorTest.java` | **新增** 7 例 |
| `.../CascadeMediaInviteListenerTest.java` | 补 3 例 |

**变更点**

1. `onInvite` 第 118 行：
   ```java
   // 现状：String ssrc = sdp.getSsrc() != null ? sdp.getSsrc() : "0";
   String ssrc = resolveSsrc(sdp, channel, playback);   // 上级优先，缺失时按规则生成并记 info 日志
   ```
2. 新增 `resolveSsrc`：调 `CascadeSsrcGenerator.generate(localSipId, localChannelId, playback)`。
   规则对齐 CSS：`首位(0实时/1回放) + localSipId[4..9] + %04d(通道序号)`，总长 10 位。
   通道序号取 `localChannelId` 数字部分；非数字（如 Lab 的 `lab_ch`）用 `CRC32(localChannelId) % 10000` 保持稳定。
3. 同一通道并发会话防撞：生成器对同 `(localChannelId, playback)` 的结果做**原子递增偏移**（最多 10000 个号），避免两次点播拿到同一 ssrc 导致 ZLM `startSendRtp` 互相覆盖。
4. `parseSsrc`（第 506 行）：`Integer.parseInt` 在 ssrc 超 `Integer.MAX_VALUE` 时抛异常回落 0，改为 `Long.parseLong` 后 `(int) (v & 0xFFFFFFFFL)`，并在越界时打 warn。
5. `sendInviteOk`（第 420 行）的 `defaultIfBlank(ssrc, "0")`：`resolveSsrc` 已保证非空，此处保留仅作防御，实际不会走到 `"0"`。
6. 配置项 `gateway.cascade.media.ssrc-fallback-enabled` 用 `@Value` 默认值 `true` 承载，未写进 `application-*.yml`（避免动环境文件）；需要关闭时在 `application-prod.yml` 显式加一行即可。

**验收**

- 单测：`CascadeSsrcGeneratorTest` 覆盖实时/回放、数字/非数字通道号、并发唯一性（7 例全绿）；`CascadeMediaInviteListenerTest` 补"上级不带 ssrc"用例，断言生成 10 位非 0、实时 `0` / 回放 `1` 开头。
- 日志关键字：`级联 INVITE 未携带 ssrc，已生成: callId=..., ssrc=..., localChannelId=..., playback=...`
- 现场：抓 200 OK SDP 确认 `y=` 为 10 位；ZLM `getRtpInfo` 能看到对应 ssrc。

### 4.2 批次二：active 会话回收（D2）— ✅ 已完成

**改动文件**

| 文件 | 变更 |
|---|---|
| `.../cascade/CascadeMediaInviteListener.java` | `MediaSession` 增 `lastActiveAt`；新增 `reapStaleActiveSessions()`；BYE 路径复用统一的 `stopSession()` |
| `.../CascadeMediaInviteListenerTest.java` | 补 4 例（源流消失/探活异常/空闲超时/宽限期） |

**变更点**

1. `MediaSession` 增加 `volatile long lastActiveAt`（`startPush`、`onInfo` 时刷新）。
2. 新增 `@Scheduled(fixedDelayString = "${gateway.cascade.media.active-scan-interval-ms:30000}")` 的 `reapStaleActiveSessions()`，对每个 active 会话判活，命中任一条件即 `stopSendRtp` + 移除 + 释放端口：
   - **条件 A（权威）**：源流已不在 ZLM 上。复用 `LiveSessionGcService.streamAliveOnZlm` 的模式（`ZlmRestService.isMediaOnline(schema=rtsp, app=rtp, stream=<session.stream>)`）。查询异常时**保守判活**，本轮跳过，下一轮再对。
   - **条件 B（兜底）**：`now - lastActiveAt > active-idle-timeout-sec`（默认 `600`，对齐 CSS 的 600s）。
   - **宽限期**：会话建立不足 `30s` 跳过，避开首播窗口竞态。
3. 抽 `stopSession(callId, session)` 统一收尾：`stopSendRtp(ssrc)` + 从 `activeSessions` 移除 + 日志。`onBye` 也改调它，行为不变。
4. 端口占用判定不变（`isPortInUse` 同时查 pending/active），回收后自然释放。

**为什么用 `isMediaOnline` 而不是查 sendRtp 列表**：`LiveSessionGcService` 已用它做 ACTIVE 会话对账并经现场验证，接口语义稳定（返回 `online` 布尔）；`getMediaInfo` 无 `online` 字段不能判活。级联的源流落在同一个 `rtp` app 下，参数可直接复用。

**验收**

- 单测：`CascadeMediaInviteListenerTest` 补「ZLM 查无源流 → 调 stopSendRtp 并移除会话」「探活异常 → 保留会话」「空闲超时 → 回收」「宽限期内 → 不探活」四例，全部通过。
- 日志关键字：`级联会话收尾: callId=..., 原因=源流已消失|空闲超时 Ns|收到上级 BYE|容器关闭, ssrc=..., srcPort=...`（回收与 BYE 共用同一行日志，靠 `原因` 区分）
- 现场：正常点播 → 海康不发 BYE 直接断开 → 一个扫描周期后日志出回收，`activeSessions` 归零。

### 4.3 批次三：容器关闭全停（D3）— ✅ 已完成

1. `CascadeMediaInviteListener` 加 `@PreDestroy`：遍历 `pendingSessions + activeSessions`，逐个 `stopSendRtp` 后清空，日志汇总条数。
2. 与 `CascadeClientScheduler.destroy()` 的执行顺序无关（两者互不依赖），但必须在 Spring 关闭 ZLM HTTP 客户端之前完成——`@PreDestroy` 默认顺序满足。
3. 可与批次二合并提交，单独成节便于回滚。

### 4.4 批次四（暂不实施）：媒体链路对齐（D4 + D5）

> 本节只记录方案，**不在本次实施范围**。做完批次一二三后，级联"有目录、能起推流"，但对 type=4 RTSP 设备点播仍出不来画面。

| 改动点 | 现状 | 目标 |
|---|---|---|
| `ensureStreamOnline` | 给设备发 GB28181 SIP INVITE，把**上级地址透传给设备** | 调 `MediaPlayService.startLive(deviceId, channelId, profile)` 在**本地**建流（RTSP → `addStreamProxy`；GB28181 → `openRtpServer`），返回 streamId |
| `MediaSession.stream` | `localDeviceId + "_" + localChannelId` | 用 `startLive` 返回的 streamId（`rtsp_live_<deviceId>[_<channelId>][_sub]` / `gb_live_*`） |
| 推流目标 | 设备直推上级 + ZLM `startSendRtp` 双路径并存 | 统一为 ZLM `startSendRtp(dst=上级)`，等源流 ready 后再推 |
| 回放 | `invitePlayBack` 直连设备 | 走本地回放流，再由 ZLM 转发 |

估算 0.5~1 天 + 联调 0.5 天。验收标准：海康侧点播 `34020000001320000007`（映射到 `hk-nvr165-ch3` 的通道 7）能出画面，BYE 后 ZLM 侧流与发送器都被清理。

---

## 5. 新增配置项一览

| 配置键 | 默认值 | 说明 | 生效批次 | 落地方式 |
|---|---|---|---|---|
| `gateway.cascade.media.ssrc-fallback-enabled` | `true` | 上级未带 ssrc 时按国标规则生成 | 一 | `@Value` 默认值，可按需在 yml 显式覆盖 |
| `gateway.cascade.media.active-idle-timeout-sec` | `600` | 活跃会话空闲回收阈值（对齐 CSS 的 600s） | 二 | 同上 |
| `gateway.cascade.media.active-scan-interval-ms` | `30000` | 活跃会话扫描间隔 | 二 | 同上 |
| `gateway.cascade.media.probe-grace-sec` | `30` | 对账宽限期，避开首播竞态 | 二 | 同上 |

已有配置保持不变：`send-port-start/end`（30000/30999）、`ack-timeout-sec`（32）、`optimistic-push-delay-ms`（2000）、`ack-scan-interval-ms`（1000）。

---

## 6. 回归与验收

### 6.1 单测

```bash
mvn -o -pl voglander-web -am "-Dtest=Cascade*Test" test
```
基线为上一轮 134 用例全绿；本批新增 13 例（`CascadeSsrcGeneratorTest` 7、`CascadeMediaInviteListenerTest` 6），实测 **140 用例 0 失败、BUILD SUCCESS**。

### 6.2 端到端步骤

1. 重启后端（配置与代码均需重启生效），确认启动日志无 `CascadeQueryHandler` 相关告警。
2. 海康侧触发一次目录同步 → 本地日志出现 `响应目录查询: platformId=37000020002000000100, channelCount=1`。
3. 海康侧点播「通道7-测试」→ 本地日志依次出现：
   - `级联 INVITE 未携带 ssrc，已生成`（仅当上级确实不带 ssrc）
   - `级联 INVITE 已应答，等待 ACK 后推流`（含非 0 ssrc）
   - `级联推流已启动`
4. 海康侧停止点播 → `级联会话收尾: ..., 原因=收到上级 BYE`。
5. **异常演练**：点播后直接拔网线/杀海康进程不发 BYE → 一个扫描周期后出现 `级联会话收尾: ..., 原因=源流已消失`（或 `空闲超时 Ns`），`srcPort` 被释放。
6. **关闭演练**：点播中停后端 → 出现 `级联媒体监听已关闭: 停止推流 N 个, 丢弃待推流会话 M 个`；ZLM `getRtpInfo` 查不到残留发送器。

### 6.3 已知的后续阻塞

即使三个批次全部完成，`hk-nvr165-ch3`（type=4 RTSP）**仍然点不出画面**，原因见 §4.4（D4/D5）。目录能看到、状态显示 ON，是符合当前实现预期的。

---

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 生成的 ssrc 与上级约定不一致，反而更糟 | `ssrc-fallback-enabled` 可一键关闭回到"透传上级值"；生成规则严格对齐 CSS 与国标附录 |
| 判活误杀正常流 | 探活异常一律保守判活；30s 宽限期；只对 `activeSessions` 生效，不碰 pending |
| 回收逻辑与 BYE 并发导致重复 `stopSendRtp` | `stopSession` 用 `activeSessions.remove(callId)` 的返回值决定是否执行，天然幂等 |
| 重启后 ZLM 残留发送器 | 批次三的 `@PreDestroy` 覆盖；ZLM 侧 `sendRtp` 本身有 `/index/api/close_rtp_server` 可手工兜底 |

回滚方式：三项均为新增逻辑，配置开关可关闭；代码层面单独提交，按批次 revert 不影响其他批次。

---

## 8. 交付记录（2026-10-29）

### 8.1 改动清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `voglander-integration/.../cascade/CascadeSsrcGenerator.java` | 新增 | 106 行。`generate(domainSipId, localChannelId, playback)` → 10 位 ssrc；`midDigits` 取 `[4..9]` 且校验纯数字；`channelSeq` 取数字部分或 `CRC32 % 10000`；`AtomicInteger CURSOR` 做并发递增偏移 |
| `voglander-integration/.../cascade/CascadeMediaInviteListener.java` | 修改 | `resolveSsrc` + `ssrcFallbackEnabled`；`parseSsrc` 改 long；`reapStaleActiveSessions` / `isSourceStreamAlive` / `stopSession`；`MediaSession.lastActiveAt` + `touch()`；`onBye` 走 `stopSession`；`@PreDestroy destroy()` |
| `voglander-web/src/test/.../cascade/CascadeSsrcGeneratorTest.java` | 新增 | 7 例 |
| `voglander-web/src/test/.../cascade/CascadeMediaInviteListenerTest.java` | 修改 | 12 → 18 例（ssrc 3、回收 4、销毁 1） |
| `doc/1.0.11/CASCADE-MEDIA-ALIGNMENT-WITH-CSS.md` | 新增/修改 | 本文 |
| `doc/1.0.11/OPTIMIZATION-AND-TODO-2026-10.md` | 修改 | §一.9 交付条目、P0-3/4/5 与 P1-6 置 ✅、日志关键字表、回归用例数 134 → 140 |

### 8.2 关键实现约定

1. **ssrc 中段取 `cascadeChannelId` 而非 `localSipId`**：级联通道编码是 20 位国标编码、与平台域同源，取 `[4..9]` 等价于 CSS 对 `sipId[4:9]` 的语义；拿不到时补 `00000`，输出恒 10 位。
2. **`stopSession` 是唯一收尾入口**：BYE / 源流消失 / 空闲超时 / 容器关闭四条路径共用，靠 `ConcurrentHashMap.remove` 返回值保证幂等，不会重复调 ZLM。
3. **判活失败一律保留**：`isSourceStreamAlive` 在 `zlmConfig == null`、`node == null`、`status == null`、`online == null`、抛异常五种情况全部返回 `true`，宁可漏收一轮也不误杀。
4. **配置项全部用 `@Value` 默认值承载**，不改 `application-*.yml`，避免污染环境文件；需要覆盖时显式加键即可。

### 8.3 验证证据

```text
mvn -o -pl voglander-integration -am -Dgpg.skip=true -Dmaven.test.skip=true compile
  → BUILD SUCCESS

mvn -o -pl voglander-web -am -Dgpg.skip=true -Dtest=Cascade*Test test
  → Tests run: 140, Failures: 0, Errors: 0, Skipped: 0
  → CascadeSsrcGeneratorTest        7 例
  → CascadeMediaInviteListenerTest  18 例
  → BUILD SUCCESS
```

### 8.4 尚未覆盖（需真机确认）

| 项 | 状态 |
|---|---|
| 海康侧实际点播出画面 | ❌ 仍不可用，属 D4/D5（§4.4），本批未动媒体编排 |
| 200 OK SDP 的 `y=` 与 ZLM `startSendRtp` 实际 ssrc 一致 | 未做报文级抓包，仅单测覆盖 |
| 拔网线异常演练 / 停后端关闭演练 | 未执行，待在真实级联环境按 §6.2 走一遍 |

