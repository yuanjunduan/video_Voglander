# 级联目录组织树（Domain → Org → Channel）实施方案

> 版本：1.0.11 · 2026-10-29
> 参考实现：`D:\work\视频平台\CSS\Trunk`（Go 版 CSS 平台，`service/gb28181/gb28181_message.go`、`gb28181_syncdata.go`）
> 现象：级联注册成功、Keepalive 正常、海康能查到目录，但 iSecure Center 界面里始终看不到通道。

---

## 1. 结论先行

海康侧看不到通道，不是"没发目录"，而是**目录树只有叶子、没有中间节点**。

GB28181 的 Catalog 是**一棵树**，`<Item>` 之间用 `ParentID` 串起父子关系：

```
域节点(平台编码) ── 组织节点 ── 通道节点
```

voglander 当前只下发"通道节点"，且 `ParentID`（修复前甚至缺失）直接指向平台编码，
上级拿到一条**没有父节点的孤儿通道**：SIP 层回 `200 OK`（表示"报文我收到了"），
但目录树里不建节点，界面自然空白。

对照 CSS 实现，一次合法的 Catalog 应答由三部分构成：

| 部分 | 条数 | DeviceID | ParentID |
|---|---|---|---|
| 域节点 | 1 | 本级平台编码（`34020000002000000001`） | 空 / 上级编码 |
| 组织节点 | N | 组织编码（20 位，类型 215/216） | 上级组织编码，根级为域编码 |
| 通道节点 | M | 通道编码（20 位，类型 132） | **所属组织编码** |

`SumNum = 1 + N + M`。

---

## 2. 已定位并已修复的两个协议层缺陷

在梳理组织树之前，先记录本轮已经修掉的、会**直接吞掉推送**的问题（代码已改，待重启生效）：

### 2.1 主动 NOTIFY 的 Call-ID 被上级请求污染 → `400 Bad Request`

日志证据（`~/logs/voglander/voglander-sip.log`，2026-10-29 10:59:11）：

```
→ 我方发出  MESSAGE  Call-ID: 589515361z16796_de88ca41c325   ← 这是海康 SUBSCRIBE 的 Call-ID
← 海康返回  SIP/2.0 400 Bad Request
```

根因在框架 `SipRequestBuilder` 构造器：

```java
// Call-ID优先级：ThreadLocal事务上下文 > ToDevice.callId > 新生成
String contextCallId = SipTransactionContext.getCurrentCallId();
```

`pushCatalogSnapshot` 被 `CascadeSubscribeRequestProcessor` / `CascadeClientRegisterListener`
在**SIP 请求处理线程**里同步调用，ThreadLocal 里存着上级 SUBSCRIBE/REGISTER 的 Call-ID，
于是 NOTIFY 带着上级自己的 Call-ID 发出去，被判定为非法请求。

**修复**：`CascadeNotifyPublisher#sendIsolated` 显式 `SipSender.request(...).callId(SipRequestUtils.getNewCallId())`。
应答（`CascadeQueryHandler`）必须继续沿用入站 Call-ID，**不能**走该方法。

### 2.2 目录 Item 缺必填字段

修复前实际报文（2026-10-29 11:02:00，海康主动 Query 的应答）：

```xml
<Item>
    <DeviceID>34020000001320000007</DeviceID>
    <Name>通道7-测试</Name>
    <Parental>0</Parental>
    <SafetyWay>0</SafetyWay>
    <RegisterWay>1</RegisterWay>
    <Status>ON</Status>
    <Secrecy>0</Secrecy>
    <Certifiable>0</Certifiable>
</Item>
```

缺 `ParentID` / `Owner` / `Manufacturer` / `Model` / `CivilCode` / `Address`。

**修复**：`CascadeQueryHandler#toDeviceItem` 补齐上述字段。

> 这两个修复解决的是"推送被 400 拒收"和"条目被判无效"，
> 但**没有解决组织层级**——通道的 `ParentID` 目前仍直接指向平台编码，缺中间组织节点。

---

## 3. 现状盘点

### 3.1 数据现状（MySQL `192.168.118.229:3306/voglander`）

```
tb_dept                     1 条（总公司 / ROOT / parent_id=0）        ← 本地组织树，未参与级联
tb_device                  10 条（1 个 GB28181 设备 + 9 个 RTSP/ONVIF 接入）
tb_device_channel          12 条（channel_id=7 属于 hk-nvr165-ch3）
tb_cascade_platform         1 条（37000020002000000100 → 192.168.106.35:7100，GB2312/UDP）
tb_cascade_channel          1 条（hk-nvr165-ch3 / channel 7 → 34020000001320000007）
tb_cascade_subscribe        1 条（CATALOG，SN=11438，到期 11:29:12）
```

### 3.2 代码现状

| 层 | 构件 | 现状 |
|---|---|---|
| 表 | `tb_cascade_channel` | `platform_id / local_device_id / local_channel_id / cascade_channel_id / cascade_name / enabled`，**无组织维度** |
| Manager | `CascadeChannelManager` | 扁平 CRUD + `batchBind`，无组织概念 |
| 协议 | `CascadeQueryHandler#onCatalogQuery` | 只组装通道条目 |
| 协议 | `CascadeNotifyPublisher#pushCatalogSnapshot` | 只组装通道条目 |
| 组织 | `DeptManager / DeptService` | 已有部门树能力（`parent_id` / `dept_name` / `dept_code`），**与级联完全解耦** |

### 3.3 结论

"一套组织 + 按上级共享"这件事，voglander **有组织底座（tb_dept），缺的是**
① 组织的 GB28181 编码；② 组织与上级平台的共享关系；③ 通道挂在哪个组织下；④ 目录组装时的三级拼装。

---

## 4. 目标模型

### 4.1 目录树结构

```
34020000002000000001              域节点（平台自身，type=200）
├── 34020000002150000001          组织A（type=215，ParentID=域编码）
│   ├── 34020000001320000007      通道1（type=132，ParentID=组织A）
│   └── 34020000001320000008      通道2
└── 34020000002150000002          组织B（ParentID=域编码）
    └── 34020000001320000009      通道3
```

### 4.2 报文样例（Catalog 应答，修复目标）

第一包：域节点

```xml
<?xml version="1.0" encoding="GB2312"?>
<Response>
    <CmdType>Catalog</CmdType>
    <SN>11439</SN>
    <DeviceID>34020000002000000001</DeviceID>
    <SumNum>4</SumNum>
    <DeviceList Num="1">
        <Item>
            <DeviceID>34020000002000000001</DeviceID>
            <Name>Voglander</Name>
            <Manufacturer>Voglander</Manufacturer>
            <Model>CascadePlatform</Model>
            <Owner>Voglander</Owner>
            <CivilCode>340200</CivilCode>
            <Address>34020000002000000001</Address>
            <Parental>0</Parental>
            <SafetyWay>0</SafetyWay>
            <RegisterWay>1</RegisterWay>
            <Secrecy>0</Secrecy>
            <Status>ON</Status>
        </Item>
    </DeviceList>
</Response>
```

第二包起：组织 + 通道（按 `gateway.cascade.catalog.batch-size` 分片，`SumNum` 始终为总数）

```xml
<Response>
    <CmdType>Catalog</CmdType>
    <SN>11439</SN>
    <DeviceID>34020000002000000001</DeviceID>
    <SumNum>4</SumNum>
    <DeviceList Num="3">
        <Item>
            <DeviceID>34020000002150000001</DeviceID>
            <Name>总公司</Name>
            <ParentID>34020000002000000001</ParentID>
            <Owner>34020000002000000001</Owner>
            <CivilCode>340200</CivilCode>
            <Parental>0</Parental>
            <RegisterWay>1</RegisterWay>
            <Secrecy>0</Secrecy>
            <Status>ON</Status>
        </Item>
        <Item>
            <DeviceID>34020000001320000007</DeviceID>
            <Name>通道7-测试</Name>
            <ParentID>34020000002150000001</ParentID>
            <Owner>34020000002000000001</Owner>
            <Manufacturer>Voglander</Manufacturer>
            <Model>CascadeChannel</Model>
            <CivilCode>340200</CivilCode>
            <Address>China</Address>
            <Parental>0</Parental>
            <RegisterWay>1</RegisterWay>
            <Secrecy>0</Secrecy>
            <Status>ON</Status>
        </Item>
        ...
    </DeviceList>
</Response>
```

**订阅后的完整快照 NOTIFY** 结构相同，根标签换成 `<Notify>`，并走独立 Call-ID。

### 4.3 编码规则（GB28181 20 位）

`中心编码(8) + 行业编码(2) + 类型编码(3) + 网络标识(1) + 序号(6)`

| 节点 | 类型编码 | 生成规则 | 示例 |
|---|---|---|---|
| 平台域 | 200 | 平台配置 `local_client_id` | `34020000002000000001` |
| 组织 | **215**（业务分组）/ 216（虚拟组织） | `{平台前10位}+215+0+6位序号` | `34020000002150000001` |
| 通道 | 132 | 已有 `cascade_channel_id`，保持不变 | `34020000001320000007` |

> 平台前 10 位 = 中心 8 位 + 行业 2 位（`3402000000`）。
> 组织编码**一旦下发给上级就不能改**——上级按编码建树，改了等于删旧建新。

---

## 5. 数据模型改造

### 5.1 新表 `tb_cascade_org`（级联组织树，每平台一套）

```sql
CREATE TABLE tb_cascade_org (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    platform_id    VARCHAR(64)  NOT NULL,           -- 上级平台编码
    parent_org_id  BIGINT       DEFAULT NULL,       -- 树内父节点（本表 id），NULL=根组织，直接挂域节点
    org_path       VARCHAR(512) NOT NULL DEFAULT '',-- 物化路径：根到自身的编码链，形如 /A/B/
    cascade_org_id VARCHAR(64)  NOT NULL,           -- 上报给上级的组织编码（20 位）
    org_name       VARCHAR(255) NOT NULL,           -- 上报名称
    sort_order     INTEGER      DEFAULT 0,
    enabled        INTEGER      DEFAULT 1 NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cascade_org_code (platform_id, cascade_org_id)
);
CREATE INDEX idx_cascade_org_platform ON tb_cascade_org (platform_id);
CREATE INDEX idx_cascade_org_parent   ON tb_cascade_org (parent_org_id);
CREATE INDEX idx_cascade_org_path     ON tb_cascade_org (platform_id, org_path);
```

设计取舍：

- **独立于 `tb_dept`**：级联组织是"对外暴露的视图"，与内部部门编制解耦；每条上级平台可维护自己的组织层级，
  改级联组织不影响本地权限/数据归属。
- **只存 `parent_org_id`，不存 `cascade_parent_id`**：上报的 `ParentID` 由 Manager 实时解析
  （根组织取平台编码，子组织取父级 `cascade_org_id`），避免两处冗余写坏后不一致。
- **`enabled=0` 的组织整棵剪掉**：其自身与全部子孙组织、其下通道都不出现在目录里。
- **邻接表 + 物化路径双写**：`parent_org_id` 仍是唯一事实来源（改父、判重、环检测都靠它），
  `org_path` 是纯派生冗余，只为子树查询走索引，理由见 §5.4。

### 5.2 `tb_cascade_channel` 增加组织归属

```sql
ALTER TABLE tb_cascade_channel ADD COLUMN cascade_org_id VARCHAR(64) DEFAULT NULL;
CREATE INDEX idx_cascade_channel_org ON tb_cascade_channel (platform_id, cascade_org_id);
```

语义：
- `cascade_org_id` 非空 → 通道挂在对应组织节点下（`ParentID = cascade_org_id`）
- `cascade_org_id` 为空 → 通道直接挂域节点（`ParentID = 平台编码`），**保持今天的行为**
- 写入时校验：该编码必须存在于**同一 `platform_id`** 的 `tb_cascade_org` 且 `enabled=1`，否则拒绝保存

### 5.3 SQL 维护点（三个方言 + 三份 migration，缺一不可）

| 文件 | 改动 |
|---|---|
| `sql/voglander.sql` | 新增 `tb_cascade_org` DDL（含 `org_path`）；`tb_cascade_channel` 加列（L768 附近） |
| `sql/voglander-sqlite.sql` | 同上（L747 附近，SQLite 不支持多列合并，`ALTER` 需逐列） |
| `sql/voglander-postgresql.sql` | 同上（L662 附近） |
| `sql/migration/mysql/1.0.11-cascade-org-tree.sql` | 存量库升级：建表 + 通道加列 |
| `sql/migration/sqlite/1.0.11-cascade-org-tree.sql` | 同上 |
| `sql/migration/postgresql/1.0.11-cascade-org-tree.sql` | 同上 |
| `sql/migration/mysql/1.0.11-cascade-org-path.sql` | 存量库升级：加 `org_path` + 索引 + 递归 CTE 回填 |
| `sql/migration/sqlite/1.0.11-cascade-org-path.sql` | 同上 |
| `sql/migration/postgresql/1.0.11-cascade-org-path.sql` | 同上 |

> **执行顺序不可颠倒**：每个方言都必须先跑 `1.0.11-cascade-org-tree.sql`（建表 + 通道加列），
> 再跑 `1.0.11-cascade-org-path.sql`（加 `org_path` + 索引 + 回填）。
> 按文件名排序时 `-path` 恰好排在 `-tree` 之前，整目录全选执行会报
> `ERROR 1146 Table 'xxx.tb_cascade_org' doesn't exist`（PG 为 `relation does not exist`）。
> 三份 path 脚本开头已加前置检查，表缺失时会先打印 `[STOP] ...请先执行 1.0.11-cascade-org-tree.sql`。

> `sql/migration/sqlite/*.sql` **不会被应用自动执行**（`SqliteSchemaInitializer` 只调硬编码迁移器）。
> 因此 SQLite 侧的真实升级入口是 `SqliteCascadeOrgSchemaMigrator`：建表、加列（先查
> `pragma_table_info` 判重）、建索引、递归 CTE 回填、校验、写 `schema_migration_history`，
> 全在一个事务里，可重复执行。MySQL / PostgreSQL 仍由 DBA 执行 `sql/migration/*` 下的脚本。

> 若新增"级联组织"菜单页，还需按 `.agents/skills/add-menu-page` 走一遍：
> `tb_menu` 插入 + 权限码 + 两份初始化 SQL + 现有 `app.db` 即时生效。

### 5.4 树查询设计：为什么是"邻接表 + 物化路径"

需求是两类，且明确要求支持"十几层嵌套"：

1. 查某组织的**所有子孙组织**（可能十几层）
2. 查某组织下的**所有通道**（含所有子孙组织下的通道）

四种主流方案的取舍：

| 方案 | 取整棵子树 | 改父/移支 | 写入成本 | 适用判断 |
|---|---|---|---|---|
| 纯邻接表（只有 `parent_org_id`） | 需 N 次查询递归下钻，或数据库递归 CTE | 一条 UPDATE | 最低 | 层级浅（≤3 层）够用 |
| **物化路径（`org_path`）** | **一次 `LIKE '/A/%'` 走索引** | 需重写子孙 path | 一次 INSERT | **父节点不可变 → 无代价** |
| 闭包表（ancestor,descendant,depth） | 一次 JOIN，可带深度 | 增删边 | 每节点 O(深度) 行 | 需要频繁移动节点 |
| 嵌套集（lft,rgt） | 一次区间查询 | **改一次要重写半棵树** | 高 | 只读为主，写入极少 |

选 **物化路径** 的决定性理由是：本项目的组织**没有移动语义**。
`CascadeOrgManager#update` 只允许改 `orgName / sortOrder / enabled`，父节点与编码都不可变
（改父等价于"删掉旧节点、新建一个节点"，已绑定通道会变孤儿，接口直接拒绝）。
物化路径唯一的硬伤（移动子树要重写全部子孙 `org_path`）在这里根本不会发生，于是：

- `org_path` 语义：`/根编码/.../自身编码/`，**前后都带 `/`**，前缀匹配才能恰好覆盖整层
  （否则 `/A/` 会误匹配 `/AB/`）
- 存**组织编码**而非自增 id：与 `tb_cascade_channel.cascade_org_id` 同域，
  通道查询可以直接 `IN (子树的编码集合)`
- 层级推导：`org_path` 的斜杠数 − 1 = 深度；祖先链直接切分字符串，**不回表**
- 长度预算：编码 20 位 + 分隔符 = 21 字符/层，`VARCHAR(512)` ≈ 24 层；
  MySQL utf8mb4 下 512×4 = 2048 字节，仍在 3072 字节索引上限内

对应查询（三方言写法已收敛到 `org_path` 前缀匹配，不需要 `WITH RECURSIVE`）：

```sql
-- 子孙组织（含自身）
SELECT * FROM tb_cascade_org WHERE platform_id = ? AND org_path LIKE '/A/%';
-- 某组织下的全部通道（含嵌套）
SELECT * FROM tb_cascade_channel WHERE platform_id = ?
   AND cascade_org_id IN (SELECT cascade_org_id FROM tb_cascade_org
                          WHERE platform_id = ? AND org_path LIKE '/A/%');
```

三条兜底规则（已落进 `CascadeOrgManager`）：

- **写入即定**：`add()` 时路径与编码同时生成，均不接受外部传入
- **读时补齐**：`buildOrgPath` 向上找最近的已回填祖先，缺失层级用 `parent_org_id` 链补齐，
  即历史库没跑回填脚本，新节点的路径也是对的；`ensureOrgPath` 读到空路径会顺手回写
- **环保护**：`buildOrgPath` 与 `tree()` 的组装都带 `Set` 访问哨兵，人为改库造环时不递归、
  不丢节点（不可达节点提升为该层根节点）

> GB/T 28181-2016 只规定业务分组（215）为根、虚拟组织（216）为分支、设备为叶的**树形结构**，
> Item 里只有 `ParentID`、没有 level/depth，也没有"查子树"的目录命令。
> 也就是说：多层嵌套是标准允许的（虚拟组织下可再分子虚拟组织），但**子树查询纯属本级平台内部能力**，
> 怎么存、怎么查由本端决定，不影响上报报文的合法性。

---

## 6. 后端改造清单

### 6.1 新增（manager / web 层）

| 文件 | 职责 |
|---|---|
| `voglander-manager/.../domaon/dto/cascade/CascadeOrgDTO.java` | 级联组织 DTO（含 children，供树形出参） |
| `voglander-manager/.../manager/CascadeOrgManager.java` | 树 CRUD、编码生成、父节点解析、启用态剪枝 |
| `voglander-manager/.../service/CascadeOrgService.java` + `Impl` | 业务编排 + 变更后触发 NOTIFY |
| `voglander-repository/.../do/CascadeOrgDO.java` + `Mapper` | 持久化 |
| `voglander-web/.../api/cascade/CascadeOrgController.java` | REST 入口 |
| `voglander-web/.../api/cascade/req/CascadeOrgShareReq.java` 等 | 入参 |
| `voglander-web/.../api/cascade/assembler/CascadeWebAssembler.java`（改造） | 新增 req↔dto 映射 |

### 6.2 改造

| 文件 | 改动 |
|---|---|
| `CascadeChannelDTO` / `CascadeChannelDO` | 加 `cascadeOrgId` |
| `CascadeChannelCreateReq` / `UpdateReq` / `BatchBindReq.BindItem` | 加 `cascadeOrgId` |
| `CascadeChannelManager#add/update/batchBind` | 写组织归属；校验组织属于该平台且启用 |
| `CascadeChannelVO` | 出参补 `cascadeOrgId` |
| **`CascadeQueryHandler#onCatalogQuery`** | 组装三段：域节点 + 组织节点 + 通道节点；`SumNum = 1 + N + M`；首包单独发域节点 |
| **`CascadeNotifyPublisher#pushCatalogSnapshot`** | 复用同一组装器，保证应答与推送结构一致 |
| `CascadeClientRegisterListener` / `CascadeSubscribeRequestProcessor` | 保持现有触发点（已接入） |

### 6.3 关键设计：抽一个目录组装器

应答与 NOTIFY 必须产出**完全相同**的条目集合，否则上级在"查询"和"订阅推送"两条路径上看到不同目录。
建议抽出：

```java
// voglander-integration/.../cascade/CascadeCatalogAssembler.java
public class CascadeCatalogAssembler {
    /** 域节点（1 条）+ 组织节点（N 条）+ 通道节点（M 条） */
    public List<CascadeCatalogNode> assemble(CascadePlatformDTO platform);

    /** 渲染为 <Response> 或 <Notify> 的 MANSCDP XML，按 batch-size 分片 */
    public List<String> render(CascadeCatalogNode domain, List<CascadeCatalogNode> items,
                               String sn, boolean notify, int batchSize);
}
```

`CascadeQueryHandler` 与 `CascadeNotifyPublisher` 都只调它，避免两处 XML 漂移。

### 6.4 组织编码生成

```java
// 输入：平台 local_client_id = 34020000002000000001
// 输出：34020000002150000001 / 34020000002150000002 ...
String prefix = localClientId.substring(0, 10);        // 3402000000
String seq    = String.format("%06d", nextSeq);        // 000001
String orgId  = prefix + "215" + "0" + seq;            // 20 位
```

序号来源：同一平台下 `select max(cascade_org_id)` 自增，或维护独立序列。
**必须保证唯一且稳定**——已下发的编码不允许变更。

---

## 7. 接口契约（供前端仓库对接）

所有接口统一前缀 `/api/v1/cascade`，返回体为 `AjaxResult<T>`（`code` / `msg` / `data`），
时间字段一律 **Unix 毫秒**。

### 7.1 组织树 `/cascade/org`

| 方法 | 路径 | 入参 | 出参 |
|---|---|---|---|
| `POST` | `/cascade/org` | `{platformId, parentOrgId, orgName, sortOrder, enabled}` | `Long` 新增 id |
| `PUT` | `/cascade/org/{id}` | `{orgName, sortOrder, enabled}` | `Boolean` |
| `DELETE` | `/cascade/org/{id}` | — | `Boolean` |
| `GET` | `/cascade/org/{id}` | — | `CascadeOrgVO` |
| `GET` | `/cascade/org/list?platformId=` | 平台编码 | `List<CascadeOrgVO>`（平铺） |
| `GET` | `/cascade/org/tree?platformId=&rootOrgId=` | `rootOrgId` 选填，空=整棵树 | `List<CascadeOrgVO>`（根节点，含 `children`） |
| `GET` | `/cascade/org/{id}/tree` | — | `List<CascadeOrgVO>`：以该组织为根的子树树形，前端懒加载用（与 `/tree?rootOrgId=` 等价，只是按主键定位） |
| `GET` | `/cascade/org/{id}/descendants?includeSelf=false` | `includeSelf` 默认 `false` | `List<CascadeOrgVO>`：该组织的后代平铺列表（可跨十几层），适合做下拉、批量绑定 |

`CascadeOrgVO` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 主键，用于 `parentOrgId`、绑定通道 |
| `platformId` | String | 所属上级平台编码 |
| `parentOrgId` | Long | 父组织主键；`null` = 根组织（直接挂平台节点） |
| `orgPath` | String | **只读**。物化路径，形如 `/A/B/`；斜杠数 − 1 = 层级，可直接切分做面包屑，前端不必递归父链 |
| `cascadeOrgId` | String | 上报给上级的 20 位编码，**后端生成、不可变更** |
| `orgName` | String | 上报给上级的名称 |
| `sortOrder` | Integer | 同级排序，越小越靠前 |
| `enabled` | Integer | 1 上报 / 0 不上报（停用连带整棵子树从目录中剪掉） |
| `channelCount` | Integer | **直属**通道数，仅 `tree` 接口填充；`>0` 时删除会被拒绝 |
| `children` | `List<CascadeOrgVO>` | 仅 `tree` 接口填充 |
| `createTime` / `updateTime` | Long | Unix 毫秒 |

树查询行为约定：

- `tree(platformId)` 一次返回整棵树；`rootOrgId` 非空时只返回该组织的子树，**子树根成为顶层节点**
  （父节点不在结果集内 → 认作该层根），因此前端可以逐层懒加载
- 父节点已停用/删除时，其子孙会"提升"为所在层的根，**不会整片消失**（`promoteUnreachable`）
- 组织不存在时返回空列表，不报错；组织编码不存在时通道分页返回**空页**，
  **绝不放行为"不过滤"**（否则会把全平台通道吐给调用方）

### 7.2 通道 `/cascade/channel`（增量字段）

| 方法 | 路径 | 新增字段 |
|---|---|---|
| `POST` | `/cascade/channel` | 入参 `cascadeOrgId`（选填，空=挂平台根节点） |
| `PUT` | `/cascade/channel/{id}` | 入参 `cascadeOrgId`：`null` 不改；`""` 迁回根节点；非空迁到该组织 |
| `POST` | `/cascade/channel/batchBind` | 批次层 `cascadeOrgId`，整批统一挂到该组织 |
| `POST` | `/cascade/channel/getPage` | 入参 `cascadeOrgId` 过滤 + `includeSubOrg`（默认 `false`）；出参 VO 含 `cascadeOrgId` |

`getPage` 的 `includeSubOrg` 语义：

- `false`（默认，保持既有行为）：只匹配 `cascade_org_id = 传入编码` 的**直属**通道
- `true`：先按 `org_path` 前缀取出整棵子树的组织编码集合，再 `cascade_org_id IN (...)`；
  层级由十几层也不增加查询次数（一次组织查询 + 一次通道分页）
- 传入的 `cascadeOrgId` 不存在或已删除 → 返回空页（`total=0`），不报错、不放行

### 7.3 校验与变更语义

- `cascadeOrgId` 非空时必须是**同平台、已启用**的组织编码，否则接口直接报错、不落库
- 组织编码与 `orgPath` 均由后端生成（编码：`平台前10位 + 215 + 0 + 6位序号`），入参**不接受**这两个字段
- 组织新增 / 改名 / 启停 / 删除、通道换组织 → 后端自动重推一次**完整目录快照**
  （这类变化改的是父子关系，单条增量条目表达不了，见 §6.3）
- 通道上线/下线仍走单条 `Catalog Event` 增量推送
- 删除组织时若仍有子组织或已绑定通道，接口返回失败并说明数量

---

## 8. 前端改造清单（独立仓库）

1. **上级平台详情 → 新增「组织树」Tab**：树形管理，支持新增子组织、重命名、排序、启停、删除；每个节点展示上报编码（只读）与直属通道数
2. **通道映射表单**：新增「所属组织」下拉（数据源=`/cascade/org/tree`，允许留空=挂平台根节点）
3. **批量绑定**：先选组织，再批量勾选通道（批次统一 `cascadeOrgId`）
4. 通道列表增加「组织」列（用 `cascadeOrgId` 反查组织树取名称）
5. 顶部提示：组织编码由系统生成、下发后不可变更；删除组织前需先迁走其下通道
6. **层级深时用懒加载**：`/cascade/org/tree?rootOrgId=` 或 `/cascade/org/{id}/tree` 按节点展开取子树，
   避免一次性拉十几层全树；面包屑直接用 `orgPath` 切分，不必自己递归 `parentOrgId`
7. **批量/下拉场景**用 `/cascade/org/{id}/descendants` 取平铺列表；通道列表加「包含子组织」勾选项，
   对应 `getPage` 的 `includeSubOrg`


---

## 9. 分阶段实施计划

| 阶段 | 产出 | 验收 | 预估 |
|---|---|---|---|
| **P0 止血**（已改 2 项） | ① NOTIFY 独立 Call-ID ② Item 补全必填字段 ③ 域节点 | 海康对 NOTIFY 回 `200 OK`（不再 400）；`voglander-sip.log` 里能看到 `<Response>` 含 `ParentID` | 0.5d |
| **P1 组织底座** | 新表 `tb_cascade_org` + DTO/DO/Mapper/Manager/Service + 编码生成 + 三份 SQL + 三份 migration | 单测覆盖"编码生成 / 唯一约束 / 增量序号"；`mvn test` 全绿 | 1.5d |
| **P2 目录三级** | `CascadeCatalogAssembler` 抽取 + 应答/NOTIFY 接入组织节点 + 通道父节点改指向组织 | 应答 `SumNum=1+N+M`；海康界面出现"平台→组织→通道" | 1.5d |
| **P3 接口与联动** | `CascadeOrgController` 全套 + 通道 `cascadeOrgId` 贯通 + 变更触发增量 NOTIFY | Postman 全流程；上级侧增删组织/通道能实时反映 | 1d |
| **P4 前端** | 共享组织 Tab + 表单下拉 + 列表列（独立仓库） | 页面完成"先共享组织、再共享通道"闭环 | 1.5d |

**建议先做 P0 验证假设**：只加域节点 + 已修的两项，重启后让海康重新同步一次。
如果海康界面出现"Voglander 平台节点"但通道仍不显示，则组织层是硬需求（预期如此）；
如果连平台节点都不出现，说明还有别的拦截点（届时按 `voglander-sip.log` 原始报文继续定位）。

---

## 10. 迁移与兼容

- 存量 `tb_cascade_channel.cascade_org_id` 为 `NULL` → 通道继续挂域节点，**上线零影响**
- 存量 `tb_cascade_org.org_path` 为 `''` → 迁移脚本用递归 CTE 一次性回填；
  即使没跑脚本，`buildOrgPath` 也会在新增/读取时按 `parent_org_id` 链补齐并回写，最迟一次读即收敛
- 提供「一键建根组织」：为该平台创建 1 个根组织（名称默认取平台名），可选把该平台下
  `cascade_org_id IS NULL` 的通道批量挂进去 —— 不建也能用，只是通道与平台节点平级
- 组织取消共享时：先判该组织下是否仍有 `enabled=1` 的通道，有则拒绝并提示

---

## 11. 验收清单

**协议层**（`~/logs/voglander/voglander-sip.log`）

- [ ] 海康 `MESSAGE/Query Catalog` → 我方回 `200 OK` → 回 `<Response>`（`SumNum = 1+N+M`）
- [ ] 应答 `<Item>` 全部含 `ParentID` / `Owner` / `Manufacturer` / `Model` / `CivilCode` / `Address`
- [ ] 海康对每条应答回 `200 OK`（无 `4xx`）
- [ ] 订阅后首包 `<Notify>` 的 `Call-ID` **不等于**海康 SUBSCRIBE 的 Call-ID
- [ ] `<Notify>` 共 `1 + N + M` 条，分包时 `SumNum` 恒为总数、`Num` 为本包条数

**数据层**

- [ ] `tb_cascade_org` 编码唯一且稳定，重复共享不产生新编码
- [ ] 通道绑定不存在的组织 → 接口报错，不落库
- [ ] 每个组织的 `org_path` 等于父组织 `org_path` + 自身编码 + `/`；根组织为 `/编码/`
- [ ] `GET /cascade/org/{id}/descendants` 能一次取全十几层子孙，且不含祖先与本层之外的节点
- [ ] `POST /cascade/channel/getPage` 带 `includeSubOrg=true` 能查到子组织下的通道；组织编码不存在时 `total=0`

**功能层**

- [ ] 海康界面：平台节点 → 组织节点 → 通道，层级与 `ParentID` 一致
- [ ] 组织改名 / 通道新增 / 通道停用 → 上级在秒级内刷新

---

## 12. 风险与待确认

| 风险 | 说明 | 应对 |
|---|---|---|
| 组织类型编码 | 海康对 215（业务分组）/ 216（虚拟组织）处理可能不同 | 先用 215；不认则切 216，做成配置项 `gateway.cascade.org-type` |
| 编码不可回改 | 上级按编码建树，改编码=删旧建新 | 页面明确提示；后端禁止修改已下发编码 |
| 字符集 | 平台配置 `charset=GB2312`，我方应答 XML 声明为 UTF-8，中文名称可能乱码 | P2 一并对齐：XML 声明与 MESSAGE 编码都跟随平台 `charset` |
| 组织层级深度 | 海康是否支持多级组织未验证 | 先按 `tb_cascade_org` 实际深度原样上报；若异常则压平为单层 |
| 分片与域节点 | CSS 把域节点单独发一包（`Num=1`），`SumNum` 仍为总数 | 照此实现，第一包只含域节点 |

### 已确认的设计决策（2026-10-29）

1. **组织源**：新建**独立的级联组织树** `tb_cascade_org`，每平台一套，不与 `tb_dept` 绑定。
2. **NVR 中间层**：不上报。目录层级固定为 `域 → 组织 → 通道`，`tb_device` 只用于取通道状态，不产生设备节点。
3. **兜底策略**：保留。`cascade_org_id` 为空的通道挂域节点，存量数据无需迁移。
4. **子树查询方案**：`tb_cascade_org` 加 `org_path` 物化路径（详见 §5.4），不引入闭包表/嵌套集；
   理由是本项目组织**没有移动语义**，路径写入后永不失效。
5. **上报深度压平**：**暂不做**。先按实际层级原样上报；`max-depth` 之类的压平开关留到
   海康侧确认接受度后再加（若真需要，只需在 `CascadeCatalogAssembler` 组装时按 `org_path` 深度截断，
   存储层不用改）。

### 仍需在实施中确认的细节

- 组织类型编码取 215 还是 216（先 215，失败再切，做成 `gateway.cascade.org-type` 配置项）
- 海康对多级组织（组织套组织）的接受程度（先按实际深度上报，异常则压平为单层）

---

## 13. 已落地的代码改动

### 13.1 协议层止血（早前已改，编译通过）

| 文件 | 改动 |
|---|---|
| `CascadeNotifyPublisher.java` | `sendIsolated`（独立 Call-ID）避免复用上级请求的 Call-ID 被回 `400` |
| `CascadeQueryHandler.java` | 目录 Item 补齐 `ParentID`/`Owner`/`Manufacturer`/`Model`/`CivilCode`/`Address` |
| `logback-spring.xml` | `scan` 关闭（热重载丢 appender）；`[traceId=%X{traceId:--}]` 默认值修正 |

### 13.2 P0 域节点

| 文件 | 改动 |
|---|---|
| `CascadeQueryHandler.java` | 首包只含域节点，其余节点按 `gateway.cascade.catalog.batch-size` 分片；`SumNum` 始终为总数 |
| `CascadeNotifyPublisher.java` | 同上（订阅后的首包） |

### 13.3 P1 组织底座

| 文件 | 改动 |
|---|---|
| `sql/voglander.sql`、`voglander-sqlite.sql`、`voglander-postgresql.sql` | 新建 `tb_cascade_org`；`tb_cascade_channel` 加 `cascade_org_id` + `idx_cascade_channel_org` |
| `sql/migration/{mysql,sqlite,postgresql}/1.0.11-cascade-org-tree.sql` | 幂等迁移脚本 |
| `repository`：`CascadeOrgDO`、`CascadeOrgMapper`、`CascadeChannelDO` | 组织实体与通道归属列 |
| `manager`：`CascadeOrgDTO`、`CascadeOrgService(+Impl)`、`CascadeOrgManager` | 编码生成（`平台前10位+215+0+6位序号`）、树构建、启用剪枝、父节点解析、删除校验 |

### 13.4 P2 目录三级

| 文件 | 改动 |
|---|---|
| `CascadeCatalogNode.java`（新） | 目录节点 record，`Kind = DOMAIN/ORG/CHANNEL` |
| `CascadeCatalogAssembler.java`（新） | **唯一**节点产出点：域 → 组织（按 `sortOrder`）→ 通道；通道父节点取组织，组织缺失/停用时兜底挂域节点 |
| `CascadeQueryHandler.java` | 应答改用组装器；删除自带的 `domainItem`/`toDeviceItem`/`loadChannelStatus` |
| `CascadeNotifyPublisher.java` | 推送改用组装器；删除自带的 `CatalogItem`/`catalogNotify`/`escapeXml` |

### 13.5 P3 接口与联动

| 文件 | 改动 |
|---|---|
| `CascadeOrgController.java`、`CascadeOrgCreateReq`、`CascadeOrgUpdateReq`、`CascadeOrgVO`（新） | 组织树 CRUD + `list`/`tree` |
| `CascadeChannelCreateReq`/`UpdateReq`/`PageReq`/`BatchBindReq`/`VO` | 贯通 `cascadeOrgId` |
| `CascadeChannelManager.java` | 写入前校验组织归属（同平台且启用）；`update` 支持"迁回根节点"（显式 `set null`） |
| `CascadeOrgManager.java`、`CascadeChannelManager.java` | 组织增删改、通道换组织时发布 `LocalCascadeCatalogChangeEvent` |
| `LocalCascadeCatalogChangeEvent.java`、`CascadeEventBridge.java` | 结构变更 → 重推完整目录快照 |

### 13.6 P5 子树查询（`org_path` 物化路径）

| 文件 | 改动 |
|---|---|
| `sql/voglander.sql`、`voglander-sqlite.sql`、`voglander-postgresql.sql` | `tb_cascade_org` 加 `org_path VARCHAR(512) NOT NULL DEFAULT ''` + `idx_cascade_org_path` |
| `sql/migration/{mysql,sqlite,postgresql}/1.0.11-cascade-org-path.sql` | 加列（幂等判重）+ 索引 + 递归 CTE 回填 + 校验 SELECT |
| `SqliteCascadeOrgSchemaMigrator.java`（新） | SQLite 真实升级入口：建表/加列/索引/回填/校验在一个事务内完成，可重复执行 |
| `SqliteSchemaInitializer.java` | 启动链路接入上面的迁移器 |
| `CascadeOrgDO` / `CascadeOrgDTO` / `CascadeOrgVO` | 加 `orgPath`（只读出参） |
| `CascadeOrgManager.java` | `add()` 同时生成 path；新增 `getByPlatformAndCode`、`listSubtree`、`listSubtreeCodes`、`tree(platformId, rootOrgId)`；`buildOrgPath`/`ensureOrgPath` 兜底补齐；`tree()` 增加环保护 `promoteUnreachable` |
| `CascadeChannelManager.java` | `getPage` 增加 `includeSubOrg` 重载：子树编码集合 → `IN` 过滤；组织不存在返回空页 |
| `CascadeChannelPageReq` / `CascadeChannelController` | 透出 `includeSubOrg` |
| `CascadeOrgController` | `/tree` 加 `rootOrgId`；新增 `/{id}/tree`、`/{id}/descendants` |

测试（`CascadeOrgManagerTest`）：`OrgPath`（路径生成/层级推导）、`Subtree`（跨层取全、排除自身、
不含祖先、组织不存在、子树树形）、`ChannelSubtree`（含子组织通道、未知组织返回空页）。

> 全部模块 `mvn compile` 通过。单测与协议实测见 §11 验收清单。

