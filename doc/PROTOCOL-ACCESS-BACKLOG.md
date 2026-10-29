# 协议接入待办清单（ONVIF / RTSP / GT1078）

> 状态基线：2026-10-26
> 定位：本文件是**待办池**，不代表已排期。进入开发前需按 `doc/DEVELOPMENT_WORKFLOW.md` 走「调研 → Check 关键点 → 确认 → 设计 → TDD → 验收」。

---

## 一、现状基线

| 能力 | 状态 | 落点 |
|---|---|---|
| GB28181 设备接入 | ✅ 已实现 | `Gb28181ProtocolHandler`（信令）、`Gb28181MediaProtocolHandler`（媒体） |
| RTSP 拉流代理（手工填 URL） | ✅ 已实现 | `/api/v1/proxy/*`、前端菜单 `304 MediaStreamProxy`、`StreamProxyBizServiceImpl` |
| RTSP 设备级接入（设备→实时预览） | ✅ 已实现（含 UI 新增入口） | `RtspMediaProtocolHandler` + `MediaProtocolHandler.requiresChannel()`，入口见 §3.7 |
| ONVIF 协议 | ❌ 仅枚举占位 | `DeviceProtocolEnum.ONVIF(2)`，无任何 handler |
| GT1078 协议 | ❌ 零实现 | 全仓库无命中，连枚举都没有 |
| README「全协议支持」宣称 | ⚠️ 与代码不符 | `README.md` 第 5/27/36 行 |

**关键认知**：ONVIF 的视频流本身 100% 落在 RTSP 上。ONVIF 买到的是「免手填 URL + 云台 + 事件」，不是「能看画面」。所以 ONVIF 与 RTSP 的优先级应绑定评估，而不是并行。

---

## 二、待办总表

| ID | 任务 | 优先级 | 预估 | 依赖 | 产出形态 |
|---|---|---|---|---|---|
| ONVIF-1 | WS-Discovery 设备发现 | P1 | 3~5 人日 | 无 | 新 `voglander-integration/wrapper/onvif` |
| ONVIF-2 | SOAP 媒体配置（自动取 RTSP URL） | P1 | 3~4 人日 | ONVIF-1 | `OnvifDeviceService` |
| ONVIF-3 | PTZ 云台控制 | P2 | 3~5 人日 | ONVIF-2 | `PtzCommandService` + REST |
| ONVIF-4 | 事件订阅（移动侦测/报警） | P3 | 4~6 人日 | ONVIF-2 | 接入现有事件总线 |
| RTSP-1 | 设备级 RTSP 接入收尾验收 | P0 | 0.5 人日 | 已完成代码 | 本文件 §三 |
| RTSP-2 | URL 凭据加密与脱敏 | P1 | 2 人日 | RTSP-1 | `tb_device.extend.rtspUrl` 密文 |
| RTSP-3 | 前端表单 rtspUrl + 新增设备入口 + 连通性探活（全部完成） | ✅ | — | RTSP-1 | vben 前端 + `/device/probe-rtsp` |
| RTSP-4 | 断流自愈（重连/退避） | P2 | 3 人日 | RTSP-1 | hook 内重试策略 |
| RTSP-5 | 批量导入（CSV/Excel） | P2 | 2~3 人日 | RTSP-3 | 新增导入接口 |
| RTSP-6 | 设备巡检（定时探活） | P3 | 2~3 人日 | RTSP-1 | 复用 Durable Task |
| DOC-1 | README 能力对齐（ONVIF/GT1078） | P0 | 0.5 人日 | 无 | 改 README 或补路线图 |
| GT1078-1 | GT1078 协议评估与实现 | P3 | 待评估 | 原始需求文档 | 先出可行性结论 |

---

## 三、RTSP-1：设备级 RTSP 接入收尾验收（当前进行中）

### 3.1 已完成的代码改动（12 文件，编译通过）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `voglander-common/.../enums/DeviceAgreementEnum.java` | 新增 `RTSP_IPC(4, "RTSP拉流摄像头", IPC, DeviceProtocolEnum.RTSP.getType())` |
| 2 | `voglander-manager/.../dto/DeviceDTO.java` | `ExtendInfo` 增加 `rtspUrl` |
| 3 | `voglander-web/.../device/req/DeviceCreateReq.java` | `ExtendInfoReq` 增加 `rtspUrl` |
| 4 | `voglander-web/.../device/req/DeviceUpdateReq.java` | `ExtendInfoReq` 增加 `rtspUrl` |
| 5 | `voglander-web/.../device/assembler/DeviceWebAssembler.java` | create/update 两处 `setRtspUrl` |
| 6 | `voglander-manager/.../manager/DeviceManager.java` | `saveOrUpdate` 逐字段 merge 增加 `rtspUrl` |
| 7 | `voglander-service/.../live/protocol/MediaProtocolHandler.java` | 新增 `liveStreamIdPrefix()` / `requiresChannel()` 两个 default |
| 8 | `voglander-service/.../live/impl/MediaPlayServiceImpl.java` | handler 入口解析、按 `requiresChannel` 校验通道、`buildLiveStreamId(handler,...)` |
| 9 | `voglander-service/.../live/protocol/impl/RtspMediaProtocolHandler.java` | **新建**：`addStreamProxy` 拉流 + `deleteStreamProxy` 回收 |
| 10 | `voglander-integration/.../zlm/impl/VoglanderZlmHookServiceImpl.java` | `onStreamNotFound` 白名单补 `rtsp_live_` |
| 11 | `voglander-web/.../live/domain/LiveStartReq.java` | `channelId` 去掉 `@NotBlank` |
| 12 | `voglander-web/src/test/.../RtspMediaProtocolHandlerTest.java` | **新建**：7 个用例 |
| 13 | `voglander-web/.../device/vo/DeviceVO.java` | `ExtendInfoVO` 增加 `rtspUrl` 字段 + 映射（否则建完设备回查看不到地址） |
| 14 | `voglander-web/.../device/assembler/DeviceWebAssembler.java` | **修存量 bug**：`expires` 在 DTO 中是基本类型，请求不传时拆箱 NPE（create/update 两处补 null 判断） |
| 15 | `voglander-service/.../MediaPlayServiceImpl.java` | `buildLiveStreamId` 修正：无 channel 时不再拼 `_deviceId`（原会产出 `rtsp_live_x_x`） |

### 3.2 设计要点

- **零 SQL 迁移**：RTSP 地址存 `tb_device.extend` JSON 的 `rtspUrl`，复用现成字段。
- **流名前缀** `rtsp_live_<deviceId>[_<channelId>]`，与 `gb_live_` / `gb_back_` 隔离，避免 GB28181 回收逻辑误伤。
- **proxyKey 复用 `callId`**：`tb_media_session.call_id` 承载 ZLM 的 `vhost/app/stream` 三元组，stop 时据此 `deleteStreamProxy`。
- **GB28181 行为不变**：前缀仍 `gb_live_`，`requiresChannel()` 默认 `true`。

### 3.3 验收清单（DoD）——已全部通过（2026-10-26）

- [x] `RtspMediaProtocolHandlerTest` 7 用例通过（`Tests run: 7, Failures: 0, Errors: 0`）
- [x] 重启后端加载新 handler（21s 启动）
- [x] **新增设备 UI 入口**（权限码 `Device:Device:Add` / menu id 50112）：抽屉提交建 RTSP 设备 → `code=0`，响应 `extendInfo.rtspUrl` 原样回显，`serverIp=''` 不触发 NOT NULL 报错（`id=205`）
- [x] 纯 UI 新建的 `cam-rtsp-ui-001` 直接 `live/start` → `code=0`、**1.0s**、`streamId=rtsp_live_cam-rtsp-ui-001`、FLV `HTTP 200` 且首字节为 `FLV`
- [x] `POST /api/v1/device/insert` 带 `extendInfo.rtspUrl` 成功（id=203, type=4）
- [x] `POST /api/v1/live/start` 仅传 `deviceId` → `code=0`，**1.95s**
- [x] FLV / HLS 可播（ffprobe h264 1280x720 30fps；FLV 3s / 883,506 字节；HLS m3u8 正常分片）
- [x] `getMediaList` 出现 `rtp/rtsp_live_cam-rtsp-001`，`originTypeStr=pull`（拉流而非推流）
- [x] `POST /api/v1/live/stop` → `delStreamProxy` 成功、ZLM 中 `rtsp_live_*` 归零、`reason=idle_gc`
- [x] GB28181 回归：`gb_live_34020000001320000011_34020000001320000001` 1.45s 起流成功，前缀/链路未变
- [x] **连通性探活** `POST /api/v1/device/probe-rtsp`：活流 200/1~2ms、关闭端口「端口未开放或被拒绝」、8082「未返回 RTSP 响应」、`http://` 与空串回 400 语义；单测 `Tests run: 9, Failures: 0`

### 3.4 验收实录

```
live/start  RTSP  : 1.95s  code=0  9 个播放地址  refCount=1
live/start  GB28181: 1.45s  code=0  9 个播放地址  refCount=1
ZLM(rtsp_live)    : originTypeStr=pull  originUrl=rtsp://127.0.0.1:554/live/rtsp_src
ffprobe           : h264 1280x720 30/1
FLV(3s)           : http=200 bytes=883506
live/stop         : code=0  → [rtsp.terminate] 拉流代理已删除 → [closeStream] reason=idle_gc
```

### 3.5 本次踩到的环境坑（已记入记忆）

| 现象 | 根因 | 处理 |
|---|---|---|
| `mvn` 编译报 200+ 处 `找不到符号`（全是 Lombok 生成的 getter） | shell 的 `JAVA_HOME=C:\soft\jdk25`，Lombok 不兼容 JDK25，注解处理器静默失效 | 执行 mvn 前 `$env:JAVA_HOME='C:\soft\jdk17'` |
| `device/insert` 报 `AjaxResult cannot be cast to DeviceDTO` | `ControllerExceptionLogAspect` 切点 `web.api..*.*(..)` 覆盖了 assembler，吞掉真实异常并返回 AjaxResult | 看日志的**第一条** `异常方法: DeviceWebAssembler.toDeviceDTO` 才是真因（`expires` 拆箱 NPE） |
| 设备插入 `Field 'server_ip' doesn't have a default value` | `tb_device.server_ip` NOT NULL 且无默认值（MySQL 严格模式） | 创建请求必须带 `serverIp` |
| ffmpeg 推的 RTSP 源 20s 后消失，`addStreamProxy` 超时 | ZLM `streamNoneReaderDelayMS=20000`，无观看者时主动断开推流源 | 测试源用 bat 循环重推（`logs/rtsp-src.bat`）；生产场景 IPC 持续推流不受影响 |
| 后端停掉时 ffmpeg 推流 `401 Unauthorized` | ZLM `on_rtsp_auth` hook 指向 8181，后端不在线即拒绝 | 顺序必须是「先起后端，再起源」 |

### 3.6 已知限制（后续项）

- `rtpType=0`（TCP）写死，不支持 UDP 模式 → 见 RTSP-4 一并考虑
- 新建/编辑表单均可填 `rtspUrl`，且已带保存前 OPTIONS 探活；但探活不校验收流（地址"通"≠"能出画面"）→ 见 §3.8
- 手工新建的拉流设备 `status=0`（离线）：没有注册/心跳语义，列表「在线」列不反映真实可达性 → 只能靠 `live/start` 反推
- 断流后不自动重连，依赖 ZLM `autoClose=false` + timeout → 见 RTSP-4

### 3.7 新增设备 UI 入口（2026-10-26 补齐）

**为什么需要**：原设计「设备靠注册落库」，只有 GB28181 有 REGISTER 报文。RTSP/ONVIF 拉流设备没有注册报文 → 无 `deviceId` 台账 → 无法发起 `live/start`，也就无法实测。旧路子只有 `POST /api/v1/device/insert`，只能靠 curl 验。

**前端改动**（仓库 `voglander-vben-frontend-main`，5 文件）

| 文件 | 改动 |
|---|---|
| `api/device.ts` | 新增 `DeviceCreateReq` 类型 + `createDevice()`（`POST /api/v1/device/insert`）|
| `views/device/modules/device-create-form.vue` | **新建**抽屉：deviceId/name/type/ip/port/`extendInfo.rtspUrl`，type 默认 `RTSP IPC(4)`，端口默认 554，打开时 `resetForm()` |
| `views/device/list.vue` | 工具栏加「新增设备」按钮（`hasAccessByCodes(['Device:Device:Add'])`）+ `CreateDrawer` |
| `locales/langs/{zh-CN,en-US}/device.json` | `action.add` / `msg.createSuccess` / `field.deviceIdHelp` |

**权限码与 SQL**（三份初始化 SQL 各 +1 行，插在 admin 自动授权语句之前）

```sql
(50112, 501, 'DeviceAdd', 'device.action.add', 3, null, null, '', 12, 1, 'Device:Device:Add',
 JSON_OBJECT('title', 'device.action.add', 'hideInMenu', true))   -- SQLite 版改 JSON 字符串
```

- `menu_type=3`（按钮）挂在设备列表 `501` 下，`Device:Device:Add`。
- 后端 `DeviceController#insert` **无 `@PreAuthorize`**，权限纯粹是前端按钮可见性；所以只补菜单不用改 Java。
- 现库（MySQL `192.168.118.20:3306/voglander`）已用 JDBC 单文件补插 + 授权给 admin 角色（`logs/DbExecMenuPermission.java`）。

**契约要点**
- `serverIp` 传空串 `''`：`tb_device.server_ip` 是 NOT NULL 且无默认值，而拉流设备无 SIP 注册节点语义。
- 手工建的设备 `status=0`（无心跳），列表不影响 `live/start`。

**验证（实测）**

```
/auth/codes                 → 含 Device:Device:Add
POST /device/insert         → code=0  id=205 (type=4, serverIp='')
getPage 回查                → extendInfo.rtspUrl=rtsp://127.0.0.1:554/live/rtsp_src 原样回显
POST /live/start            → code=0  1.0s  streamId=rtsp_live_cam-rtsp-ui-001  refCount=1
FLV 拉流                    → HTTP 200  magic=FLV
vite 编译（5666 直取模块）  → device-create-form.vue / list.vue / device.ts 均 200
```

**坑**：`/system/menu/permissions` 只下发**页面级** `authCode`（`menu_type=2`），按钮码（`menu_type=3`）不会出现在路由树里（`Device:Device:Edit` 同样不在）。前端 `hasAccessByCodes` 读的是 `/auth/codes`，补按钮权限时验证要以它为准。

### 3.8 连通性探活（2026-10-26 补齐）

**为什么**：地址填错要到 `live/start` 才报错；探活把这一步提前到保存前，不用先建台账再试错。

**落点**

| 层 | 文件 | 内容 |
|---|---|---|
| Integration | `wrapper/rtsp/RtspProbeClient.java` | TCP 建连 + `OPTIONS`；带凭据时**同连接**内走 Digest 挑战重发，3s 超时，请求行剥 `user:pass@`，日志只打 `host:port` |
| Integration | `wrapper/rtsp/support/RtspResponse.java` | 按条读状态行 + 响应头（连读两条响应不会串包） |
| Integration | `wrapper/rtsp/support/DigestChallenge.java` | 解析 `WWW-Authenticate`，支持无 qop（RFC 2069）/ `qop=auth` / `auth-int` / `MD5-sess` |
| Integration | `wrapper/rtsp/dto/RtspProbeResult.java` | 8 种结论工厂（可达/未带凭据/方案不支持/凭据通过/凭据被拒/非RTSP/不可达）+ 中文文案 |
| Web | `device/req/RtspProbeReq.java`、`device/vo/RtspProbeVO.java` | 入参 `{rtspUrl}`，出参 8 字段（含 `authenticated`） |
| Web | `DeviceController#probeRtsp` | `POST /api/v1/device/probe-rtsp`（无需新权限码，沿用 Add/Edit） |
| 前端 | `api/device.ts`、`device-create-form.vue`、`device-form.vue` | 「测试连通性」按钮 + 结果文案（`action.probe`）|
| 测试 | `RtspProbeClientTest` | 12 用例，本地 `ServerSocket` 假服务（支持多轮脚本），不依赖真机 |

**判定语义**

| 探测结果 | reachable | authenticated | 提示 |
|---|---|---|---|
| RTSP 2xx（设备不鉴权） | true | false | RTSP 服务可达（200 OK）—— 仅完成协议握手，未校验收流 |
| 401 + 地址带凭据 + Digest 重发得 2xx | true | **true** | 设备可达，账号密码校验通过（200 OK） |
| 401 + 地址带凭据 + 重发仍 401 | true | false | 设备可达，但账号密码未通过校验（401）—— 确认账号密码与通道权限 |
| 401 + 地址不带凭据 | true | false | 设备可达，但要求鉴权 —— 请补成 `rtsp://账号:密码@主机:端口/...` |
| 401 + 设备只给 Basic / SHA-256 摘要 | true | false | 设备可达，但该认证方式暂不支持，无法校验账号密码 |
| 404 / 454 | true | false | 设备可达，但该流地址不存在 |
| 其他 4xx/5xx | true | false | 设备可达，返回 xxx |
| 拒绝 / 超时 / DNS 失败 | false | false | 连接失败：<原因> |
| 端口通但非 RTSP 响应 | false | false | 端口可连接，但未返回 RTSP 响应 —— 多半不是 RTSP 端口 |

**关键取舍**

- 不发 `DESCRIBE`：各厂商对 DESCRIBE 的鉴权/通道号要求差异太大，深探会制造大量假失败。
- **挑战与重发必须复用同一条 socket**：大华等设备把 Digest nonce 绑在 TCP 连接上，换连接重放旧 nonce 一律 401（2026-10-26 实测：跨连接 401、同连接 200 OK）。因此探活从“发一条读状态行”改为“按条吃完整响应头再发下一条”。
- **只在 URL 带凭据时才校验密码**：没带凭据时无从校验，文案直接要求补全地址，不笼统说“鉴权失败”——把“我们没发凭据”误报成“你密码错了”是上一版的真实缺陷。
- **不挂熔断**（有意偏离 wrapper 惯例）：探测目标是用户随手输入的任意 IP，熔断会把一次失败扩散成对其它设备的误判。
- **只提示不拦截**：部分设备不响应 `OPTIONS` 但 `live/start` 能起流，硬拦会挡住本来可用的设备。
- 文案刻意不写"地址可用"：ZLM 对**任意**路径的 `OPTIONS` 都回 200（实测 `/live/definitely_not_here` 同样 200），只能说"服务可达"。

**实测（ZLM 554 + ffmpeg 推流源）**

```
rtsp://127.0.0.1:554/live/rtsp_src               → reachable=true   200  1~2ms
rtsp://127.0.0.1:554/live/definitely_not_here    → reachable=true   200  1ms   (OPTIONS 不校验流)
rtsp://127.0.0.1:59999/live/x                    → reachable=false        端口未开放或被拒绝
rtsp://127.0.0.1:8082/live/x  (HTTP 端口)         → reachable=false        未返回 RTSP 响应
rtsp://admin:secret@127.0.0.1:554/live/rtsp_src  → reachable=true   200          (请求行已剥凭据)
http://127.0.0.1:554/live/x                      → code=500  只支持 rtsp:// 开头的地址
空串                                              → code=500  RTSP 地址不能为空
RtspProbeClientTest                              → Tests run: 12, Failures: 0
```

**真机验证（大华 IPC 192.168.60.207，2026-10-26）**

```
rtsp://admin:Admin123@192.168.60.207:554/cam/realmonitor?channel=1&subtype=1
  → reachable=true  200  authRequired=true   authenticated=true   115ms  账号密码校验通过
rtsp://admin:WrongPass@192.168.60.207:554/cam/realmonitor?channel=1&subtype=1
  → reachable=true  401  authRequired=true   authenticated=false   35ms  账号密码未通过校验
rtsp://192.168.60.207:554/cam/realmonitor?channel=1&subtype=1   (无凭据)
  → reachable=true  401  authRequired=true   authenticated=false   13ms  请补成 rtsp://账号:密码@...
rtsp://127.0.0.1:554/live/rtsp_src             (ZLM，不鉴权)
  → reachable=true  200  authRequired=false  authenticated=false    2ms  RTSP 服务可达
```

大华在**跨连接**重放 nonce 时的实际表现（用裸 Socket 手工对照）：

```
A) OPTIONS 匿名（连接1）          → 401 + WWW-Authenticate: Digest realm="Login to 78e78...", nonce=...
B) OPTIONS 带 Digest（连接2，复用 A 的 nonce） → 401   ← nonce 绑定连接，换连接必失败
C) OPTIONS 匿名（连接3）          → 401 + 新 nonce
D) OPTIONS 带 Digest（连接3，同连接）          → 200 OK
E) DESCRIBE 带 Digest（连接3，同连接）         → 200 OK + SDP 含 m=video
```

**已知边界**：`OPTIONS` 只能证明“服务端在且认 RTSP”，证明不了“这根 URL 真能出画面”。Digest 挑战通过只说明**凭据被接受**，通道号/子码流是否存在、编码是否可播（如 H.265 与 flv.js 不兼容）仍要靠 `live/start` 实拉验证（RTSP-6 定时巡检同理）。

---

## 四、ONVIF-1：WS-Discovery 设备发现

**背景**：现在加设备要手敲 IP/端口/RTSP URL。ONVIF 的标准发现能自动列出网段内所有支持 ONVIF 的摄像机。

**方案要点**
- 组播 `239.255.255.250:3702`（IPv4）/ `FF02::C`（IPv6），发送 SOAP `Probe`，收 `ProbeMatch` 解析 `XAddrs` + `Types`（`dn:NetworkVideoTransmitter`）。
- 纯 UDP，无第三方 SDK；可手写 SOAP 报文（模板固定，`<wsa:MessageID>` 用 UUID）。
- 落点：`voglander-integration/src/main/java/.../wrapper/onvif/discovery/`
- 对外：`POST /api/v1/device/discover?timeoutSec=3` → 返回候选设备列表（不入库，前端勾选后批量建）

**风险**
- Windows 防火墙/多网卡环境下组播可能被拦；需要绑定指定网卡（`MulticastSocket.setNetworkInterface`）。
- Docker 容器内需 `--network host` 或显式组播转发。

---

## 五、ONVIF-2：SOAP 媒体配置（自动取 RTSP URL）

**背景**：这是 ONVIF 的核心价值——不再手填 `rtsp://admin:pwd@ip:554/...`。

**方案要点**
- 调用链：`GetCapabilities` → 取 `Media` 的 `XAddr` → `GetProfiles` → `GetStreamUri(ProfileToken, RTP-Over-RTCP=true)` → 得到 RTSP URL。
- 认证：WS-UsernameToken（`PasswordDigest = Base64(SHA1(nonce + created + password))`）。
- 权限：ONVIF `GetStreamUri` 需要管理员/操作员角色，需在设备管理页存账号密码（**必须加密**，与 RTSP-2 合并）。
- 结果直接写入 `extend.rtspUrl`，后续走 `RtspMediaProtocolHandler`，**媒体面零新增**。

**验收**：海康/大华各一台真实设备（或 ONVIF Device Manager 模拟器）能自动取到 URL 并出画面。

---

## 六、ONVIF-3：PTZ 云台控制

**方案要点**
- `ContinuousMove`（带 `PanTilt x/y`、`Zoom x`）、`Stop`（`PanTilt=true, Zoom=true`）、`AbsoluteMove`（带 `PTZSpeed`）、预置位 `SetPreset`/`GotoPreset`/`RemovePreset`。
- 对外 REST：`POST /api/v1/device/ptz/{deviceId}`，body `{action, pan, tilt, zoom, speed, presetToken}`。
- 前置：需要 `GetCapabilities` 里有 `PTZ` 能力，否则返回明确错误而非静默失败。
- 超时保护：`ContinuousMove` 必须配套 `Stop`，否则镜头会一直转 → 服务端加 5s 兜底 `Stop`。

**风险**：不同厂商对速度取值范围（`-1.0~1.0` vs `-100~100`）实现不一致，需要能力探测 + 归一化。

---

## 七、ONVIF-4：事件订阅

**背景**：移动侦测、遮挡报警、外部 IO 触发，是"告警中心"的数据源。

**方案要点**
- 优先用 `PullPointSubscription`（拉模式，穿透 NAT 友好），避免 `BaseNotification` 推送需要公网回调。
- 长轮询 `PullMessages(timeout=PT5S, messageLimit=10)`，解析 `wsnt:NotificationMessage` 的 `Topic` + `Source` + `Data`。
- 落点：复用现有事件总线（`voglander-common/event`）+ SSE 推送，与 GB28181 告警走同一通道。
- 去重：同一 `Topic+Source` 在窗口期内合并，避免前端刷屏。

---

## 八、其余待办速记

### DOC-1：README 与能力对齐（P0，0.5 人日）
README 第 5/27/36 行宣称「GB28181 / ONVIF / GT1078 全协议接入」，与代码不符（GT1078 零实现）。二选一：
- A. 改为「GB28181 已支持，ONVIF/GT1078 规划中」+ 保留路线图；
- B. 补齐实现后再宣称。

建议 A，立刻消除能力误判。

### RTSP-2：URL 凭据加密（P1）
`rtsp://user:pass@ip/...` 明文存 `tb_device.extend` 且经 API 明文返回，属于凭据泄露风险。方案：入库前 AES 加密（复用现有加解密工具），`DeviceVO` 返回时脱敏为 `rtsp://***:***@ip/...`，仅在 `Handler` 内部解密使用。

### RTSP-3：前端表单 + 探活（P1，全部完成 2026-10-26）

**已完成**
- 设备编辑抽屉新增「拉流地址」输入框，仅当协议为 `ONVIF IPC(3)` / `RTSP IPC(4)` 时显示（`dependencies.show` + `triggerFields: ['type']`）。
- 协议下拉补 `RTSP IPC(4)`（原只有 1/2/3，RTSP 设备的 `type` 会显示为空）。
- `LiveStartReq.channelId` 改为可选（RTSP 拉流按设备级 URL，无通道概念）。
- i18n：`device.field.rtspUrl` / `device.field.rtspUrlHelp`（zh-CN + en-US）。
- 涉及文件：`api/device.ts`、`views/device/data.ts`、`views/device/modules/device-form.vue`、`views/device/modules/device-create-form.vue`（新增）、`views/device/list.vue`、`locales/langs/{zh-CN,en-US}/device.json`、`sql/voglander{-sqlite,-postgresql,}.sql`。
- 建设备入口与 `rtspUrl` 细节见 §3.7。
- 表单提交结构 `{...8 个字段, extendInfo: {rtspUrl}}`，与 `DeviceUpdateReq.extendInfo` 对齐；点路径字段在 `getValues()` 中自动成嵌套对象（与 `media/stream-proxy` 同套路）。

**顺带修复的存量缺陷（重要）**
`DeviceDTO.convertDO` 用 `JSON.toJSONString(extendInfo)` 整体覆盖 `tb_device.extend`，编辑请求未携带的扩展字段会以 null/0 落库——实测把 GB28181 设备的 `{"charset","deviceInfo","expires","streamMode","transport"}` 清成 `{"expires":0}`。
- 修法：新增 `DeviceDTO.mergeExtend(base, patch)` / `mergeExtendJson(existingJson, patch)`，`DeviceManager.update` / `updateById` 两条链路改为以库中现值为基底逐项覆盖。
- 回归：不传 `extendInfo`、只传 `rtspUrl:null`、只改 `rtspUrl` 三种请求均不再丢字段（RTSP 设备 rtspUrl 不会被误清）。

**探活（已完成 2026-10-26，含 Digest 凭据校验）**
- 「测试连通性」按钮：保存前 `OPTIONS` 探活，避免保存后才在 `live/start` 报错。
- 地址带 `账号:密码@` 时，在同一 TCP 连接内走 Digest 挑战重发，把“要求鉴权”细化成“密码对 / 密码错”；不带凭据时明确提示补全地址。
- 实现、判定语义与实测证据见 §3.8；单测 12 用例。

### RTSP-4：断流自愈（P2）
`onStreamNotFound` 现在只做日志白名单。方案：`rtsp_live_` 流超时后按指数退避（1s/2s/4s/8s，上限 30s，最多 5 次）重新 `addStreamProxy`，成功则回写会话；连续失败则置会话 `FAILED` 并推 SSE 告警。

### RTSP-5：批量导入（P2）
CSV 列：`deviceId,name,ip,port,rtspUrl,subType`。逐行校验 + 唯一键去重，支持 dry-run 预览。

### RTSP-6：设备巡检（P3）
复用现有 Durable Business Task 引擎，定时（5min）对 `RTSP_IPC` 设备做探活，维护 `online` 状态并走 SSE 上线/离线事件。注意与 `onStreamChanged` 的状态来源优先级：**会话活跃时以 ZLM hook 为准**，避免巡检误判离线。

### GT1078-1：协议评估（P3）
先确认 GT1078 的真实需求来源（是「国标 1078」= JT/T 1078 车载终端？还是厂商私有？）。现有代码零基础，工作量取决于标准范围（信令 + 附件服务器 + 主动安全报警），需单独评估后再排期。

---

## 九、建议实施顺序

```
RTSP-1（验收收尾）
   ↓
DOC-1（避免能力误判，成本极低）
   ↓
RTSP-2 + RTSP-3（安全 + 可用性，投入产出比最高）
   ↓
ONVIF-1 + ONVIF-2（自动发现 + 自动取流，一次性解决"手填 URL"痛点）
   ↓
RTSP-4（稳定性）
   ↓
ONVIF-3（PTZ）/ RTSP-5 / RTSP-6
   ↓
ONVIF-4 / GT1078-1（需单独评估）
```
