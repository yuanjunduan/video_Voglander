# Voglander Agent 上下文手册

> **用途**：新会话接管这个仓库时，先读这一份，避免从零重建认知。
> **维护约定**：每次完成一轮需求后，把「新增的结构约束」「踩过的坑」「未闭环事项」补进来；
> 与代码冲突时以代码为准，并回来修正本文。
> **最后更新**：2026-11-05（HEAD = `29fe552` feat(cascade): 本端注册身份拆为独立客户端表，平台改存 clientRefId）

---

## 1. 项目定位

Voglander 是一套**视频设备接入 + 向上级 GB28181 级联**的平台：

- **向下**：接 GB28181 设备/下级平台（SIP 注册、目录、点播、PTZ、录像），另有一条手填 RTSP URL 的拉流链路。
- **向上**：作为下级平台，把本地通道以 GB28181 目录形式上报给上级（实测对端是海康 iSecure Center / NCG），支持上级点播回推 RTP。
- **横向**：码流由 ZLMediaKit 承担，web 侧只编排会话与推流参数；前端是 vben5 (Vue3 + antd + vxe-table)。

一句话记法：**SIP 信令归 integration，目录/组织的业务规则归 manager，持久化归 repository，HTTP 入口归 web。**

---

## 2. 模块与依赖方向

| 模块 | 职责 | 禁止 |
| --- | --- | --- |
| `voglander-common` | 常量、事件、通用工具 | 依赖任何业务模块 |
| `voglander-client` | 对外契约、SIP/GB28181 客户端封装 | 反向依赖 web |
| `voglander-repository` | DO / Mapper / Service(MyBatis-Plus) | 写业务规则 |
| `voglander-manager` | 领域规则、缓存、事件发布、节点路由 | 依赖 integration / web |
| `voglander-integration` | 外部适配（GB28181 cascade、ONVIF、媒体协议） | 被 manager 反向依赖 |
| `voglander-service` | 用例编排（直播、录像等横跨多 manager） | 直接拼 SQL |
| `voglander-web` | Controller、Req/VO、Assembler、启动类 | 写业务规则、暴露 DO |
| `voglander-test` | 测试支撑 | — |

- 包前缀固定 `io.github.lunasaw.voglander`，启动类 `voglander-web/.../web/ApplicationWeb.java`。
- 父 POM 版本 `1.0.2-SNAPSHOT`，编译目标 **Java 17**（`maven.compiler.source/target=17`）。

---

## 3. 构建 / 运行 / 测试

### 3.1 必须先把 JAVA_HOME 钉到有 JDK21 的目录

内置终端默认 JDK 会让 Lombok 静默失效（报「找不到符号 getXxx」一类诡异错误）。本机已验证可用的固定前置：

```powershell
$env:JAVA_HOME="C:\soft\jdk21"     # 目录名是 jdk21，但编译目标是 Java 17
```

### 3.2 常用命令

```powershell
mvn clean compile                                   # 全模块
mvn spring-boot:run -pl voglander-web               # 启动（web 端口 8181）
mvn test                                            # 全量
# 定向：-Dtest 用逗号分隔；跨模块必须带 -am，且加 failIfNoSpecifiedTests=false
mvn test -pl voglander-web -am "-Dtest=Cascade*Test" "-Dsurefire.failIfNoSpecifiedTests=false"
```

**PowerShell 两个必踩点**：

1. 形如 `-Dtest=A,B` / `-Dsurefire.xxx` 的参数**整体加引号**，否则被拆解报 `参数列表中缺少参量` 或 `LifecyclePhaseNotFound`。
2. `mvn -q` 会吞掉 surefire 摘要，判断成败要看 `target/surefire-reports/*Test.txt`。
3. **不要用 `mvn install`**——会走到 gpg 签名而失败。跨模块改动只要 `-am` 即可。

### 3.3 运行配置

- `voglander-web/src/main/resources/application.yml`：`server.port=8181`，`spring.profiles.active=prod,repo,inte`。
- SIP：本端 server 5060、client 5061；另有 9092（媒体/HTTP 相关）。
- 本地默认库 SQLite（`test-app.db` / `data` 下实例库），Redis 不可用时相关测试自动跳过。
- **SQLite 的 `sql/migration/sqlite/*.sql` 不会自动执行**：`SqliteSchemaInitializer` 只跑硬编码注册的迁移器 bean。给 SQLite 加列必须新写一个幂等迁移器（`pragma table_info` 判列 + 记录 `schema_migration_history`）并接进 initializer，否则运行中的库直接 `no such column`。MySQL/PG 侧靠人工执行 DDL。

---

## 4. 级联（向上）模块 —— 最需要小心的地方

### 4.1 三级目录结构

```
域节点(34020000002000000001) → 组织节点(tb_cascade_org) → 通道节点(tb_cascade_channel)
```

- **唯一组装入口**：`voglander-integration/.../cascade/CascadeCatalogAssembler`。
  上级 `Query` 的应答（`CascadeQueryHandler`）与主动 `NOTIFY`（`CascadeNotifyPublisher`）都只调它。
  **任何新增上报路径必须复用它**，否则「被查到的目录」和「被推送的目录」会长期不一致。
- `SumNum = 1(域) + N(生效组织) + M(通道)`。
- 组织树：`tb_cascade_org` 用 `parent_org_id`（自表 id）串父子，`org_path` 存 `/编码1/编码2/` 物化路径；查子树是 `org_path LIKE '/A/%'` 前缀匹配，任意深度一次查全，不用递归 CTE。
- 组织编码 20 位、类型位 `215`，由后端 `CascadeOrgManager.nextOrgCode` 生成，**创建后不可改**。

### 4.2 `enabled` ≠ 「上级看得见」（2026-10-30 新增的核心口径）

`tb_cascade_org.enabled` 只是**自己的开关**。祖先停报后，子孙的 `enabled` 仍是 1，但它们早已随祖先被 `DEL` 从上级目录删除。因此：

| 概念 | 含义 | 实现 |
| --- | --- | --- |
| `enabled` | 自己的上报开关 | DB 字段 |
| `effective` | 自身 + 整条祖先链都在报（上级目录里真实可见） | `CatalogVisibility` |

- **`CatalogVisibility`**（`voglander-manager/.../manager/CatalogVisibility`）：整平台一次查库算好的快照，包含 `effectiveCodes`、`blockedByOrgName`（挡住它的停报节点名）；通道可见性按组织 `revokePolicy`：`REROUTE` 上浮域节点仍可见 / `CASCADE` 一起消失。
- **闸门（写入口）**：
  - `CascadeChannelManager.publish()`、`update()` 的 `0→1`、绑组织（`normalizeOrgId`）→ `assertOrgPublished`；
  - `CascadeOrgManager.publish()`、`update()` 的 `0→1` → `assertAncestorsEnabled`；
  - `CascadeOrgManager.add()` 在停报父链下**自动落成 `enabled=0`** 且不发事件。
  - 恢复失败只报错、**不自动打开祖先链**（关一个开关会放进一整棵子树，用户会失去预期）。
- **出参**：`CascadeOrgVO` / `CascadeChannelVO` 带 `effective` + `blockedByOrgName`，前端列表有「上级可见」列。
- 修改这块时**务必同步改 `CascadeCatalogAssembler#isChannelVisible` 与 `CatalogVisibility#isChannelVisible`**，两者口径必须一致。

### 4.3 `tb_cascade_channel` 是四向读写的中点

| 方向 | 入口 | 注意 |
| --- | --- | --- |
| 上级查询目录 | `CascadeQueryHandler.onCatalogQuery` | 只取 `enabled==1` 且组织可见的通道；表空则上级目录恒空 |
| 上级点播/PTZ/录像 | `CascadeMediaInviteListener` / `CascadeControlHandler` | 用 `(platformId, 上级 DeviceID)` 反查，**查不到静默 return，上级侧表现为超时** |
| 本端主动通知 | `CascadeNotifyPublisher` | 组装 Catalog Notify |
| 写入 | `CascadeChannelManager` | 唯一键 `(platform_id, local_channel_id)`；`cascade_channel_id` **无唯一约束**，填重复不会报错但会在上级目录产生重复 DeviceID |

`localDeviceId` 填错**不会在校验期报错**，只会在点播时拉不到流。

### 4.4 海康侧报文三条铁律（已实测取证）

1. **NOTIFY 必须带 `Event: Catalog;id=<订阅SN>` 与 `Subscription-State` 头**，缺则海康回 `400 Bad Request`（同会话 Keepalive 却 200，可据此判定是报文格式而非链路问题）。
2. **每包 `SumNum` 恒等于总节点数**，`DeviceList/@Num` 才是本包条数。框架的 `sendCatalogCommandBatched` 会用本包条数覆写 `SumNum`，**不可用**；应答必须自建 `MESSAGE` 且 Call-ID 沿用入站 Call-ID。
3. **Keepalive 的 `<Status>` 只能是 `ON`/`OFF`/`OK`**，填平台编码会被判「下级域离线」。

另一条已落地决策：**`<CivilCode>` 取该节点自己的父节点编码**（与 `<ParentID>` 同源），仅无父的域根节点保留固定前缀 `340200`；组织节点也照发 CivilCode（不照抄 CSS 的省略做法）。

### 4.5 停报 / 取消同步的语义

- **取消同步（revoke）**：只把 `enabled` 置 0，本地数据一条不删，随时可恢复；组织侧按 `revokePolicy` 决定通道 `CASCADE`（一起删）或 `REROUTE`（改挂域节点继续上报）。
- **删除**：物理删除且不可恢复；组织删除前置条件是「无子组织、无绑定通道」。
- **删除必须先取消上报，确认后才动本地**（2026-11-03 改口径，取代此前「先删本地再事后推 DEL」）：设备 / 通道 / 组织三类主动删除一律两段式——`CascadeAssetCleanup.revokeDevice/revokeChannels/revokeOrg` 同步向上级发 `Event=DEL` 并**等该平台回 2xx**（`gateway.cascade.catalog.delete-confirm-timeout-sec`，默认 10s），确认通过才在同一编排里清 `tb_cascade_publish` 行与本地行。**任一平台没确认就整批不删**，抛 `IllegalArgumentException`（文案带平台编码与原因：拒绝响应码 / 无响应 / 发送失败），由 `GlobalExceptionHandler` 转成 `AjaxResult.error` 透到页面。不这么做，上级目录会残留节点、还能对着已不存在的资产发起点播。
  - 两条放行例外（写在契约 javadoc 里，别在调用方另判）：`PLATFORM_ABSENT`（发布行指向的平台配置已不存在，无处可撤）与 `sip.client.enabled=false` 导致出站 bean 缺失。
  - **被动路径不阻断**：设备自己上报 Catalog `DEL` 时走 `deleteDeviceChannelUpstreamDeleted`，只清本地 + 发事件通知，不等上级确认——设备台账已经没这路通道了，本端挡住就会与设备长期不一致。
  - **事务边界**：10s SIP 等待不能困在 DB 事务里（SQLite 是整库写锁），撤销必须在事务外发起，本地删除用 `TransactionTemplate` 收口。
  - 设备删除编排在 `DeviceRemovalManager`（撤销 → 发布层 → 本地通道 → 设备本体，顺序不可换），`DeviceController` 两个删除接口都走它，**前端不要再自己拼「先删通道再删设备」**。主动路径已同步发过 `DEL`，不能再让 `afterCommit` 事件链路发第二遍；只有被动路径仍按平台在提交后合并成一个 `DEL` 事件（拆开发会被 4 线程的 `sipNotifierExecutor` 乱序执行，父节点先没了子节点的删除会被部分上级忽略）。
- 快照是**增量 upsert**：节点「这次没出现」不会被上级删除，**必须显式推 `DEL`**。

### 4.6 本端身份只在 `tb_cascade_client`（1.10.5）

向上级注册的身份 = 20 位平台编码 + Contact 头对外通告 IP，**只存在客户端表**；
`tb_cascade_platform` 只剩可空 `client_ref_id` 外键，原来的 `local_client_id / local_ip / local_port` 三列已删。

- **读路径派生**：`CascadePlatformManager.fillDerived` 按 `client_ref_id` 填 `localClientId/localIp/localPort`
  （端口恒取 `sip.client.port`，不落库）。DTO 保留这三个字段只是为了让 integration 侧拼 From 头的代码零改动，
  **不能**再把它们当存储字段写回平台表。
- **写路径**：新增平台必须带 `clientRefId`（`Assert` 拦截）；改客户端的 `client_ip` 会
  `stopPlatform → startPlatform` 重建引用它的 `enabled=1` 平台任务。
- **删除有引用保护**：仍被平台引用时抛 `IllegalStateException`，Controller 转可读错误，不做级联删除。
- **编码创建后不可改**：换编码等于换 From 头身份，上级侧已建对象全部认不出来。
- 迁移脚本：MySQL 1.0.10→1.10.5 已合并为单支 `sql/migration/mysql/1.10.0-upgrade-from-1.0.9.sql`
  （只能对 1.0.9 基线库执行一次）；PostgreSQL / SQLite 仍按版本号分文件。老 SQLite 库由
  `SqliteCascadeClientSchemaMigrator` 就地补表 / 删列 / 回填。
- 菜单：页面 `605` + 按钮 `60501`–`60504`（三份初始化 SQL 同步）。
  **`604` 已被 1.0.13 的「级联运行状态」页占用**，新增菜单前先在三份 SQL 里 grep 主键确认空闲段。

---

## 5. 媒体 / 直播链路

- 码流服务器：ZLMediaKit（本机部署在独立目录，`config.ini` 与项目内 `config.ini` 需双源对齐）。
- 直播会话：`liveStart` 拉流 → 心跳/指标 SSE 回填 → `liveStop` 归还会话（引用计数）。
- 兼容性坑：H.265 在部分前端播放器（jessibuca/flv）会黑屏，需按编码策略降级；`doc/PLAYER-CODEC-STRATEGY-AND-PLAN.md` 有完整策略。
- 级联点播路径：INVITE → 回 200 OK → 等 ACK → 才启 `startSendRtp` 回推（ACK 监听挂在**请求**分派表，不是响应表）。

---

## 6. 前端（vben5）—— 在工作区外

**路径**：`D:\2026_git\shipin\voglander-vben-frontend-main\apps\web-antd\src`
**注意**：该目录不是 git 仓库，也**不在当前工作区内**，编辑工具不能直接写。

### 6.1 改前端的固定流程（staging + 字节覆盖）

```powershell
# 1) 把要改的文件复制到工作区内的中转目录（保留相对路径）
$src="D:\2026_git\shipin\voglander-vben-frontend-main\apps\web-antd\src"
$dst="D:\2026_git\voglander-master\.fe-staging"
Copy-Item (Join-Path $src "views\cascade\org\data.ts") (Join-Path $dst "views\cascade\org\data.ts") -Force
# 2) 用 SearchReplace/Write 改 staging 里的副本（UTF-8 无 BOM）
# 3) 改完复制回去
Copy-Item (Join-Path $dst "views\cascade\org\data.ts") (Join-Path $src "views\cascade\org\data.ts") -Force
```

> staging 里可能有**上一轮遗留的旧副本**，动手前先 Copy-Item 覆盖成最新，别在半年前的版本上改。
> `.fe-staging/` 目前**未加入 .gitignore**，提交后端时务必按路径显式 `git add`，不要 `git add -A`。

### 6.2 验证命令

```powershell
cd D:\2026_git\shipin\voglander-vben-frontend-main\apps\web-antd
npx vue-tsc --noEmit --skipLibCheck        # 类型检查，输出为空即通过

# 单测必须从仓库根跑，否则缺 jsdom 环境报 sessionStorage is not defined
cd D:\2026_git\shipin\voglander-vben-frontend-main
npx vitest run apps/web-antd/src/views/cascade
```

### 6.3 约定

- **前端仓库不由我 add/commit**（用户明确要求），只负责把文件改对。
- 中文文案改 `locales/langs/zh-CN/*.json` 必须同步 `en-US/*.json`，键名严格一致。
- 表格列用 `slots: { default: 'xxx' }` + 模板插槽，不要往列里塞 render 函数。
- 表单 schema 的 `componentProps` 里带联动要用 `dependencies.componentProps + triggerFields`。

---

## 7. 编码规范（照抄周边代码，不要自创风格）

- Java 17、`jakarta.*`、Lombok、**FastJSON2**（禁止 Jackson/Gson）。
- 四空格缩进；模型命名 `*DO` / `*DTO` / `*Req` / `*VO`；Controller 通过 Assembler 转换，**Manager 只暴露 DTO，绝不把 DO 抛到上层**。
- MyBatis-Plus 查询用**条件 lambda 链**（`qw.eq(cond, Field, val)`）。
- 时间：内部 `LocalDateTime`，Web 出参 Unix 毫秒（DTO 上有 `createTimeToEpochMilli()` 之类转换方法）。
- 多行 SQL/DDL 三套方言（MySQL / PostgreSQL / SQLite）要一起维护。
- **`AjaxResult.success(null)` 有重载陷阱**：存在 `success(String msg)`，`null` 会被解析成 `AjaxResult<Void>` 而编译报「不兼容的类型」。解法是先用声明好类型的局部变量接住再传。

---

## 8. 测试规范

- 命名 `*Test`；Controller/Service 用 JUnit5 + Mockito + `MockMvc`；Manager/Repository 用 `@SpringBootTest` 继承 `voglander-web/src/test/java/io/github/lunasaw/voglander/BaseTest`。
- 固定数据用 `UniqueKeyFactory.deviceId()/channelId()` 生成唯一键，**不要写死编码**（同库多次跑会撞）。
- `BaseTest` 带 `@Transactional`：验证 **afterCommit** 行为（例如事件是否真的发出、报文内容）必须另外用 `@Transactional(propagation = NOT_SUPPORTED)`，否则监听器永远不触发。
- 断言报文时**抓 content 断言内容**，不要只断言「调了哪个方法」。
- 涉及固定常量的全量否定断言容易误伤例外节点（如域节点本就该带 `340200`），改用**出现次数断言**。
- 已知 flaky：`CascadeMediaInviteListenerTest.reap_idleTimeout_stopsPush` 在整包跑时偶发失败，单独跑稳定通过——先单独复跑再判定是否回归。
- 已知**环境限制**（不是回归）：`LocalImageStorageServiceTest.keysOutsideNamespaceOrThroughSymlink_shouldBeRejected` 要建符号链接，Windows 非管理员进程报 `FileSystemException: 客户端没有所需的特权`，整个 reactor 会在 `voglander-integration` 就中断、`voglander-web` 根本跑不到。要拿 web 全量证据用 `-Dtest="!LocalImageStorageServiceTest"` 排除（配合 `-Dsurefire.failIfNoSpecifiedTests=false`）。

---

## 9. 已踩过的坑（速查）

| # | 现象 | 根因 / 规避 |
| --- | --- | --- |
| 1 | Lombok 全模块「找不到符号」 | JAVA_HOME 没指向 JDK17/21。机器上装到 jdk25 时同样全量报错（Lombok 注解处理失效，连 `log` 和 `@Data` 的 getter 都找不到），构建前先 `$env:JAVA_HOME='C:\soft\jdk17'` |
| 2 | `-pl voglander-web` 跑测试报 `NoClassDefFound` | 少了 `-am`，用了本地仓库陈旧 jar |
| 3 | PowerShell 里 mvn 参数报错 | 含点号/逗号参数整体加引号 |
| 4 | `mvn install` 失败 | gpg 签名，改用 `compile` / `test` |
| 5 | 页面显示「上报中」，上级却看不到 | 只看 `enabled`，要用 `effective`（见 4.2） |
| 6 | 海康「资源检索」0/0 | 先查 NOTIFY 是否 400 → 再查 SumNum → 再查数据面（组织 enabled、通道 `cascade_org_id`） |
| 7 | 通道点播上级超时 | `cascade_channel_id` / `local_device_id` 反查不到；查不到时后端静默 return |
| 8 | 组织改了父节点，物化路径失效 | `update` 禁止改父节点，移动必须走 `move()`（先删旧父再重推） |
| 9 | SQLite 运行库 `no such column` | 迁移器没写/没接进 `SqliteSchemaInitializer` |
| 10 | 前端编辑后不生效 | 改的是 staging 副本，忘了 Copy-Item 回前端目录；或 staging 里是旧副本 |
| 11 | 前端单测 `sessionStorage is not defined` | 在 app 目录跑 vitest 丢了 jsdom，改从仓库根跑 |
| 12 | 前端改完类型检查找不到错 | `vue-tsc` 输出为空才代表通过，别只看最后一行 |
| 13 | 级联报文修改后「没生效」 | 后端还跑着旧 class，必须重启 |
| 14 | 定时任务类新增后启动即报错/不触发 | 需补 `@TechnicalScheduler` 一类注解与架构测试期望表 |
| 15 | 改完数据列表依旧值（推送配置后「已推送平台」还是「未推送」） | 组织树在内存里只存一份，`proxyConfig.ajax.query` 读的就是它；抽屉提交成功回调必须先 `invalidateCascadeOrgTree()` 再 `query()`，toolbar 刷新按钮靠 query 收到 `PointerEvent` 才强制重取 |
| 16 | 删了设备/通道，上级目录里节点还在、页面还显示推送记录 | DEL 与本地删除必须两段式且「先撤销后删本地」（见 4.5）；撤销是同步出站，`DEL` 发了没人 `track` 就等于无法判定取消成功；主动路径不要再走 `afterCommit` 事件链路发第二遍 |
| 17 | 删除用例集体等满 10s 后报「上级未响应」 | `BaseTest`/`BaseAsyncTest` 启用了 `sip.client.enabled`，真会发 UDP；测试基座必须 `@Import` `CascadeRevokeTestConfig`，用 `@Primary` 的 `StubCascadeCatalogRevokeService` 放行，再按用例 `refuse/noResponse/sendFailed` 复现阻断 |
| 18 | 撤销逻辑把 SQLite 整库写锁占住 10s | 同步 SIP 等待困在事务里；撤销放事务外，本地删除用 `TransactionTemplate` |
| 19 | 页面删除失败只看到「操作失败」，看不出哪个平台没确认 | `deleteOne/deleteBatch` 里的 `catch (IllegalArgumentException) { throw e; }` 不能省，否则撤销原因会被洗成通用错误码；全局 `errorMessageResponseInterceptor` 已自动 `message.error` 后端 `message`，页面不要再重复弹一次 |
| 20 | 平台表又冒出 `local_*` 列 / 注册身份与页面不一致 | 本端身份只在 `tb_cascade_client`（见 4.6）；平台侧只存 `client_ref_id`，`localClientId/localIp/localPort` 是派生值 |
| 21 | 新菜单页「进不去」，按钮却挂在别的页面下 | 菜单 `id` 撞车：`INSERT OR IGNORE` 遇主键冲突整行被静默丢弃，按钮因 `id` 不撞而建出来。新增菜单前先在三份初始化 SQL 里 grep 目标 id；已发生过的两处：`604`（1.0.13 级联运行状态）、`605`（1.10.5 本端客户端） |
| 22 | 执行合并脚本报 `ERROR 1093` 或存储过程被拆碎 | `1.10.0-upgrade-from-1.0.9.sql` 含 `DELIMITER $$`，简单 SQL 切分器（JDBC script runner）会把过程体拆断，必须用支持 `DELIMITER` 的客户端；其中 `tb_menu` 同表子查询已改为自连接。整支脚本**只能对 1.0.9 基线库跑一次**（MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`） |
| 23 | 新增 RTSP/ONVIF 设备后「没有通道」，日志里也没有 `channel.discover` | 这类设备的通道不在平台侧生成，只能由 `POST /deviceChannel/discover/{deviceId}` 读设备管理面台账；而该按钮在通道页，通道页入口是设备列表里 `channelCount>0` 的钻取链接 → 0 通道时进不去，形成死锁。现已两头修：`DeviceController#insert` 对 type=3/4 异步触发一次发现（失败只 warn），前端 0 也渲染成可点链接。诊断时先 `curl --digest -u <user>:<pwd> http://<ip>/ISAPI/ContentMgmt/InputProxy/channels` 确认设备侧台账是否可读 |

---

## 10. 协作约定（用户偏好，务必遵守）

1. **先给方案与分步计划再动手**，小改动也一样；计划给出后按序连续执行，中间逐步汇报，不反复确认。
2. **每完成一个后端需求就 `git add` 指定路径 + `git commit`**，Conventional Commits（`fix(cascade): ...`），body 用中文说清「原来错在哪 / 现在怎么做 / 验证了什么」。**`git push` 必须用户明确要求**。
3. **前端仓库不提交**，只改文件。
4. 业务口径不确定时才问用户；能用代码事实判断的就自己定，并在汇报里写清依据。
5. 同一个问题连续三次尝试失败，就换思路或回来对齐，不要硬堆补丁。

---

## 11. 未闭环事项（2026-11-03）

- 「先撤销后删本地」的删除改造（设备 / 通道 / 组织）**后端未重启验证**；真实上级下的验证点：上级离线时删通道应报「上级未响应撤销请求，本地数据未删除」且刷新后通道还在。
- 上级不可达时删除会被挡住（严格阻断是用户定的口径）；批删跨设备只能前端逐条 `deleteOne`。
- `bde8bd4` 里的可见性校验与页面列**尚未重启后端验证**；验证点：停报上级组织后，在子组织下点通道「恢复上报」应报「级联组织【X】处于停报状态，请先恢复它」。
- 用户在更早一轮挂起的人工动作：向海康发一次 `DEL` 以重建通道 `34020000001320000007`（未执行）。
- 新增设备自动补通道（`3bb7471`）**未在运行实例上验证**：后端 PID 需重启后才生效，验证点见提交说明（新增 RTSP 设备 → 日志出 `[channel.discover] 同步完成` → 列表通道数由 0 变 N）。
- `CascadeMediaInviteListenerTest.reap_idleTimeout_stopsPush` 的偶发失败未定位（疑定时器抖动）。
- 1.10.5 本端客户端拆分（`29fe552`）**未做真机注册验证**：改 IP 会重建引用平台的注册任务，需对着真实上级确认
  「改一次客户端 IP → 所有引用它的平台自动用新地址重新 REGISTER」，以及页面菜单 605 登录后可见。
- 工作区两个 `test-app.db` 已手工改成菜单 605（若重建库则自动带）；线上 MySQL 需执行
  `sql/migration/mysql/1.10.0-upgrade-from-1.0.9.sql`，PostgreSQL 执行
  `sql/migration/postgresql/1.10.5-cascade-client.sql`。

---

## 12. 目录索引（找东西时先看这里）

| 想找 | 位置 |
| --- | --- |
| 架构总览 | `doc/architecture/current/ARCHITECTURE-OVERVIEW.md` |
| 开发流程（含 Check 关键点确认） | `doc/DEVELOPMENT_WORKFLOW.md` |
| 本地全栈启动清单 | `doc/LOCAL-STACK-RUNBOOK.md`、`doc/本地全栈服务启动清单.md` |
| 播放器编码策略 | `doc/PLAYER-CODEC-STRATEGY-AND-PLAN.md` |
| 级联全流程 / 组织树 / 媒体对齐 | `doc/1.0.11/CASCADE-*.md` |
| 级联代码（信令+组装） | `voglander-integration/src/main/java/io/github/lunasaw/voglander/intergration/wrapper/gb28181/cascade/` |
| 级联业务规则 | `voglander-manager/src/main/java/io/github/lunasaw/voglander/manager/manager/Cascade*` |
| 级联 HTTP 入口 | `voglander-web/src/main/java/io/github/lunasaw/voglander/web/api/cascade/` |
| 建表与迁移 SQL | `sql/`（三方言 + `sql/migration/{dialect}/`）；MySQL 升级只有 `1.10.0-upgrade-from-1.0.9.sql` 一支 |
| 前端级联页面 | `<前端>/apps/web-antd/src/views/cascade/`（api 在 `src/api/cascade/`） |
