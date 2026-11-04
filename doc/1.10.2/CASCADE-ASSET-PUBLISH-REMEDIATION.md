# 级联整改方案：三类本地资产独立发布

> 承接 `doc/1.10.1/CASCADE-THREE-TIER-ARCHITECTURE.md`。1.10.1 把**组织**改成了本地资产，
> 但 `tb_cascade_channel` 仍是「每平台一行」，通道还带着平台维度。本文是补齐设备、通道
> 两类资产的整改方案与实施计划。

---

## 1. 问题定性

`tb_cascade_channel` 的缺陷不是写法问题，是**身份错位**：它把「本地通道」与「某平台的上报配置」
压成一行。

| 症状 | 根因 |
| --- | --- |
| 一个通道推 3 个平台就有 3 行，改个名字要改 3 行 | 通道没有独立实体，identity 寄生在 `(platform_id, local_channel_id)` |
| 「这个通道推给了哪些平台」只能数表行数 | 反查维度是平台，不是通道 |
| 写入口要做 `assertOrgPublished` 这类校验 | 归属与发布挤在同一行，脏数据无从下手 |
| 本地想让通道从所有平台下线，只能逐平台关 | 本地没有 `enabled`，启停被迫按平台重复 |

这与 1.10.1 修掉的「组织按平台一套树」是同一个病。组织治好了、通道没治，就是瘸腿状态。

---

## 2. 需求不变量

1. **组织、设备、通道是独立的**：三类对象各自有独立生命周期与独立身份，任何一类都不"属于"
   某个上级平台。三张本地表**永远不出现 `platform_id`**。
2. **每类都能独立推送给不同上级平台**：配置动作粒度是「某对象 → 某平台」，不是「某平台一套副本」。
3. **每个对象推给了哪些平台可查**：关系表必须支持从对象侧反查。
4. **同一对象在不同上级可以挂不同父节点**（集团平台归"总部"、区级平台归"某小区"）：
   这是「独立于平台」与「按平台差异」唯一真正的矛盾点，必须给覆盖位。

---

## 3. 行业口径

**GB28181-2016**

- 目录 `Catalog` 是扁平条目列表，树由 `Item/ParentID` 表达，标准不规定层数 → 层级是产品选择。
- 标准定义**业务分组（Type=215）/ 虚拟组织（Type=216）**：把摄像机按业务组织结构组织起来。
  通道直挂虚拟组织是**合规且主流**的，不必须插设备层。
- `DeviceID` 在**该上级域内唯一**（非全局），因此"同一通道在不同平台用不同编码"标准允许。
- `Parental` 表达"是不是父节点"；当前实现对它写死 `0`，等于告诉上级整棵树都是叶子。
- 变更必须显式 `DEL`，完整快照是增量 upsert 语义。

**主流平台产品**

| 产品 | 形态 |
| --- | --- |
| LiveGBS | 上级平台配置里勾选要共享的通道，每个上级一套勾选 |
| WVP-GB28181-pro | 「国标级联 → 国标通道」按上级勾选，不同上级可用不同 DeviceID |
| 海康 iSecure / 大华 DSS | 组织「共享/授权」给上级域，通道随组织走，可单独剔除 |

结论：「本地资产层 + 按平台的共享/发布层」是行业通行形态；**没有任何主流产品是"一个通道在每个
上级存一行实体"**。

---

## 4. 目标数据模型

```
本地资产层（永远无 platform_id）
  tb_cascade_org      id / parent_id / org_code / enabled / 七段编码列
  tb_device           id / device_id / org_id  / enabled        ← R1 补 enabled
  tb_device_channel   id / (device_id, channel_id) / org_id / enabled   ← R1 补两列

发布层（唯一表达"谁推给谁"）
  tb_cascade_publish
    platform_id, object_type(ORG|DEVICE|CHANNEL), object_key
    publish_code, publish_name, enabled, sort_order
    parent_key                                    ← R2 新增
    manufacturer/model/address/ip_address/ptz_type/civil_code
    revoke_policy                                 （组织专属）
  uk_publish_object (platform_id, object_type, object_key)
  uk_publish_code   (platform_id, publish_code)   ← 平台内跨类型防重码，反查靠它
```

### 4.1 `object_id BIGINT` → `object_key VARCHAR(128)`

| object_type | object_key |
| --- | --- |
| ORG | `org_code` |
| DEVICE | `device_id` |
| CHANNEL | `device_id + ":" + channel_id` |

理由：三类对象在本地都有**已建唯一索引的业务键**（`uk_cascade_org_code` / `uk_device` /
`idx_channel_device`），而自增 id 是存储细节。

- 上级点播反查只查 publish **一次**即可拿到本地通道标识，不必二次回表。
- 设备/通道行被物理删重建时（`DeviceChannelManager.deleteOne` 确会物理删）发布关系不会孤儿化。
- 「这个通道推给了哪些平台」= `WHERE object_type='CHANNEL' AND object_key=?`。

代价：丢掉（本来也不存在的）外键约束，靠 Manager 层在删对象时清理 publish 行。

### 4.2 新增 `parent_key`（可空）

不填 = 按本地结构推导；填了 = 该平台下挂到这个节点。这让「三类独立」与「按平台差异」同时成立，
也是迁移旧数据时不丢信息的唯一手段。

### 4.3 两套启停 + 一个在线状态

| 开关 | 语义 | 影响面 |
| --- | --- | --- |
| `tb_cascade_org.enabled` / `tb_device.enabled` / `tb_device_channel.enabled` | 本地业务启停 | 从**所有**平台消失 |
| `tb_cascade_publish.enabled` | 是否推给该平台 | 只影响一个平台 |
| `tb_device.status` / `tb_device_channel.status` | 在线状态 | 只影响报文的 `Status` 字段 |

组织层早已是这个模型，设备与通道要对齐，否则"本地下线"被迫变成"逐平台关发布行"。

---

## 5. 目录组装规则

```
节点 = 域 → (组织树) → [设备层?] → 通道
可见 = publish 行 enabled=1 && 本地 enabled 链有效 && 父节点可见
```

通道父节点解析顺序：

1. `publish.parent_key` 填了 → 用其 `publish_code`（`uk_publish_code` 保证存在）
2. 通道自身 `org_id` 在该平台可见 → 组织节点
3. 平台开了设备层 && 设备已发布 && 设备 `org_id` 可见 → 设备节点
4. 设备已发布但组织不可见 → 按该组织的 `revoke_policy`：`CASCADE` 丢弃 / `REROUTE` 挂域节点
5. 其余 → 域节点

设备层由 `tb_cascade_platform.catalog_with_device_layer`（默认 `0` = 虚拟组织模式）控制：
本地结构不迁就某个上级，这正是 `vendor_profile` 该管的事。

`Parental` 改为 `kind == CHANNEL ? 0 : 1`。

---

## 6. 实施阶段

原 1.10.1 文档的 S3/S4/S5 按"功能"切，会导致组装器改完还要再改一次。改为按**依赖方向**切：

| 阶段 | 内容 | 依赖 |
| --- | --- | --- |
| **R1** | 本地资产补齐：`tb_device` 加 `enabled`；`tb_device_channel` 加 `org_id`/`enabled`；设备/通道可配组织与本地启停；删组织时校验占用 | 无 |
| **R2** | 发布层收敛：`object_id→object_key`；加 `parent_key`；统一门面 `CascadePublishFacade`；反查 API | R1 |
| **R3** | 组装器切换：消费 publish；设备层开关；`parent_key` 解析；`Parental` 按 kind | R2 |
| **R4** | 删 `tb_cascade_channel`；9 处反查链路改走 publish；`CascadeChannelManager` 收敛为 `CascadeCatalogPushService` | R3 |
| **R5** | 前端：级联通道页改「本地通道 + 推送抽屉」；组织/设备页加「已推送平台」列 | R2 |
| **R6** | `CascadeCodeRuleService` 接口 + 空实现；通道编码派生接进 `publish_code` | R2 |

R2 与 R1 可并行；R4 必须卡在 R3 之后。

---

## 7. R1 详细实施

### 7.1 Schema

```sql
ALTER TABLE tb_device          ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE tb_device_channel  ADD COLUMN org_id  INTEGER DEFAULT NULL;
ALTER TABLE tb_device_channel  ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1;
CREATE INDEX idx_channel_org ON tb_device_channel (org_id);
```

- 三方言主脚本（`sql/voglander.sql`、`sql/voglander-postgresql.sql`、`sql/voglander-sqlite.sql`）
- 新建 `SqliteCascadeAssetSchemaMigrator`（独立 MIGRATION_KEY，一个事务），
  接入 `SqliteSchemaInitializer` 的两条分支（sentinel 存在 / 空库）
- MySQL/PostgreSQL 迁移脚本 `sql/migration/{mysql,postgresql}/1.10.2-cascade-local-asset.sql`

### 7.2 存储与 DTO

- `DeviceDO` / `DeviceChannelDO` 加字段（`Integer enabled`、`Long orgId`）
- `DeviceDTO` / `DeviceChannelDTO` 同名字段，**默认 null**（不是 1）
- `DeviceChannelManager.batchUpsert`：目录同步来的 DTO 不带 `org_id`/`enabled`，
  MyBatis-Plus `updateBatchById` 跳过 null → 用户配置不会被目录同步冲掉。
  这条要写成注释 + 单测钉住（历史上设备表 extend 被覆盖过一次）。

### 7.3 Manager / Web

- `DeviceChannelManager`、`DeviceManager` 支持读写 `orgId` 与 `enabled`
- 设备/通道 Req、VO 加 `orgId`、`orgName`、`enabled`
- `CascadeOrgManager.delete` 增加占用校验：该组织下仍有设备或通道时拒绝，
  报错要说明"是设备占用还是通道占用、共几条"

### 7.4 验证

- `SqliteCascadeAssetSchemaMigratorTest`：列/索引就位、重复启动幂等、既有数据保留
- 设备/通道批量 upsert 不覆盖 `org_id`/`enabled` 的单测
- 删组织占用校验的单测（设备占用 / 通道占用 / 两者都有）

---

## 8. 迁移（R4）

`tb_cascade_channel` → publish 行是纯翻译：

```
platform_id      不变
object_type      'CHANNEL'
object_key       local_device_id || ':' || local_channel_id
publish_code     cascade_channel_id
publish_name     cascade_name
enabled          enabled
parent_key       cascade_org_id（空则留空 = 按本地结构推导）
manufacturer / model / address / ip_address / ptz_type   原样搬
revoke_policy    空
```

**数据取舍**：原表 `cascade_org_id` 是**按平台**的归属，而 `tb_device_channel.org_id` 只能存一份。
迁移时：

- 同一通道在所有平台的归属一致 → 回填 `tb_device_channel.org_id`，`parent_key` 留空
- 不一致 → `org_id` 取出现最多的那个，**差异值逐行写进 `parent_key`**

这正是加 `parent_key` 的直接收益：迁移不丢信息。

---

## 9. 验证策略

1. **可见性矩阵单测**（先写测试再改组装器）：对象类型{组织,设备,通道} × 发布{有/无/停用} ×
   祖先链{完整/中断} × `revoke_policy`{CASCADE/REROUTE} × 设备层{开/关}。
2. **迁移回归**：造含 3 个平台的旧库，断言 publish 行数 = 旧行数、编码/名称/启停/覆盖列逐字段一致、
   `parent_key` 只出现在原本不一致的通道上、旧表已删、重复启动幂等。
3. **反查回归**：点播 / PTZ / 录像 / 告警 / 移动位置 / 广播 6 条链路各造"通道推两个平台"用例。
4. **端到端**：本机 SIP 自环 + 目录查询，核对 `SumNum`、节点数、`Parental`。

---

## 10. 后续规划

| 阶段 | 能力 | 价值 |
| --- | --- | --- |
| N1 | 平台画像：`vendor_profile` + `catalog_with_device_layer` + `SumNum` 分批 | 一套代码适配海康/大华/宇视报文差异 |
| N2 | 发布分组/模板：一组对象 + 一组平台 = 一条可复用配置 | 现场几十上百路时逐条勾选不可接受 |
| N3 | 编码规则真正生效：按七段生成 + 平台内查重 | 现在靠回落本地编码，非国标通道 ID 只能人工填 |
| N4 | 下级接入层（`tb_cascade_source` 系列） | 本端既当上级又当下级 |
| N5 | 目录一致性对账：定期比对上级目录，自动补 DEL | 报文单向，丢一发就长期不一致 |

**架构红线**：`CascadeCatalogAssembler` 之后不允许出现第二处拼目录报文的地方。
`CascadeOrgManager` 里按平台算 DEL 集合的逻辑（`listEffectiveChannels`）R3 之后必须改成从 publish 取，
否则"推的目录"与"删的目录"来自两套判断。

---

## 11. 实施进度

### R1 本地资产补齐（已完成）

| 交付物 | 内容 |
| --- | --- |
| 三方言 schema | `tb_device.enabled`、`tb_device_channel.org_id` / `enabled`、`idx_channel_org` |
| 迁移器 | `SqliteCascadeAssetSchemaMigrator`，迁移键 `cascade-local-asset-device-channel` |
| 存储层 | `DeviceDO` / `DeviceChannelDO` 加字段；目录同步走定点更新，不再整行覆盖用户配置 |
| Manager / Web | 设备与通道可配置组织归属和本地启停；`org_id` 三态（`null` 不改 / `0` 清空 / 其余按 id） |
| 删组织校验 | 组织下仍有设备或通道时拒绝删除，报错区分占用来源与条数 |
| 测试 | 迁移器、组织归属与启停读写、删组织占用校验 |

**关键取舍**：本地 `enabled`（从所有平台消失）与 `status`（只影响报文 Status 字段）严格分离。
设备/通道批量 upsert 时只刷新目录同步字段，`org_id` / `enabled` 交给用户显式设置——
否则每次目录同步都会把人工配置抹掉。

### R2 发布层收敛（已完成）

| 交付物 | 内容 |
| --- | --- |
| 数据模型 | `CascadePublishDO` / `CascadePublishDTO`：`Long objectId` → `String objectKey`，新增 `String parentKey` |
| 业务键 | `CascadeConstant.PublishKeys`：ORG=`org_code`、DEVICE=`device_id`、CHANNEL=`device_id:channel_id` |
| 门面 | `CascadePublishManager` 全面 objectKey 化，新增 `indexByObjectKey`、`objectKeysOf`、`platformIdsByObjectKey`、`setParentKey` |
| 调用方切换 | `CascadeOrgManager`（11 处）、`CascadeOrgController`（4 处）、`CascadePublishVO`、`CascadeWebAssembler` |
| SQLite 迁移 | 新建 `SqliteCascadePublishKeyMigrator`（迁移键 `cascade-publish-object-key`）：表重建 + 三类 join 回填 + 孤儿行丢弃 |
| 1.10.1 迁移器 | 改为形状感知（`publishKeyColumn()` / `orgKeyExpr()`），新旧两种发布表形状都能跑通 |
| 外部库脚本 | `sql/migration/{mysql,postgresql}/1.10.2-cascade-publish-object-key.sql` |
| 测试 | `SqliteCascadePublishKeyMigratorTest`（7 用例），更新迁移器 / 组织 Manager / 事件测试 |

**为什么用业务键而不是自增 id**：设备与通道会被目录同步物理删后重建，id 一变发布关系就成了孤儿；
上级点播反查本地通道时，只有业务键能让一次查询直接拿到 `device_id` / `channel_id`。

**`parent_key` 只存差异**：留空 = 按本地结构推导，因此本地组织树仍然只有一份，
同一对象在不同上级挂不同父节点的例外才写进该列。

**迁移顺序**：老库启动时先跑 `migrateCascadeSchema()`（1.10.1，`CREATE TABLE IF NOT EXISTS` +
按实际列建索引与回填），紧接 `migrateCascadePublishKeySchema()` 重建发布表；
下次启动 1.10.1 迁移器全部 `IF NOT EXISTS` no-op，不会把形状退回去。

**验证结论**：定向 `Cascade*Test,SqliteCascade*Test` 324/324 通过；
全量 1658 tests / 2 failures / 11 errors / 25 skipped，失败项逐条对照基线均为既有环境依赖
（SIP e2e 需真实对端、`LabMediaPushServiceTest` 在 Windows 上执行 `.sh` 返回 error=193），
用例数较基线 +7 恰为本次新增迁移器测试，零回归。

### R3 组装器切换（已完成，expand）

| 交付物 | 内容 |
| --- | --- |
| 唯一读入口 | 新建 `CascadePublishedAssetResolver`：`listChannels` / `listDevices` 把发布行翻成目录视图，统一过滤口径（发布停用、本地停用、孤儿行） |
| 目录组装 | `CascadeCatalogAssembler` 只认 resolver 的输出；`Parental` 按节点类型（通道 0 / 目录节点 1）；`parent_key` 命中优先，指向目录外则告警并回落本地推导 |
| 设备层 | `catalog_with_device_layer=1` 时插入设备节点，通道改挂设备；关闭时通道仍直挂组织 |
| 架构红线闭环 | `CascadeOrgManager.listEffectiveChannels` 改为「发布集 ∩ 本地子树」，与组装器共用同一口径 |
| 存量迁移 | 新建 `SqliteCascadeChannelPublishMigrator`（迁移键 `cascade-channel-into-publish`）：老表逐行翻成 CHANNEL 发布行 |
| 过渡双写 | `CascadeChannelManager` 写老表的同时同步发布行，公开 API 与前端契约不变 |
| 外部库脚本 | `sql/migration/{mysql,postgresql}/1.10.2-cascade-channel-into-publish.sql` |
| 测试 | `CascadePublishedAssetResolverTest`(6)、`SqliteCascadeChannelPublishMigratorTest`(7)，改造 `CascadeCatalogAssemblerTest`(15) / `CascadeNotifyPublisherTest`(15) / `CascadeOrgManagerTest`(52) |

**为什么 R3 必须同时做迁移 + 双写**：读侧一收敛，目录就只认发布行。若存量不迁移，升级后上级目录会立刻清空；
若写侧仍只落老表，新配的通道推不上去。两步都做，切换才是对现场无感的。

**迁移不丢语义**：通道在所有平台的归属一致 → 回填 `tb_device_channel.org_id` 并把 `parent_key` 留空
（以后本地换组织，上级目录自动跟随）；归属不一致或用户已配过本地归属 → 本地列不动，
差异逐行写进 `parent_key`，上报结果与迁移前逐字节一致。

**验证结论**：`-Dtest=Cascade*Test,Sqlite*Test` 共 365 用例全绿（较 R2 的 324 增加 41，为本次新增与扩充的用例）。

**R4 待办**：删 `tb_cascade_channel` 与对应 DO/Mapper/Service/Manager 公开面，换掉
`SqliteSchemaInitializer` 的 sentinel 表，去掉 `CascadeChannelManager` 双写，
并把「通道推给了哪些平台」的反查 API 收敛到发布门面。

### R4 老表退场（已完成，contract）

| 交付物 | 内容 |
| --- | --- |
| 读路径先去表依赖 | `CascadeChannelManager` 重写为「发布门面 + 本地资产投影」：`add`/`update`/`revoke`/`publish`/`delete`/`getPage`/反查全部只读写 `tb_cascade_publish` + `tb_device_channel` |
| 投影入口 | `CascadePublishedAssetResolver` 抽出 `project(rows, requireEnabled)`，新增 `projectChannels`（页面用，不看启停）/ `findChannel`（按业务键反查）/ `getChannel`（按发布行主键） |
| 发布层查询 | `CascadePublishManager` 加 `getById` / `getByCode` / `listByType`；`DeviceChannelManager` 加 `getByChannelId` |
| 删表迁移 | 新建 `SqliteCascadeChannelDropMigrator`（迁移键 `cascade-channel-drop`），`DROP TABLE IF EXISTS tb_cascade_channel` |
| 删表守护 | 老表还有行、而 `cascade-channel-into-publish` 未登记时**拒绝删表**并阻断启动——数据没搬完就删等于静默丢配置 |
| 空库判定改造 | `SqliteSchemaInitializer` 弃用 sentinel 表，改判「库里有没有任何 `tb_` 表」；`EXPECTED_TABLE_COUNT` 29 → 28 |
| 代码面清理 | 删 `CascadeChannelDO` / `CascadeChannelMapper` / `CascadeChannelService` / `CascadeChannelServiceImpl` 与 `CascadeAssembler` 的两个通道映射方法 |
| 建表脚本 | 三方言 `sql/voglander.sql` / `voglander-sqlite.sql` / `voglander-postgresql.sql` 去掉 `tb_cascade_channel` |
| 外部库脚本 | `sql/migration/{mysql,postgresql}/1.10.2-cascade-channel-drop.sql`（含删表前自查 SQL） |
| 测试 | 新增 `SqliteCascadeChannelDropMigratorTest`(5)；改造 `SqliteSchemaInitializerTest`(5)、`CascadeChannelManagerTest`、`CascadeQueryHandlerTest` |

**为什么必须换掉 sentinel 表**：原方案用「建表序列最后一张表」`tb_cascade_channel` 判定空库，
R4 之后它永久不存在；若改挑任何一张较新的表，缺少该表的**合法老库会被误判成空库**——
空库分支要执行含 `DROP TABLE` 的全量脚本，等于把现场数据清掉。代价是半途中断的坏库不再自动自愈，
改由 `verifySchemaComplete()` 的表数校验 fail-fast 暴露给人工处理。这条红线由
`nonEmptyIncompleteDb_shouldFailFastWithoutWiping` 钉住（构造残缺库并断言原有数据一行不丢）。

**为什么删表要单独一个迁移键**：搬迁与删表合在一个事务里，一旦搬迁成功而删表失败，回滚会把数据
一起退回去；分开之后老表哪怕留在库里也只是一张死表，不影响读写，重跑也安全。

**验证结论**：`-Dtest=Cascade*Test,Sqlite*Test` 共 369 用例全绿（较 R3 的 365 增加 4：
新增 5 个删表迁移器用例，移除 1 个已作废的「半途中断自愈」用例）。

### R5 前端（已完成，前端仓库）

| 交付物 | 内容 |
| --- | --- |
| 级联通道页 | 改为以本地通道为主键的列表（本地设备ID / 通道ID / 名称 / 在线 / 归属组织 / 本地启停 / 已推送平台 / 创建时间 / 操作），行操作打开「推送」抽屉逐平台配编码、名称、父节点覆盖、上报开关 |
| 组织页 | 去掉顶部平台选择器，展示全局唯一组织树；新增「已推送平台」反查抽屉（勾选/取消 + 通道处置策略）；直属通道数可钻取到 `?orgId=` 筛选的通道列表 |
| 后端补齐 | `CascadeChannelVO` 增 `parentKey` 出参；通道启停改走发布行；组织契约与设备通道字段补齐 |
| 实测 | 本地全栈（后端 8181 + 前端 5666）用 Playwright 跑通「列表列 / 推送抽屉三态回显 / 组织反查与钻取」，报告与截图见 `logs/r5-ui-report.md`、`logs/shots/` |
| 顺带修复 | 组织表列宽与 small 开关文案溢出；`common.save`、`ui.actionMessage.createSuccess` 两个未定义 i18n key（vben 未定义 key 会原样渲染） |

**为什么前端源码不在本仓库**：vben 前端是独立目录，本仓库只保留契约、接口与实测脚本；改动经 `.fe-staging` 中转后回写，避免与真实文件版本漂移。

### R6 编码规则接口（已完成，预留）

| 交付物 | 内容 |
| --- | --- |
| 接口 | 新建 `manager/code/CascadeCodeRuleService`：`enabled()` / `orgCode` / `deviceCode` / `channelCode` / `reapply(orgId, dryRun)` |
| 结果对象 | `CascadeCodeReapplyResult`：`ruleEnabled` / `dryRun` / `applied` / `changes` / `conflicts` / `note`；`Change` 保留 `before` 与 `after`，因为推 `DEL` 时要用旧编码 |
| 默认实现 | `LocalCascadeCodeRuleService`（`@Component`）：`enabled=false`，三个 code 方法原样回本地编码，`reapply` 不落库、不发报文，但仍校验组织存在 |
| 接入点 | `CascadeChannelManager.add()` 编码留空 → `deriveChannelCode()`：查本地通道主键 → 规则服务 → 空白回退本地编码；`update()` 不派生 |
| 未做（留 N3） | `POST /api/v1/cascade/org/{id}/code/reapply` 不注册路由（没有前端入口的 501 空路由只会多一份权限码负担）；七段生成、序号分配、平台内查重 |
| 测试 | 新增 `CascadeCodeRuleServiceTest`(7)、`CascadeChannelCodeDerivationTest`(4)，`CascadeChannelManagerTest` 增 1 例 |

**为什么 `update()` 不派生**：更新时编码是「空白 = 不动」的三态语义（`updateById` 跳过 null）。
若这里也回落本地编码，前端只改个名称就会把人工填的编码冲掉——「这次没传」与「要改回本地编码」必须能区分。

**为什么派生只在服务里做**：`deriveChannelCode` 只有一行调用，规则怎么算（七段拼接、序号分配、平台内查重）
全部留在实现类。N3 上线时替换 Bean 即可，`CascadeChannelManager`、目录组装器都不用改。

**为什么 `enabled()` 独立成方法**：让「开关关着」可被观测。靠「返回值等于本地编码」反推，
在本地编码恰好符合规则时会得出错误结论。

**行为零变化**：默认实现返回的正是 `channel_id`，与改动前「留空即本地编码」逐字节等价；
`CascadeChannelCodeDerivationTest` 用 mock 换掉服务返回值，钉住「派生真的走了服务」而不是又一处硬编码。

**验证结论**：定向 `CascadeCodeRuleServiceTest,CascadeChannelCodeDerivationTest,CascadeChannelManagerTest`
26 用例全绿；`-Dtest=Cascade*Test,Sqlite*Test` 共 381 用例全绿（较 R4 的 369 增加 12，
恰为本次新增的 7 + 4 + 1），零回归。

### N3 编码规则生效（N3a–N3d 已完成，N4 设备编码待做）

**两个策略定调**（按行业惯例与 `nextOrgCode()` 现有实现给出默认值）：

| 决策点 | 默认值 | 依据 |
| --- | --- | --- |
| 序号分配 | 全库同前缀取 max+1，`code_start_seq` 只作下界 | 国标只认 20 位全局唯一；按组织号段各自计数迟早撞 `uk_cascade_org_code`，撞了也只能退回全局最大值。与 `nextOrgCode()` 现有实现同构，零迁移 |
| 冲突处理 | 整批拒绝，`conflicts` 一次列全 | `uk_publish_code` 是 `(platform_id, publish_code)`、不含 `object_type`；`org_code` 又是 `object_key` / `parent_key` / `org_path` 三处结构的一部分。部分成功会产生半个新目录，而报文单向没有回滚通道 |

**N3a 组织编码生效（已完成）**

| 交付物 | 内容 |
| --- | --- |
| 纯计算器 | `manager/code/CascadeCodeBuilder`：`Segments` record（`center`/`prefix`/`head`/`withType`）+ `resolve` 逐段回落（自身 → 父组织 → 全局）+ `fromPrefix` + `join` + `nextSeq` + `seqOf` + `isGbCode`。无 Spring、无 DB，用单测钉死公式 |
| 组织发号 | `CascadeOrgManager.nextOrgCode(dto, parent)`：总闸 + 组织开关都打开才按七段生成，否则逐字节沿用旧实现；`add()` 改传父组织以支持段继承 |
| 总闸 | `gateway.cascade.code-rule.enabled`（默认 `false`）；与组织级 `code_rule_enabled` 是「与」的关系——总闸管「本实例是否成立」，组织开关管「哪个组织放行」 |
| 号池口径 | **13 位匹配**（前缀 + 类型，忽略网络位）：旧实现就是跳过网络位取末 6 位，收紧到 14 位会把历史数据漏出计数范围，新号立刻撞车 |
| 测试 | `CascadeCodeBuilderTest`(14)、`CascadeOrgCodeRuleTest`(5)：只测公式与开关，不测推送 |

**N3b 通道编码派生（已完成）**

| 交付物 | 内容 |
| --- | --- |
| 规则解析 | `CascadeOrgManager.effectiveCodeRule(orgId)` → `OrgCodeRule(segments, startSeq)`：段位已逐段回落完成，号段起点随组织下发给它的设备与通道 |
| 通道编码 | `LocalCascadeCodeRuleService.channelCode`：组织未开规则则回 `channel_id`；已发布过的国标编码**直接复用**；否则「组织段 + 类型 131 + 全库号池」发号 |
| 号池 | `publish_code` ∪ 本地 `channel_id`。本地标识也要进池——历史数据里通道标识本身就是 20 位国标码，只数发布行会漏掉它们，撞的是上级目录重号，更难发现 |
| 设备编码 | 仍回本地 `device_id`：仓库里还没有创建 `DEVICE` 发布行的入口，现算的号无处落库，下次调用就可能变 |
| 测试 | `CascadeChannelCodeRuleTest`(4)：按段生成 / 总闸关 / 组织关 / 已发布编码复用 |

**为什么通道编码必须「先查已有、再发号」**：同一路通道可以推给多个上级。每推一个平台就重新发号，
同一个对象在不同上级那里编码不同，对账与跨平台核查全部失效，而这种错在单平台上完全看不出来。

**N3c 编码重算落库（已完成）**

| 交付物 | 内容 |
| --- | --- |
| 规划 | `CascadeOrgManager.planCodeReapply(rootId)` **只算不写**：返回 `CodeReapplyPlan(changes, conflicts)`，`changes` 含未变更项以便页面回显「本来就对」的节点 |
| 幂等 | 只重算「不符合当前规则」的节点（`seqOf(after, head) <= 0`）；否则每点一次重编码，同一批组织就整体换号，上级要跟着做一次全量删除重建 |
| 发号 | 号池实时追加：一次重算多个节点若各自取 max+1 会算出同一个号 |
| 落库 | `applyCodeReapply(plan)` 单事务：① 整批挪到 `T<id>`（非数字前缀，不参与发号，长度 ≤ 20）② 写目标编码 + 重建整棵子树 `org_path` ③ 同步 `tb_cascade_publish.object_key` 与通道 `parent_key` ④ 提交后逐平台发 `DEL` |
| 为什么两阶段 | A↔B 互换编码时任何一条先写都会撞 `uk_cascade_org_code`；中间态从外部看只是「编码暂时不可识别」，不会短暂重号 |
| 为什么同步发布行 | `object_key` 就是 `org_code`，通道的 `parent_key` 也可能指着它；只改组织表等于把发布配置全部变成孤儿 |
| 平台内查重 | `publishConflicts`：`uk_publish_code` 不含 `object_type`，所以按「同平台 + 编码」跨类型查；`self` 判定为「同平台 + ORG + object_key 等于旧编码」，否则组织的既有发布行会被自己判成冲突 |
| 路由 | `POST /api/v1/cascade/org/{id}/code/reapply?dryRun=`，`dryRun` **默认 true**：漏传参数不等于「确认执行」。返回 `CascadeCodeReapplyResult` |
| Service | `LocalCascadeCodeRuleService.reapply` 六分支：总闸未启用 → 空转；组织未放行 → 空转（与「已合规」分开说明）；有冲突 → 整批拒绝；无变更 → 幂等；`dryRun` → 预览；否则落库 |
| 开关区分 | `CascadeOrgManager.codeRuleEnabled(orgId)`：总闸开但组织开关关时，子节点全部被跳过，`changes` 同样为空——报成「已符合规则」会让人去查一个没问题的总闸 |
| 测试 | `CascadeCodeReapplyTest`(5)：dryRun 不写库 / 落库后 code+path+object_key+parent_key 全同步 / 已符合规则幂等 / 组织未放行不报合规 / 冲突整批拒绝；`CascadeOrgControllerTest` +2（默认 dryRun、显式落库） |

**N3d 前端入口（已完成，前端仓库）**

| 交付物 | 内容 |
| --- | --- |
| API | `api/cascade/org.ts`：`CodeReapplyResult` / `CodeReapplyChange` + `reapplyCascadeOrgCode(id, dryRun = true)` |
| 入口 | 组织行操作增「重算编码」（复用 `Cascade:Org:Edit`），操作列宽 360 → 440 |
| 弹窗 | `modules/code-reapply-modal.vue`：打开即 `dryRun` 试算，按「规则未启用 / 有冲突 / 无变更 / 有变更」四种结果分支展示；只有第四种把确认按钮改成「开始重算」，其余皆为「知道了」且点下去只关窗不落库 |
| 变更明细 | 组织名 + 旧编码（划线）→ 新编码；组织名按旧编码反查组织树索引，落库后才换键，所以每次都查得到 |
| i18n | `cascade.org.code.*` 中英双份 |
| 测试 | `org-data.test.ts` +1（操作列含 `reapplyCode` 且在 `delete` 之前） |

**与 R6 阶段设想的偏差（如实记录）**

| 原设想 | 实际 | 原因 |
| --- | --- | --- |
| 重算后一并重算通道 `publish_code` | **不动通道编码** | 通道编码一旦上报过，就是上级侧历史录像、订阅与点播的引用键；跟着换会让每路摄像机在上级变成一台「新设备」。重编码只改组织，通道靠 `parent_key` 跟着搬到新父节点下 |
| 中间前缀 `__tmp_` | `T<id>` | `org_code` 为 20 位定长，`__tmp_` 超长；`T` 非数字，不会与任何合法国标编码相撞 |
| 独立权限码 `Cascade:Org:Code` | 复用 `Cascade:Org:Edit` | 权限码是登录时的快照，新增一个码要让所有人重登才生效；重编码动的是目录结构，与编辑同一量级 |

**N3 剩余事项**

| 事项 | 待决点 |
| --- | --- |
| 七段配置 UI | 已在 N4a 补齐（见下节） |
| 设备编码落库 | 仓库里还没有创建 `DEVICE` 发布行的入口，`deviceCode` 仍回本地 `device_id` |
| 总闸运行期可切 | `gateway.cascade.code-rule.enabled` 是 `@Value` 启动期常量，改配置需重启；两个 Bean 各持一份，暂无运行期开关 |

**验证结论**：N3a 定向 30 用例全绿、N3b 定向 34 用例全绿、N3c 定向 39 用例全绿；
`-Dtest=Cascade*Test,Sqlite*Test` 共 **411** 用例全绿（较 N3b 的 404 增加 7 = `CascadeCodeReapplyTest` 5 + `CascadeOrgControllerTest` 2），零回归。
前端：`pnpm typecheck` 通过、`eslint` 通过、`vitest run --dom src/views/cascade` 21 用例全绿。

### N4a 组织七段配置 UI（已完成，前端仓库）

**为什么必须做**：N3a/N3b/N3c 全部是「规则打开后才成立」的分支，而真实前端此前 0 匹配七段字段——
`code_rule_enabled` 永远写不进 1，组织开关与总闸都打不开，三批后端能力全部处于待命状态。

| 交付物 | 内容 |
| --- | --- |
| API | `api/cascade/org.ts`：抽 `OrgCodeRuleReq` 给 `OrgCreateReq` / `OrgUpdateReq` 共用；VO 补七段回显字段 |
| 表单 | `useOrgFormSchema` 增「编码段规则」分组：`Divider` 分段 + `Switch` 开关 + 六段 `Input` + `Select`(215/216) + 号段起点 `InputNumber`；六段用 `dependencies.show` 随开关展开 |
| 段位用字符串不用数字控件 | 省/县/行业段都可能以 0 开头（如 `0101`、`03`），`InputNumber` 会吃掉前导 0，拼出来的编码少一位 |
| 提交语义 | `collectOrgCodeRule()` 统一整理：开关关闭**整组不传**（后端 `null` = 不改，否则一关开关就清空已配段位）；打开时空段传 `''`（后端 `trimToNull` 后回落父/全局）；号段起点留空落 `0`（不设下界，不能用 `null`） |
| 位宽校验 | 提交前按 `ORG_CODE_SEGMENTS` 校验位数与纯数字：后端 `join()` 抛错发生在写入路径上，只能变成 500，用户看不出是哪一段填错了 |
| 新增/编辑都给 | 新增时打开规则直接用段位出码（省一次「建完再重算」）；编辑时只能改配置，生效仍需再点「重算编码」 |
| 回显修正 | 编辑抽屉原来把 `orgPath` 当「组织编码」展示；改显 `orgCode` 并与段位对照。同时补 `resetForm()`，避免上一行组织的段位残留到下一行 |
| 列表标识 | 组织编码列后加「规则」小标（`codeRuleEnabled === 1` 才显示）：重算对未启用组织是空转，不标出来分不清 |
| i18n | `cascade.org.code.rule.*` 中英双份（含「保存不改编码」与总闸提示） |
| 测试 | `org-data.test.ts` 8 → 15：两模式都含七段 / 六段随开关显隐 / 开关关整组不传 / 开关开提交七段 / 空段传空串 / 三种位宽非法 / 号段起点 `null` 与空都落 0 |

**验证**：`pnpm typecheck` 通过、`eslint` 通过、`vitest run --dom src/views/cascade` **28** 用例全绿（较 N3d 的 21 增加 7）。
本批零 Java 改动，未重跑后端用例（上一批 411 全绿仍有效）。
**未做视觉验收**：抽屉里的 `Divider` 分段与六段联动的实际观感需浏览器确认（未在本批范围内）。
