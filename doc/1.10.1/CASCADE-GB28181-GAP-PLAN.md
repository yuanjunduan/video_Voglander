# 级联（下级平台）GB/T 28181-2016 功能缺口与实施方案

> 范围：本平台作为**下级平台**、向上级（海康 iSecure Center 等）级联的全部信令与媒体能力。
> 基线：GB/T 28181-2016 §9.2 实时点播 / §9.3 录像回放 / §9.5 设备查询 / §9.8 历史视频回放 / §9.12 语音广播，
> 附录 A（MANSCDP 报文）、附录 B（MANSRTSP）、附录 M（媒体流保活）、附录 N（目录订阅/通知）。
> 代码位置：`voglander-integration/.../wrapper/gb28181/cascade/`。

## 0. 上一轮结论更正

| 早前判断 | 实测结论 |
| --- | --- |
| 9.8 历史视频回放未实现 | **已实现**：`CascadeMediaInviteListener` 已按 `s=Playback/Download` 走 `invitePlayBack`，回 200 OK 带时间窗 SDP，INFO 控制透传 `controlPlayBack` |
| 附录 L（TCP 媒体传输）未实现 | **已实现**：`startSendRtp` 依 `sdp.transport` 选择 `udp=true/false`，TCP 主动/被动均可推流 |

以上两项不再列为缺口。

## 1. 已实现能力（本次不动）

- 注册 / 注销 / 心跳（`CascadeClientScheduler`、`CascadeClientRegisterListener`、`CascadeRegisterAuthProcessor`）
- 目录查询应答（分片）+ 目录变更 NOTIFY 推送（`CascadeQueryHandler`、`CascadeCatalogAssembler`、`CascadeNotifyPublisher`）
- 订阅登记与清理（`CascadeSubscribeHandler`、`CascadeSubscribeCleanScheduler`）
- 录像查询转查（`CascadeRecordService` + `tb_cascade_record_request` 关联表）
- 云台/看守位/布防撤防等控制转发（`CascadeControlHandler`）
- 告警、移动位置推送（`CascadeEventBridge` + `CascadeNotifyPublisher`）
- 实时点播 / 回放点播 / MANSRTSP 回放控制 / BYE 停推 / 僵尸会话回收 / `@PreDestroy` 全停

## 2. 缺口清单

| # | 缺口 | 国标依据 | 现状 | 影响 |
| --- | --- | --- | --- | --- |
| G1 | 语音广播**应答**缺失 | §9.12 + A.2.6 e) | 框架 `NotifyListener.onBroadcastNotify` 返回 `void`，只发 Spring 事件，**无法回 XML** | 上级发广播通知后收不到 `<Response><CmdType>Broadcast</CmdType>`，判定广播失败 |
| G2 | `s=Talk`（语音对讲）被误当实时点播 | §9.12 | `isPlayback()` 只区分 PLAY_BACK/DOWNLOAD，Talk 走 `inviteRealTimePlay` 分支 | 上级发起对讲时本端去拉视频流，语义错乱并占用端口/ZLM 资源 |
| G3 | DeviceInfo/DeviceStatus/ConfigDownload/PresetQuery 应答**身份错误** | §9.5.3 | 交给框架 `ClientListenerAdapter`，其 From 取 `sip.client.clientId`（设备编码），非 `tb_cascade_platform.local_client_id` | 与目录曾出现的「应答有回包但上级不认」同源，字段级查询全部失效 |
| G4 | DeviceInfo 硬编码、缺 Channel；DeviceStatus 缺 DeviceTime/Encode/Record | §9.5.3.2/.3 + A.2.6 f/g | 固定 `Voglander/CascadePlatform/v1.0.4`，`Channel` 未填；状态仅 `Online=ONLINE` | 上级资产台账信息不完整；部分实现对缺必选字段的应答直接丢弃 |
| G5 | ConfigDownload 不返回 BasicParam | §9.5.3.4 + A.2.6 j | 只回 `Result=OK` | 上级拿不到设备名称/注册有效期/心跳参数，级联参数校验提示不一致 |
| G6 | 媒体流异常结束时**不向上级发 BYE** | 附录 M | 僵尸回收/源流消失只 `stopSendRtp` | 上级侧会话悬挂至自身超时，期间点播按钮不可用 |
| G7 | `s=Download`（录像下载）会话仍响应 MANSRTSP INFO | 附录 B | `onInfo` 只判 `playback` | 下载会话收到无意义的回放控制，且会刷新空闲计时 |
| G8 | 目录分片**不串行**、后续分片复用入站 Call-ID | 附录 N | 一次性 burst 发完全部分片，全部分片共用一个 Call-ID | 大目录 UDP 突发丢包/乱序；同 Call-ID 多 MESSAGE 被部分上级判为重复请求 |
| G9 | 预置位查询恒返回空列表 | §9.5.3.5 | `onPresetQuery` 固定 `PresetList(0, empty)` | 上级云台预置位面板永远空白（真实设备预置位早已缓存在 `device.extend.presets`） |
| G10 | 未识别的 MESSAGE 不回 200 OK | RFC3261 | `doMessageHandForEvt` 找不到处理器直接 `return` | 上级反复重传直至超时，日志噪音，事务悬挂 |

## 3. 实施方案（按依赖顺序）

### S1 自建 MANSCDP 报文渲染（`CascadeManscdpXml`）
- 抽出 `escape` / `append` / 根元素骨架，供 Broadcast/DeviceInfo/DeviceStatus/ConfigDownload/PresetQuery 复用。
- 报文外观与 `CascadeCatalogAssembler` 保持一致（`\n` 换行、每行一个前导空格、`encoding="GB2312"` 声明、空值省略）。
- 验收：纯函数单测，逐字段断言 + 转义断言。

### S2 平台身份 MESSAG 发送器（`CascadeUpperMessenger`）
- 统一「以 `tb_cascade_platform` 身份发 MESSAGE」：From=`CascadeDeviceSupplier.buildFromDevice`，To=`buildToDevice`。
- 支持 `callId` 复用（应答沿用入站 Call-ID）与新建（主动通知），支持 okEvent 回调（供 S6 串行分片）。
- 平台不存在 / `localIp` 未回填 → 返回 false 且不抛异常。
- 验收：mock 静态 `SipSender` 断言 From userId = `local_client_id`、to = `platform_id`。

### S3 语音广播应答（G1 + G10）
- `CascadeMessageRequestProcessor` 在 `doMessageHandForEvt` **之后**判断 `rootType=Notify && CmdType=Broadcast`，
  交给 `CascadeBroadcastHandler` 按 §9.12 回 `<Response><CmdType>Broadcast</CmdType><SN/><DeviceID/><Result/></Response>`
  （DeviceID 取 TargetID；TargetID 不是本级 `local_client_id` 也不是已登记通道时回 `ERROR`）。
- 同时：`MESSAGE_HANDLER_CMD_MAP` 查不到处理器时自行回 200 OK + WARN，避免上级重传。
- 验收：广播通知 → 200 OK 已被框架回出 + 一条 `Response Broadcast`；非法 TargetID → `Result=ERROR`。

### S4 查询应答改为自发送并补字段（G3/G4/G5/G9）
- `onDeviceInfoQuery`：DeviceName/Result/Manufacturer/Model/Firmware/**Channel**（Channel=该平台启用通道数）。
- `onDeviceStatusQuery`：Result/Online/Status/Encode/Record/**DeviceTime**（`yyyy-MM-ddTHH:mm:ss`）。
- `onConfigDownloadQuery`：`BasicParam{Name, Expiration=registerExpires, HeartBeatInterval=keepaliveInterval, HeartBeatCount}`。
- `onPresetQuery`：读 `deviceManager.getDtoByDeviceId(localDeviceId).extendInfo.presets`（`handlePreset` 已回填
  `PresetQueryResponse` JSON），重映射 SN + DeviceID=级联通道编码；缓存缺失时 fire-and-forget
  `serverDeviceCommand.queryDevicePreset(localDeviceId)` 刷新，本次仍回空 `PresetList@Num=0`。
- 平台不存在 / `localIp` 缺失时**回退返回实体交框架**（保持可用性，不因配置缺失彻底失联）。
- 验收：XML 字段逐项断言；身份断言；presets 缓存在/缺失两条路径。

### S5 媒体会话语义修正（G2/G6/G7）
- `s=Talk`：回 488 Not Acceptable Here 并记 WARN（音频通道需真机验证，本轮不做）。
- `s=Download`：`MediaSession` 增 `download` 标记，`onInfo` 直接忽略并记 DEBUG。
- 建立 200 OK 后抓取 UAS `Dialog`（`ctx.getServerTransaction().getDialog()`），仅**本端主动收尾**
  （源流消失/空闲超时）时用 `dialog.createRequest(BYE)` + `DialogExt.getSipProvider()` 发 BYE；
  收到上级 BYE 的路径绝不回 BYE。
- 验收：Talk→488；Download INFO 不透传；僵尸回收触发一次 BYE，收到上级 BYE 不发 BYE。

### S6 目录串行分片（G8）
- 新增 `CascadeCatalogPager`：按页入队 → 发首页（沿用入站 Call-ID）→ 收到 200 OK（okEvent 按 Call-ID 回调）
  或超时（`gateway.cascade.catalog.ok-timeout-ms`，默认 2000）后发下一页（新 Call-ID）。
- 开关 `gateway.cascade.catalog.serial`（默认 true）：关闭时退回一次性 burst。
- 看门狗 `@Scheduled` 扫描超期页，防止上级不回 200 OK 时目录卡死。
- 验收：纯逻辑单测（发首页→OK→发次页→队列空；超时→强制推进；关闭开关→一次性发完）。

### S7 配置与文档
- 全部新参数沿用 `@Value` 默认值（`application.yml` 无 `gateway.cascade.*` 块，保持现状）。
- 遗留项登记：语音广播的**音频通道建立**（本端作为语音输出设备主动 INVITE 上级、`m=audio 8000 RTP/AVP 8` +
  `a=rtpmap:8 PCMA/8000`）需真机联调；预置位缓存的通道级精度（当前缓存在设备级）。

## 4. 验证

```bash
mvn -o -pl voglander-web -am "-Dtest=Cascade*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -o -pl voglander-web -am test
```

- 单测：`CascadeManscdpXmlTest`(9)、`CascadeUpperMessengerTest`(9)、`CascadeBroadcastHandlerTest`(6)、
  `CascadeCatalogPagerTest`(7)、`CascadeQueryHandlerTest`(13)、`CascadeMediaInviteListenerTest`(23)、
  `CascadeMessageRequestProcessorTest`(6)。
- 回归：`-Dtest=Cascade*Test` 合计 **232** 个用例全绿；`CascadeCatalogAssemblerTest` 等既有用例不受影响。

## 5. 实施结果（已全部落地）

| 步骤 | 新增/改造文件 | 状态 |
| --- | --- | --- |
| S1 | `CascadeManscdpXml`（新建，5 类应答 + escape/append 骨架） | 完成 |
| S2 | `CascadeUpperMessenger`（新建，平台身份 MESSAGE；`sendByPlatformId` 与 `send(platform,…)` 双入口避免重载二义） | 完成 |
| S3 | `CascadeMessageRequestProcessor`（广播后置应答 + 无处理器补 200 OK）、`CascadeBroadcastHandler`（新建） | 完成 |
| S4 | `CascadeQueryHandler`（四类查询改自发送并补字段 + 平台缺失回退实体） | 完成 |
| S5 | `CascadeMediaInviteListener`（Talk→488、Download 忽略 INFO、UAS 对话抓取与主动 BYE） | 完成 |
| S6 | `CascadeCatalogPager`（新建，串行分片 + 200 OK 推进 + 看门狗） | 完成 |
| S7 | 本文件（缺口/方案/结果）+ 遗留项登记 | 完成 |

### 实施中额外发现的两个坑

1. **`StringUtils.defaultIfBlank(x, getNewCallId())` 会白生成 Call-ID**：第二个参数是急求值的，即使
   `x` 非空也会生成一个无用的 Call-ID（UDP 场景下无实际影响，但单测里会让 Call-ID 序列错位）。
   已改为 `isNotBlank(x) ? x : getNewCallId()`（`CascadeCatalogPager`、`CascadeUpperMessenger`）。
2. **200 OK 可能早于 `send` 返回**：分片器必须先 `inflight.put(firstCallId, batch)` 再发送，
   否则上级的 200 OK 会因找不到批次而被丢弃，串行链卡死在首页。

### 遗留项（需真机/上级联调）

- 语音广播的**音频通道建立**：本端作为语音输出设备主动 INVITE 上级，SDP 需 `m=audio 8000 RTP/AVP 8` +
  `a=rtpmap:8 PCMA/8000`，并做 PS/PCMA 解包；本轮只补齐「通知 → 应答」信令闭环，收到 RTP 音频后能否出声需真机验证。
- 预置位缓存的**通道级精度**：`device.extend.presets` 是设备级快照，同一设备多通道共用一个预置位列表。
- `gateway.cascade.catalog.serial/ok-timeout-ms/batch-size` 等新参数均取默认值，未写入 `application.yml`。
