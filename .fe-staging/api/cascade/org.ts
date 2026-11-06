import { requestClient } from '#/api/request';

/**
 * 级联组织 API（Cascade Org）。
 *
 * 组织是**本地资产**：全库只有一棵树，没有平台维度。同一个组织推给哪几个上级由
 * `publishedPlatformIds` 表达，「某平台的组织树」= 这棵树按平台过滤后的视图，
 * 而不是另取一份组织数据。
 *
 * 组织编码（20 位国标编码）由后端按规则生成，**不接受前端直接填写**：上级按编码建树，
 * 改编码等价于删掉旧节点再建一个新节点，已挂在该组织下的通道会变成孤儿。
 * 唯一的改写入口是「重算编码」（`/{id}/code/reapply`），它按当前七段配置整棵子树重算。
 * 故新增/更新入参都不含 orgCode。
 *
 * 后端响应统一 AjaxResult{code,msg,data}，requestClient 已配 `responseReturn:'data'`
 * + `successCode:0`，故下列函数直接拿到 `data` 本体。
 *
 * Base URL: `/api/v1/cascade/org`
 */
export namespace CascadeOrgApi {
  /** 组织 VO（镜像后端 CascadeOrgVO，时间 Unix 毫秒）。 */
  export interface CascadeOrgVO {
    id?: number;
    createTime?: number;
    updateTime?: number;
    /** 父组织主键，空表示根组织（直接挂在域节点下） */
    parentId?: number;
    /**
     * 物化路径（只读）：根到自身的**编码**链，形如 `/A/B/`。
     * 层级 = 去空段后的段数；面包屑按段反查组织名称。
     */
    orgPath?: string;
    /** 本地组织编码（国标 20 位，后端生成，不可变更） */
    orgCode?: string;
    orgName?: string;
    /** 同级排序，越小越靠前 */
    sortOrder?: number;
    /**
     * 本地启用 1启用 / 0停用。
     *
     * 与「不推给某个平台」是两件事：后者是发布行 enabled=0，组织在本地照常可用；
     * 本地停用会让其下设备与通道从**所有**上级目录里消失。
     */
    enabled?: number;
    /**
     * 本地是否真的可达：自身与整条祖先链都启用。
     *
     * 祖先停用后子孙的开关仍是 1，但它们已经随祖先从所有平台目录里消失，
     * 只看 enabled 会显示成「启用中、上级其实看不到」。
     */
    effective?: boolean;
    /** 被哪一级组织连带停用；生效时为 null */
    blockedByOrgName?: null | string;
    /**
     * 已推送到的上级平台编码，**含已停用的发布行**。
     *
     * 含停用行是刻意的：页面要能区分「配了但没开」与「根本没配」——
     * 前者打开开关就生效，后者要先勾选平台。
     */
    publishedPlatformIds?: string[];
    /**
     * 0=未启用（后端默认生成编码） 1=按七段规则生成。
     *
     * 与总闸 gateway.cascade.code-rule.enabled 是「与」的关系：总闸关时这里写 1 也不生效。
     */
    codeRuleEnabled?: number;
    /** 省编码（2 位，空 = 沿用父组织） */
    codeProvince?: string;
    /** 市编码（2 位，空 = 沿用父组织） */
    codeCity?: string;
    /** 县区编码（4 位，空 = 沿用父组织） */
    codeCounty?: string;
    /** 行业编码（2 位，空 = 沿用父组织） */
    codeIndustry?: string;
    /** 类型编码（3 位，215 业务分组 / 216 虚拟组织，空 = 沿用父组织） */
    codeType?: string;
    /** 网络标识（1 位，空 = 沿用父组织） */
    codeNetwork?: string;
    /** 起始序号（只作号池下界：0 或空表示不设下界） */
    codeStartSeq?: number;
    /** 直属通道数（仅树形出参填充，不含子组织） */
    channelCount?: number;
    /** 子组织（仅树形出参填充） */
    children?: CascadeOrgVO[];
  }

  /**
   * 编码段规则（七段 + 开关）。
   *
   * 单独抽成接口是因为新增与更新两处入参同口径，而「哪些字段可不传」的语义很反直觉：
   * `undefined`（JSON 里直接不出现）表示**不改**，空串表示**清空并按回落链取值**。
   * 前端不是「多态更新」而是整份提交，故不需要三态，只在开关关闭时整组不传。
   */
  export interface OrgCodeRuleReq {
    /** 0=未启用（后端默认生成编码） 1=按七段规则生成 */
    codeRuleEnabled?: number;
    codeProvince?: string;
    codeCity?: string;
    codeCounty?: string;
    codeIndustry?: string;
    codeType?: string;
    codeNetwork?: string;
    /** 号池下界；0 表示不设下界 */
    codeStartSeq?: number;
  }

  /** 组织新增请求（编码由后端生成，不接受 orgCode）。 */
  export interface OrgCreateReq extends OrgCodeRuleReq {
    /** 父组织主键；为空表示根组织 */
    parentId?: number;
    orgName: string;
    sortOrder?: number;
    enabled?: number;
    /** 新增后立即发布到的平台；为空表示先只建本地组织 */
    platformIds?: string[];
  }

  /**
   * 组织更新请求：名称 / 排序 / 本地启停 / 编码段规则。
   *
   * 换父节点走 move，换编码只能走「重算编码」——两者都会让上级目录重建，
   * 不能从编辑接口悄悄提交。
   */
  export interface OrgUpdateReq extends OrgCodeRuleReq {
    id: number;
    orgName?: string;
    sortOrder?: number;
    enabled?: number;
  }

  /**
   * 通道处置策略：组织在该平台不可见时，其下通道怎么办。
   *
   * CASCADE：通道跟着组织一起从上级目录移除（默认）
   * REROUTE：只移除组织，通道改挂到域节点下继续上报
   */
  export type RevokePolicy = 'CASCADE' | 'REROUTE';

  /** 某组织的发布配置（反查视图单行，含已停用行）。 */
  export interface OrgPublishVO {
    id?: number;
    platformId?: string;
    /** 固定为 ORG */
    objectType?: string;
    /** 本地组织编码 */
    objectKey?: string;
    /** 父节点覆盖；组织挂在平台根节点下时为空 */
    parentKey?: string;
    /** 上报编码覆盖；空 = 沿用本地编码 */
    publishCode?: string;
    /** 上报名称覆盖；空 = 沿用本地名称 */
    publishName?: string;
    /** 1 发布 / 0 未发布（配置保留） */
    enabled?: number;
    sortOrder?: number;
    /** 该平台的通道处置策略 */
    revokePolicy?: RevokePolicy;
  }

  /** 配置发布范围：未勾选的平台置停用，但保留编码/名称覆盖。 */
  export interface OrgPublishReq {
    platformIds: string[];
    publishCodeByPlatform?: Record<string, string>;
    publishNameByPlatform?: Record<string, string>;
    revokePolicyByPlatform?: Record<string, RevokePolicy>;
  }

  /** 受影响节点（组织或通道）在上级目录里的身份。 */
  export interface RevokeNodeVO {
    /** 上级目录里的节点编码 */
    cascadeId?: string;
    /** 上级目录里显示的节点名称 */
    name?: string;
    /** 上级目录里的父节点编码；根组织为空，由发送方回落域编码 */
    parentId?: string;
  }

  /** 单个平台的投递条件与策略。 */
  export interface DeliveryVO {
    platformId?: string;
    revokePolicy?: RevokePolicy | string;
    /** 上级平台是否在线（离线时 DEL 发出去就丢） */
    platformOnline?: boolean;
    /** 上级是否建立了有效的目录订阅 */
    catalogSubscribed?: boolean;
    /** 上面的报文此刻能不能真正送达 */
    deliverable?: boolean;
    warnings?: string[];
  }

  /**
   * 本地停用影响面预览。
   *
   * orgs / channels 是「会被动到的节点」，channelCount 不参与 deleteCount：
   * REROUTE 下通道是改挂而不是删除，算进删除数会让用户以为操作量翻倍。
   */
  export interface RevokePreviewVO {
    orgId?: number;
    orgCode?: string;
    orgName?: string;
    orgs: RevokeNodeVO[];
    channels: RevokeNodeVO[];
    /** 会被移出上级目录的组织数（含全部下级） */
    orgCount: number;
    /** 受影响的通道数：CASCADE 会被删，REROUTE 会被改挂 */
    channelCount: number;
    /** 实际会发出的删除报文条数 */
    deleteCount: number;
    /** 逐平台的策略与投递风险：各平台策略可以不同，混在一起会分不清该修哪个 */
    deliveries: DeliveryVO[];
    /** 风险提示，恒为数组（可能为空） */
    warnings: string[];
  }

  /** 批量停用的单条结果。 */
  export interface RevokeBatchItem {
    id?: number;
    orgName?: string;
    ok?: boolean;
    /** 失败原因；成功时为空 */
    message?: string;
  }

  /** 批量停用结果（逐条隔离，一条失败不影响其余）。 */
  export interface RevokeBatchResp {
    total: number;
    succeeded: number;
    failed: number;
    items: RevokeBatchItem[];
  }

  /**
   * 批量推送请求：一批组织 × 一批上级平台。
   *
   * 与 `OrgPublishReq` 的区别：那个是「一个组织配哪些平台，逐平台带编码/名称/策略覆盖」，
   * 这里是「一批组织一起推」，**不带覆盖**——批量只决定推不推，覆盖值回单条发布抽屉里改。
   */
  export interface OrgPublishBatchReq {
    ids: number[];
    platformIds: string[];
    /**
     * 是否把祖先一并推上去，不传按 true。
     *
     * 默认开：只勾叶子时它在上级目录里立不起来（父节点上级没有），
     * 组装器会静默剔除，页面「已推送」而上级一条收不到。
     */
    includeAncestors?: boolean;
  }

  /** 批量推送的单条结果。 */
  export interface OrgPublishBatchItem {
    id?: number;
    orgName?: string;
    orgCode?: string;
    /** 是否为「为了让它立起来而自动补推的祖先」，不是你勾的 */
    ancestor?: boolean;
    ok?: boolean;
    /** 失败原因；成功时为空 */
    message?: string;
  }

  /** 批量推送结果（逐条隔离，按层级由浅到深执行）。 */
  export interface OrgPublishBatchResp {
    /** 实际执行的组织数（含自动补入的祖先） */
    total: number;
    succeeded: number;
    failed: number;
    /** 其中自动补推的祖先数 */
    ancestorsAdded: number;
    items: OrgPublishBatchItem[];
  }

  /** 单个对象的编码变更。 */
  export interface CodeReapplyChange {
    /** ORG / DEVICE / CHANNEL，与发布行 object_type 同口径 */
    objectType?: string;
    /** 业务键：组织编码、设备 ID 或 `设备ID:通道ID` */
    objectKey?: string;
    /** 改之前的编码 */
    before?: string;
    /** 改之后的编码 */
    after?: string;
  }

  /**
   * 编码重算结果。
   *
   * `ruleEnabled=false` 表示编码规则没开（总闸或组织开关），一切都是空结果；
   * `dryRun=true + applied=false` 是预览；`conflicts` 非空表示已整批拒绝、一条都没写。
   * before/after 成对返回是有意的：推 DEL 的一方必须拿得到旧编码。
   */
  export interface CodeReapplyResult {
    ruleEnabled: boolean;
    dryRun: boolean;
    applied: boolean;
    changes: CodeReapplyChange[];
    /** 平台内查重冲突，出现即整批回滚 */
    conflicts: string[];
    /** 后端的一句话结论，可直接回显 */
    note?: string;
  }

  /**
   * 某上级平台目录里的组织清单。
   *
   * 与 `getCascadeOrgTree` 的区别：这是**上级看到的样子**，编码/名称/父节点都可能是
   * 平台级覆盖值（`platformCode` / `parentPlatformCode`），不是本地那棵树。
   *
   * items 是「这一刻在上级目录里真实存在」的节点；blocked 是「用户勾了推送、却因为
   * 自己停用或祖先没发布而没上去」的节点——只给 items 的话，用户会以为勾选丢了。
   * 两份出自同一次计算，同一节点不会两边都没有。
   */
  export interface PlatformOrgCatalogVO {
    /** 上级平台编码（请求参数原样回显） */
    platformId?: string;
    /** 已进入上级目录的组织，按上级排序值升序 */
    items: PlatformOrgCatalogItemVO[];
    /** 已勾选推送但被阻断的组织 */
    blocked: PlatformBlockedOrgVO[];
    deliveredCount: number;
    blockedCount: number;
    /** 因祖先阻断、通道改挂域节点继续上报的组织数 */
    reroutedCount: number;
  }

  /** 上级目录里的单个组织节点（字段与后端 ItemVO 同口径）。 */
  export interface PlatformOrgCatalogItemVO {
    /** 本地组织主键，用于跳回本地树 */
    id?: number;
    parentId?: number;
    /** 本地组织编码 */
    localCode?: string;
    /** 该平台眼里的编码：发布行有覆盖时为覆盖值 */
    platformCode?: string;
    /** 该平台眼里的名称 */
    name?: string;
    sortOrder?: number;
    /** 上级眼里的父节点编码；根组织为空 */
    parentPlatformCode?: string;
    /** 该组织的通道是否因自己立不起来而改挂域节点 */
    rerouted?: boolean;
  }

  /** 勾了推送却没进上级目录的组织，带「谁挡住了」。 */
  export interface PlatformBlockedOrgVO {
    id?: number;
    localCode?: string;
    name?: string;
    /** 阻断节点编码（可能是自己） */
    blockedByCode?: string;
    /** 阻断节点名称 */
    blockedByName?: string;
    /** LOCAL_DISABLED | ANCESTOR_LOCAL_DISABLED | ANCESTOR_NOT_PUBLISHED */
    reason?: string;
  }
}

/**
 * 新增级联组织（返回新增记录 id）
 */
export async function createCascadeOrg(data: CascadeOrgApi.OrgCreateReq) {
  return requestClient.post<number>('/api/v1/cascade/org', data);
}

/**
 * 更新级联组织（父节点与组织编码不可改）
 */
export async function updateCascadeOrg(
  id: number,
  data: CascadeOrgApi.OrgUpdateReq,
) {
  return requestClient.put<boolean>(`/api/v1/cascade/org/${id}`, data);
}

/**
 * 移动组织：整棵子树改挂到另一个父组织下（targetParentId 为空表示移到根）。
 *
 * 组织编码不变，但上级目录没有「移动节点」语义，后端会先按旧父逐条 DEL 再重推快照，
 * 对上级而言这是一次重建，因此调用前必须让用户看到影响面。
 */
export async function moveCascadeOrg(id: number, targetParentId?: number) {
  return requestClient.post<boolean>(`/api/v1/cascade/org/${id}/move`, {
    targetParentId,
  });
}

/**
 * 删除级联组织（仍有子组织或已绑定通道时后端拒绝）
 */
export async function deleteCascadeOrg(id: number) {
  return requestClient.delete<boolean>(`/api/v1/cascade/org/${id}`);
}

/**
 * 查询级联组织详情
 */
export async function getCascadeOrgById(id: number) {
  return requestClient.get<CascadeOrgApi.CascadeOrgVO>(
    `/api/v1/cascade/org/${id}`,
  );
}

/**
 * 平铺查询级联组织（按 sortOrder 升序，不含 children）
 */
export async function listCascadeOrgs() {
  return requestClient.get<CascadeOrgApi.CascadeOrgVO[]>(
    '/api/v1/cascade/org/list',
  );
}

/**
 * 树形查询级联组织（带 children 与直属通道数）
 *
 * 传 rootOrgCode 时只返回该组织的子树，供前端懒加载。
 */
export async function getCascadeOrgTree(rootOrgCode?: string) {
  return requestClient.get<CascadeOrgApi.CascadeOrgVO[]>(
    '/api/v1/cascade/org/tree',
    { params: { rootOrgCode } },
  );
}

/**
 * 以某组织为根的子树（路径参数为主键 id，返回的顶层节点就是该组织自身）
 */
export async function getCascadeOrgSubtree(id: number) {
  return requestClient.get<CascadeOrgApi.CascadeOrgVO[]>(
    `/api/v1/cascade/org/${id}/tree`,
  );
}

/**
 * 平铺查询某组织下的全部子组织（任意层级一次取全）
 */
export async function listCascadeOrgDescendants(
  id: number,
  includeSelf = false,
) {
  return requestClient.get<CascadeOrgApi.CascadeOrgVO[]>(
    `/api/v1/cascade/org/${id}/descendants`,
    { params: { includeSelf } },
  );
}

/**
 * 本地停用的影响面预览（只计算，不落库不发报文）。
 *
 * 返回值里的 deliveries 是逐平台的策略与投递风险：上级离线或未订阅目录时
 * DEL 发出去就丢了，本地却已经记为停用，两边会永久错位。
 */
export async function previewCascadeOrgDisable(id: number) {
  return requestClient.get<CascadeOrgApi.RevokePreviewVO>(
    `/api/v1/cascade/org/${id}/disable/preview`,
  );
}

/**
 * 本地停用：整棵子树从各平台目录移除，本地数据一条不删，随时可用 enable 恢复。
 */
export async function disableCascadeOrg(id: number) {
  return requestClient.post<boolean>(`/api/v1/cascade/org/${id}/disable`);
}

/**
 * 本地启用：上级目录靠快照 upsert 把整棵子树加回来。
 *
 * 祖先组织仍处于停用状态时后端会拒绝（否则只会得到一棵挂不上去的子树）。
 */
export async function enableCascadeOrg(id: number) {
  return requestClient.post<boolean>(`/api/v1/cascade/org/${id}/enable`);
}

/**
 * 按当前编码规则重算该组织子树的国标编码。
 *
 * **先预览再落库**：`dryRun` 默认 true，只有显式传 false 才写库。
 * 写入会同时改整棵子树的 orgCode、orgPath、发布行的 objectKey 与通道的 parentKey，
 * 并逐平台推 DEL 让上级重建——这正是必须预览的原因。
 *
 * 只重算组织：通道编码一旦上报过，就是上级侧历史录像/订阅/点播的引用键，
 * 跟着换会让每路摄像机在上级变成一台「新设备」。
 */
export async function reapplyCascadeOrgCode(id: number, dryRun = true) {
  return requestClient.post<CascadeOrgApi.CodeReapplyResult>(
    `/api/v1/cascade/org/${id}/code/reapply`,
    undefined,
    { params: { dryRun } },
  );
}

/**
 * 批量本地停用：逐条独立执行，单条失败不中断整批，结果按条返回。
 */
export async function disableCascadeOrgsBatch(ids: number[]) {
  return requestClient.post<CascadeOrgApi.RevokeBatchResp>(
    '/api/v1/cascade/org/disable/batch',
    { ids },
  );
}

/**
 * 查询该组织的发布配置（含已停用的发布行）——「已推送平台」反查视图的数据源。
 */
export async function getCascadeOrgPublish(id: number) {
  return requestClient.get<CascadeOrgApi.OrgPublishVO[]>(
    `/api/v1/cascade/org/${id}/publish`,
  );
}

/**
 * 配置该组织的发布范围与逐平台覆盖。
 *
 * 不删行只置停用：删掉等于把编码覆盖一起丢掉，重新勾选时用户得重填一遍。
 * 返回值是落库后的整份配置（含未勾选但保留覆盖的行）。
 */
export async function configureCascadeOrgPublish(
  id: number,
  data: CascadeOrgApi.OrgPublishReq,
) {
  return requestClient.put<CascadeOrgApi.OrgPublishVO[]>(
    `/api/v1/cascade/org/${id}/publish`,
    data,
  );
}

/**
 * 批量推送组织到一组上级平台。
 *
 * 按层级从浅到深逐条执行、单条失败不中断整批；默认自动补齐祖先——只勾叶子组织时它在
 * 上级目录里立不起来，而报文层不会报错，页面会显示「已推送」但上级一条都收不到。
 *
 * 不带编码/名称覆盖：批量只决定「推不推」，逐平台覆盖值请用单条发布抽屉维护。
 */
export async function configureCascadeOrgPublishBatch(
  data: CascadeOrgApi.OrgPublishBatchReq,
) {
  return requestClient.post<CascadeOrgApi.OrgPublishBatchResp>(
    '/api/v1/cascade/org/publish/batch',
    data,
  );
}

/**
 * 某上级平台目录里的组织清单（只读）。
 *
 * 回答两个问题：「这个平台到底收到了哪些组织」与「我勾了推送却没上去的是哪些、卡在哪」。
 * 平台页与组织页共用本接口，避免两处各拼一套过滤逻辑后口径漂移。
 */
export async function getCascadePlatformOrgCatalog(platformId: string) {
  return requestClient.get<CascadeOrgApi.PlatformOrgCatalogVO>(
    '/api/v1/cascade/org/published',
    { params: { platformId } },
  );
}
