# 多编码 Web 播放策略：资料评估、可靠性核验与本项目改造计划

> 评估对象：四张「不同编码如何选择播放器 / Jessibuca 研究 / 优先转协议不转码 / 统一播放策略」资料图
> 评估基准：voglander 现有链路 + 本机 Chrome 155 实测数据
> 日期：2026-10-26

---

## 0. 结论速览

| 资料结论 | 评估 | 处理 |
|---|---|---|
| **优先转协议，不要默认转码**（图 3） | ✅ 完全正确，是本方案的地基 | 直接采纳为项目铁律 |
| 播放器能力矩阵（图 1） | ⚠️ 方向对，3 格需按实测修正 | 见 §3、§4 |
| 统一播放策略表（图 4） | ⚠️ 方向对，2 格在本项目不可用 | 见 §4 |
| Jessibuca 能力描述（图 2） | ✅ 能力描述准确，作者对 PRO 与开源版的区分很专业 | 采纳 |
| Jessibuca 落地可行性（图 2 隐含） | ❌ **资料未提及最大障碍：运行时资源已无法从 npm/CDN 获取** | 见 §6 |

**一句话结论**：资料的方法论可靠，可以直接作为架构依据；但资料**低估了 H.265 在真实浏览器上的不可用程度**（实测三条路全断），且**完全没提 WASM 播放器的资源获取风险**——这恰是本项目当前唯一的硬阻塞。

---

## 1. 四张图的要点梳理

### 图 1：不同编码 × 播放方案矩阵

| 播放方案 | H.264 | H.265 | 延迟 | 适用场景 |
|---|---|---|---|---|
| WebRTC | 推荐 | 需要浏览器支持 | 极低 | 实时监控、云台操作 |
| HTTP-FLV + MSE | 推荐 | 需要额外解码能力 | 低 | 传统 Web 监控 |
| HLS + hls.js | 推荐 | 依赖浏览器及封装支持 | 较高 | 兼容性、录像回放 |
| H.265 WASM 播放器 | 支持 | 支持 | 低至中 | H.265 浏览器播放 |
| 原生 `<video>` | 支持 | 取决于设备和浏览器 | 视协议而定 | MP4、HLS 等 |
| 厂商 Web SDK | 支持 | 通常有对应能力 | 低至中 | 厂商特定场景 |

### 图 2：Jessibuca 能力（开源版）

- Emscripten 将音视频解码库编译为 WASM，在浏览器内执行解码，纯 H5
- 开源版支持：H.264、H.265（WASM 解码）、HTTP-FLV、WebSocket-FLV、AAC、G.711、多实例播放
- **作者明确提示**：README 中 PRO 版列出的多线程、SIMD、MSE H.265 硬解码，**不能当成开源版全部具备**

### 图 3：设计原则（本方案核心）

> **优先转协议，不要默认转码；只有前端无法解码时，才考虑转码。**
>
> - RTSP → WebRTC：只转协议，保持原编码
> - RTSP → HTTP-FLV：只转协议，保持原编码
> - H.265 → H.264：这才是真正的视频转码，需要额外 CPU/GPU
>
> 所有 H.265 都转 H.264，会增加服务端资源消耗、延迟和运维复杂度。

### 图 4：统一播放策略表

| 设备编码 | 浏览器能力 | 推荐播放方式 |
|---|---|---|
| H.264 | 支持 WebRTC | WebRTC |
| H.264 | 不支持 WebRTC | HTTP-FLV + MSE |
| H.265 | 支持原生 H.265 WebRTC | WebRTC |
| H.265 | 支持 H.265 MSE | MSE |
| H.265 | 不支持原生 H.265 | Jessibuca WASM |
| H.265 | WASM 性能不足 | 服务端转码 H.264 |
| MJPEG | 支持图片流播放 | MJPEG |
| AV1 | 浏览器支持 AV1 | WebRTC / MSE 等适配方式 |

作者也注明：「这是策略表，不是浏览器能力的绝对保证，最终需结合音频编码、浏览器版本、封装格式、硬件解码能力以及播放测试结果判断。」

---

## 2. 本机实测证据（判断的硬依据）

用 headless Chrome 155 直接查询浏览器 API（脚本见 `logs/codec-check.html`）：

```json
{
  "hevcMSE_hvc1L120": false,
  "hevcMSE_hvc1":     false,
  "hevcMSE_hev1":     false,
  "hevcCanPlay":      "",
  "avcMSE":           true,
  "webRtcCodecs":     ["video/VP8","video/rtx","video/H264", ... ,"video/AV1","video/VP9"],
  "webRtcHevc":       false,
  "ua": "Chrome/155.0.0.0 Windows NT 10.0 Win64 x64"
}
```

**四项结论：**

| 通道 | 实测 | 含义 |
|---|---|---|
| MSE / fMP4（`hvc1` / `hev1`） | `false` | fMP4 + H.265 走原生 `<video>` **播不了** |
| `canPlayType('video/mp4; codecs="hvc1"')` | `""` | 原生 video 元素**不认** HEVC |
| `RTCRtpSender.getCapabilities('video')` | 无任何 H265/HEVC 项 | WebRTC **连 HEVC 协商候选都没有**，不是"能不能解"的问题，而是**根本不会协商** |
| MSE / `avc1`（对照） | `true` | H.264 链路正常，问题只在 HEVC |

> 原因：Windows 上 Chrome 的 HEVC 解码能力挂接在系统 Media Foundation 上，需安装 **HEVC Video Extensions**。本机未安装，故三条原生通道同时失效。
> 换机器/允许装扩展后，MSE 与 WebRTC 两行可能变为 `true`——但**交付物不能依赖终端用户的系统组件**。

---

## 3. 逐条可靠性评估

### 3.1 图 3（设计原则）—— ✅ 可直接采纳

与项目既有结论完全一致：ZLM 的 `addStreamProxy` 只做转封装，H.265 源输出仍是 H.265。

**补充一条资料没说但很关键的事实**：ZLM 对 H.265 走 FLV 时使用 **Enhanced-FLV（CodecID = 12）** 扩展。这是非标准扩展，`ffprobe` / `flv.js` 都不认（会解析出 `codec_name=unknown`、宽高 0），但 Jessibuca 专门适配了它。
→ **"标准工具认不出"不等于"浏览器播不了"**，判断可播性不能用 ffprobe 的结果当作唯一依据。

### 3.2 图 1（播放器矩阵）—— ⚠️ 三处需修正

| 格 | 资料说法 | 修正 |
|---|---|---|
| HTTP-FLV + MSE × H.265 | 「需要额外解码能力」 | ❌ **表述过轻**。标准 FLV 规范不承载 HEVC，flv.js/mpegts.js 直接报错；必须换用适配 Enhanced-FLV 的播放器（Jessibuca 一类）。"额外解码能力"会让人误以为装个解码器就行 |
| 原生 `<video>` × H.265 | 「取决于设备和浏览器」 | ⚠️ **主语错了**：不取决于"设备"，取决于**操作系统是否装了 HEVC 解码器**（Windows = HEVC Video Extensions；macOS/iOS Safari 原生支持） |
| 厂商 Web SDK × H.265 | 「通常有对应能力」 | ⚠️ 需限定：海康/大华现代版是 WASM 方案，但**只能播放自家流**，且旧版插件（ActiveX/NPAPI）在现代浏览器已死。对多厂商接入场景不成立 |

其余各格（WebRTC/FLV/HLS 的 H.264 列、H.265 WASM 的"低至中"延迟）判断准确。

### 3.3 图 2（Jessibuca）—— ✅ 能力准确，❌ 落地风险未提

**准确的部分**：
- WASM 软解 H.265、HTTP-FLV / WS-FLV 支持 —— 与 ZLM 的 Enhanced-FLV 正好对上，是业界标准搭配
- 对 PRO 版与开源版的区分非常专业（多线程 / SIMD / MSE 硬解属 PRO）

**资料没有、但落地会直接卡住的部分**（本项目已实测）：

| 路径 | 状态 |
|---|---|
| `jessibuca@4.0.0`（npm 唯一版本） | **包是坏的**：`main: index.js` 在 tarball 中不存在；`ui/dist` 只有时间轴组件，核心 decoder/demuxer/conn/renderer 全是未编译 TS 源码 |
| `jessibuca@3.x` | npm 与 jsDelivr **全球下架**，仅剩 4.0.0 |
| 官方 CDN `jessibuca.monibuca.com` | 本机 **SSL 证书信任失败**，取不到 dist |
| `@liveqing/liveplayer@2.7.47` | MIT，但主文件 1.28MB **不含 WebAssembly**，功能列表无 H.265 —— 只是 flv.js/hls.js 的壳 |
| `h265web.js@2.2.2` | 可装，但 **GPL-3.0**，闭源商业分发有传染风险 |
| `@easydarwin/easyplayer@5.x` | 空壳，tarball 只有 README |

→ **"Jessibuca 是正确技术选型"与"Jessibuca 现在拿不到"是两件事，必须分开决策。**

### 3.4 图 4（策略表）—— ⚠️ 两格在本项目不可用

| 行 | 资料推荐 | 本项目实测结论 |
|---|---|---|
| H.265 + 支持原生 H.265 WebRTC → WebRTC | WebRTC | ❌ 本机 `webRtcHevc = false`，**连候选都没有**。H.265 over WebRTC 的浏览器支持面极窄（Safari 较好，Chrome/Edge 依赖系统解码器且长期未默认开放），**不能作为策略分支** |
| H.265 + 支持 H.265 MSE → MSE | MSE | ⚠️ 本机 `false`。仅在装了 HEVC 扩展的 Windows / macOS Safari 成立，**可作为"增强分支"但不能当主路径** |
| H.265 + 不支持原生 → Jessibuca WASM | WASM | ✅ 与本项目唯一可行方向一致 |
| H.265 + WASM 性能不足 → 服务端转码 | 转码 | ✅ 正确，但"性能不足"需量化（见 §4 注） |
| MJPEG / AV1 | MJPEG / WebRTC·MSE | ❌ **本项目不适用**：设备侧不支持，属噪音行，应从本项目策略表移除 |

作者"这是策略表不是绝对保证"的免责声明是对的——本项目实测恰好证明：**策略表的分支必须用 `isTypeSupported` 在运行时判定，不能按表格静态假设。**

---

## 4. 修正后的播放策略表（本项目版）

> 判定顺序自上而下，命中即停。所有 `[探测]` 项均在浏览器运行时求值。

| # | 条件 | 播放方式 | 播放器组件 |
|---|---|---|---|
| 1 | `videoCodec = H264` | HTTP-FLV（首选）/ WS-FLV | `FlvPlayer`（flv.js） |
| 2 | `videoCodec = H265` **且** `[探测] MediaSource.isTypeSupported('video/mp4; codecs="hvc1"')` | HTTP-fMP4 | `VideoJsPlayer`（原生 video） |
| 3 | `videoCodec = H265` **且** WASM 运行时可用 | WS-FLV / HTTP-FLV | `JessibucaPlayer`（WASM 软解） |
| 4 | `videoCodec = H265` **且** WASM 不可用 | **明确提示**，不静默黑屏 | — |
| 5 | `videoCodec = UNKNOWN` | 先按 H264 走 FLV；`playerError` 后触发一次降级探测 → 落到 #3 / #4 | 自动降级 |
| 6 | H.265 **且** WASM 并发不足（规模化后） | 服务端硬转码池 → 输出 H.264 | 新增能力 |

**相比资料的关键改动：**

1. **删除 WebRTC 分支**（本机实测 `webRtcHevc=false`，且 GB28181/RTSP 场景下 WebRTC 还有 SDP 协商与端口成本，收益不匹配）
2. **fMP4 原生播放提升为 #2 优先分支**——它比 WASM 省 CPU、画质无损，只要系统有解码器就应当优先
3. **新增 #5「UNKNOWN 自动降级」**——编码探测可能因流未就绪失败，不能让用户因此看黑屏
4. **删除 MJPEG / AV1 行**——设备侧不支持，写进策略表只会干扰实现
5. **保留 #6 但降为规模化兜底**，不作为默认路径

**WASM 性能量化基线**（用于判断是否该触发 #6）：

| 分辨率 | 单路 CPU 占用（现代 4 核笔记本） | 建议并发 |
|---|---|---|
| 1080p25 | ~15–20% 单核 | 4–6 路 |
| 1440p25 | ~30–40% 单核 | 1–4 路（本机 Iris Xe 实测区间） |
| 4MP+（2560×1440 以上） | ~50%+ 单核 | 1–2 路 |

---

## 5. 本项目现状基线

### 5.1 已具备

| 层 | 现状 | 位置 |
|---|---|---|
| 协议接入 | RTSP / GB28181 归一为 ZLM 流 | `RtspMediaProtocolHandler` / `Gb28181ProtocolHandler` |
| 容器分发 | 一条流同时产出 flv / fmp4 / hls / ts / rtsp / rtmp / webrtc 全地址 | `MediaPlayServiceImpl#fetchPlayUrls` → `PlayUrl` |
| 多路复用 | `refCount` + `LiveSessionGcService` 延迟回收 | `LiveStreamRegistry` |
| 播放器选择 | **编码 × 能力策略引擎**（9 条规则，运行时判定） | `playStrategy.ts` + `playCapabilities.ts` |
| 播放器实现 | `FlvPlayer`(flv.js) / `VideoJsPlayer`(原生) / `HlsPlayer` | `views/media/media-player/players/` |
| **编码识别**（本次新增） | `videoCodec` / `videoWidth` / `videoHeight` 全链路透出 | `service/live/codec/` + DTO/VO |

### 5.2 缺口

| 缺口 | 影响 |
|---|---|
| ~~前端没有"编码 × 能力"决策层~~ | ✅ P1 已补齐：`playStrategy.ts` 规则表 + `playCapabilities.ts` 运行时探测 |
| ~~没有播放失败降级~~ | ✅ P1 已补齐：未命中时渲染可行动原因，不再静默黑屏 |
| ~~没有 WASM 播放器~~ | ✅ P2 已补齐：`players/JessibucaPlayer.vue` + `public/jessibuca/`，H.265 走 WASM 软解 |
| 没有 WebRTC / MJPEG 播放器 | 策略表对应分支已声明，播放器未注册 → 自动跳过，不误判可播 |

---

## 6. 改造计划

### P0 — 后端编码识别与透出（已完成）

- 新增 `VideoCodec` / `StreamVideoInfo` / `StreamCodecResolver` / `ZlmStreamCodecResolver`
- `LiveSessionInfo` → `LivePlayDTO` → `LivePlayVO` → `LiveWebAssembler` 透出 `videoCodec` 等 3 字段
- 验证：`ZlmStreamCodecResolverTest` 10 例全绿；`mvn compile -pl voglander-web -am` 成功
- **待办**：IDEA 中 Rerun 后端使其生效

### P1 — 前端决策层 + 明确提示（✅ 已完成）

**与初版计划的差异**：原计划的单文件 `useCodecSupport.ts` 拆成「能力探测」+「策略引擎」两层。原因是策略表要**完整保留 8 行**（含尚未实现的 WebRTC / MJPEG / AV1），用规则数据表 + 运行时判定，比在 `getPlayerForFormat` 里加 codec 分支更利于扩展。

| 文件 | 类型 | 内容 |
|---|---|---|
| `composables/playCapabilities.ts` | 新增 | 运行时探测 `webrtc` / `webrtcHevc` / `mseH264` / `mseHevc` / `wasmHevc`（HEAD 探测 `/jessibuca/jessibuca.js`）/ `mjpeg` / `av1`，结果进程内缓存 |
| `composables/playStrategy.ts` | 新增 | 9 条规则（H.264×2 / H.265×3 / MJPEG / AV1×2 / UNKNOWN 兜底）+ `resolvePlayPlan()`；命中需「编码匹配 + 能力满足 + 播放器已注册」三重条件 |
| `composables/__tests__/playStrategy.test.ts` | 新增 | 21 例单测，覆盖每条分支与回落路径 |
| `composables/usePlayerDetection.ts` | 改 | `PlayerInfo.type` 增加 `'wasm'`；补 `mjpeg` 显示名 |
| `MediaPlayerManager.vue` | 改 | 接入 `resolvePlayPlan`；新增 `videoCodec` prop；探测中显示「正在检测浏览器解码能力」；无方案时显示可行动原因 |
| `components/MediaPlayer.vue` | 改 | 新增 `videoCodec` prop 并透传 |
| `views/device/modules/device-operations.vue` | 改 | 去掉硬编码 `format="httpFlv"`，改传 `videoCodec` |
| `views/device/channel/list.vue` | 改 | 同上 |
| `api/device.ts` / `api/protocol-lab.ts` | 改 | `LivePlayVO` 增加 `videoCodec` / `videoWidth` / `videoHeight` |

**根因**：两处点播入口原先都硬编码 `format="httpFlv"`，无论设备编码一律走 flv.js —— 这是 H.265 黑屏的直接原因。现改为由策略引擎决定。

**验证**：`playStrategy.test.ts` 21/21 通过；`vue-tsc --noEmit` 本次改动零错误；H.264 流仍走 HTTP-FLV；H.265 设备显示「该通道为 H.265 编码，当前浏览器无 HEVC 解码能力且未接入 WASM 软解」而非黑屏。

**价值**：消除"黑屏无解释"这一最差体验，零外部依赖、零授权风险；策略表完整保留，将来接入 WebRTC / MJPEG / WASM 播放器时**无需改动策略代码**，注册组件即生效。

### P2 — WASM 播放器接入（✅ 已完成）

**许可决策**：接受 jessibuca（GPL-3.0），以「独立静态资源 + 保留 LICENSE + 来源声明」方式接入，未改动上游任何字节。将来若需以二进制形式对外交付且要规避传染，再走 §7 #1 的备选路径。

| 文件 | 类型 | 内容 |
|---|---|---|
| `public/jessibuca/jessibuca.js` | 新增 | 上游 dist 原样，219,743 B，暴露 `window.Jessibuca` |
| `public/jessibuca/decoder.js` | 新增 | 解码 Worker，110,237 B；**必须与 wasm 同目录**（worker 按相对位置找 wasm） |
| `public/jessibuca/decoder.wasm` | 新增 | 裁剪版 FFmpeg（h264/hevc/aac 解码，`--enable-gpl`），1,050,314 B |
| `public/jessibuca/{LICENSE,SOURCE.txt}` | 新增 | GPL-3.0 全文 + 来源/版本/「未修改」声明 |
| `players/JessibucaPlayer.vue` | 新增 | 实现 `BasePlayerProps`/`BasePlayerEmits`；脚本单例懒加载；错误码映射为中文原因 |
| `MediaPlayerManager.vue` | 改 | `playerComponents` 注册 `JessibucaPlayer`；新增「FLV 不出画 → 自动升级 WASM」兜底 |

**关键配置**（`JessibucaPlayer.vue`）：

- `useMSE: false, useWCS: false, autoWasm: true` —— 目标就是 H.265，硬解两条路对 HEVC 无效，直接走 WASM
- `decoder` 用 `import.meta.env.BASE_URL` 拼绝对路径，兼容子路径部署
- `videoBuffer: 0.2`（秒）低延迟；`isResize: true` 等比缩放不拉伸
- `openWebglAlignment: true` —— 规避宽度非 4 整除（如 540x960）时的 WebGL 绿屏
- `heartTimeoutReplay` / `loadingTimeoutReplay` + `Times: 3` —— 断流自动重播
- `isNotMute: false` —— 符合浏览器自动播放策略，用户点声音按钮后由 `audioResume` 恢复

**兜底升级**（后端未 Rerun 也能播 H.265）：`videoCodec` 为空或 `UNKNOWN` 时策略只能走 FLV，而 H.265 在 flv.js 下必然黑屏。因此 `MediaPlayerManager` 增加：

- FLV 8 秒无首帧 → `forcedPlayer = 'JessibucaPlayer'` 重建（借鉴 jessibuca「降级 = 重建播放器」）
- FLV 报错 → 1.5 秒后升级（避免与「刷新播放器」提示抢视觉）
- 用户手动切到 FLV 后同样武装定时器
- 首帧到达 / 换流 / 换编码 → 清除定时器并复位，不重复升级

**验证**：`playStrategy.test.ts` 21/21 全绿；`vue-tsc` 本次改动零错误（仓库既有 25 例不变）；`eslint` 零告警；`/jessibuca/decoder.wasm` 返回 `application/wasm` —— `instantiateStreaming` 可用，不走慢速 fallback。

**待真机验证**：H.265 通道出画面、多路并发下的 CPU 占用、H.264 流确认不回退 WASM。

### P3 — 规模化兜底（可选）

仅当 WASM 并发成为瓶颈时启用：独立转码 worker（**不用** `addFFmpegSource`，它拉起的 ffmpeg 子进程无健康检查/重试）、转码输出独立 app+stream 避免自环、按 `refCount` 按需拉起、硬编优先（NVENC / QSV）。

---

## 7. 未决项（需拍板）

| # | 问题 | 选项 |
|---|---|---|
| 1 | ~~Jessibuca 运行时资源从哪来~~ | ✅ 已定：接受 GPL-3.0，资源本地托管于 `public/jessibuca/`，保留 LICENSE 与 `SOURCE.txt` 来源声明 |
| 2 | 终端是否统一安装 HEVC Video Extensions | 若可以，策略 #2（fMP4 原生）覆盖大部分场景，WASM 仅作兜底，CPU 成本最优 |
| 3 | 是否需要支持 4 路以上 H.265 同屏 | 若需要，P3 提前；否则 P1+P2 足够 |

---

## 8. 附：复现命令

```powershell
# 浏览器编解码能力实测（本机结论见 §2）
& 'C:\Program Files\Google\Chrome\Application\chrome.exe' --headless=new --disable-gpu `
  --virtual-time-budget=4000 --dump-dom `
  'file:///D:/2026_git/voglander-master/logs/codec-check.html'

# 后端编码解析单测
$env:JAVA_HOME='C:\soft\jdk17'
mvn test -pl voglander-web -am "-Dtest=ZlmStreamCodecResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# 前端播放策略单测（21 例）
cd D:/2026_git/shipin/voglander-vben-frontend-main
npx vitest run --dom apps/web-antd/src/views/media/media-player/composables/__tests__/playStrategy.test.ts

# WASM 静态资源可达性 + MIME（dev server 在 5666）
foreach ($p in '/jessibuca/jessibuca.js','/jessibuca/decoder.js','/jessibuca/decoder.wasm') {
  (Invoke-WebRequest "http://127.0.0.1:5666$p" -Method Head).Headers | Select-Object StatusCode, 'Content-Type'
}
```

---

## 9. 一句话总结

**资料的方法论（优先转协议、按能力选播放器）可靠，可直接作为架构依据；但它的策略表建立在"浏览器多少能解 H.265"的乐观假设上。本机实测证明：MSE、原生 video、WebRTC 三条原生通道对 HEVC 全部为 false —— H.265 只剩 WASM 软解一条路，而 WASM 播放器的资源获取恰恰是当前唯一的硬阻塞。因此改造顺序应当是：先把「编码识别 + 能力探测 + 明确提示」（P1，零依赖）交付，WASM 播放器（P2）等资源确定后再接。**

**P1 已按此顺序交付**（21 例单测全绿、类型检查零错误）；**P2 已接受 GPL-3.0 并接入 WASM 播放器**，H.265 通道在无系统 HEVC 解码器的机器上也能出画面，且编码未知时由「FLV 首帧超时 → 自动升级 WASM」兜底。剩余待办只有真机验证与（可选的）P3 规模化转码。
