# Voglander 本地全栈启动手册（Windows）

> 本文档记录本机当前这套「前后端 + 媒体面」的实际运行方式、每个服务的关键配置、验证命令与踩坑点。
> 所有端口/路径/账号均按本机实况核对过（2026-10-26 实测通过）。

---

## 0. 一张图看懂拓扑

```
                        浏览器
                          │
                          ▼
        ┌──────────────────────────────────┐
        │ vben 前端  vite dev  :5666        │  apps/web-antd
        │  /api/** ──proxy──► 8181 (剥掉 /api)│
        └──────────────┬───────────────────┘
                       ▼
        ┌──────────────────────────────────┐        ┌─────────────────────┐
        │ voglander  ApplicationWeb :8181  │◄──────►│ MySQL 3306 / Redis   │
        │  · REST /api/v1/**               │  JDBC  │ 192.168.118.20      │
        │  · Hook  /index/hook/**          │  /缓存 │ (库 voglander, db13) │
        │  · SSE   /api/v1/sse/**          │        └─────────────────────┘
        │  · SIP 服务端 :5060 (进程内)      │
        │  · SIP 客户端 :5061 (进程内)      │
        └──────────────┬───────────────────┘
                       │ Hook 回调 (ZLM → 8181)   REST 调用 (8181 → 8082)
                       ▼
        ┌──────────────────────────────────┐
        │ ZLMediaKit  MediaServer.exe       │
        │  REST :8082  RTSP :554            │
        │  RTMP :1935  HTTPS :443           │
        │  RTP  :10000 WebRTC :8000         │
        └──────────────────────────────────┘
```

要点：**SIP 不是独立进程**，它由 `sip-gateway` starter 在 voglander 的 JVM 内绑定 5060/5061，没有"SIP 服务要单独启动"这回事。

---

## 1. 端口与服务一览

| 端口 | 协议 | 承载进程 | 作用 | 必需 |
|---|---|---|---|---|
| 3306 | MySQL | `192.168.118.20`（远程） | 主库 `voglander` | ✅ |
| 6380 | Redis | `192.168.118.20`（远程） | 缓存，`database: 13`，无密码 | ✅ |
| 8181 | HTTP | `java`（voglander） | 业务 API、Hook 接收、SSE、静态 `index.html`、swagger | ✅ |
| 5060 | UDP SIP | voglander 同一 JVM | GB28181 服务端（设备注册上来） | ✅ |
| 5061 | UDP SIP | voglander 同一 JVM | GB28181 客户端（级联注册到上级） | ✅ |
| 5666 | HTTP | `node`（vite dev） | vben 前端开发服务器 | ✅ |
| 8082 | HTTP | `MediaServer.exe` | ZLM REST API | ✅ |
| 554 | RTSP | `MediaServer.exe` | RTSP 拉流/推流 | ✅ |
| 1935 | RTMP | `MediaServer.exe` | RTMP 推流 | ✅ |
| 443 | HTTPS | `MediaServer.exe` | HTTPS-FLV / WebRTC（依赖 `default.pem`） | ⚠️ 缺证书则不可用 |
| 10000 | UDP/TCP | `MediaServer.exe` | RTP 收流（`[rtp_proxy]`） | ✅ |
| 8000 | UDP | `MediaServer.exe` | WebRTC | ⚠️ 同上 |
| 8081 | HTTP | `java`（starter demo） | 仅自测 starter 用 | ❌ 可选 |

---

## 2. 启动顺序（很重要，顺序错会有"僵尸节点"）

```
① MySQL/Redis（远程，确认可达）
② voglander 8181   ← 必须早于 ZLM！
③ ZLMediaKit 8082
④ 前端 5666
⑤（可选）starter demo 8081
```

**为什么 voglander 必须先起**：ZLM 启动瞬间会回调 `on_server_started`，voglander 用这次回调把节点写进 `tb_media_node`（带 `host`、`secret`）。
如果 ZLM 先起、回调打空，之后只有每 10s 的 `on_server_keepalive`：
`VoglanderZlmHookServiceImpl.onServerKeepLive` 调 `saveOrUpdateNodeStatus(serverId, null, ts, null, null)`，此时节点不存在 → **会用 `host=null` 创建一行**；后续 `on_server_started` 命中"已存在"分支只更新 `status/keepalive`，`host` 永远是 null → 播放取地址必失败。
修复：`delete from tb_media_node where server_id='zlm-dev';` 然后重启 ZLM。

---

## 3. 逐服务详解

### 3.1 MySQL + Redis（外部依赖）

| 项 | 值 |
|---|---|
| MySQL | `192.168.118.20:3306`，库 `voglander`，`root/123456` |
| Redis | `192.168.118.20:6380`，无密码，`database: 13` |
| 配置文件 | `voglander-repository/src/main/resources/application-repo.yml` |

验证：

```powershell
Test-NetConnection 192.168.118.20 -Port 3306 -InformationLevel Quiet
Test-NetConnection 192.168.118.20 -Port 6380 -InformationLevel Quiet
# 本机无 mysql CLI，可用 python + pymysql
python -c "import pymysql;c=pymysql.connect(host='192.168.118.20',port=3306,user='root',password='123456',database='voglander',charset='utf8mb4');cur=c.cursor();cur.execute('select count(*) from tb_media_node');print(cur.fetchall());c.close()"
```

⚠️ `spring.cache.type: redis` 必须保留。缺了它 `sip-common` 的 `ConcurrentMapCacheManager` 会顶替成唯一 `CacheManager`，表现为 `Cannot find cache named 'device'`。

### 3.2 voglander（8181，核心后端）

| 项 | 值 |
|---|---|
| 启动类 | `io.github.lunasaw.voglander.web.ApplicationWeb` |
| profile | `prod,repo,inte`（`application.yml` 里默认已配） |
| 端口 | `application.yml → server.port: 8181` |
| 产物 | `voglander-web/target/voglander.jar`（Spring Boot fat jar，Build-Jdk 21，运行需 JDK17+） |

关键配置（三个文件，链路是 `-prod` 定义 `local.*` → `-inte` 映射成 starter 能用的一级属性）：

| 文件 | 键 | 值 | 说明 |
|---|---|---|---|
| `voglander-web/src/main/resources/application-prod.yml` | `local.zlm.*` | `enable/hook-enable=true, server-id=zlm-dev, host=127.0.0.1, port=8082, secret=zlm` | **ZLM 对齐的锚点** |
| 同上 | `local.sip.server.*` | `ip=127.0.0.1, port=5060, domain=34020000002000000001` | 设备注册目标 |
| 同上 | `local.sip.client.*` | `ip/domain=192.168.1.101, port=5061` | 级联注册身份 |
| 同上 | `local.gateway.gb28181.store.type` | `memory` | 单机用内存存 INVITE 上下文（改 redis 会抢 Redis 连接工厂） |
| 同上 | `voglander.protocol-lab.push.ffmpeg-path` | `/usr/bin/ffmpeg` | ⚠️ Windows 需改 `C:/soft/ffmpeg-7.0.2/bin/ffmpeg.exe` |
| `voglander-integration/src/main/resources/application-inte.yml` | `zlm.enable / zlm.hook-enable / zlm.servers[0].*` | 由 `${local.zlm.*}` 注入 | starter 的入口 |
| `voglander-repository/.../application-repo.yml` | 数据源 / redis | 见 3.1 | |

启动方式（任选其一）：

```powershell
# A. IDEA：Run Configuration → Application → Main class = ApplicationWeb（working dir 用项目根）
# B. fat jar（已构建过才有；注意 jar 内是打包时的 yml，改过配置需重新 install）
java -jar voglander-web\target\voglander.jar --spring.profiles.active=prod,repo,inte
# C. Maven（首次需先把各模块装进本地仓库）
mvn -s settings.xml -DskipTests install
mvn -s settings.xml -pl voglander-web spring-boot:run
#    ⚠ spring-boot:run 默认工作目录是【模块目录】voglander-web\，而 protocol-lab 的 media-file
#    是相对【项目根】的路径，必须显式指定工作目录，否则自环推流报“文件校验失败”：
#    mvn -s settings.xml -pl voglander-web spring-boot:run "-Dspring-boot.run.workingDirectory=D:\2026_git\voglander-master"
```

验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8181/api/v1/health
# => {"code":0,"data":{"status":"UP","components":{"db":{"status":"UP"},
#      "redis-A":{"status":"UP","ping_ms":5},"zlm":{"status":"UP","circuit_state":"CLOSED"}}}}
```

⚠️ `zlm` 字段只是 Resilience4j **熔断器状态**（`CLOSED`=未熔断），**不能**当作 ZLM 在线的判据；真正的判据是「8082 REST 可达 + `tb_media_node.status=1` + keepalive 持续变新」。

⚠️ JWT 拦截器只挂在 `/api/v1/**`（`ResourcesConfig`），所以 `/index/hook/**`、`/zlm/**`、`/index.html` 免鉴权——这是 ZLM Hook 能打通的前提。

### 3.3 ZLMediaKit（8082，媒体面）

| 项 | 值 |
|---|---|
| 运行时目录 | `D:\2026_git\shipin\ZLMediaKit`（`MediaServer.exe` + `config.ini` + `www` + `default.pem` + `log\console.log`） |
| 源码镜像 | `D:\2026_git\shipin\ZLMediaKit-src`（gitee `xia-chu/ZLMediaKit`，未编译） |
| 预编译包 | `D:\2026_git\shipin\zlm-bin-src\V*.windows\MediaServer.exe`（换版本直接覆盖运行时 exe） |
| 启动脚本 | `scripts\zlm-start.ps1`（幂等对齐配置 + 脱离 shell 启动 + REST/DB 自检） |

`config.ini` 与 voglander 必须严格对齐的 5 项：

| 段 | 键 | 值 | 对应 voglander |
|---|---|---|---|
| `[api]` | `secret` | `zlm` | `local.zlm.secret` |
| `[general]` | `mediaServerId` | `zlm-dev` | `local.zlm.server-id` / `tb_media_node.server_id` |
| `[http]` | `port` | `8082` | `local.zlm.port` |
| `[hook]` | `enable` | `1` | `local.zlm.hook-enable` |
| `[hook]` | `on_*`（16 条） | `http://127.0.0.1:8181/index/hook/on_*` | voglander 的地址 |

其他端口：`[rtsp] 554`、`[rtmp] 1935`、`[http] sslport 443`、`[rtp_proxy] 10000`、`[rtc] 8000`、`[ffmpeg] bin=C:/soft/ffmpeg-7.0.2/bin/ffmpeg.exe`。

启动：

```powershell
cd D:\2026_git\voglander-master
powershell -ExecutionPolicy Bypass -File .\scripts\zlm-start.ps1
# 可选参数：-Root <运行时目录> -HttpPort 8082 -Secret zlm -MediaServerId zlm-dev -BackendPort 8181
```

验证：

```powershell
Invoke-RestMethod "http://127.0.0.1:8082/index/api/getServerConfig?secret=zlm"
# data[0] 里应看到：general.mediaServerId=zlm-dev / http.port=8082 / hook.enable=1
#   hook.on_server_started=http://127.0.0.1:8181/index/hook/on_server_started
Get-Content D:\2026_git\shipin\ZLMediaKit\log\console.log -Tail 20
```

后端日志应出现（`%USERPROFILE%\logs\voglander\voglander-info.log`）：

```
onServerStarted::param = ...
处理服务器启动回调成功，节点ID: zlm-dev, 完整地址: http://127.0.0.1:8082
```

两个必须知道的坑：

1. **不要用 `Start-Process` 起 MediaServer**：工具/会话结束后子进程会被回收（进程消失、8082 不监听，但节点可能已被注册）。脚本用的是 `Invoke-CimMethod Win32_Process.Create` + `cmd /c ... > log\console.log`，真正脱离 shell。
2. **`[hook]` 只能指一个地址**：指 8181（voglander）就别再指 8081（starter demo），否则 voglander 收不到心跳，节点会变僵尸。
3. 仓库根目录和 `api\config.ini` 只是**模板**（80/9092/hook→8081），不是运行配置；ZLM 实际加载的是运行时目录那份。

### 3.4 前端 vben（5666）

| 项 | 值 |
|---|---|
| 目录 | `D:\2026_git\shipin\voglander-vben-frontend-main`（pnpm + turbo monorepo） |
| 应用 | `apps/web-antd` |
| 端口 | `apps/web-antd/.env.development → VITE_PORT=5666` |
| 后端地址 | `vite.config.ts → server.proxy['/api'].target = http://0.0.0.0:8181`，且 `rewrite` **剥掉 `/api` 前缀** |
| Node | `^22.18.0 \|\| ^24.12.0`，`pnpm@11.16.0` |

启动：

```powershell
cd D:\2026_git\shipin\voglander-vben-frontend-main
pnpm install          # 首次
pnpm dev              # turbo-run dev，起所有 app；或只起一个：
cd apps\web-antd; pnpm dev
```

验证：

```powershell
Invoke-WebRequest http://127.0.0.1:5666/ -UseBasicParsing | Select-Object -Expand StatusCode
# 注意"双层 /api"是正常的：requestClient 的 baseURL = VITE_GLOB_API_URL = /api，
# 而各 api 模块里路径本身已写成 /api/v1/xxx，浏览器最终请求 /api/api/v1/xxx，
# 代理剥掉一层 /api → 后端收到 /api/v1/xxx。
Invoke-RestMethod http://127.0.0.1:5666/api/api/v1/health   # ✅ code=0
Invoke-RestMethod http://127.0.0.1:5666/api/v1/health       # ❌ 500 "No static resource v1/health"
```

### 3.5 SIP（5060 / 5061，无独立进程）

- 由 `voglander-integration` 引入的 `sip-gateway` starter 在 voglander 启动时**同 JVM 内**绑定 UDP 5060（服务端）、5061（客户端）。
- 配置：`application-prod.yml → local.sip.server.* / local.sip.client.*`，再由 `application-inte.yml` 映射到 `sip.server.* / sip.client.*`。
- 验证：

```powershell
Get-NetUDPEndpoint -LocalPort 5060,5061 | Select-Object LocalAddress,LocalPort,OwningProcess
```

- 设备注册排查：日志里 `io.github.lunasaw.gbproxy` / `io.github.lunasaw.sip` / `javax.sip` 已开到 DEBUG（`application.yml`），看 `voglander-info.log`。

### 3.6 starter demo（8081，可选，只为自测）

| 项 | 值 |
|---|---|
| 坐标 | `io.github.lunasaw:zlm-spring-boot-starter-test:1.0.11` |
| 目录 | `D:\2026_git\shipin\zlm-spring-boot-starter-master\zlm-spring-boot-starter-test` |
| 启动类 | `io.github.lunasaw.zlm.ZlmApplicationTest` |
| 端口 | `application.yml → server.port: 8081`，profile `dev` |
| 配置 | `application-dev.yml → zlm.nodes[0].host = http://127.0.0.1:8082`、`secret=zlm` |

它是 starter 的**宿主壳**（main 下只有启动类 + `LocalZlmHookService` 两个类，真正的资产是 `src/test` 下 20 个测试类），作用等价于 voglander 里的 `voglander-web` 之于各模块。

启动（IDEA 或命令行）：

```powershell
cd D:\2026_git\shipin\zlm-spring-boot-starter-master\zlm-spring-boot-starter-test
$env:JAVA_HOME='C:\soft\jdk17'
C:\soft\apache-maven-3.6.3\bin\mvn.cmd -s D:\2026_git\voglander-master\settings.xml -B `
  org.springframework.boot:spring-boot-maven-plugin:3.5.3:run
```

> 不要用 `mvn spring-boot:run`：本模块 pom 里 `spring-boot-maven-plugin` 没写版本、也没有 spring-boot parent，靠前缀解析会拿错版本；直接写全坐标最稳。IDEA 里则直接 Run `ZlmApplicationTest`。

验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8081/zlm/api/version
# => {"code":0,"data":{"buildTime":"2024-08-22T19:41:17","branchName":"master","commitHash":"046aaa3"},"msg":"success"}
Invoke-RestMethod http://127.0.0.1:8081/zlm/api/statistic     # 线程/对象统计
Invoke-RestMethod http://127.0.0.1:8081/zlm/api/server/config # ZLM 全量配置
Invoke-RestMethod http://127.0.0.1:8081/zlm/api/api/list      # ZLM 支持的 API 清单
```

三点说明：

- `ZlmApiController` 的路由风格是 `/zlm/api/{action}`，**不带** `serverId` 参数——节点由 `LoadBalancer`（默认 `ROUND_ROBIN`）从 `zlm.nodes` 里选。
- demo 的 `ZlmHookController` 是 `@ConditionalOnProperty("zlm.hook-enable"="true")`，demo 配置里没有这一项 → **默认不注册 `/index/hook/**`**。
- 保持不开启：ZLM 的 hook 已经指给 8181，开了也没流量，只会误导排查。

---

## 4. 端到端验证（本次实测）

```powershell
# 1) 端口全通
foreach ($p in 8181,5666,8081,8082,554,1935,5060,5061) {
  "$p => " + [bool](Test-NetConnection 127.0.0.1 -Port $p -InformationLevel Quiet -WarningAction SilentlyContinue)
}
# 2) 后端健康
Invoke-RestMethod http://127.0.0.1:8181/api/v1/health
# 3) ZLM 配置基线
Invoke-RestMethod "http://127.0.0.1:8082/index/api/getServerConfig?secret=zlm"
# 4) 节点入库 + 心跳在动
python -c "import pymysql;c=pymysql.connect(host='192.168.118.20',port=3306,user='root',password='123456',database='voglander',charset='utf8mb4');cur=c.cursor();cur.execute('select server_id,host,secret,status,enabled,keepalive from tb_media_node');print(cur.fetchall());c.close()"
# 5) starter REST 通道
Invoke-RestMethod http://127.0.0.1:8081/zlm/api/version
# 6) 前端 → 后端 代理
Invoke-RestMethod http://127.0.0.1:5666/api/api/v1/health
```

期望结果：`tb_media_node` 恰好一行 `('zlm-dev','http://127.0.0.1:8082','zlm',1,1,<持续变新的毫秒时间戳>)`。

---

## 5. 端到端实录：从零到能播（协议验证台自环）

本机 `tb_device` 为空（无真实设备），用 `protocol-lab` 自环把「注册 → 目录 → INVITE → 推流 → ZLM 收流 → Hook → SSE → 播放」整条链路跑通。
**注意：`/auth/login` 没有 `/api/v1` 前缀**（`AuthController` 映射就是 `/auth`），其余业务接口都在 `/api/v1` 下。

```powershell
# ① 登录拿 token（本机 admin / admin123）
$tok = (Invoke-RestMethod 'http://127.0.0.1:8181/auth/login' -Method Post -ContentType 'application/json' `
        -Body '{"username":"admin","password":"admin123"}').data.accessToken
$H = @{ Authorization = "Bearer $tok" }

# ② 模拟设备注册（不带参数 = 自环注册到本机 127.0.0.1:5060）
Invoke-RestMethod 'http://127.0.0.1:8181/api/v1/lab/client/register' -Method Post -Headers $H `
  -ContentType 'application/json' -Body '{}'
# 结果：tb_device 出现 34020000001320000011（status=1，ip=127.0.0.1:5061）
#       tb_device_channel 出现 4 条：3402000000132000000{1..4}（Lab-ch1..4）
#       日志：设备上线 → DeviceInfo 查询 → Catalog 查询（4 通道）→ 设备信息更新

# ③ 开一个 SSE 订阅（放另一个窗口，用于观察事件）
#    GET /api/v1/stream/events?topics=live,device,alarm&token=<jwt>

# ④ 发起直播（15s 内设备必须开始推流，否则报 700001）
Invoke-RestMethod 'http://127.0.0.1:8181/api/v1/live/start' -Method Post -Headers $H -ContentType 'application/json' `
  -Body '{"deviceId":"34020000001320000011","channelId":"34020000001320000001","protocol":"FLV","streamMode":"UDP"}'
# 返回 code=0 / streamId=gb_live_<deviceId>_<channelId> / refCount=1 / playUrls 共 9 个（rtsp/rtmp/httpFlv/wsFlv/hls/httpTs/wsTs/httpFmp4/wsFmp4）
```

`live/start` 内部时序（实测日志）：

```
命令下发 gb28181.Invite.Play → 客户端收到 INVITE → Lab 回 200 OK → 平台 ACK
→ Lab 起 ffmpeg 推流 → ZLM onPublish(code=0) → on_stream_changed → SSE live.ready
→ ZLM 建 rtp 中继流 rtp/<streamId> → live/start 返回播放地址
```

**这里有个必踩的坑**：`push.auto=true` 会用配置里的 `ffmpeg-path` 自动推流，而 `application-prod.yml` 里写的是 `/usr/bin/ffmpeg`，在 Windows 上直接抛：

```
java.io.IOException: Cannot run program "/usr/bin/ffmpeg": CreateProcess error=2, 系统找不到指定的文件。
  at ...LabMediaPushService.startPushViaZlm(LabMediaPushService.java:255)
→ live/start 等 15s 后返回 {"code":700001,"msg":"直播拉流超时，设备未在规定时间内推流"}
```

两种修法：

- **推荐**：把 `voglander.protocol-lab.push.ffmpeg-path` 改成 `C:/soft/ffmpeg-7.0.2/bin/ffmpeg.exe` 后重启后端；
- **注意工作目录**：`media-file` 是相对【项目根】的路径（`voglander-web/src/main/resources/files/invite.mp4`）。用 IDEA（working dir=项目根）没问题；用 `mvn -pl voglander-web spring-boot:run` 时工作目录是模块目录，会报
  `文件校验失败 - 配置路径: voglander-web/src/... 当前工作目录: D:\...\voglander-web` → 启动时加 `-Dspring-boot.run.workingDirectory=D:\2026_git\voglander-master`。
- **不重启后端**：INVITE 完成后手动补推（`push/start` 支持参数覆盖）：

```powershell
# 必须先让 live/start 在后台跑着（它在等流就绪），再补推
Invoke-RestMethod 'http://127.0.0.1:8181/api/v1/lab/client/push/start' -Method Post -Headers $H -ContentType 'application/json' `
  -Body '{"ffmpegPath":"C:/soft/ffmpeg-7.0.2/bin/ffmpeg.exe","mediaFile":"voglander-web/src/main/resources/files/invite.mp4","zlmMode":true}'
# => state=RUNNING, mediaIp=127.0.0.1, mediaPort=31074, ssrc=200006867
#    cmd: ffmpeg -re -stream_loop -1 -i ... -c:v libx264 -f flv rtmp://127.0.0.1:1935/live/lab_push
Invoke-RestMethod 'http://127.0.0.1:8181/api/v1/lab/client/push/status' -Headers $H   # 看 lastLog 里的 ffmpeg 输出
```

播放验证（实测）：

```powershell
& C:\soft\ffmpeg-7.0.2\bin\ffprobe.exe -rtsp_transport tcp -show_entries stream=codec_name,width,height,r_frame_rate `
  -of default=noprint_wrappers=1 "rtsp://127.0.0.1:554/rtp/gb_live_34020000001320000011_34020000001320000001"
# codec_name=h264 / width=1280 / height=720 / r_frame_rate=30/1

curl.exe -s --max-time 3 -o flv.bin "http://127.0.0.1:8082/rtp/gb_live_..._01.live.flv"   # 3s ≈ 995KB
# hls.m3u8 正常返回 #EXTM3U + ts 分片
```

停止与回收（实测时间轴）：

```
12:49:59  live/stop → code=0,true；日志「直播引用计数归零，标记延迟回收」
12:50:58  LiveSessionGcService 命中 → BYE → 5 个 schema 的流下线回调 → ffmpeg 退出 → ZLM 流消失
          SSE: event:live.closed  data:{"streamId":"lab_push","reason":"stream_offline"}
               event:live.closed  data:{"streamId":"gb_live_...","reason":"idle_gc"}
```

> stop **不会立刻关流**：`refCount` 归零只打 `live:pending_close` 标记（`PENDING_CLOSE_SEC=90`），由 `LiveSessionGcService`（60s tick）执行真实 BYE。所以 stop 后几十秒内 ffmpeg 还在推、流还在，这是设计行为，不是泄漏。

SSE 实收事件（`/api/v1/stream/events`）：

| 事件 | 触发 | data 示例 |
|---|---|---|
| `live.ready` | 流上线（每个 schema 各一次，会重复） | `{"streamId":"gb_live_..._01"}` |
| `live.closed` | 流下线 / 空闲回收 | `{"streamId":"...","reason":"stream_offline"}` |
| `alarm.new` | 设备上报告警 | `{"deviceId":"34020000001320000011"}` |
| `device.keepalive` | 设备心跳 | `{"deviceId":"...","ts":1792990181389}` |
| `ping` | 连接保活 | 空 |

自环场景会看到两条**属预期**的噪音日志：

- `流上线/下线状态更新失败，未找到匹配的流代理记录 - app: rtp`：lab 是直接推 rtmp 到 ZLM，没有走平台的 stream-proxy 表，所以匹配不到记录；真实设备走 INVITE 收流时由平台建代理，不会出现。
- `dispatcherServlet threw exception ... IOException: 你的主机中的软件中止了一个已建立的连接`：SSE 客户端（curl/浏览器）断开时必然出现一次。

---

## 6. 故障速查

| 现象 | 根因 | 处理 |
|---|---|---|
| 前端播放/取流失败，`tb_media_node.host` 为 NULL | ZLM 先于 voglander 启动，节点被 keepalive 用空 host 建出来 | `delete from tb_media_node where server_id='zlm-dev';` 后重启 ZLM |
| `/api/v1/health` 里 `zlm` 不是 UP，但 8082 明明是通的 | 那是熔断器状态，不是在线状态 | 用 8082 REST + `tb_media_node` 判断 |
| ZLM 启动后进程立刻消失，8082 不监听 | 用 `Start-Process` 起的子进程被回收 | 用 `scripts\zlm-start.ps1`（Win32_Process.Create + 日志重定向） |
| `.ps1` 报一堆"数组索引表达式丢失"、"缺少终止符" | Windows PowerShell 5.1 读无 BOM 的 UTF-8 中文脚本 | 脚本保持纯 ASCII，或保存为 UTF-8 **带 BOM** |
| `config.ini` 改完中文乱码、`port=` 行丢失 | `Get-Content -Raw` 默认按 ANSI 解码 UTF-8 | 用 `[IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)` + `WriteAllText(...,UTF8Encoding($false))` |
| starter demo `/zlm/api/version` 返回 500 `Connect to http://127.0.0.1:9092 failed` | `zlm.nodes[0].host` 还是模板里的 9092 | 改成 `http://127.0.0.1:8082`；改完确认 `src` 与 `target/classes` 都是新值（IDEA 打开着旧缓冲区时可能覆盖回去） |
| 浏览器请求 `/api/v1/health` 报 `No static resource v1/health` | 漏了 baseURL 的 `/api` 层 | 直接用 `/api/api/v1/health`，或在前端里按 `requestClient` 的方式调用 |
| 协议验证台 INVITE 能收到但 `live/start` 报 `700001 直播拉流超时` | `push.ffmpeg-path` 是 `/usr/bin/ffmpeg`，日志里是 `Cannot run program "/usr/bin/ffmpeg"` | 改 `C:/soft/ffmpeg-7.0.2/bin/ffmpeg.exe` 重启；或手动 `lab/client/push/start` 传 `ffmpegPath` |
| `live/start` 报 `文件校验失败 - 配置路径: voglander-web/src/... 当前工作目录: ...\voglander-web` | `media-file` 相对项目根，而 `mvn -pl voglander-web spring-boot:run` 的工作目录是模块目录 | 启动加 `-Dspring-boot.run.workingDirectory=D:\2026_git\voglander-master`，或把 `media-file` 改绝对路径 |
| `POST /api/v1/auth/login` 报 `No static resource api/v1/auth/login` | `AuthController` 映射是 `/auth`，**没有** `/api/v1` 前缀 | 直接打 `/auth/login`（前端经代理时是 `/api/auth/login`） |
| `live/stop` 返回成功但 ffmpeg 还在跑、流还在 | 延迟回收：`refCount` 归零只打标记，90s 内由 GC 执行 BYE | 等 60~90s，或直接 `Stop-Process -Name ffmpeg` 强制断流 |
| SSE 订阅断开后后端报 `IOException: 主机中的软件中止了一个已建立的连接` | SSE 长连接被客户端关闭 | 属预期噪音，可忽略 |
| 登录提示密码错误 | `tb_user` 里种子账号哈希可能是占位串 | 用 `PasswordUtils` 生成真实哈希更新该行 |
| 级联/网关侧缓存报 `Cannot find cache named 'device'` | `spring.cache.type: redis` 丢了，或 `gateway.gb28181.store.type=redis` 抢了连接工厂 | 恢复 `cache.type: redis`；单机保持 `store.type: memory` |

---

## 7. 目录与关键代码索引

```
D:\2026_git\
├── voglander-master\                     # 后端仓库（唯一启动入口 voglander-web）
│   ├── scripts\zlm-start.ps1             # ZLM 启动 + 自检脚本
│   ├── voglander-web\                    # HTTP/SSE 入口、配置文件、voglander.jar
│   ├── voglander-integration\            # ZLM/SIP 适配、VoglanderZlmHookServiceImpl
│   ├── voglander-manager\                # MediaNodeManager（节点 upsert）
│   └── doc\LOCAL-STACK-RUNBOOK.md        # 本文档
└── shipin\
    ├── voglander-vben-frontend-main\     # 前端（apps/web-antd, 5666）
    ├── ZLMediaKit\                       # ZLM 运行时（exe+config.ini+www+default.pem）
    ├── ZLMediaKit-src\                   # ZLM 源码镜像（未编译）
    ├── zlm-bin-src\                      # ZLM 预编译包（多版本 exe）
    ├── zlm-spring-boot-starter-master\   # starter 库 + demo(8081)
    └── Sip-Proxy-master\                 # SIP 代理（本套环境未使用）
```

学习链路时建议的阅读顺序：

1. `ZlmHookController`（starter）→ 看清 ZLM 回调 16 个端点
2. `VoglanderZlmHookServiceImpl`（voglander-integration）→ `onServerStarted` 如何拼 `host`、如何 upsert 节点
3. `MediaNodeManager.saveOrUpdateNodeStatus` → 为什么"已存在只更新状态"会让 NULL host 永久化
4. `VoglanderNodeSupplier` → 节点从 DB 来，不是从 yml 来（`zlm.nodes` 配了也不生效）
5. `MediaPlayServiceImpl` → 播放地址依赖 `tb_media_node.host/secret`
6. `ResourcesConfig` → 为什么 Hook 端点不需要 JWT
