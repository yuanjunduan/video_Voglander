# 级联注册全流程对比：Voglander vs CSS

> 用途：本平台（Voglander）作为 GB/T 28181 **下级**向海康 iSecure Center 等**上级**平台注册时，
> 对照已成功对接的 CSS（`D:\work\视频平台\CSS\Trunk`，Go 实现）逐阶段比对其代码逻辑，
> 用于定位「注册不上 / 注册上了但上级看不到通道 / 点播黑屏 / 掉线不恢复」这类问题。
>
> 代码快照：2026-10-28。Voglander 为 `voglander-integration` 的 `cascade` 包 + `voglander-web` 的 cascade 接口；
> CSS 为 `service/gb28181` 包。凡标注「未核实」的结论，是本次未做运行时验证的推断，不要当既定事实。

---

## 0. 一分钟结论：两边最本质的差别

| 维度 | Voglander（Spring Boot） | CSS（Go） | 排障含义 |
| --- | --- | --- | --- |
| 平台配置来源 | 数据库表 `tb_cascade_platform`，Spring 启动时读一次并建定时任务 | CMS HTTP 接口轮询（`QueryUpperDomains`），新增/修改/删除实时生效 | Voglander 改库或改配置后**必须重建任务**（前端 update/enable 或重启），否则跑的还是旧快照 |
| 注册执行者 | 每平台一个 `ScheduledFuture`，固定周期发一次 REGISTER | 每平台一个 goroutine 的 **同步循环** `registerUntilSuccess`，失败按固定间隔一直重试到成功 | 排「为什么不重试」时看 Voglander 的退避策略（30/60/120/300s）是否已耗尽 |
| 401 重认证 | 框架默认处理器被本项目摘掉，改由 `CascadeRegisterAuthProcessor` 用**平台自身身份**重认证 | `p.createAuthString()` 用 `PlatformInfo.Username/Password` 手拼 Digest | 两边最终都是「摘要 username 来自平台配置，不是全局设备编码」 |
| 注册后的续期 | 按 `expires * 2/3` 重发 REGISTER（再走一次 401 挑战） | 按 `expires / 2` 用**缓存的 auth 串**直接重发（不再挑战） | Voglander 每轮续期都依赖 401 处理器正常工作 |
| 心跳 | 独立 Keepalive MESSAGE，**不校验上级是否回包** | Keepalive + **心跳超时判定**（interval×count），超时即置离线并重新注册 | Voglander 缺「心跳超时自愈」，上级静默丢包时状态会长期停在 ONLINE 直到下次续期失败 |
| 目录数据源 | 实时查 `tb_cascade_channel`（**当前 0 条 → 上级看不到任何通道**） | 注册前从 CMS 拉全量组织和设备到内存，Catalog 查询直接回内存 | 「注册成功但没有通道」优先查 Voglander 的 `tb_cascade_channel` |
| 目录分片 | 一次返回 sumNum + 全部条目，无分片逻辑 | 按 `CatalogItemNum` 分片批量发送，连续失败 3 次中止 | 上级通道多时需关注单包大小 |
| 订阅 | 收到 SUBSCRIBE 登记 `tb_cascade_subscribe`，后续按订阅类型主动 NOTIFY | 收到 SUBSCRIBE **只回 200**，不登记；增量变更由 CMS 驱动主动 NOTIFY | 两边推送触发方式不同，Voglander 依赖订阅表非空 |
| 点播推流时机 | 收到 INVITE **立即**调用 ZLM `startSendRtp` | INVITE 只做 MPS 协商并回 200，**收到 ACK 才 StartPush** | 上级不回 ACK 时 Voglander 会推无效流，CSS 不会 |
| 注销 | **没有** Expires=0 注销逻辑，停用只取消任务 | `unRegister()` 发 Expires=0（并走鉴权），成功才置离线 | 变更 IP/端口后上级侧可能残留旧 Contact |

---

## 1. 术语与角色

| 名称 | 含义 | Voglander 字段 | CSS 字段 |
| --- | --- | --- | --- |
| 本端客户端 ID | 本平台在上级侧登记的国标编码（下级域编码） | `tb_cascade_platform.local_client_id` | `LocalDomain.LocalSipSn` |
| 本端 IP / 端口 | 本端 SIP 通告地址、监听端口（须等于客户端监听点） | `local_ip` / `local_port`（默认 5061） | `LocalDomain.LocalIp` / `LocalSipPort` |
| 上级平台编码 | 上级域的国标编码 | `platform_id` | `PlatformInfo.PlatformSn` |
| 上级 SIP 地址 | 上级「SIP 通信端口」（海康不是 7099 子节点端口） | `platform_ip` / `platform_port` | `PlatformInfo.Ip` / `Port` |
| 鉴权用户名 | 摘要 `username=`，上级「鉴权登录名」 | `username`（**2026-10-28 起真正参与摘要**） | `PlatformInfo.Username` |
| 鉴权密码 | 摘要密码 | `password` | `PlatformInfo.Password` |
| 注册状态 | 0 离线 / 1 在线 / 2 注册中 / 3 失败 | `register_status` + `extend.lastError` | `onlineStatus`（0/1/2），离线原因 `offlineErr` |

---

## 2. 阶段 A：进程启动 → SIP 栈就绪 → 平台配置装载

### 2.1 Voglander

关键类：`ServerStart`、`CascadeRegisterProcessorInstaller`、`CascadeClientScheduler`、`CascadePlatformController`。

```
Spring 容器启动
 ├─ Bean 创建：CascadeRegisterAuthProcessor（继承框架 ClientRegisterResponseProcessor）
 │             CascadeClientScheduler / CascadeClientRegisterListener / CascadeQueryHandler ...
 ├─ CommandLineRunner：ServerStart.run()
 │     sipLayer.addListeningPoint(listenIp, 5060, sipListener)   ← 服务端监听点（接入设备）
 │     sipLayer.addListeningPoint(listenIp, 5061, sipListener)   ← 客户端监听点（级联）
 │     注：绑定地址取 listenIp（默认 0.0.0.0），对外通告仍是 local_ip
 ├─ ApplicationReadyEvent（顺序由 @Order 决定）
 │     ① CascadeRegisterProcessorInstaller（HIGHEST_PRECEDENCE）
 │          摘掉 SIP 栈 RESPONSE_PROCESSOR_MAP["REGISTER"] 里的框架默认处理器，
 │          只保留 CascadeRegisterAuthProcessor
 │     ② CascadeClientScheduler.onApplicationReady()
 │          refreshRegistrations() → 查 enabled=1 的平台 → 每个平台
 │            startPlatform() = scheduleRegister(initialDelay=0) + scheduleKeepalive(initialDelay=interval)
 └─ 首轮 REGISTER 发出
```

必须记住的三点（历史上踩过的坑）：

1. `ServerStart` 是 `CommandLineRunner`，`CascadeClientScheduler` 用 `ApplicationReadyEvent` 而不是
   `@PostConstruct`，就是为了保证 REGISTER 发出时监听点已绑定、`ClientCommandSender` 已注入。
2. 处理器接管必须先于首轮 REGISTER（`Ordered.HIGHEST_PRECEDENCE`），否则框架默认处理器仍会抢发一条错误请求。
3. 调度任务闭包捕获的是**启动时的 DTO 快照**。改库不重建任务 = 配置不生效。运行期唯一可靠生效方式是
   前端触发一次 update/enable（内部 `stopPlatform → startPlatform(最新DTO)`）或重启后端。

接口入口（`voglander-web`，前缀 `/api/v1/cascade`）：

| 方法 | 路径 | 对注册的影响 |
| --- | --- | --- |
| POST | `/platform`（add） | 回查后 `startPlatform` |
| PUT | `/platform/{id}` | `stopPlatform` → `startPlatform` 重建任务 |
| POST | `/platform/{id}/enable` / `disable` | 启停任务；disable 只取消任务并置 OFFLINE，**不发注销** |
| DELETE | `/platform/{id}` | 先 stop 再删（否则留幽灵任务） |
| POST | `/platform/refresh` | `refreshRegistrations()`，只补未调度的平台 |
| POST | `/channel/batchBind` | 维护 `tb_cascade_channel`（决定上级能否看到通道） |

### 2.2 CSS

关键类：`platformManager`、`sipProtocol`、`GbPlatform`。

```
main → PlatformManager().Init()
 ├─ 轮询 CMS 取 LocalDomain（LocalSipSn/LocalIp/LocalSipPort 任一为空则 sleep 重试）
 ├─ newProtocol().init(tag, LocalSipSn, LocalIp, LocalSipPort)   ← msip 绑定 SIP 监听
 ├─ task.TaskManager().Init(LocalSipSn)
 ├─ go periodicGetLocalDomain()      本域编码/IP/端口变了 → 整个 SIP 栈 reset 重建
 └─ go periodicGetPlatformInfo(tag) 每 NormalDelayTime 秒拉一次上级平台列表
       ├─ CMS QueryUpperDomains（失败回退 QueryEnergyUpperDomains）
       └─ updatePlatformInfo：新平台 add() → go p.init()；变更 update()；CMS 已删的 remove()
```

`GbPlatform.init()`：

```go
sdm.init(tag, p)          // 目录数据协程
p.registerUntilSuccess(tag)   // 同步阻塞：注册成功才返回
go p.monitor(tag)             // 续期 + 心跳 + 状态上报 + 心跳超时
go p.periodicUpdateSessions() // 媒体会话保活
go p.checkNetworkStatus(tag)  // ping 上级 IP
```

### 2.3 阶段 A 对比

| 检查点 | Voglander | CSS | 建议 |
| --- | --- | --- | --- |
| 配置生效方式 | 需重建调度任务 | 每轮轮询 CMS 自动 add/update | 只改库不改任务时，看 `register_status` 是否从 0→2 判断新配置是否真的用上 |
| 监听口 | server 5060 / client 5061，`local_port` 必须 = 5061 | 单进程单口（LocalSipPort） | Voglander 一个客户端栈只监听一个口，不能给不同平台配不同本端端口 |
| 本域变更 | 无自动重建，需重启 | `reset()` 自动重建 | 换网段后 Voglander 记得改 `local_ip` 并重建任务 |

---

## 3. 阶段 B：向上级发 REGISTER 并完成鉴权

### 3.1 Voglander：注册这一次请求做了什么

`CascadeClientScheduler.doRegister()`：

```java
cascadePlatformManager.updateRegisterStatus(id, REGISTERING);              // 2
String callId = ClientCommandSender.sendRegisterCommand(from, to, expires); // 框架发首包（无 Authorization）
scheduleRegisterTimeoutCheck(platform, callId);                             // 30s 后仍是 REGISTERING → FAILED
log: 发起级联注册 / 级联 REGISTER 已发送（含 callId、耗时）
```

首包字段（`CascadeDeviceSupplier` + 框架 builder）：

| 头/行 | 取值 | 说明 |
| --- | --- | --- |
| Request-URI | `sip:{platform_id}@{platform_ip}:{platform_port}` | 摘要 uri 必须与之完全一致 |
| From | `sip:{local_client_id}@{local_ip}:{local_port}`，带 tag | 本端身份 |
| To | `sip:{platform_id}@{platform_ip}:{platform_port}`，**不带 tag** | 海康要求 To 无 tag（CSS 注释同样踩过） |
| Contact | `sip:{local_client_id}@{local_ip}:{local_port}` | 上级后续 INVITE/MESSAGE 回打这个地址 |
| Expires | `register_expires`（默认 3600） | |
| Call-ID | 首次由框架生成 | 重认证必须沿用 |
| CSeq | 框架自增 | 重认证 CSeq+1 |

### 3.2 响应分支（Voglander）

由 `CascadeRegisterAuthProcessor.process()` 分派：

| 响应 | 处理 | 结果 |
| --- | --- | --- |
| 200 OK | 框架发布 `ClientRegisterSuccessEvent` | `CascadeClientRegisterListener` → `register_status=1`、清 `extend.lastError`、SSE `cascade.register` |
| 401 | 取响应 To 头 user（= platform_id）定位平台 → 用**平台身份**重发（见 3.3） | 日志 `级联 401 重认证已发送` |
| 403 | 显式发布 `ClientRegisterFailureEvent`（框架默认不上报） | `register_status=3` + `extend.lastError` 带排查提示 |
| 400 / 其他 | 交给父类，框架无失败事件 → 只能等 30s 超时 | `REGISTER 响应超时(30s)` |
| 无响应 | 30s 超时回调 | 同上；**基本可断定端口/网络错**（打到正确信令口必有状态码） |

失败后的自愈：`markRegisterFailure()` 写 `extend.lastError/lastErrorAt`，并按
30s → 60s → 120s → 300s 追加一次性重试；连续 4 次后回落到常规续期周期（`expires*2/3`，最短 60s）。

### 3.3 Voglander：401 重认证的摘要计算（本项目自研部分）

```java
FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform); // From/Contact = local_client_id
ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
Request request = SipRequestBuilderFactory.createRegisterRequest(from, to, expires, callId); // 沿用 Call-ID
String auth = buildAuthorization(request, challenge, platform);
request.setHeader(SipRequestUtils.createHeader("Authorization", auth));
request.setHeader(SipRequestUtils.createCSeqHeader(originalCSeq + 1, Request.REGISTER)); // 必须递增
SipSender.transmitRequest(from.getIp(), request);
```

摘要（RFC 2617，无 qop 分支为海康实际形态）：

```
username = platform.username（为空回落 local_client_id）   ← 与 From 头 user 可不同
uri      = request.getRequestURI().toString()              ← 与请求行严格一致
HA1      = MD5(username:realm:password)
HA2      = MD5("REGISTER":uri)
response = MD5(HA1:nonce:HA2)                 // 无 qop
response = MD5(HA1:nonce:nc:cnonce:auth:HA2)  // 有 qop=auth 时
```

被替换掉的框架默认行为（为什么必须自己实现）：框架
`ClientRegisterResponseProcessor.processReAuthentication` 固定用
`deviceSupplier.getClientFromDevice()`（**全局客户端 deviceId + 客户端密码**）重认证，且
`RegisterRequestBuilder.buildRegisterRequestWithAuth` 用 `to.getUserId()/to.getPassword()` 算 HA1、
却把 `from.getUserId()` 写进 Authorization 的 username —— 三者不一致时上级必然拒绝，
而框架又不对 403 发失败事件，所以现象只剩「30s 超时」。

### 3.4 CSS：register 做了什么

```go
resp := sipSrv.SendRegisterRequest(localUri, remoteUri, "", expire)   // 首包无 Authorization
if isAuth && resp.StatusCode() != 401 { return 鉴权失败 }
if !isAuth && resp.StatusCode() != 200 { return 鉴权失败 }
if isAuth {
    auth := p.createAuthString(resp, username, password)              // username = PlatformInfo.Username
    resp  = sipSrv.SendRegisterRequest(localUri, remoteUri, auth, expire)
    if resp.StatusCode() != 200 { return ErrTaskSipRegisterAuthFail }
    p.auth = auth                                                     // 缓存，续期直接复用
}
p.heartBeatTime, p.registeredTime = now, now
p.setOnlineStatus(On, ErrSuccess, false)                              // 下一轮 monitor 上报 CMS
```

```go
func (p *GbPlatform) createAuthString(resp, username, password) string {
    realm, nonce := resp.GetWWWAuthenticate()
    sipUri := fmt.Sprintf("sip:%s@%s:%d", p.PlatformSn, p.Ip, p.Port)  // 与 Request-URI 一致
    ha1 := utils.HA1("MD5", username, realm, password, nonce, "")
    ha2 := utils.HA2("MD5", "", "REGISTER", sipUri, "")
    response := utils.GetResponse("MD5", "", nonce, "", "", ha1, ha2)  // 无 qop
    return fmt.Sprintf(`Digest username="%s", realm="%s", nonce="%s", uri="%s", response="%s", algorithm=MD5`,
        username, realm, nonce, sipUri, response)
}
```

注册外层循环 `registerUntilSuccess`（与 Voglander 最大的结构差异）：

```go
p.clearAllBeforeRegister()   // 清增量缓存 + 停掉遗留媒体推流
p.sdm.activeGetTotalData()   // ★ 注册前先把目录全量从 CMS 拉到内存
for {
    statusCode, cost, err := p.register(tag)
    if err == nil { break }
    // 失败 → setOnlineStatus(Off, AuthFail/RespondTimeout, sync=true) → 上报 CMS
    time.Sleep(RegisterInterval)
}
```

### 3.5 阶段 B 字段级对照

| 项 | Voglander | CSS | 差异影响 |
| --- | --- | --- | --- |
| REGISTER 首包 To | 上级平台 URI | **本端 URI**（`FHost/FUser` 取 from，无 tag） | 海康都能接受；若遇严格平台，注意此处写法不同 |
| Authorization uri | 与请求行一致（代码取 Request-URI） | 手拼 `sip:PlatformSn@Ip:Port` | 二者都必须与请求行一致，手拼时易写错端口 |
| 摘要 username 来源 | `tb_cascade_platform.username` | `PlatformInfo.Username` | 必须等于上级「鉴权登录名」 |
| qop | 兼容有 qop 与无 qop | 只实现无 qop | 上级启用 qop 时 CSS 不适用、Voglander 可 |
| Call-ID / CSeq | 沿用首包 Call-ID、CSeq+1 | 每平台固定 `registerCallID`、CSeq 全局自增 | 都被上级视为同一注册请求的刷新 |
| 401 后的失败可见性 | 403/400 有显式日志与状态（403 直接上报） | 记录 statusCode 与错误码上报 CMS | 排障时 Voglander 优先看 `extend.lastError` |
| 失败重试节奏 | 退避 30/60/120/300s，之后按续期周期 | 固定 `RegisterInterval` 无限重试 | Voglander 长期失败后会「安静」下来，容易误判 |

### 3.6 排障断言（海康类上级）

1. 收到 **400 + `0x02201037`**：上级不认这个下级来源（未登记下级域 / IP 或端口不符），**还没进入鉴权**。
2. 收到 **401 + `Digest realm=..., algorithm=MD5`（无 qop）**：已进入鉴权，比对平台表 `username/password`。
3. 收到 **401 → 403**：鉴权失败。只换 username 的实测矩阵（同密码）为：
   `username=鉴权登录名 → 200`；`username=本端编码 → 403`；`username=客户端 deviceId → 403`。
4. **完全无响应 / 30s 超时**：报文没到信令口。上级「子节点端口」（如 7099）是内部通信用，静默丢弃；
   要填「SIP 通信端口」（如 7100）。

---

## 4. 阶段 C：注册成功的那一瞬间，各自做了什么

| 动作 | Voglander | CSS |
| --- | --- | --- |
| 状态落库/内存 | `register_status = 1(ONLINE)` | `onlineStatus = 1`，同时置 `onlineStatusChanged` |
| 失败信息清理 | `markRegisterSuccess()` → 清 `extend.lastError`、清退避计数 | `offlineErr = ErrSuccess` |
| 推送/上报 | SSE `cascade.register` 通知前端列表刷新 | 无（下一轮 monitor 里 `notifyPlatformStatusToCms` 上报 CMS） |
| 目录数据 | **不做**任何同步，等上级来查 | 已经在此前的 `activeGetTotalData()` 里全量准备好（内存） |
| 媒体会话 | 不处理 | `clearAllBeforeRegister()` 已停掉上次遗留的推流并清空会话 |
| 任务起点 | 注册任务按周期继续；心跳任务独立计时 | `monitor` / `periodicUpdateSessions` / `checkNetworkStatus` 三个协程从此持续运行 |

结论差异：**CSS 是"注册前把数据准备好"，Voglander 是"查询时实时查库"**。
所以「注册成功但上级看不到通道」在 Voglander 侧几乎必然是 `tb_cascade_channel` 为空或 `enabled=0`；
在 CSS 侧则要查 CMS 返回的 orgs/devs 是否为空。

---

## 5. 阶段 D：稳态保活（续期 + 心跳 + 自愈）

### 5.1 Voglander

| 机制 | 实现 | 关键参数 |
| --- | --- | --- |
| 注册续期 | `scheduleRegister`：`scheduleWithFixedDelay(period)`，每轮调 `doRegister`（**再走一次 401 挑战**） | `period = max(60, register_expires * 2/3)` |
| 保活心跳 | `scheduleKeepalive`：`doKeepalive` 仅当 `register_status == ONLINE` 才发 Keepalive MESSAGE | `keepalive_interval`，默认 60s；首个心跳在启动后一个 interval |
| 心跳内容 | `ClientCommandSender.sendKeepaliveCommand(from, to, local_client_id)`，deviceId = 本端客户端 ID | |
| 心跳结果处理 | **不校验上级应答**，只有发送异常才告警（debug 级日志） | —— |
| 掉线判定 | 只有 REGISTER 失败或 30s 超时才转 FAILED/OFFLINE | 无「心跳超时」概念 |
| 网络探测 | 无 | —— |
| 会话清理 | 内存表 `activeSessions`（callId→ssrc/设备），仅在 BYE/异常时移除；**重启即丢** | 上级异常断连后 ZLM 侧推流不会被主动停 |

风险提示：上级只丢 MESSAGE 但接受 REGISTER 时，Voglander 会一直显示在线；
上级完全静默时，最长要等一个续期周期（默认 40 分钟）才会发现掉线。

### 5.2 CSS

单个 `monitor` goroutine 每 `LessDelayTime` 秒做四件事（顺序固定）：

```go
p.refreshRegister(transType, expire/2, now, registeredTime, localUri, remoteUri, auth)
p.sendKeepalive(tag, heartBeatInterval, now)
p.notifyPlatformStatusToCms(tag, online, statusChanged, desc, networkStatus)
p.heartBeatTimeout(tag, heartBeatTime, heartBeatInterval, heartBeatCount, now)
```

| 机制 | 实现要点 |
| --- | --- |
| 注册续期 | `now - registeredTime >= expire/2` 时用**缓存的 auth 串**直接重发，不再经历 401；失败只 warn，`registeredTime` 仍推进 |
| 心跳 | 间隔 `heartBeatInterval`；`KeepaliveNotify{DeviceID: LocalSipSn}`；**发送成功即刷新 `heartBeatTime`** |
| 心跳超时 | `heartBeatTime + interval*count <= now` → 置离线（`ErrPlatformHeartbeatTimeout`）+ `go registerUntilSuccess()` 重新注册 |
| 参数自适应 | 上级下发 ConfigDownload（BasicParam）时更新 `expire` / `heartBeatInterval` / `heartBeatCount` |
| 网络探测 | `utils.NetWorkStatus(p.Ip)`（ping），网络状态变化也触发上报 CMS |
| 会话保活 | `periodicUpdateSessions()`：向 MPS 查询会话状态，结束的移除、存活的刷新 `updateTime`，超时会话清理并停止推流 |

### 5.3 阶段 D 对比要点

| 关注点 | Voglander | CSS | 影响 |
| --- | --- | --- | --- |
| 续期是否重新挑战 | 是（依赖 401 处理器） | 否（缓存 auth） | Voglander 的 401 处理器一旦失效，续期会 30s 后失败 |
| 心跳超时自愈 | 无 | 有（interval×count） | 建议 Voglander 后续补齐（见第 9 节） |
| 上级改心跳参数 | 忽略（ConfigDownload 回 OK 不解析 BasicParam） | 采纳 | 上级若下发较小 interval，Voglander 仍按 60s 发，可能被判离线 |
| 会话残留 | 重启丢失内存会话 | 定期与 MPS 对账 | Voglander 重启后旧 ssrc 推流需人工或 ZLM 侧清理 |

---

## 6. 阶段 E：目录暴露（上级 Catalog 查询）

### 6.1 Voglander

入口：框架把上级 MESSAGE（CmdType=Catalog）转给 `QueryListener`，本项目实现为 `CascadeQueryHandler`。

```java
List<CascadeChannelDTO> channels = cascadeChannelManager.listByPlatformId(platformId);
DeviceResponse resp = new DeviceResponse("Catalog", query.getSn(), query.getDeviceId());
resp.setSumNum(channels.size());
resp.setDeviceItemList(channels.stream()
    .filter(c -> c.getEnabled() != null && c.getEnabled() == 1)
    .map(this::toDeviceItem).toList());
```

要点：

- 数据源为 `tb_cascade_channel`（platform_id + local_channel_id ↔ cascade_channel_id 映射）。
- 条目 `deviceId = cascade_channel_id`（上级认识的编码），`name` 取 `cascade_name` 或回落 `local_channel_id`。
- `sumNum` 用**映射总数**，而 `items` 只含 `enabled=1`，两者可能不一致 —— 若配了禁用通道，上级可能认为目录缺项。
- `status` 恒为 `"ON"`，不反映真实通道在线状态。
- 本项目**没有分片发送逻辑**：一次性给 sumNum + 全量 items（框架是否再分包未核实）。
- 另有 `onDeviceInfoQuery` / `onDeviceStatusQuery` 返回固定信息（Manufacturer=Voglander，Online=ONLINE）。

### 6.2 CSS

```go
func (p *GbPlatform) catalogResponse(tag int, header *manscdp.Header) {
    orgs := p.sdm.cloneOrgs()          // 内存：注册前全量拉取
    devs := p.sdm.cloneDevs()
    sumNum := len(orgs) + len(devs) + 1
    p.domainCatalogResponse(...)       // ★ 先单独发一条"域信息"（DeviceID = LocalSipSn）
    items := p.convertCatalogItems(orgs, devs)
    // 按 CatalogItemNum 分片循环发送，连续失败 3 次中止；剩余尾包单独发
}
```

要点：

- 先发 200，再 `go catalogResponse`（异步，不阻塞信令线程）。
- 第一条固定是「域条目」（本端编码，Name=CSS），`sumNum` 已把它计入。
- 分片大小由 `CatalogItemNum` 决定（≤0 时按 1 处理）。
- 数据源是内存快照，**与上级是否订阅无关**；变更通过增量 NOTIFY 同步（见阶段 F）。

### 6.3 阶段 E 对比

| 项 | Voglander | CSS |
| --- | --- | --- |
| 数据来源 | 实时查库 `tb_cascade_channel` | 内存全量（CMS → 注册前拉取） |
| 是否含域条目 | 无，只有通道 | 有，且计入 sumNum |
| 分片 | 无 | 有（CatalogItemNum） |
| 在线状态 | 恒 ON | 取设备实际 `DeviceStatus` |
| 空目录表现 | sumNum=0、items=0 → 上级显示"无设备" | 至少一条域条目 |

---

## 7. 阶段 F：订阅与主动推送（上级要知道变化）

### 7.1 Voglander

```
上级 SUBSCRIBE(Catalog/Alarm/MobilePosition)
  → CascadeSubscribeHandler（框架 SubscribeListener）
      expires<=0 → cascadeSubscribeManager.expire(platformId, type)   // 退订
      否则       → upsertActive(platformId, type, sn, expires, interval) // 落 tb_cascade_subscribe
本地事件（Gb28181ProtocolHandler 落库后发 Spring 事件）
  → CascadeEventBridge（@Async("sipNotifierExecutor")）
      AlarmCreatedEvent / LocalChannelChangeEvent / LocalMobilePositionEvent
  → CascadeNotifyPublisher
      listActiveByType(SubType) → 逐平台上查 cascade_channel 映射 → ClientCommandSender.sendXxxNotify
```

要点：

- 推送对象来自**订阅表**；没登记订阅（或已过期被 `CascadeSubscribeCleanScheduler` 清理）就不会推送。
- deviceId 必须重映射成 `cascade_channel_id`；映射不存在则跳过该上级（告警/位置），目录变更同理。
- 通知里的 `sn` 复用订阅时的 sn。

### 7.2 CSS

```go
func (p *GbPlatform) subscribeHandler(srv, ir) { srv.SendResponseOnRequest(ir, 200, "") } // 只回 200，不登记
```

推送由 CMS 驱动，经消息队列串行处理（`syncDataManager.processSyncData`）：

| msgType | 处理 |
| --- | --- |
| `TotalDataMsgType(1)` | 清增量缓存 → `passiveGetTotalData()` 重拉全量 |
| `OrgDeltaMsgType(2)` | 更新全量缓存 → `CatalogNotify` 推送 → 失败进增量缓存 |
| `DevDeltaMsgType(3)` | 同上（ADD/UPDATE/DELETE/ONLINE/OFFLINE） |
| `ClearDeltaDataMsgType(4)` | 清增量缓存 |

增量可靠性：发送失败的条目留在 `orgDelta/deviceDelta`，`DeltaCacheTimeout=600s` 过期、
上限 `MaxDeltaCache=30000`（超限整体清空），之后的轮次会重发。

### 7.3 阶段 F 对比

| 项 | Voglander | CSS |
| --- | --- | --- |
| 是否登记订阅 | 是（`tb_cascade_subscribe`） | 否（只回 200） |
| 推送触发 | 本地数据变更事件 | CMS 增量回调 |
| 无订阅时 | 不推送 | 照样推送 |
| 失败重试 | 无（一次不成就丢） | 增量缓存 600s 内重发 |
| 编码映射 | 查 `tb_cascade_channel`，缺映射即跳过 | 直接用 CMS 给的设备编码 |

---

## 8. 阶段 G：点播（上级 INVITE）

### 8.1 Voglander（`CascadeMediaInviteListener`）

```
上级 INVITE（SDP 里带 cascade_channel_id、ssrc、收流 ip/port）
 1. parseCascadeChannelId / parsePlatformId → 查 tb_cascade_channel
      查不到 → 直接 return（当作非级联流量忽略，不回应答）
 2. ensureStreamOnline：向真实 IPC 拉流（实时 inviteRealTimePlay / 回放 invitePlayBack）
 3. ZLM startSendRtp(app="rtp", stream=localDeviceId_localChannelId, ssrc, 上级收流地址)  ← 立即推流
 4. 回 200 OK（sendonly SDP，推流地址 = ZLM 节点本地 IP + startSendRtp 返回端口）
 5. 记录 activeSessions[callId] = {ssrc, localDeviceId, userId, playback}
BYE   → stopSendRtp(ssrc) 并移除会话
INFO  → 按 To 头 userId 反查活跃回放会话 → 透传真实设备（PAUSE/PLAY/SCALE/RANGE）
```

注意：**没有等 ACK 就先推流**。上级不发 ACK 或 ACK 丢失时，ZLM 侧已经在向一个未建立的会话推包。

### 8.2 CSS（`gb28181_invite.go`）

```
上级 INVITE
 1. parseOfferSdp(subject, body) → deviceSn / ssrc / 上级收流 ip,port / transType / 起止时间
 2. getPullAddrFromCms：Play→CMS GetLiveAddr；Playback→CMS GetVodAddrByTime
 3. mps.GetOptimalMps(deviceSn) 选最优媒体节点
 4. mps.PrePush(拉流地址 + 推流目标) → 返回 PushLocalIP/PushLocalPort/SessionId（协商，未推流）
 5. 回 200 OK（answer SDP 用 PushLocalIP/Port）
 6. addDialog(callId → sessionId, mpsAddr, 起止时间)
ACK   → mps.StartPush(sessionId)   ← ★ 真正开始推流
BYE   → mps.StopPush(sessionId) + removeDialog
periodicUpdateSessions：向 MPS 查询会话状态，结束/超时的回收并停推
```

### 8.3 阶段 G 对比

| 项 | Voglander | CSS | 影响 |
| --- | --- | --- | --- |
| 推流时机 | INVITE 立即推 | ACK 后推 | 上级不回 ACK 时 Voglander 产生无效推流 |
| 拉流地址 | 本平台向 IPC 直接发 INVITE | 由 CMS 提供（可能是已缓存流） | CSS 更省时，Voglander 依赖 IPC 响应速度 |
| 媒体节点 | 固定 ZLM 默认节点 | 按设备选最优 MPS | 多节点部署时 Voglander 单点 |
| 会话回收 | 仅 BYE/异常；重启即失忆 | 周期性对账 + 超时清理 | 上级崩溃后 Voglander 推流可能残留 |
| 回放控制 | INFO 透传到 IPC | （另见 ptz/record 模块） | —— |

---

## 9. 阶段 H：注销与销毁

| 动作 | Voglander | CSS |
| --- | --- | --- |
| 停用平台 | `stopPlatform`：取消注册+心跳任务，`register_status=0`，**不发 Expires: 0** | `fini()`：`unRegister()` 发 Expires=0（含 401→鉴权重发），成功才置离线并上报 CMS |
| 删除平台 | 先 stop 再 delete | CMS 列表中消失 → `remove()` → `fini()` |
| 本域变更 | 无自动处理 | `reset()`：先销平台（含注销）→ sleep → `sp.fini()` → 重建 SIP |
| 进程退出 | 无注销（`@PreDestroy` 只关线程池） | 未见全局退出注销，但平台级 fini 会注销 |

残留风险：Voglander 换 `local_ip/local_port` 后，上级侧仍可能记着旧 Contact，
导致上级 INVITE/查询打到旧地址。**变更加解绑 IP/端口时，建议先在上级删除该下级域再重加**，
或后续补一个 `Expires: 0` 注销能力。

---

## 10. 日志速查表（Voglander 侧，按时间顺序）

| 日志关键字 | 出现位置 | 含义 / 下一步 |
| --- | --- | --- |
| `级联注册响应处理器已接管: 摘除默认处理器 N 个` | `CascadeRegisterProcessorInstaller` | 正常。**缺失说明自定义鉴权没生效** |
| `级联调度刷新完成: 启用平台=1, 注册任务=1, 保活任务=1` | `CascadeClientScheduler` | 配置已装载 |
| `发起级联注册: localClientId=... -> sip:...@ip:port` | `doRegister` | 核对 localClientId / 目标端口 |
| `级联 REGISTER 已发送: ... callId=... cost=..ms` | `doRegister` | 报文已出栈；cost 大增说明网络慢 |
| `级联 401 重认证已发送: 鉴权用户名=..., realm=..., cseq=...` | `CascadeRegisterAuthProcessor` | 进入鉴权。核对「鉴权用户名」是否等于上级登录名 |
| `级联注册成功: eventUserId=..., platformId=...` | `CascadeClientRegisterListener` | register_status=1 |
| `级联注册被拒绝(403): 鉴权用户名=...` | `CascadeRegisterAuthProcessor` | 用户名/密码不匹配（不是端口问题） |
| `级联注册响应超时: ... timeout=30s` | `scheduleRegisterTimeoutCheck` | 报文大概率没到信令口（端口错/来源未登记） |
| `级联注册失败: ... statusCode=400` | `CascadeClientRegisterListener` | `0x02201037` = 上级未登记该下级 |
| `级联注册失败，Ns 后第 n 次快速重试` | `scheduleRetry` | 看 `extend.lastError` 定位根因，别只看重试 |
| `响应目录查询: platformId=..., channelCount=0` | `CascadeQueryHandler` | 上级查目录但返回 0 条 → `tb_cascade_channel` 没配 |
| `级联 INVITE过滤: ... 不是级联通道` | `CascadeMediaInviteListener` | 点播失败常见原因：通道映射缺失 |
| `ZLM startSendRtp 失败` | `CascadeMediaInviteListener` | ZLM 不可达 / 流不存在 |
| `级联保活已发送`（debug） | `doKeepalive` | 需要开 debug 才可见，心跳故障时先确认它在发 |

---

## 11. 排障检查表（按现象）

### 11.1 注册不上

| 现象 | 优先排查 | 判断依据 |
| --- | --- | --- |
| `REGISTER 响应超时(30s)`，无任何 SIP 回包 | ① `platform_port` 是否为上级「SIP 通信端口」（海康 7100 而不是 7099）；② `local_ip` 是否为本机到上级的真实源 IP；③ 防火墙/UDP | 打到正确信令口一定会有状态码 |
| 400 + `0x02201037` | 上级「下级域」是否登记、外域编码是否 = `local_client_id`、IP/端口是否一致 | 此时还未进入鉴权，改密码无用 |
| 401 后 403 | 平台表 `username` 是否等于上级「鉴权登录名」、`password` 是否一致 | 只换 username 的矩阵已验证 |
| 日志里没有「处理器已接管」 | classpath 是否是旧产物（IDEA 未 Build / 未 install） | 说明仍在用框架默认重认证 |
| 状态一直停在 2（REGISTERING） | 30s 超时任务是否执行；线程池是否被占满 | 正常会在 30s 内转 3 |
| 改了配置没变化 | 调度任务仍是旧 DTO 快照 | 触发前端 update/enable 或重启；看 `register_status` 是否走过 2 |

### 11.2 注册成功但上级看不到通道

| 检查 | 位置 | 期望 |
| --- | --- | --- |
| `tb_cascade_channel` 是否有该平台记录 | 库 | ≥1 条且 `enabled=1` |
| 上级是否真的发了 Catalog 查询 | 日志 `响应目录查询` | 有该日志说明查询到达 |
| `channelCount` 与 `sumNum` 是否一致 | 日志 | 不一致说明有禁用通道被过滤 |
| 海康侧「开启订阅」开关 | 上级控制台 | 关闭时上级不会主动订阅，目录需手动刷新 |
| 通道编码是否符合上级规范 | 库 | `cascade_channel_id` 建议按上级域编码规则生成 |

### 11.3 在线但点播黑屏

| 检查 | 说明 |
| --- | --- |
| INVITE 是否被识别为级联通道 | 日志 `不是级联通道` 说明 `cascade_channel_id` 对不上 |
| ZLM 是否可达、`startSendRtp` 是否返回 code=0 | 失败会回 4xx/5xx 给上级 |
| 上级「检测流来源」是否开启 | 开启时 RTP 必须从登记的本端 IP 发出，多网卡机器要确认出网源 IP |
| 上级是否回了 ACK | Voglander 不等 ACK 就推流，若上级未建会话会表现为"推了但看不到" |
| 是否 `stopSendRtp` 残留 | 上次 BYE 丢失时 ssrc 复用会冲突 |

### 11.4 在线一段时间后掉线 / 不恢复

| 检查 | 说明 |
| --- | --- |
| 心跳是否在发（debug 日志） | 仅有 ONLINE 才发 |
| 上级是否改过心跳间隔（ConfigDownload BasicParam） | Voglander 不解析，仍按 60s |
| 下次续期是否成功 | 最长要等 `expires*2/3`（默认 40 分钟）才发现异常 |
| 是否有心跳超时判定 | Voglander 无，CSS 有；上级只丢 MESSAGE 时 Voglander 会长期显示在线 |

---

## 12. 已知差异带来的风险与改进建议（按优先级）

| 优先级 | 差异 | 风险 | 建议 |
| --- | --- | --- | --- |
| P0 | 目录数据源是 `tb_cascade_channel`，当前 **0 条** | 上级永远看不到通道 | 先补通道映射（`/cascade/channel/batchBind`），再让上级重查目录 |
| P0 | 无心跳超时判定 | 上级静默丢包时状态假在线，不自动恢复 | 参照 CSS：`heartBeatTime + interval*count` 超时即置离线并重注册 |
| P1 | 无 `Expires: 0` 注销 | 变更 IP/端口后上级残留旧 Contact | 停用/删除平台时补发注销 |
| P1 | 点播不等 ACK 就推流 | 无效推流占用端口/ssrc | 改为 ACK 后 `startSendRtp`，或 ACK 超时再停 |
| P1 | 推送失败无重试 | 上级瞬时不可达即丢一次目录/告警变更 | 参照 CSS 增量缓存（超时+上限） |
| P2 | ConfigDownload 不解析 BasicParam | 上级下发的心跳/有效期参数不生效 | 解析后更新任务周期 |
| P2 | Catalog 不分片、sumNum 与 items 不一致 | 通道多时单包过大；禁用通道计数偏差 | 分片发送，sumNum 用实际下发条数 |
| P2 | 通道状态恒 ON | 上级显示全部在线，误导运维 | 关联 `tb_device_channel` 实际状态 |

---

## 13. 附录：关键代码位置索引

### Voglander（Java）

| 文件 | 职责 |
| --- | --- |
| `voglander-integration/.../gb28181/start/ServerStart.java` | 绑定 5060/5061 监听点 |
| `.../gb28181/cascade/CascadeRegisterProcessorInstaller.java` | 摘除框架默认 REGISTER 处理器 |
| `.../gb28181/cascade/CascadeRegisterAuthProcessor.java` | 401 重认证（平台身份 + 独立摘要 username）、403 上报 |
| `.../gb28181/cascade/CascadeClientScheduler.java` | 注册续期、心跳、超时检查、退避重试、lastError |
| `.../gb28181/cascade/CascadeClientRegisterListener.java` | 成功/失败事件 → 状态落库 + SSE |
| `.../gb28181/cascade/CascadeDeviceSupplier.java` | From/To 构造（本端编码 vs 平台编码） |
| `.../gb28181/cascade/CascadeQueryHandler.java` | Catalog / DeviceInfo / DeviceStatus / RecordInfo / Config / Preset 应答 |
| `.../gb28181/cascade/CascadeSubscribeHandler.java` | 上级订阅登记/退订 |
| `.../gb28181/cascade/CascadeEventBridge.java` | 本地事件 → 级联推送桥 |
| `.../gb28181/cascade/CascadeNotifyPublisher.java` | 目录/告警/位置 NOTIFY（含编码重映射） |
| `.../gb28181/cascade/CascadeMediaInviteListener.java` | INVITE/BYE/INFO 媒体转发 |
| `.../gb28181/cascade/CascadeRecordService.java` | 录像查询转发与回包 |
| `.../gb28181/cascade/CascadeRecordRequestScheduler.java` | 录像查询超时清理 |
| `.../gb28181/cascade/CascadeSubscribeCleanScheduler.java` | 订阅过期清理 |
| `voglander-web/.../api/cascade/CascadePlatformController.java` | 平台增删改、启停、刷新（调度重建入口） |
| `voglander-web/.../api/cascade/CascadeChannelController.java` | 通道映射维护（上级可见性来源） |

### CSS（Go）

| 文件 | 职责 |
| --- | --- |
| `service/gb28181/gb28181_platform_manager.go` | 平台列表轮询、add/update/remove、SIP 栈 init/reset |
| `service/gb28181/gb28181_platform.go` | 平台状态机、monitor（续期/心跳/上报/超时）、网络探测 |
| `service/gb28181/gb28181_register.go` | register / createAuthString / unRegister / refreshRegister / registerUntilSuccess |
| `service/gb28181/gb28181_message.go` | 上级 MESSAGE 分发（Info/Status/Catalog/Record/Ctrl/Config/Preset）、subscribeHandler |
| `service/gb28181/gb28181_syncdata.go` | 全量+增量目录缓存、CatalogNotify 推送与重试 |
| `service/gb28181/gb28181_invite.go` | INVITE/ACK/BYE 媒体协商、会话保活与清理 |
| `service/gb28181/msip/request_builder.go` | REGISTER 报文构造（含「对接海康 To 不能带 tag」注释） |
| `service/gb28181/msip/msip_server.go` | SendRegisterRequest（auth 追加、响应等待超时） |
| `service/gb28181/utils/digest_algorithm.go` | HA1/HA2/response 计算 |

---

## 14. 一句话记住的分工

- **Voglander**：`tb_cascade_platform` 决定"能不能注册"（编码/IP/端口/用户名/密码），
  `tb_cascade_channel` 决定"上级能不能看到和点播"，`CascadeClientScheduler` 决定"掉线能不能自己回来"。
- **CSS**：`CMS` 决定数据（本域信息、平台列表、目录全量/增量、拉流地址），
  进程内只负责 SIP 协商与状态机，因此它的自愈与推送都比 Voglander 更"厚"，但强依赖 CMS 可用。

---

## 15. 整改实施记录（2026-10-28 夜）

第 12 节列出的 7 项代码差异已全部落地，第 8 项（通道映射数据）属配置动作。下表是"改了什么 / 怎么验证 / 出问题看哪条日志"。

| # | 原差异 | 改动 | 新增/修改的文件 | 关键日志 |
| --- | --- | --- | --- | --- |
| 1 | 无心跳超时判定 | 在 MESSAGE / REGISTER 响应里登记"上级最近一次响应时间"；心跳周期内顺带判定，超过 `keepaliveInterval × keepaliveTimeoutCount` 即置 OFFLINE 并立即重注册。**保护**：从未见过该平台 MESSAGE 响应时只告警不判离线（避免误伤不回 200 的上级） | 新增 `CascadePeerAliveTracker`、`CascadeMessageResponseProcessor`；改 `CascadeClientScheduler` | `级联心跳超时，判定上级失联并自愈重注册`；`级联心跳始终无应答（按策略不判离线）` |
| 2 | 无 `Expires: 0` 注销 | 停用/删除平台时先发 `REGISTER + Expires: 0`（首包无鉴权，401 后由 `CascadeRegisterAuthProcessor` 按注销标记把 Expires 置 0 重认证）；注销成功的 200 置 OFFLINE 而非 ONLINE；update 场景只取消任务不注销 | 新增 `CascadeUnregisterService`；改 `CascadeClientScheduler.stopPlatform(id, notifyPeer)`、`CascadeRegisterAuthProcessor`、`CascadeClientRegisterListener` | `级联注销已发送` / `级联注销已完成(Expires=0)` / `级联注销被拒绝(403)，按本地停用处理` |
| 3 | 点播不等 ACK 就推流 | INVITE 只分配本端发送端口并回 200（SDP 端口 = `src_port`），收到 `ClientAckEvent` 才 `startSendRtp`；等待 ACK 的会话超时自动回收；BYE 同时清理待推流会话。**注意**：框架的 `ClientAckEvent` 实际不可达（见 15.4），故补了 ACK 请求处理器 + 2s 兜底推流 | 改 `CascadeMediaInviteListener`；新增 `CascadeAckRequestProcessor`、`CascadeRegisterProcessorInstaller#installAckProcessor` | `级联 INVITE 已应答，等待 ACK 后推流: ... srcPort=` / `级联推流已启动` / `级联 INVITE 在 Nms 内未观测到 ACK，启用兜底推流` / `级联 INVITE 未收到 ACK，放弃推流` |
| 4 | Catalog 不分片、sumNum 不一致 | `sumNum` 改为**实际下发条数**（只含 `enabled=1`）；超过 `batch-size` 时用框架 `sendCatalogCommandBatched` 分片下发并返回 null 阻止框架再整包发一次 | 改 `CascadeQueryHandler` | `响应目录查询: ... channelCount=, batchSize=` / `目录分片下发完成: 总数=, 批次大小=` |
| 5 | 通道状态恒 ON | 目录应答前一次 `in(device_id)` 批量取 `tb_device_channel.status`，内存按 `deviceId#channelId` 查；查不到按 OFF | 改 `CascadeQueryHandler.toDeviceItem`；`DeviceChannelManager` 新增 `mapChannelStatus`/`channelKey` | 无（结果体现在上级列表状态） |
| 6 | 推送失败无重试 | 目录变更 NOTIFY 登记 callId + 重发动作，收到同 callId 的 MESSAGE 响应即出表；超时未确认重发**一次**后放弃。告警/位置属流式数据不重试 | 新增 `CascadeNotifyAckTracker`；改 `CascadeNotifyPublisher`、`CascadeMessageResponseProcessor` | `级联推送 Ns 未确认，重发一次` / `级联推送重发后仍无确认，放弃` / `级联推送未被上级接受(不再重发)` |
| 7 | ConfigDownload 不解析 BasicParam | 新增 `ConfigListener`：解析上级下发的 `HeartBeatInterval` / `Expiration`，越界（间隔 5–3600s、有效期 60–86400s）或非数字则不落库；落库后重建该平台任务使新周期立即生效 | 新增 `CascadeConfigListener` | `上级重设 BasicParam 已落库: ... 心跳间隔=Ns(原Ms), 有效期=...` |

### 15.1 新增可调配置

| 配置键 | 默认值 | 作用 |
| --- | --- | --- |
| `gateway.cascade.keepalive-timeout-count` | 3 | 连续 N 个心跳周期无响应判失联 |
| `gateway.cascade.media.send-port-start` / `-end` | 30000 / 30999 | 本端 RTP 发送端口池（写入 200 OK 的 SDP） |
| `gateway.cascade.media.ack-timeout-sec` | 32 | 等待 ACK 的最长时间，超时放弃推流 |
| `gateway.cascade.media.ack-scan-interval-ms` | 1000 | 待推流会话扫描间隔（同时决定兜底推流的实际触发粒度） |
| `gateway.cascade.media.optimistic-push-delay-ms` | 2000 | 兜底推流延迟：到点仍未观测到 ACK 就先推流避免黑屏；设 0 或负数则只认 ACK |
| `gateway.cascade.catalog.batch-size` | 50 | 单条目录 MESSAGE 的通道条数上限 |
| `gateway.cascade.notify.ack-timeout-sec` | 10 | 目录变更推送等确认的最长时间 |
| `gateway.cascade.notify.ack-scan-interval-ms` | 5000 | 推送确认扫描间隔 |

### 15.2 回归范围

`mvn -o -pl voglander-web -am "-Dtest=Cascade*Test,DeviceChannel104Test,DeviceChannelBatchUpsertTest" test` → **148 个用例全通过**；
补齐 ACK 请求处理器后复跑 `-Dtest=Cascade*Test,DeviceChannelManagerTest` → **132 个用例全通过**（含新增 `CascadeAckRequestProcessorTest` 3 个用例）。
既有测试因构造签名变化同步更新：`CascadeClientSchedulerTest`（两处）、`CascadeClientRegisterListenerTest`、`CascadeQueryHandlerTest`、`CascadeNotifyPublisherTest`、`CascadeMediaInviteListenerTest`。

### 15.3 仍需人工确认的事项

| 事项 | 说明 |
| --- | --- |
| 通道映射数据 | 线上库现状（只读巡检，未改动）：`tb_cascade_platform` 1 条且 `register_status=1`（在线），`tb_cascade_channel` **0 条**，`tb_device_channel` 12 条（Lab 4 条 + `hk-nvr165-ch3` 8 条）。因此上级此刻能连上但目录为空、无法点播。需通过 `/api/v1/cascade/channel/batchBind` 或前端「级联通道」页绑定要暴露的通道；**绑定哪些通道、用什么编码属业务决策，未自动写入** |
| 海康「开启订阅」 | 关闭时上级不主动订阅，目录变更通知不会下发 |
| ACK 后推流的实测 | 首个点播看日志：出现 `级联推流已启动` 且无 `启用兜底推流` → ACK 链路正常；只出现 `级联 INVITE 在 Nms 内未观测到 ACK，启用兜底推流` → ACK 仍未被 SIP 栈回调，画面由兜底推流接管（功能可用，但比严格语义早 2s 推流）。若连兜底链路也不出画面，把 `optimistic-push-delay-ms` 设为 0 做一次对照实验，可确认问题是否在"推流时机" |
| 心跳超时阈值 | 若上级对 Keepalive 不回 200，日志会周期性出现 `级联心跳始终无应答（按策略不判离线）`，此时不应把 `keepalive-timeout-count` 调小 |

### 15.4 实施中发现并修正的框架缺陷：`ClientAckEvent` 不可达

第 3 项按"等 ACK 再推流"改完后，我回头核对事件到底从哪来，结论是：**这条链路原本永远不会触发**。

- `ClientAckEvent` 全仓库唯一的发布点是框架的 `ClientAckResponseProcessor`，
  而它注册在**响应**分派表上（`RESPONSE_PROCESSOR_MAP`，按 CSeq 的 method 匹配）。
- ACK 在 SIP 里是**请求**，走 `REQUEST_PROCESSOR_MAP`，由 `AbstractSipListener#processRequest` 按
  `request.getMethod()` 分派。两者是两张表，永远碰不上。
- 框架自带的 `ClientAckRequestProcessor`（在请求表里）只调用 `SipSubscribe.publishAckEvent(RequestEvent)`，
  那是"点对点回调订阅表"（`okSubscribes`），**不发布 Spring 事件**，业务侧监听不到。

因此严格按"等 ACK"实现会让点播**彻底不推流**。修正分两步：

1. **补事件源**：新增 `CascadeAckRequestProcessor implements SipRequestProcessor`，在 `process()` 里取 `CallIdHeader` 后
   `publishEvent(new ClientAckEvent(this, callId))`；由 `CascadeRegisterProcessorInstaller#installAckProcessor`
   在 `ApplicationReadyEvent`（`HIGHEST_PRECEDENCE`）注册到请求表的 `"ACK"` 键，并按实例去重，避免同一 ACK 被处理两遍。
   注册成功日志：`级联 ACK 请求监听已注册: 当前 ACK 处理器 N 个`。
2. **留兜底**：`CascadeMediaInviteListener` 的推流入口抽成幂等的 `startPush(callId, session)`（`activeSessions` 已含即返回），
   ACK 事件与 `@Scheduled cleanExpiredPending()` 共用；扫描时若 pending 超过 `optimistic-push-delay-ms`（默认 2s）
   仍在等 ACK，就先推流并 `warn`，避免"某一版 SIP 栈不回调 ACK"这类环境差异导致上级永久黑屏。
   `activeSessions` 中已有该 callId 时 ACK 迟到也只是幂等空操作，BYE 仍按 `activeSessions` 正常停流。

> 结论：ACK 可见时严格按 ACK 推流；ACK 不可见时 2s 后兜底推流。两种情况下 BYE 都能停流，端口都能回收。


