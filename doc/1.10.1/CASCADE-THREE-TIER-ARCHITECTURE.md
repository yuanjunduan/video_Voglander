# 级联架构重构设计 v2：本地资产 + 发布层

> 状态：**设计梳理待确认**（未动代码）
> 版本基线：HEAD `79ef36b`（1.0.2-SNAPSHOT）
> v2 修订：2026-11-02 —— 组织与设备**不再绑定上级平台**，改为「本地资产 + 发布层」；
> 编码生成规则**预留不实现**。
>
> **前提：新项目，不承担存量迁移与旧表兼容。** 表结构按目标形态直接设计。

---

## 1. 需求与现状差距

### 1.1 五条主线

| # | 需求 | 现状 | 差距 |
| --- | --- | --- | --- |
| 1 | 上级目录是 组织 → 设备 → 通道 | 只有 域 → 组织 → 通道；`CascadeCatalogNode.Kind` 无 `DEVICE` | **缺设备层**，全库无 `tb_cascade_device` |
| 2 | **添加组织/设备时不选上级平台**，加完可推给任意平台 | `tb_cascade_org.platform_id NOT NULL`，唯一键 `(platform_id, cascade_org_id)`；组织抽屉入参带 `platformId` | **组织被绑死在平台上**：A 平台的组织树在 B 平台看不到，新增 B 平台要从头建一遍 |
| 3 | 设备要归属组织 | `tb_device` 无 `org_id`；设备与组织唯一的联系在 `tb_cascade_channel(platform_id, cascade_org_id, local_device_id)` | **设备没有本地归属**，组织关系只存在于「某个平台的上报配置」里 |
| 4 | 同一设备推多个平台，编码各自独立 | `tb_cascade_channel` 按 `(platform_id, local_channel_id)` 一行，编码靠人工填 | 结构上能存多平台，**但没有「发布」这个动作**，只能逐平台手工录入 |
| 5 | 编码生成规则（省/市/县/行业/类型/网络/起始序号） | 组织编码由 `nextOrgCode` 按平台前缀 + 215 + 序号生成，无任何可配置段 | **规则不可配置**，也无法按组织分配编码段 |

### 1.2 三条关键判断

1. **组织树只应该有一份**。行业里「每个上级一套组织树」的做法，本质是给「不同上级要求不同层级」留余地；
   实际部署中 95% 的组织结构是同一套（园区/楼栋/部门），重复建树的维护成本远大于那 5% 的灵活性。
   真正需要按平台区分的只有**编码**和**是否上报**，不是**结构**。
2. **「上报」是一个动作，不是一条字段**。现在把 `platform_id` 塞进组织表，等于强迫对象在被创建时就选边站；
   正确做法是对象先存在（本地资产），再被「发布」到一个或多个平台。
3. **编码是发布配置的一部分**，不是本地资产的属性。本地设备只有一个 `device_id`，
   给海康上报什么码、给大华上报什么码，属于「发布到该平台时的参数」。

### 1.3 新项目前提带来的简化

| 旧设计的妥协 | 新项目做法 |
| --- | --- |
| 通道表保留 `cascade_org_id` 作派生缓存 | **整表重构**，组织归属只在设备上 |
| 存量迁移脚本 | **不写** |
| `device-level=false` 回退阀 | **不要** |
| 通道编码唯一索引延后 | **建表就上** |
| 组织编码允许外部传入 | **后端生成**（预留编码规则） |

---

## 2. 目标架构分层

```
┌──────────────────────────────────────────────────────────────────────┐
│  接入层 Inbound（下端，P4/P5）                                        │
│  tb_cascade_source + tb_access_org/device/channel + tb_access_binding │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ 导入（单向写入）
┌───────────────────────────▼──────────────────────────────────────────┐
│  本地资产层 Local Asset（唯一事实源，**不含 platform_id**）            │
│  tb_cascade_org       组织树（全局一套，可配编码段）                   │
│  tb_device            设备（org_id 归属组织）                         │
│  tb_device_channel    通道（device_id 归属设备）                      │
│                                                                      │
│  ← 页面在「这里」新增组织/设备，不需要知道有几个上级                    │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ 发布（选择推给哪些平台 + 可选编码覆盖）
┌───────────────────────────▼──────────────────────────────────────────┐
│  发布层 Publish（每个上级一套可见性视图）                              │
│  tb_cascade_platform   上级平台配置（含 vendor_profile / code_prefix） │
│  tb_cascade_publish    (platform_id, object_type, object_id, code, enabled) │
│                                                                      │
│  ← 「推送配置」页在「这里」勾选平台，同一对象的 N 行互不影响            │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ 组装（唯一出口）
┌───────────────────────────▼──────────────────────────────────────────┐
│  协议层 Protocol                                                     │
│  GB28181 SIP/MANSCDP：CascadeCatalogAssembler 唯一出口                │
└──────────────────────────────────────────────────────────────────────┘
```

**铁律**

1. 本地资产层的表**永远不出现 `platform_id`**。出现即说明又把对象绑死在平台上了。
2. 发布层只存「差异」：编码、名称、启停、元数据覆盖。**树形结构完全来自本地组织树**。
3. 协议层只调 `CascadeCatalogAssembler`，不允许第二处拼报文。
4. 组装器内联零条编码规则，编码一律来自 `publish_code`（空则回落本地编码）。

---

## 3. 数据模型

### 3.1 本地资产层

#### 3.1.1 `tb_cascade_org`（去平台化 + 预留编码规则）

```sql
CREATE TABLE tb_cascade_org (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    parent_id    BIGINT UNSIGNED DEFAULT NULL COMMENT '父组织，空=根组织（挂域节点）',
    org_path     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '物化路径 /A/B/（存 org_code 链）',
    org_code     VARCHAR(64)  NOT NULL COMMENT '组织编码（本地，20 位；暂无规则时后端生成）',
    org_name     VARCHAR(255) NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '本地有效；发布层另有独立启停',

    /* ── 编码段规则（预留，见 §6）── */
    code_province CHAR(2)   DEFAULT NULL COMMENT '省编码',
    code_city     CHAR(2)   DEFAULT NULL COMMENT '市编码',
    code_county   CHAR(4)   DEFAULT NULL COMMENT '县区编码',
    code_industry CHAR(2)   DEFAULT NULL COMMENT '行业编码',
    code_type     CHAR(3)   DEFAULT NULL COMMENT '类型编码（215 业务分组 / 216 虚拟组织）',
    code_network  CHAR(1)   DEFAULT NULL COMMENT '网络标识',
    code_start_seq INT      DEFAULT NULL COMMENT '起始序号（6 位）',
    code_rule_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '0=未启用（用后端默认生成），1=按上面七段生成',

    PRIMARY KEY (id),
    UNIQUE KEY uk_org_code (org_code),
    KEY idx_org_parent (parent_id),
    KEY idx_org_path (org_path)
) COMMENT ='级联组织树（本地资产，全局一套）';
```

与现状的三处差异：**去掉 `platform_id`**、唯一键从 `(platform_id, cascade_org_id)` 变成
`org_code` 全局唯一、`idx_org_path` 去掉 `platform_id`。

> 命名：`cascade_org_id` 改名为 `org_code`，避免与「上报给某个平台的编码」混淆。
> 后者统一叫 `publish_code`。

#### 3.1.2 `tb_device`（加组织归属）

```sql
ALTER TABLE tb_device
    ADD COLUMN org_id BIGINT UNSIGNED DEFAULT NULL COMMENT '所属级联组织（tb_cascade_org.id）',
    ADD KEY idx_device_org (org_id);
```

`org_id` 可空：允许设备先接入、后归组织（现场调试时很常见）。

#### 3.1.3 `tb_device_channel`（不变）

已有 `channel_id` + `device_id` + `status`，作为本地通道的唯一事实源。

> **`tb_cascade_channel` 这张表在新设计里不再需要**——它的三项职责被拆分：
> 「选入上报」→ `tb_cascade_publish`；「上报编码」→ `publish_code`；
> 「元数据覆盖」→ publish 的覆盖列。

### 3.2 发布层

#### 3.2.1 `tb_cascade_publish`（一张表承载所有上报关系）

```sql
CREATE TABLE tb_cascade_publish (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    platform_id  VARCHAR(64) NOT NULL COMMENT '上级平台ID',
    object_type  VARCHAR(16) NOT NULL COMMENT 'ORG / DEVICE / CHANNEL',
    object_id    BIGINT      NOT NULL COMMENT 'tb_cascade_org.id / tb_device.id / tb_device_channel.id',

    publish_code VARCHAR(64) NOT NULL COMMENT '上报编码；空串不允许（写入时回落本地编码）',
    publish_name VARCHAR(128) DEFAULT NULL COMMENT '空=用本地名称',
    enabled      TINYINT     NOT NULL DEFAULT 1 COMMENT '0=该平台不上报',
    sort_order   INT         NOT NULL DEFAULT 0,

    /* ── 目录元数据覆盖（仅 DEVICE / CHANNEL 有意义，空则从本地派生）── */
    manufacturer VARCHAR(64)  DEFAULT NULL,
    model        VARCHAR(64)  DEFAULT NULL,
    address      VARCHAR(255) DEFAULT NULL,
    ip_address   VARCHAR(64)  DEFAULT NULL,
    ptz_type     INT          DEFAULT NULL,
    address_ip   VARCHAR(64)  DEFAULT NULL,
    civil_code   VARCHAR(64)  DEFAULT NULL,

    /* ── 组织专属 ── */
    revoke_policy VARCHAR(16) DEFAULT NULL COMMENT 'CASCADE / REROUTE，空默认 CASCADE',

    extend      TEXT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_publish_object (platform_id, object_type, object_id),
    UNIQUE KEY uk_publish_code   (platform_id, publish_code),
    KEY idx_publish_type (platform_id, object_type, enabled)
) COMMENT ='级联发布配置（每个上级一套可见性视图）';
```

**为什么用一张表而不是三张**

- `uk_publish_code (platform_id, publish_code)` 能在库级拦住**跨对象类型的重码**——
  上级目录按 DeviceID 建索引，组织编码和通道编码撞了同样是丢节点。三张表各自唯一做不到这一点。
- 「给某平台看哪些节点」变成一次查询 `WHERE platform_id=? AND enabled=1`，组装器不用 join 三次。
- 新增对象类型（比如将来的「区域」）不需要加表。

**为什么树形结构不存进 publish**

`parent` 关系唯一来自本地：组织按 `tb_cascade_org.parent_id`、设备按 `tb_device.org_id`、
通道按 `tb_device_channel.device_id`。publish 只回答「这个节点在该平台看不看得到、用什么码」。

这条是 v2 设计的核心：**结构一份，可见性 N 份**。上一个版本把 `cascade_org_id` 冗余进设备/通道，
本质上还是在平台侧复制结构，会导致「本地移动了组织，各平台副本要逐个跟」的问题。

#### 3.2.2 `tb_cascade_platform` 增补

```sql
ALTER TABLE tb_cascade_platform
    ADD COLUMN vendor_profile VARCHAR(16) NOT NULL DEFAULT 'GENERIC'
        COMMENT 'GENERIC/HIKVISION/DAHUA/UNIVIEW，用于差异化报文行为',
    ADD COLUMN code_prefix VARCHAR(10) DEFAULT NULL
        COMMENT '编码前缀（前10位），空=取 local_client_id 前10位',
    ADD COLUMN auto_publish TINYINT NOT NULL DEFAULT 0
        COMMENT '1=新增本地设备时自动发布到本平台（enabled=0）';
```

`vendor_profile` 的用途：海康要求 `Event: Catalog;id=<SN>`、对 `SumNum` 极敏感、
`CivilCode` 必须与父节点同源；大华部分版本对空 `<Address>`、缺 `PTZType` 更宽容。
把这些差异收敛成画像开关，比在 `CascadeManscdpXml` 里散落 `if (platformId.equals(...))` 可维护。

### 3.3 下级级联接入表（P4/P5）

```sql
CREATE TABLE tb_cascade_source (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_key  VARCHAR(64)  NOT NULL COMMENT '接入源标识（唯一且稳定）',
    source_name VARCHAR(128) NOT NULL,
    protocol    VARCHAR(32)  NOT NULL COMMENT 'GB28181 / HIK_ISAPI / DAHUA_CGI / ONVIF',
    host        VARCHAR(64)  DEFAULT NULL,
    port        INT          DEFAULT NULL,
    username    VARCHAR(64)  DEFAULT NULL,
    password    VARCHAR(128) DEFAULT NULL,
    sync_cron   VARCHAR(64)  DEFAULT NULL,
    enabled     TINYINT      NOT NULL DEFAULT 1,
    extend      TEXT         DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_key (source_key)
) COMMENT ='下级级联接入源';

/* tb_access_org / tb_access_device / tb_access_channel / tb_access_binding 同 v1，略 */
```

外部台账与本地资产**分表**：外部编码结构常变，直接写进 `tb_device` 会把外部抖动传导到上报侧。

---

## 4. 目录组装链路

### 4.1 节点种类

```java
public enum Kind { DOMAIN, ORG, DEVICE, CHANNEL }
```

### 4.2 组装流程（`assemble(platformId)`）

```
1. 取平台配置（code_prefix / charset / vendor_profile）
2. 取该平台的发布集：tb_cascade_publish WHERE platform_id=? AND enabled=1
   按 object_type 分成 orgSet / deviceSet / channelSet
3. 取本地组织树（全部），按 orgSet 过滤：
   组织可见 = 自己在 orgSet 且祖先链全部在 orgSet（祖先不可见 → 整棵子树不进目录）
4. 取本地设备，设备可见 = 自己在 deviceSet 且 device.org_id 可见
5. 取本地通道，通道可见 = 自己在 channelSet 且 channel.device_id 可见
6. 输出顺序：域 → 组织（sortOrder）→ 设备（sortOrder,name）→ 通道（按设备,name）
   SumNum = 1 + N(组织) + M(设备) + K(通道)
```

**与 v1 的关键差别**：可见性不再需要「本地 enabled + 祖先链」的复杂推导，
因为「是否发布」已经显式存在 publish 表里；祖先链校验退化为一次集合包含判断（`orgSet` 里有没有它的祖先）。

### 4.3 通道挂载与兜底

```java
private String resolveChannelParent(channel, visibleDeviceIds, domainId) {
    return visibleDeviceIds.contains(channel.getDeviceId()) ? publishCodeOf(channel.getDeviceId()) : domainId;
}
```

设备不可见时通道上浮到域节点（而不是丢弃）——本地有、上级看不见的排查成本远高于层级不齐。
设备父节点同构：组织可见则挂组织，否则挂域。

### 4.4 `Parental` 字段（现状写死 0，属隐性缺陷）

GB28181 目录项 `Parental` 语义是「是否为父节点」：`1` 有子节点、`0` 无。
现状 `item.setParental(0)` 对所有节点写死，等于告诉上级「整棵树都是叶子」。改为：

```java
item.setParental(node.kind() == Kind.CHANNEL ? 0 : 1);
```

`renderCatalog` 里的 `<Parental>` 常量同步改为按节点取值。

### 4.5 停报与删除的推送面

| 场景 | 推送 |
| --- | --- |
| 发布行 `enabled` 1→0（组织） | 子树内 组织 + 设备 + 通道 `DEL`；`revokePolicy=REROUTE` 时通道上浮域节点 |
| 发布行 `enabled` 1→0（设备） | 该设备下通道 `DEL` + 设备 `DEL` |
| 发布行 `enabled` 1→0（通道） | 单条 `DEL` |
| 删除本地组织/设备/通道 | 先按各平台算 `DEL` 集合，推送后再删本地行 |
| 新增本地对象且平台 `auto_publish=1` | 生成 publish 行（`enabled=0`），不推送 |

**顺序不能反**：删除本地对象前必须先算出「哪些平台的哪些节点要删」，否则删完就查不到编码了。

---

## 5. 一个对象发布到多个平台

### 5.1 形态

```
本地资产（1 份）
  tb_cascade_org      id=10  园区
  tb_device           id=100 device_id=34020000001320000001  org_id=10
  tb_device_channel   id=200 channel_id=34020000001320000001 device_id=100

发布层（N 份，只存差异）
  platform=海康(34020000002000000001)
    ORG     obj=10  publish_code=34020000002150000001  enabled=1
    DEVICE  obj=100 publish_code=34020000001320000001  enabled=1
    CHANNEL obj=200 publish_code=34020000001320000001  enabled=1
  platform=大华(34020100002000000001)
    ORG     obj=10  publish_code=34020100002150000001  enabled=1
    DEVICE  obj=100 publish_code=34020100001320000007  enabled=1   ← 编码不同
    CHANNEL obj=200 publish_code=34020000001320000001  enabled=0   ← 可选择不报
```

同一设备在两边的编码、名称、启停、是否出现，**完全独立**；共享的只有本地 `id` 与 `device_id`。

### 5.2 「推送」这个动作的三种入口

| 入口 | 场景 | 行为 |
| --- | --- | --- |
| 组织/设备列表行的「推送到上级」抽屉 | 单个对象快速配置 | 勾选平台 → 批量 upsert publish 行（编码留空=用本地码） |
| 平台详情页的「上报范围」树 | 新平台接入时批量选 | 树形勾选本地组织/设备/通道 → 批量建 publish 行（`enabled=0`） |
| 平台配置 `auto_publish=1` | 新增设备自动进平台 | 事件驱动建 publish 行（`enabled=0`），人工核对后开启 |

**默认 `enabled=0`**：投影生成完先核对（编码段、层级、通道子集），确认后批量打开。
直接 `enabled=1` 会让一个不成熟的方案瞬间推到上级目录，回滚代价很高。

### 5.3 本地变更的扇出

| 本地变更 | 扇出动作 |
| --- | --- |
| 新增设备 | `auto_publish` 的平台建 publish 行（`enabled=0`），不推送 |
| 删除设备 | 各平台先推通道 `DEL`、设备 `DEL`，再删本地行与 publish 行 |
| 设备改名 | 更新未单独覆盖 `publish_name` 的发布行 + 推 `UPDATE` |
| 通道离线（status 0） | **不动发布行**，组装时状态写 `OFF` |
| 组织移动 | **不动发布行**（结构本地一份），各平台按新路径重新组装即可 |

最后一条是 v2 相对 v1 的最大收益：组织移动原本要逐个平台重推，现在只需本地改一次 `parent_id` + `org_path`。

---

## 6. 编码体系（本轮只预留，不实现）

### 6.1 国标 20 位编码的分段

```
中心编码(8)      + 行业(2) + 类型(3) + 网络(1) + 序号(6)   ← GB/T 28181
  = 省(2) + 市(2) + 县区(4)
```

对应你给的编码配置界面：

| 段 | 位数 | 示例 | 说明 |
| --- | --- | --- | --- |
| 省编码 | 2 | `37` | 行政区划（GB/T 2260） |
| 市编码 | 2 | `01` | 行政区划 |
| 县区编码 | 4 | `0200` | 行政区划 |
| 行业编码 | 2 | `04` | 行业 |
| 类型编码 | 3 | `216` | 215 业务分组 / 216 虚拟组织 / 132 前端设备 / 131 视频通道 |
| 网络标识 | 1 | `1` | 网络类型 |
| 起始序号 | 6 | `000123` | 该组织占用的号段起点，后续对象从此递增 |

「前 14 位为必填」= 省2 + 市2 + 县4 + 行业2 + 类型3 + 网络1 = 14，序号 6 位由系统分配。
「覆盖已有编码」= 保存后把该组织（及其子树）的 `org_code` / `publish_code` 按新规则重算并覆盖，
属于高危操作，必须弹影响面预览。

### 6.2 本轮要预留的东西（只落结构，不做生成）

| 预留项 | 落点 | 本轮做到什么程度 |
| --- | --- | --- |
| 编码段字段 | `tb_cascade_org` 的 `code_*` 七列 + `code_rule_enabled` | 建表 + DO/DTO 字段 + 前端表单，**默认不启用** |
| 生成器接口 | `CascadeCodeRuleService`（manager 包） | 定义接口与 `enabled=false` 的空实现（返回本地编码） |
| 覆盖动作 | `POST /api/v1/cascade/org/{id}/code/reapply` | **只留路由占位**，返回 501 或直接不注册 |
| 前端编码配置弹窗 | 组织编辑抽屉里的「编码配置」 | 可打开、可保存七段，但**不影响** actual 编码 |

### 6.3 接口草案

```java
/** 编码规则服务：本轮只定义，不接入组装链路 */
public interface CascadeCodeRuleService {

    /** 组织自己的编码：从配置段按类型位 + 起始序号生成 */
    String orgCode(Long orgId);

    /** 组织下设备的编码：沿用组织前 10 位，类型位换成设备类型位，序号在组织号段内递增 */
    String deviceCode(Long deviceId);

    /** 通道编码 */
    String channelCode(Long channelId);

    /** 覆盖动作：重算并落库，返回受影响对象清单（供预览与 DEL/ADD 推送） */
    CodeReapplyResult reapply(Long orgId, boolean dryRun);
}
```

实现放 P3，本轮只保证「字段在、接口在、开关关着」，避免以后接编码规则时再改表。

---

## 7. 交互重新设计

### 7.1 组织页（`views/cascade/org`）

| 现状 | 目标 |
| --- | --- |
| 顶部先选上级平台，再展示该平台的组织树 | **去掉平台选择器**，直接展示全局组织树 |
| 抽屉入参带 `platformId` | 入参只带 `parentId` / 编辑行 |
| 表单：名称、排序、启停 | 名称、排序、启停 + **编码配置**（按钮打开七段弹窗） |
| 列：组织编码 / 层级 / 通道数 / 排序 / 启停 / 上级可见 | 组织编码 / 层级 / 设备数 / 排序 / 启停 / **已发布平台**（标签） |
| 行操作：新增下级、编辑、移动、删除 | 再加 **推送到上级** |

「已发布平台」列展示该组织已发布的平台简称，鼠标悬浮列出全部，`enabled=0` 的标灰。

### 7.2 设备页（`views/device`）

| 现状 | 目标 |
| --- | --- |
| 设备列表无组织概念 | 新增「所属组织」列 + 顶部组织树筛选 |
| 新增设备表单：设备ID、名称、IP、端口、协议 | 增加「所属组织」（TreeSelect，可空） |
| 无上报概念 | 新增「已发布平台」列 + 行操作 **推送到上级** |

### 7.3 推送配置抽屉（新组件，组织/设备/通道共用）

```
┌─────────────────────────────────────────────────┐
│  推送到上级平台                                  │
├─────────────────────────────────────────────────┤
│  ☑ 海康 iSecure     编码 [34020000001320000001] │
│  ☑ 大华 DSS         编码 [                    ] │
│  ☐ 宇视平台         编码 [                    ] │
├─────────────────────────────────────────────────┤
│  编码留空 = 使用本地编码                          │
│  批量：全选 / 全不选 / 仅启用                     │
└─────────────────────────────────────────────────┘
```

- 保存 = 勾选的 upsert、取消勾选的 delete（或 `enabled=0`，二选一，建议后者便于恢复）
- 组织的「推送」默认沿子树：勾选组织时提供「同时发布其下设备与通道」复选项

### 7.4 平台页（`views/cascade/platform`）

行操作新增 **上报范围**：打开树形选择（本地组织树 → 设备 → 通道），
用于新平台接入时的批量发布（`enabled=0`，核对后批量开启）。

### 7.5 菜单与权限

| 权限码 | 用途 |
| --- | --- |
| `Cascade:Publish:Config` | 推送配置抽屉的保存 |
| `Cascade:Publish:Scope` | 平台「上报范围」树 |
| `Cascade:Org:Code` | 组织编码配置弹窗 |

新增权限码需同步 `tb_menu` 与两份初始化 SQL（见 `add-menu-page` skill 的约定），
否则按钮灰着、重启也无效（登录时权限是快照）。

---

## 8. 下级级联接入（P4/P5，本轮不实施）

形态同 v1：`AccessCatalogDiscoverer` 接口 → 台账同步 → 绑定 → 导入本地资产层。
因为导入目标是本地资产（`tb_device` + `org_id`），导入后**自动对所有已开启 `auto_publish` 的平台可见**，
这是 v2 分层带来的额外收益。

---

## 9. 建表与方言同步

- 三套基础 DDL 同批维护：`sql/voglander.sql`（MySQL）、`sql/voglander-postgresql.sql`、
  `sql/voglander-sqlite.sql`。新项目直接改基础 DDL，不写增量迁移。
- **SQLite 运行库仍需幂等建表器**：`SqliteSchemaInitializer` 只跑硬编码注册的迁移器 bean。
  新增 `CascadePublishSchemaMigrator` / `CascadeOrgCodeRuleMigrator`
  （`pragma table_info` 判存在 + 记录 `schema_migration_history`）并注册，
  否则运行中的实例直接 `no such table` / `no such column`。

---

## 10. 分步实施计划

| 步骤 | 内容 | 产出 | 估时 |
| --- | --- | --- | --- |
| **S1** | 本地资产层改造：`tb_cascade_org` 去 `platform_id`、加 `code_*` 七列；`tb_device` 加 `org_id`；三方言 DDL + SQLite 建表器 | 表就位，组织树全局唯一 | 0.5 天 |
| **S2** | `CascadeOrgManager` 全量去 `platformId`（`listEffective` / `nextOrgCode` / `tree` / `move` / `revoke`）；前端组织页去平台选择器 | 组织页可用且不选平台 | 1 天 |
| **S3** | 设备归属组织：`DeviceManager` 读写 `org_id`；前端设备表单/列表加组织 | 设备能挂组织 | 0.5 天 |
| **S4** | 发布层：`tb_cascade_publish` + `CascadePublishManager` + 推送配置抽屉 + 平台「上报范围」树 | 任意对象可推任意平台 | 1.5 天 |
| **S5** | 组装器改造：按 publish 取节点、加 `DEVICE` 层、`Parental` 按 kind、停报推送面 | 上级目录三层且可独立配置 | 1 天 |
| **S6** | 编码规则预留：`CascadeCodeRuleService` 接口 + 空实现 + 前端七段弹窗（不生效） | 功能预留完毕 | 0.5 天 |
| **S7** | 回归：`Cascade*Test` 全绿 + 手工联调（海康一个平台） | 验收 | 1 天 |

依赖顺序严格：S1 → S2 → S3 → S4 → S5；S6 可与 S4/S5 并行。

每步独立提交（显式路径 `git add` + Conventional Commits）。

---

## 11. 待确认口径

| # | 问题 | 建议 |
| --- | --- | --- |
| 1 | 本地组织是否还需要「启用/停用」？ | 需要，但与发布层的 `enabled` 是两个概念：本地停用＝这个组织不用了；发布停用＝只是不推给这个平台 |
| 2 | 取消某平台的发布时，是删 publish 行还是置 `enabled=0`？ | **置 0**，便于恢复；真正不需要时才删 |
| 3 | 组织「推送」是否默认带子树？ | 默认带（勾选组织时同时发布其下设备与通道），但给复选项 |
| 4 | `tb_cascade_channel` 是否直接删除？ | 是，职责全部由 `tb_cascade_publish` 承担（新项目无兼容包袱） |
| 5 | 编码规则弹窗本轮是否真的落库？ | 建议落库（字段可存），但不参与生成——将来接规则时不用再改表 |

---

## 12. 风险

| 风险 | 缓解 |
| --- | --- |
| 组织去平台化后，某些上级确实要求不同层级 | publish 层不存结构，但可用 `auto_publish=0` + 手工只发布部分子树来近似；真需要「同设备在两个平台挂不同组织」时，再给 publish 加 `parent_object_id` 覆盖列（预留位） |
| 迁移期间前端两套模型并存 | 新项目直接切，不做双写；S2 与 S4 之间组织页可用、推送功能暂缺，属可接受的中间态 |
| 组装器重写引入可见性回归 | 先写「可见性矩阵」单测（组织/设备/通道 × 发布/未发布 × 祖先停报），再改组装 |
| 编码规则将来接入时改动大 | 本轮把七段字段与接口一次落全，避免二次改表 |
| SQLite 运行库缺表 | 幂等建表器必须写并注册 |

---

## 13. 与 v1 设计的差异摘要

| 维度 | v1（按平台一套组织树） | v2（本地资产 + 发布层） |
| --- | --- | --- |
| 组织表 | `platform_id` 必填 | 无 `platform_id`，全局一套 |
| 设备归属 | `tb_cascade_device(platform_id, cascade_org_id)` | `tb_device.org_id`（本地） |
| 上报表达 | 每个平台一套 org/device/channel 表 | 一张 `tb_cascade_publish` |
| 组织移动 | 每个平台重推 | 本地改一次 |
| 编码 | `tb_cascade_code_map` 规则表 | `publish_code` 覆盖 + 组织编码段规则（预留） |
| 添加对象时 | 必须先选平台 | 不选平台 |
