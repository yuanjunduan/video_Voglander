# 级联目录不可见修复 & 全链路 traceId

> 适用版本：1.0.11
> 现象：本平台作为下级向上级（海康 iSecure Center 等）注册成功、心跳正常、页面已配置并启用通道映射，
> 但上级平台设备列表/目录恒空。
> 关联文档：[CASCADE-PLATFORM-FULL-FLOW.md](./CASCADE-PLATFORM-FULL-FLOW.md)、[CASCADE-REGISTER-FLOW-COMPARISON.md](./CASCADE-REGISTER-FLOW-COMPARISON.md)

---

## 一、结论先行

「注册成功但看不到通道」不是单点故障，而是**四条链路叠在一起**，任何一条断掉表现都一样。
本次修复按「从外到内」逐层拆开，最终以前端 `X-Trace-Id` → 后端 MDC 串起整条链路，便于以后一眼定位断在哪层。

| # | 断点 | 症状 | 断点位置 |
|---|------|------|----------|
| 1 | lab 协议验证台与真实级联互斥 | 注册/心跳正常，日志里没有「响应目录查询」 | `voglander.protocol-lab.enabled` |
| 2 | 框架只认「To == 本端 clientId」 | 上级 SUBSCRIBE/MESSAGE 被 warn 后静默丢弃，连 200 OK 都不回 | `ClientMessageRequestProcessor` / `SubscribeRequestProcessor` |
| 3 | 框架订阅 handler 内部二次校验身份 | 即使绕过第 2 层，仍 `return` 且不发事件 | `SubscribeCatalogQueryMessageHandler#handForEvt` |
| 4 | 订阅成功后**从不推目录 NOTIFY** | 200 OK 不带任何通道，上级没有目录基线 | 业务侧缺失能力 |
| 5 | 目录应答的 From 用了设备编码 | 应答发出去了，上级认不出来源、直接丢弃 | `ClientListenerAdapter#handleDeviceQuery` |

---

## 二、逐层根因与证据

### 2.1 第 1 层：lab 模式把级联 handler 顶掉了

`CascadeQueryHandler` / `CascadeSubscribeHandler` 的装配条件是：

```java
@ConditionalOnExpression(
    "${sip.client.enabled:false} and not ${voglander.protocol-lab.enabled:false}"
)
```

`voglander.protocol-lab.enabled=true` 时二者都不注册，`QueryListener` 由 `LabQueryListener` 独占。
而 `LabQueryListener#onCatalogQuery` 第一行就是身份过滤：

```java
if (!isLabDevice(query.getDeviceId())) {
    return null; // 上级查的是平台域 34020000002000000001，不是 Lab 设备编码
}
```

上级查目录时 `DeviceID` 是平台域，`isLabDevice` 判否 → `return null` → **连一个字节都不回**。
而 `CascadeClientScheduler` 不受该条件约束，所以注册与心跳照常成功，只有目录永远空。

**证据**：现象特征是「REGISTER 成功 + 心跳 200 + 日志无『响应目录查询』」。

**修复**：`application-prod.yml` 中 `voglander.protocol-lab.enabled` 固定为 `false`（文件内已加说明）。
做协议自环验证时改用 `lab` profile 临时开启，验证完关回去并重启。

> `application-dev.yml` 仍为 `true`，因为 dev 默认是协议验证台场景；连真实上级请用 `prod`。

### 2.2 第 2 层：框架的方向校验按「设备」而不是「平台」判身份

框架 client 侧处理器开头统一做：

```java
if (!toUserId.equals(clientDeviceSupplier.getClientFromDevice().getUserId())) {
    log.warn("MESSAGE请求的目标用户ID与客户端不匹配: from={}, to={}", ...);
    return;   // 不回 200 OK，也不进消息链
}
```

本平台作为**下级**被上级查询时，`To` 是平台域 `34020000002000000001`（`tb_cascade_platform.local_client_id`），
而 `getClientFromDevice().getUserId()` 是 `sip.client.clientId`（`34020000001320000011`，设备编码），
两者天然不等 → 请求被丢弃。

**证据**（`voglander-info.log`）：

```
WARN ClientMessageRequestProcessor - MESSAGE请求的目标用户ID与客户端不匹配:
    from=37000020002000000100, to=34020000002000000001, clientFromDevice=FromDevice(...)
WARN SubscribeRequestProcessor  - SUBSCRIBE请求的目标用户ID与客户端不匹配:
    from=37000020002000000100, to=34020000002000000001, ...
```

**修复**：新增两个只按「from 是否为已知上级平台」判向的处理器，并在启动时**整体接管**该 method 的分派：

- `CascadeMessageRequestProcessor`（MESSAGE）
- `CascadeSubscribeRequestProcessor`（SUBSCRIBE）

接管逻辑在 `CascadeRegisterProcessorInstaller#takeoverRequestProcessor`：先摘掉框架默认处理器（避免它先跑一遍打噪音 warn），
再把接管者登记进去。注意 `AbstractSipListener#getRequestProcessors(method)` 返回的是**内部列表本体**，
遍历中删除必须先拷快照。

两个实现细节容易踩：

1. **必须在本类重新声明 `private String method`**。`SipProxyAutoConfig` 用 `getDeclaredField("method")` 反射取分派键，
   不查父类，子类不声明会抛 `NoSuchFieldException` 并打 error。
2. **转交消息链时 `FromDevice` 不能传 null**。`SipRequestProcessorAbstract#processMessageRequest` 里有
   `Optional.of(fromDevice).map(Device::getCharset).orElse(UTF_8)`，`Optional.of(null)` 直接 NPE。
   本类的 `buildUpperFromDevice` 按平台配置构造一个只用于取 charset 与打日志的非 null 对象。

### 2.3 第 3 层：SUBSCRIBE 在框架 handler 内部还有第二道身份校验

即使绕过第 2 层进到消息链，`SubscribeCatalogQueryMessageHandler#handForEvt` 里还有：

```java
DeviceSession session = getDeviceSession(event);
Device fromDevice = session.getFromDevice();
if (fromDevice == null || !userId.equals(fromDevice.getUserId())) {
    return;   // 既不回 200，也不发 ClientSubscribeEvent
}
```

`fromDevice` 取自 `getClientFromDevice()`，依旧是设备编码，与 `To` 不等 → 必然 return。

**所以 SUBSCRIBE 不能复用框架 handler**，必须在 `CascadeSubscribeRequestProcessor` 里自实现：
先回 200 OK（带 Expires）消掉上级重传，再把订阅意图交给 `CascadeSubscribeHandler` 落库。

**证据**：同 Call-ID（`1356180246z16205_de88ca41c325`）、同 CSeq（747388）、同 branch 的 SUBSCRIBE
在 10:16:56.334 / 10:16:57.735 / 10:16:59.737 出现三次 = 上级在重传，说明我们一直没应答。

> MESSAGE 侧不需要这层改造：`MessageClientHandlerAbstract#getDeviceSession` 只要求
> `getClientFromDevice()` 非 null 且 `getDevice(sipId)` 非 null，而 `VoglanderClientDeviceSupplier#getDevice`
> 有 `buildCascadePlatformDevice` 兜底，能从 `tb_cascade_platform` 解析出上级平台。

### 2.4 第 4 层：订阅成功后从不推目录 NOTIFY（本次最关键的缺失）

GB28181 §9.11 的订阅流程是：

```
上级 SUBSCRIBE(Event: Catalog)  →  下级 200 OK  →  下级 NOTIFY(完整目录)  →  之后变更再推增量
```

**200 OK 本身不带任何通道**。上级只认紧随其后的第一条 Catalog NOTIFY 来建立目录基线；错过这一包，
上级手上就永远是空目录。

而原实现里 `CascadeSubscribeHandler#onCatalogSubscribe` 只做了 `upsertActive` 落库，
`CascadeNotifyPublisher` 也只有 `pushCatalogChange`（增量 Event 通知），**没有任何「完整目录快照」能力**。
框架的 `ClientCommandSender` 同理：只有 `sendCatalogCommand`（生成 `<Response>`）与
`sendCatalogChangeNotify`（生成带 `<Event>` 的 `<Notify>`），没有完整目录 NOTIFY。

**证据**：`voglander-sip.log` 里对上级发出的 MESSAGE 只有 Keepalive 的 `<Notify>`，
从来没有 `<CmdType>Catalog</CmdType>` 的 `<Notify>`。

**修复**：`CascadeNotifyPublisher#pushCatalogSnapshot(platformId, sn)`，直接拼 MANSCDP XML 走
`SipSender.doMessageRequest(from, to, content)`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>11436</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>2</SumNum>
<DeviceList Num="2">
<Item>
<DeviceID>34020000001310000001</DeviceID>
<Name>前门</Name>
<Manufacturer>Voglander</Manufacturer>
<Model>CascadeChannel</Model>
<Owner>34020000002000000001</Owner>
<Parental>0</Parental>
<ParentID>34020000002000000001</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<Status>ON</Status>
</Item>
...
</DeviceList>
</Notify>
```

要点：

- `SumNum` 是**总数**，`DeviceList/@Num` 是**本片条数**；通道多时按 `gateway.cascade.catalog.batch-size`（默认 50）分片。
- **一个通道都没有也要发一包 `SumNum=0`**，否则上级会一直等首包、永不认为目录已就绪。
- 通道名必须做 XML 转义，否则 `&` / `<` 会让整包变成非法 XML 被上级直接丢弃。
- `DeviceID` 一律用 `cascade_channel_id`（上级认识的编码），不是本地 `local_channel_id`。
- 未启用（`enabled != 1`）的映射必须剔除，否则 `SumNum` 会永远凑不满。
- 编码用 UTF-8 与框架现有级联消息（保活）保持一致，上级实测可解析。

推送时机有三处，互为兜底：

| 时机 | 位置 | 作用 |
|------|------|------|
| 收到 Catalog 订阅（`Expires > 0`） | `CascadeSubscribeRequestProcessor#pushCatalogSnapshot` | 规范主路径 |
| 注册成功（含每次续约） | `CascadeClientRegisterListener#pushCatalogSnapshotQuietly` | 应用重启后上级订阅仍在有效期、不会重发 SUBSCRIBE，靠这包恢复可见性 |
| 通道增删改/上下线 | `CascadeNotifyPublisher#pushCatalogChange` | 增量 Event 通知（原有能力） |

退订（`Expires <= 0`）与注销（`Expires=0` 的 REGISTER）都不推。

### 2.5 第 5 层：目录应答的 From 身份不一致

`CascadeQueryHandler` 原本只在通道数 > `catalogBatchSize` 时自己下发（返回 `null` 阻止框架再发），
≤ 50 时把 `DeviceResponse` 交回框架。两条路径的身份不一样：

| 通道数 | 谁发 | From |
|--------|------|------|
| ≤ 50 | `ClientListenerAdapter#handleDeviceQuery` | `getClientFromDevice()` = `sip.client.clientId`（设备编码） |
| > 50 | `CascadeQueryHandler#sendCatalogBatched` | `cascadeDeviceSupplier.buildFromDevice(platform)`（`local_client_id`，平台编码） |

上级认的是平台编码（它自己发的 `To`），设备编码当 From 的应答会被丢弃。

**修复**：`onCatalogQuery` 不再把 `resp` 交回框架，统一由 `sendCatalogBatched` 以平台身份下发。
唯一例外是平台 `localIp` 未回填时回退给框架——自行下发会在 Via/Contact 里带空地址，一包都发不出去，
此时宁可身份不对也要先发出去。

---

## 三、改动文件清单

| 文件 | 改动 |
|------|------|
| `voglander-integration/.../cascade/CascadeMessageRequestProcessor.java` | 新增/重写：按 from 判向，转交消息链（非 null FromDevice） |
| `voglander-integration/.../cascade/CascadeSubscribeRequestProcessor.java` | 新增：自实现 200 OK + 订阅登记 + 目录快照首推 |
| `voglander-integration/.../cascade/CascadeRegisterProcessorInstaller.java` | 新增 `takeoverRequestProcessor`，启动时摘掉框架默认 MESSAGE/SUBSCRIBE 处理器 |
| `voglander-integration/.../cascade/CascadeNotifyPublisher.java` | 新增 `pushCatalogSnapshot` + 完整目录 NOTIFY 拼装 + XML 转义 |
| `voglander-integration/.../cascade/CascadeClientRegisterListener.java` | 注册成功后补推目录快照 |
| `voglander-integration/.../cascade/CascadeQueryHandler.java` | 目录应答统一以平台身份下发 |
| `voglander-web/.../filter/TraceFilter.java` | 读写 `X-Trace-Id`、写 MDC、清理、耗时日志 |
| `voglander-web/.../filter/FilterConfig.java` | traceFilter 顺序提到 `HIGHEST_PRECEDENCE + 10` |
| `voglander-web/src/main/resources/log/logback-spring.xml` | pattern 增加 `[traceId=%X{traceId:-}]` |
| `voglander-web/src/main/resources/application-prod.yml` | `protocol-lab.enabled=false` 并写明原因 |
| 前端 `apps/web-antd/src/api/request.ts` | 请求拦截器生成并携带 `X-Trace-Id` |

---

## 四、全链路 traceId

### 4.1 链路

```
页面操作
  └─ 请求拦截器 createTraceId() 生成 16 位 id，写入请求头 X-Trace-Id
       └─ 后端 TraceFilter
            ├─ 白名单校验 [A-Za-z0-9_-]{8,64}（非法则改用服务端生成值，防日志注入）
            ├─ TraceUtils.setTraceId → ThreadLocal + MDC(key=traceId)
            ├─ 响应头回显 X-Trace-Id（前端可把失败请求的 id 直接拿去查日志）
            └─ finally：打一条 HTTP 方法/URI/状态/耗时，再 clearTraceId()
                 └─ logback pattern 输出 [traceId=xxxxxxxx]
```

日志实际落盘位置：`${user.home}/logs/voglander/`（`voglander-info.log` / `-error.log` / `-sip.log`）。

### 4.2 用法

1. 页面操作后看浏览器 **Network → 该请求的 Response Headers**，或请求 Headers 里的 `X-Trace-Id`。
2. 拿这个 id 查后端日志：

```powershell
# 全部业务日志（含 SSE、调度、级联推送）
Select-String -Path "$env:USERPROFILE\logs\voglander\voglander-info.log" -Pattern "traceId=1a2b3c4d5e6f7a8b"

# SIP 报文（含收发原文）
Select-String -Path "$env:USERPROFILE\logs\voglander\voglander-sip.log" -Pattern "traceId=1a2b3c4d5e6f7a8b"
```

3. 注意：`%tid` 是 SkyWalking 的 TraceId，无 agent 时恒为 `TID: N/A`；**按 `traceId=` 查才有效**。

### 4.3 边界

- 只覆盖 **HTTP 入口**。SIP 线程（`async-sip-*`、`SIP-PROXY-*`、`cascade-register`）是独立线程池，
  没有 HTTP 上下文；这些日志要靠 `platformId` / `callId` / `SN` 关联。
- 前端重试或透传场景：调用方已带 `X-Trace-Id` 时沿用，不再重新生成，保证同一次用户操作内外一致。

---

## 五、验收清单

### 5.1 单测

```powershell
$env:JAVA_HOME="C:\soft\jdk21"   # JDK>=23 会让 Lombok 注解处理失效，编译报大量「找不到符号 log」
mvn -o -pl voglander-web -am "-Dgpg.skip=true" test "-Dtest=Cascade*Test" `
    "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false"
```

基线：`154` 例 0 失败。

### 5.2 端到端

| 检查项 | 期望 |
|--------|------|
| 启动日志 | `级联 MESSAGE 请求处理器已接管: 摘除框架默认处理器 1 个`、`级联 SUBSCRIBE 请求处理器已接管: ...` |
| 启动日志 | 不再出现 `MESSAGE请求的目标用户ID与客户端不匹配` / `SUBSCRIBE请求的目标用户ID与客户端不匹配` |
| 注册成功日志 | `级联注册成功 ...` 紧跟着 `目录快照推送完成: platformId=..., 通道数=N, 分包=1` |
| SIP 报文 | `voglander-sip.log` 出现 `<CmdType>Catalog</CmdType>` 的 `<Notify>`，From 为平台编码 |
| 上级订阅 | 收到 SUBSCRIBE 后有 `收到上级平台 SUBSCRIBE: from=..., event=Catalog` 与 `200 OK` |
| 上级平台 | 设备树出现页面配置的通道，数量与 `enabled=1` 的映射数一致 |
| 前端 | 响应头带 `X-Trace-Id`，用该值能在 info.log 里查到同一次请求的全部日志 |

### 5.3 常见误判

- **上级仍看不到**：先确认上级手上是否还有旧订阅。上级不会因为我们重启就重发 SUBSCRIBE，
  但它会在下一次续约/刷新时进来；也可在上级侧对该级联域点「刷新」强制重订阅。
- **通道数为 0**：`tb_cascade_channel` 里 `enabled` 必须为 1，且 `platform_id` 与上级平台编码一致。
- **通道名乱码**：检查通道名的实际编码来源；当前报文用 UTF-8 声明，与保活报文一致。
