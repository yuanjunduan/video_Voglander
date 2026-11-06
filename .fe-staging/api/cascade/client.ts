import { requestClient } from '#/api/request';

/**
 * 级联本端客户端 API（Cascade Local Client）。
 *
 * 本端客户端 = 「本端以哪个身份向上级注册」：20 位平台编码 + Contact 头对外通告 IP。
 * 上级平台只存它的 `clientRefId`，因此改一次 IP，引用它的所有平台同时生效。
 *
 * 后端响应统一 AjaxResult{code,msg,data}，requestClient 已配 `responseReturn:'data'`
 * + `successCode:0`，故下列函数直接拿到 `data` 本体。
 *
 * Base URL: `/api/v1/cascade/client`
 */
export namespace CascadeClientApi {
  /** 本端客户端 VO（镜像后端 CascadeClientVO，时间 Unix 毫秒）。 */
  export interface CascadeClientVO {
    id?: number;
    /** 创建时间（Unix 毫秒） */
    createTime?: number;
    /** 更新时间（Unix 毫秒） */
    updateTime?: number;
    /** 本端平台编码（20 位国标 ID）：创建后不可改 */
    clientId?: string;
    /** 名称：空=未命名，展示时回落 clientId */
    clientName?: string;
    /** Contact 头对外通告 IP（必须是上级能回包的地址） */
    clientIp?: string;
    /** 1=新增平台时的默认选项 */
    isDefault?: number;
    /** 1-启用 0-禁用 */
    enabled?: number;
    remark?: string;
    /** 仍引用该客户端的上级平台数量；>0 时删除会被后端挡住 */
    platformRefCount?: number;
  }

  /** 新增请求。clientId 创建后不可改（换编码等于换平台身份）。 */
  export interface ClientCreateReq {
    clientId: string;
    clientName?: string;
    clientIp?: string;
    isDefault?: number;
    enabled?: number;
    remark?: string;
  }

  /** 更新请求（patch 语义：不传=不改，空串=清空）。刻意不含 clientId。 */
  export interface ClientUpdateReq {
    clientName?: string;
    clientIp?: string;
    isDefault?: number;
    enabled?: number;
    remark?: string;
  }
}

/**
 * 本端客户端列表（默认项排最前）。
 *
 * @param onlyEnabled true=只返回启用的，供上级平台表单下拉使用
 */
export async function getCascadeClientList(onlyEnabled = false) {
  return requestClient.get<CascadeClientApi.CascadeClientVO[]>(
    '/api/v1/cascade/client/list',
    { params: { onlyEnabled } },
  );
}

/** 本端客户端详情 */
export async function getCascadeClientById(id: number) {
  return requestClient.get<CascadeClientApi.CascadeClientVO>(
    `/api/v1/cascade/client/${id}`,
  );
}

/** 新增本端客户端（库里没有客户端时第一条自动成为默认） */
export async function createCascadeClient(
  data: CascadeClientApi.ClientCreateReq,
) {
  return requestClient.post<number>('/api/v1/cascade/client', data);
}

/** 更新本端客户端（改通告 IP 会重建引用平台的注册调度） */
export async function updateCascadeClient(
  id: number,
  data: CascadeClientApi.ClientUpdateReq,
) {
  return requestClient.put<boolean>(`/api/v1/cascade/client/${id}`, data);
}

/** 删除本端客户端（仍被平台引用时后端拒绝） */
export async function deleteCascadeClient(id: number) {
  return requestClient.delete<boolean>(`/api/v1/cascade/client/${id}`);
}

/** 设为默认本端客户端（全局最多一条） */
export async function setDefaultCascadeClient(id: number) {
  return requestClient.post<boolean>(`/api/v1/cascade/client/${id}/default`);
}
