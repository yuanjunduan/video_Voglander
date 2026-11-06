import { requestClient } from '#/api/request';

/**
 * 级联通道管理 API（Cascade Channel Management）。
 *
 * 后端响应统一 AjaxResult{code,msg,data}，requestClient 已配 `responseReturn:'data'`
 * + `successCode:0`，故下列函数直接拿到 `data` 本体。
 *
 * Base URL: `/api/v1/cascade/channel`
 */
export namespace CascadeChannelApi {
  /** 通道映射 VO（镜像后端 CascadeChannelVO，时间 Unix 毫秒）。 */
  export interface CascadeChannelVO {
    id?: number;
    platformId?: string;
    localDeviceId?: string;
    localChannelId?: string;
    cascadeChannelId?: string;
    /**
     * 本地归属的组织编码；为空表示跟随设备归属。
     *
     * 这是本地资产上的归属，同一通道推给多个平台时值相同。
     * 目录里挂在哪个父节点完全由它推导，没有按平台的覆盖。
     */
    cascadeOrgId?: string;
    cascadeName?: string;
    /** 启用状态 0停用 1启用 */
    enabled?: number;
    /**
     * 在上级目录里是否真实可见（后端按「自身开关 + 所属组织整条祖先链都在报」算）。
     *
     * 组织停报后通道的 enabled 仍是 1，但它已随组织被 DEL 从上级目录删掉，
     * 只看 enabled 会显示成「上报中、上级却看不到」。
     */
    effective?: boolean;
    /** 所属组织被哪一级组织连带停报；仅组织不可见时才有值 */
    blockedByOrgName?: null | string;
    /**
     * 以下五项是最终上报值（覆盖值优先，留空则由设备通道数据派生），
     * 页面展示它才能核对上级将看到的目录内容。
     */
    manufacturer?: string;
    model?: string;
    address?: string;
    ipAddress?: string;
    /** 摄像机类型 1球机 2半球 3枪机 */
    ptzType?: number;
    createTime?: number;
    updateTime?: number;
  }

  /** 通道分页查询请求。 */
  export interface ChannelPageReq {
    /**
     * 上级平台编码。**不传即跨平台**：只给 localDeviceId + localChannelId 时，
     * 返回的就是「这路通道推给了哪些平台」——推送抽屉的反查口径。
     */
    platformId?: string;
    localDeviceId?: string;
    localChannelId?: string;
    cascadeChannelId?: string;
    /** 所属级联组织编码 */
    cascadeOrgId?: string;
    /**
     * 是否把子组织下的通道一并纳入（默认 false，只取直属该组织的通道）。
     *
     * 置 true 时 cascadeOrgId 变成"子树过滤"：组织树有多少层都能一次查全。
     * 此时必须同时给 platformId（组织编码只在平台内唯一）。
     */
    includeSubOrg?: boolean;
  }

  /** 通道列表响应。 */
  export interface ChannelListResp {
    total: number;
    items: CascadeChannelVO[];
  }

  /** 通道创建请求。 */
  export interface ChannelCreateReq {
    platformId: string;
    localDeviceId: string;
    localChannelId: string;
    cascadeChannelId: string;
    /**
     * 已不参与挂载：目录里的父节点由本地通道/设备的组织归属推导，
     * 后端不会用这个字段覆盖 parent_key。保留仅为兼容旧调用方。
     */
    cascadeOrgId?: string;
    cascadeName?: string;
    /** 目录项元数据（选填）：留空则按设备通道数据自动派生 */
    manufacturer?: string;
    model?: string;
    address?: string;
    ipAddress?: string;
    /** 1球机 2半球 3枪机；0 或不传为自动 */
    ptzType?: number;
  }

  /** 通道更新请求。 */
  export interface ChannelUpdateReq {
    id: number;
    platformId?: string;
    localDeviceId?: string;
    localChannelId?: string;
    cascadeChannelId?: string;
    /** 已不参与挂载，理由同 ChannelCreateReq.cascadeOrgId */
    cascadeOrgId?: string;
    cascadeName?: string;
    /** 上报状态 1上报 0不上报（1→0 会向上级推 DEL，0→1 推 ADD） */
    enabled?: number;
    /** 传空串表示改回自动派生，不传表示不改 */
    manufacturer?: string;
    model?: string;
    address?: string;
    ipAddress?: string;
    /** 0 表示改回自动派生，不传表示不改 */
    ptzType?: number;
  }

  /** 批量绑定单条。 */
  export interface BatchBindItem {
    localDeviceId?: string;
    localChannelId: string;
    cascadeChannelId?: string;
    cascadeName?: string;
  }

  /** 批量绑定请求。 */
  export interface BatchBindReq {
    platformId: string;
    /** 整批统一挂到该组织下；为空则挂在平台根节点 */
    cascadeOrgId?: string;
    channels: BatchBindItem[];
  }

  /** 批量推送的单条本地通道（只看本地资产，不含平台维度）。 */
  export interface BatchPublishItem {
    localDeviceId?: string;
    localChannelId: string;
  }

  /**
   * 批量推送请求：一批本地通道 × 一批上级平台。
   *
   * 目标通道两种给法二选一：`channels`（列表里勾选的）或 `orgId`+`includeChildren`
   * （该组织下所有设备的通道）。两者都给时以 `orgId` 为准。
   */
  export interface BatchPublishReq {
    /** 目标上级平台（必填，可多选） */
    platformIds: string[];
    /** 显式勾选的通道；给了 orgId 时忽略 */
    channels?: BatchPublishItem[];
    /** 按组织推送；0 表示「未归组织」的设备，不是错误值 */
    orgId?: number;
    /** 按组织推送时是否含下级组织（默认 true） */
    includeChildren?: boolean;
  }

  /** 批量推送失败明细。 */
  export interface BatchPublishFailure {
    localDeviceId?: string;
    localChannelId?: string;
    /** 目标平台；为空表示通道在解析阶段就没找到（与平台无关） */
    platformId?: string;
    reason?: string;
  }

  /** 批量推送结果：逐类计数 + 失败明细。 */
  export interface BatchPublishResult {
    /** 本次处理的本地通道数（去重后） */
    channelCount: number;
    /** 目标平台数 */
    platformCount: number;
    /** 新建的发布行数 */
    created: number;
    /** 早已配置且正在上报，跳过 */
    skippedEnabled: number;
    /** 早已配置但处于停报状态，跳过（不会自动恢复） */
    skippedDisabled: number;
    failures: BatchPublishFailure[];
  }
}

/**
 * 新增通道映射
 */
export async function createCascadeChannel(
  data: CascadeChannelApi.ChannelCreateReq,
) {
  return requestClient.post<number>('/api/v1/cascade/channel', data);
}

/**
 * 更新通道映射
 */
export async function updateCascadeChannel(
  id: number,
  data: CascadeChannelApi.ChannelUpdateReq,
) {
  return requestClient.put<number>(`/api/v1/cascade/channel/${id}`, data);
}

/**
 * 删除通道映射
 */
export async function deleteCascadeChannel(id: number) {
  return requestClient.delete<boolean>(`/api/v1/cascade/channel/${id}`);
}

/**
 * 查询通道详情
 */
export async function getCascadeChannelById(id: number) {
  return requestClient.get<CascadeChannelApi.CascadeChannelVO>(
    `/api/v1/cascade/channel/${id}`,
  );
}

/**
 * 分页查询通道列表（POST /getPage，page/size 走 query，条件走 body）。
 *
 * `includeSubOrg=true` 时 `cascadeOrgId` 变成子树过滤：组织树任意层级下的通道
 * 一次查全，前端不必逐层请求（此时必须同时给 platformId）。
 */
export async function getCascadeChannelPage(params: {
  cascadeChannelId?: string;
  cascadeOrgId?: string;
  includeSubOrg?: boolean;
  localChannelId?: string;
  localDeviceId?: string;
  page: number;
  platformId?: string;
  size: number;
}) {
  const { page, size, ...body } = params;
  return requestClient.post<CascadeChannelApi.ChannelListResp>(
    `/api/v1/cascade/channel/getPage`,
    body,
    { params: { page, size } },
  );
}

/**
 * 批量绑定级联通道（已存在的跳过，返回新增条数）。
 */
export async function batchBindCascadeChannels(
  data: CascadeChannelApi.BatchBindReq,
) {
  return requestClient.post<number>('/api/v1/cascade/channel/batchBind', data);
}

/**
 * 批量推送（多平台 / 按组织）。
 *
 * 与 `batchBindCascadeChannels` 的分工：那个只能「一个平台 + 一堆通道编码」；
 * 这里可以一次推给多个平台，也可以按组织整批推（`orgId` + `includeChildren`）。
 *
 * 已存在的发布行只跳过、不改回上报：返回结果里 `skippedEnabled`/`skippedDisabled`
 * 分开计数，`failures` 逐条给原因（组织不在该平台目录里、本地通道不存在等）。
 */
export async function batchPublishCascadeChannels(
  data: CascadeChannelApi.BatchPublishReq,
) {
  return requestClient.post<CascadeChannelApi.BatchPublishResult>(
    '/api/v1/cascade/channel/batchPublish',
    data,
  );
}

/**
 * 取消该通道同步：向上级推 Catalog Event=DEL，发布行保留可随时恢复。
 *
 * 后端端点是 `/{id}/disable`（早期写成 /revoke，会 404）。
 */
export async function revokeCascadeChannel(id: number) {
  return requestClient.post<boolean>(`/api/v1/cascade/channel/${id}/disable`);
}

/**
 * 恢复该通道同步：向上级推 Catalog Event=ADD。
 *
 * 父节点不会出现在该平台目录里时后端会拒绝，避免产生挂不住的孤儿通道。
 * 后端端点是 `/{id}/enable`（早期写成 /publish，会 404）。
 */
export async function publishCascadeChannel(id: number) {
  return requestClient.post<boolean>(`/api/v1/cascade/channel/${id}/enable`);
}
