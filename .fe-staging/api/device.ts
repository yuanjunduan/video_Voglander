import { requestClient } from '#/api/request';

/**
 * GB28181 设备管理 API（S4 前端）。
 *
 * 后端响应统一 AjaxResult{code,msg,data}，requestClient 已配 `responseReturn:'data'`
 * + `successCode:0`，故下列函数直接拿到 `data` 本体。
 *
 * 端点分三类（GB28181-DEVICE-MANAGEMENT-FRONTEND-PLAN.md）：
 *  - §1.1 设备分页：`/device/getPage`（S1 新端点）
 *  - §1.3 复用既有：`/ptz/*`、`/live/*`（协议台 ServerPanel 已验证，与之同源，勿重建）
 *  - §1.2 S2 支链：`/device-cmd/*`（query-status/preset/mobile-position、config、record、alarm、broadcast）
 *
 * 路径全部用完整 `/api/v1/...` 前缀（与 protocol-lab.ts 一致）：requestClient baseURL=apiURL，
 * dev 下 `/api` 被 vite 代理剥离首段转 :8081，无 404 风险（§缺口 G5 已确认）。
 */
export namespace DeviceApi {
  /** 设备分页筛选请求（全部可选，时间为 Unix 毫秒）。 */
  export interface DevicePageReq {
    id?: number;
    deviceId?: string; // 精确
    name?: string; // 模糊
    status?: number; // 1在线/0离线
    type?: number; // 协议类型 DeviceAgreementEnum
    ip?: string; // 模糊
    serverIp?: string; // 注册节点，精确
    keepaliveTimeStart?: number; // 心跳时间范围（Unix 毫秒）
    keepaliveTimeEnd?: number;
    registerTimeStart?: number; // 注册时间范围
    registerTimeEnd?: number;
    /** 所属级联组织主键：0 表示「未归组织」（按 IS NULL 查），不传即不按组织筛 */
    orgId?: number;
    /** 按组织筛时是否包含下级组织；设备总挂在叶子组织上，不带上就会「选了上级组织一台都没有」 */
    includeChildren?: boolean;
  }

  /** 设备扩展信息（含 S3 设备应答快照，均为 JSON 字符串）。 */
  export interface ExtendInfoVO {
    serialNumber?: string;
    transport?: string;
    expires?: number;
    password?: string;
    streamMode?: string;
    charset?: string;
    /** RTSP 拉流地址（RTSP/ONVIF 协议设备专用），由 ZLM addStreamProxy 主动拉流。 */
    rtspUrl?: string;
    /**
     * RTSP 辅码流地址（可选，RTSP/ONVIF 专用）。
     *
     * 设备侧第二路低码率编码，弱网或多画面预览时切到它。未配置时请求辅码流会被后端拒绝，
     * failReason 里会明示"未配置辅码流地址"。GB28181 无此字段（INVITE 信令没有码流维度）。
     */
    rtspSubUrl?: string;
    deviceInfo?: string;
    // S3 设备应答快照（JSON 字符串）
    deviceStatus?: string;
    ptzPosition?: string;
    presets?: string;
    config?: string;
    configDownload?: string;
    sdCardStatus?: string;
  }

  /** 设备出参（时间均 Unix 毫秒，字段以 Time 结尾）。 */
  export interface DeviceVO {
    id: number;
    deviceId: string;
    name?: string;
    ip?: string;
    port?: number;
    serverIp?: string;
    status: number; // 1/0
    statusName: string; // "在线"/"离线"（后端已派生）
    type?: number;
    typeName?: string;
    subType?: number; // 派生展示
    subTypeName?: string;
    protocol?: number; // 派生展示
    protocolName?: string;
    createTime?: number;
    updateTime?: number;
    registerTime?: number;
    keepaliveTime?: number;
    channelCount?: number; // S1 新增：该设备下通道数
    /** 所属级联组织主键；空表示未归组织（SIP 自动注册的设备就是这种） */
    orgId?: number;
    /** 所属级联组织名称（后端批量回填，未归组织时为空） */
    orgName?: string;
    extend?: string;
    extendInfo?: ExtendInfoVO;
    // GB28181-2022 §9.11 订阅意图开关状态（后端批量回填）
    subscription?: SubscriptionVO;
  }

  /** 订阅意图开关状态（目录/位置/告警）。 */
  export interface SubscriptionVO {
    catalog: boolean;
    position: boolean;
    alarm: boolean;
  }

  /** 订阅类型（与后端 SubscriptionConstant.Type 一致）。 */
  export type SubscriptionType = 'ALARM' | 'CATALOG' | 'MOBILE_POSITION';

  export interface DeviceListResp {
    total: number;
    items: DeviceVO[];
  }

  /* ----------------------- S5 通道层级（DeviceChannelController） ----------------------- */

  /** 通道查询请求（全部可选；deviceId 由钻取路由注入）。 */
  export interface DeviceChannelQueryReq {
    id?: number;
    channelId?: string; // 通道 Id
    deviceId?: string; // 设备 ID（钻取按此过滤）
    name?: string; // 通道名称
    status?: number; // 1在线 / 0离线
    /** 本地启用 1启用 / 0停用（与在线状态 status 无关） */
    enabled?: number;
    /**
     * 所属级联组织主键：0 表示「未归组织」（按 IS NULL 查），不传即不按组织筛。
     *
     * 口径按**所属设备**的组织算：通道行上的 org_id 已停写（恒 NULL），
     * 直接拿通道行的值过滤会什么都查不到。
     */
    orgId?: number;
    /** 按组织筛时是否包含下级组织 */
    includeChildren?: boolean;
  }

  /**
   * 通道扩展信息。
   *
   * RTSP/ONVIF 拉流设备的通道由「同步通道」从设备台账读取后落库，地址与源设备元数据都在这里；
   * 视频编码/分辨率由点播成功后回填（未知时不写值，展示层用「—」，不要当 0）。
   */
  export interface DeviceChannelExtendInfoVO {
    channelInfo?: string;
    /** 主码流地址（点播时通道优先于设备级 rtspUrl） */
    rtspUrl?: string;
    /** 辅码流地址（未同步到则为空，请求 sub 会被后端明确拒绝） */
    rtspSubUrl?: string;
    /** H264 / H265；从未点播过时为空 */
    videoCodec?: string;
    videoWidth?: number;
    videoHeight?: number;
    /** 设备厂商（如 hikvision） */
    vendor?: string;
    /** 接入协议（NVRSDK / ONVIF / HIKVISION） */
    proxyProtocol?: string;
    /** 通道所属前端摄像机的地址与端口 */
    sourceIp?: string;
    sourcePort?: number;
    /** 前端摄像机在 NVR 上的通道号 */
    sourceChannel?: number;
    sourceModel?: string;
    sourceFirmware?: string;
  }

  /** 通道出参（时间 Unix 毫秒，字段以 Time 结尾）。 */
  export interface DeviceChannelVO {
    id: number;
    channelId: string;
    deviceId: string;
    name?: string;
    status: number; // 1/0
    statusName: string; // "在线"/"离线"（后端已派生，前端直接用）
    /**
     * 通道没有自己的组织：这个值按**所属设备**的组织回填，通道行上的残留值一律被覆盖。
     * 空表示设备也未归组织（与目录里挂到域节点下是同一件事）。
     */
    orgId?: number;
    /** 所属级联组织名称，与 orgId 同源（后端按设备组织批量回填），空=设备未归组织 */
    orgName?: string;
    /**
     * 所属设备名称（后端批量回填，设备名为空时回落国标 ID）。
     *
     * 跨设备的通道列表（按组织浏览）靠它区分「这条通道属于哪台机」。
     */
    deviceName?: string;
    /**
     * 本地启用 1启用 / 0停用。
     *
     * 与在线状态 status 无关：停用后这路通道会从**所有**上级平台目录消失，
     * 但在线与否只影响报文里的 Status 字段。
     */
    enabled?: number;
    createTime?: number;
    updateTime?: number;
    extend?: string;
    extendInfo?: DeviceChannelExtendInfoVO;
  }

  export interface DeviceChannelListResp {
    total: number;
    items: DeviceChannelVO[];
  }

  /** 通道更新请求（id 必填；channelId/deviceId 为身份字段，仅展示不可改，后端按 id 更新 name/status）。 */
  export interface DeviceChannelUpdateReq {
    id: number;
    channelId?: string;
    deviceId?: string;
    name?: string;
    status?: number; // 1在线 / 0离线
    /**
     * @deprecated 通道没有独立归属：写入侧会摘掉该字段，传了也不生效。
     * 想让通道换位置只有一条路——改它所属设备的 orgId。保留字段仅为兼容历史调用方。
     */
    orgId?: number;
    /** 本地启用 1/0；不传=不改 */
    enabled?: number;
    extendInfo?: { channelInfo?: string };
  }

  /** 通道同步结果（POST /deviceChannel/discover/{deviceId}）。 */
  export interface ChannelSyncResultVO {
    deviceId: string;
    /** 识别出的设备厂商 */
    vendor?: string;
    deviceModel?: string;
    firmwareVersion?: string;
    /** 是否真的落库成功；false 时台账保持原样，看 failureReason */
    persisted: boolean;
    /** 读到的通道数 */
    discovered: number;
    /** 写入（新增+更新）行数 */
    affected: number;
    /** 其中在线通道数 */
    onlineCount: number;
    /** 失败原因（persisted=false 时有值） */
    failureReason?: string;
    /** 落库后的通道列表（persisted=false 时为本次读到的通道） */
    channels: DeviceChannelVO[];
  }

  /** 设备创建请求（仅拉流类设备手工新增；GB28181 走 SIP 注册自动落库）。 */
  export interface DeviceCreateReq {
    deviceId: string;
    name?: string;
    ip: string;
    port: number;
    /** 协议类型 DeviceAgreementEnum：3=ONVIF IPC / 4=RTSP IPC */
    type: number;
    /**
     * 所属级联组织主键（必填）：推送时按该组织决定设备挂在上级目录的哪个节点下。
     *
     * 只有手填新增才要求必填——GB28181 设备靠 SIP REGISTER 自动落库，
     * 没有表单也拿不到组织，那些设备以 orgId 为空进库，列表上显示「未归组织」。
     */
    orgId: number;
    /** 注册节点 IP：拉流设备无此概念，传空串（后端列为 NOT NULL） */
    serverIp?: string;
    /**
     * 初始在线态：仅拉流设备使用。
     * 拉流设备没有注册与心跳，后端不会自动置在线，故把创建前的探活结果作为初始值；
     * 不传即离线（GB28181 的在线态仍由信令维护，与本字段无关）。
     */
    status?: number;
    extendInfo?: { rtspSubUrl?: string; rtspUrl?: string };
  }

  /** RTSP 地址探活结果（POST /device/probe-rtsp）。 */
  export interface RtspProbeResult {
    /** 是否拿到 RTSP 协议响应（true 不等于能播） */
    reachable: boolean;
    /** RTSP 状态码：200/401/404…；未拿到响应时为空 */
    statusCode?: number;
    reason?: string;
    /** 401/403：地址对但缺凭据 */
    authRequired: boolean;
    /**
     * 凭据已被设备接受：地址带账号密码且 Digest 挑战通过才为 true；
     * 未带凭据时为 false（表示"未校验"，不等于密码错）。
     */
    authenticated: boolean;
    /** host:port（不含账号密码） */
    target: string;
    elapsedMs: number;
    /** 后端给出的中文结论，可直接展示 */
    message: string;
  }

  /** 设备更新请求（id 必填；deviceId 唯一索引不可改，仅展示）。 */
  export interface DeviceUpdateReq {
    id: number;
    deviceId?: string;
    name?: string;
    ip?: string;
    port?: number;
    /** 协议类型 DeviceAgreementEnum：1=GB28181 IPC / 2=GB28181 NVR / 3=ONVIF IPC / 4=RTSP IPC */
    type?: number;
    serverIp?: string;
    status?: number; // 1在线 / 0离线
    /**
     * 所属级联组织三态：不传=不改（自动注册的设备保持未归组织）/ 0=清空 / 其余按 id 归组织。
     */
    orgId?: number;
    /** 扩展信息（仅提交变更项；null 字段不会被后端覆盖） */
    extendInfo?: { rtspSubUrl?: string; rtspUrl?: string };
  }

  /** PTZ 控制请求（既有 /ptz/control 端点）。 */
  export interface PtzControlReq {
    deviceId: string;
    channelId?: string;
    /** 词表 UP/DOWN/.../ZOOM_IN/ZOOM_OUT/STOP（大小写无关）。 */
    command: string;
    speed?: number;
  }

  /** 实时点播请求（既有 /live/start 端点）。 */
  export interface LiveStartReq {
    deviceId: string;
    /**
     * 通道 ID：GB28181 必填（按通道寻址 INVITE）；
     * RTSP/ONVIF 拉流可不传（按设备级 rtspUrl 拉流）。
     */
    channelId?: string;
    protocol?: string;
    /** 传输模式 UDP/TCP_ACTIVE/TCP_PASSIVE；与码流档位正交，互不影响。 */
    streamMode?: string;
    /**
     * 码流档位：main=主码流（默认，streamId 与播放地址沿用原有命名）；sub=辅码流。
     * 仅 RTSP/ONVIF 生效；GB28181 设备请求 sub 会被后端明确拒绝。
     */
    streamProfile?: 'main' | 'sub';
    /**
     * 切换即替换：true 时后端在同一次请求里回收同一通道另一档码流的会话，
     * 避免主/辅两路同时占设备上行带宽。
     * 若另一档码流正在被其他用户观看，后端拒绝切换（不踢人），失败原因走 message。
     */
    replacePrevious?: boolean;
  }

  export interface LivePlayVO {
    streamId?: string;
    callId?: string;
    status?: number;
    refCount?: number;
    /** 视频编码（H264/H265/UNKNOWN），前端据此选择播放策略 */
    videoCodec?: string;
    /** 视频宽高（流未就绪时为 0） */
    videoWidth?: number;
    videoHeight?: number;
    playUrls?: {
      [key: string]: string | undefined;
      hls?: string;
      httpFlv?: string;
      rtmp?: string;
      rtsp?: string;
      wsFlv?: string;
    };
  }

  /**
   * 直播流实时指标（GET /live/{streamId}/stats）。
   * 只含媒体服务器可客观给出的字段；网络时延/丢包率需 RTCP 统计，本链路不提供。
   */
  export interface LiveStatsVO {
    streamId?: string;
    /** 流是否在媒体服务器上存活 */
    online?: boolean;
    /** 视频编码（H264 / H265） */
    codec?: string;
    width?: number;
    height?: number;
    /** 帧率 */
    fps?: number;
    /** 数据产生速率，byte/s（前端换算 kbps） */
    bytesSpeed?: number;
    /** 本协议观看人数 */
    readerCount?: number;
    /** 观看总人数 */
    totalReaderCount?: number;
    /** 流存活时长（秒） */
    aliveSecond?: number;
    /** 产生源地址（拉流设备即实际拉的 RTSP 地址） */
    originUrl?: string;
  }

  /** 下发配置请求（/device-cmd/config/set）。 */
  export interface SetConfigReq {
    deviceId: string;
    name?: string;
    expiration?: string;
    heartBeatInterval?: string;
    heartBeatCount?: string;
  }

  /** 录像查询请求（/device-cmd/record）。 */
  export interface RecordQueryReq {
    deviceId: string;
    startTime?: number; // Unix 毫秒
    endTime?: number;
  }

  /** 报警查询请求（/device-cmd/alarm/query）。 */
  export interface AlarmQueryReq {
    deviceId: string;
    startTime?: number; // Unix 毫秒
    endTime?: number;
    startPriority?: string;
    endPriority?: string;
    alarmMethod?: string;
    alarmType?: string;
  }

  /** 报警复位请求（/device-cmd/alarm/control）。 */
  export interface AlarmControlReq {
    deviceId: string;
    alarmMethod?: string;
    alarmType?: string;
  }
}

/* -------------------------------------------------------------------------- */
/*                       §1.1 设备分页（DeviceController）                       */
/* -------------------------------------------------------------------------- */

/** S1 分页条件查询：page/size 走 query，条件走 body（空条件发 {}）。 */
export async function getDevicePage(
  params: { page: number; size: number },
  body?: DeviceApi.DevicePageReq,
  signal?: AbortSignal,
) {
  return requestClient.post<DeviceApi.DeviceListResp>(
    `/api/v1/device/getPage?page=${params.page}&size=${params.size}`,
    body ?? {},
    { signal, suppressGlobalError: true },
  );
}

/**
 * 按主键查单条设备（GET /device/get/{id}）。
 *
 * "设备查询"的结果视图取数口：`extendInfo` 里的 7 个应答快照
 * （deviceInfo / deviceStatus / ptzPosition / presets / config / configDownload /
 * sdCardStatus）只由本端点与 getPage 返回，后端 CommandController 全是触发型。
 *
 * 与 getDevicePage 的差异：本端点**不补 channelCount**（getPage 里才 peek 回填），
 * 调用方合并时需沿用旧值。
 *
 * suppressGlobalError：快照刷新是背景动作，失败由调用方决定是否提示。
 */
export async function getDeviceById(id: number) {
  return requestClient.get<DeviceApi.DeviceVO>(`/api/v1/device/get/${id}`, {
    suppressGlobalError: true,
  });
}

/** 新建设备（RTSP/ONVIF 拉流设备手工录入），返回新设备主键 id。 */
export async function createDevice(data: DeviceApi.DeviceCreateReq) {
  return requestClient.post<number>('/api/v1/device/insert', data);
}

/** 探测 RTSP 拉流地址连通性（TCP + RTSP OPTIONS，不落库、不起流）。 */
export async function probeRtsp(rtspUrl: string) {
  return requestClient.post<DeviceApi.RtspProbeResult>(
    '/api/v1/device/probe-rtsp',
    { rtspUrl },
  );
}

/** 更新设备（id 必填）。 */
export async function updateDevice(data: DeviceApi.DeviceUpdateReq) {
  return requestClient.put<number>('/api/v1/device/update', data);
}

/** 删除单个设备（按主键 id）。 */
export async function deleteDevice(id: number) {
  return requestClient.delete<boolean>(`/api/v1/device/delete/${id}`);
}

/** 批量删除设备（按主键 id 列表）。 */
export async function deleteDeviceBatch(ids: number[]) {
  return requestClient.delete<boolean>('/api/v1/device/deleteIds', {
    data: ids,
  });
}

/* -------------------------------------------------------------------------- */
/*           S5 通道分页（DeviceChannelController，按 deviceId 钻取）           */
/* -------------------------------------------------------------------------- */

/** 通道分页：page/size 走 query，body 为查询条件（按 deviceId 过滤该设备下通道，空条件发 {}）。 */
export async function getDeviceChannelPage(
  params: { page: number; size: number },
  body?: DeviceApi.DeviceChannelQueryReq,
  signal?: AbortSignal,
) {
  return requestClient.post<DeviceApi.DeviceChannelListResp>(
    `/api/v1/deviceChannel/getPage?page=${params.page}&size=${params.size}`,
    body ?? {},
    { signal, suppressGlobalError: true },
  );
}

/** 更新通道（id 必填；后端 PUT /deviceChannel/update 按主键改 name/status）。 */
export async function updateDeviceChannel(
  data: DeviceApi.DeviceChannelUpdateReq,
) {
  return requestClient.put<number>('/api/v1/deviceChannel/update', data);
}

/** 单删通道（DELETE /deviceChannel/deleteOne，body 携带主键 id）。 */
export async function deleteDeviceChannelOne(id: number) {
  return requestClient.delete<boolean>('/api/v1/deviceChannel/deleteOne', {
    data: { id },
  });
}

/**
 * 按条件批量删除通道（DELETE /deviceChannel/deleteBatch，body=查询条件）。
 * 清理离线通道即传 { deviceId, status: 0 }——后端按条件匹配删除，非 id 数组。
 */
export async function deleteDeviceChannelBatch(
  query: DeviceApi.DeviceChannelQueryReq,
) {
  return requestClient.delete<boolean>('/api/v1/deviceChannel/deleteBatch', {
    data: query,
  });
}

/**
 * 从设备读取通道台账并幂等落库（RTSP/ONVIF 拉流设备）。
 *
 * 请求本身成功受理即返回，成败看结果里的 persisted/failureReason：
 * 设备连不上、凭据错、协议不支持目录都会以 persisted=false + 中文原因回来，
 * 不用把业务失败当异常处理。失败时台账保持原样，不会删掉已有通道。
 *
 * timeoutMs 为单次 HTTP 读取超时（默认后端 3000ms）。
 */
export async function discoverDeviceChannels(deviceId: string, timeoutMs?: number) {
  const query = timeoutMs ? '?timeoutMs=' + timeoutMs : '';
  return requestClient.post<DeviceApi.ChannelSyncResultVO>(
    `/api/v1/deviceChannel/discover/${encodeURIComponent(deviceId)}${query}`,
  );
}

/** 列出设备下已落库的全部通道（含编码、分辨率等探测结果，不分页）。 */
export async function listDeviceChannels(deviceId: string) {
  return requestClient.get<DeviceApi.DeviceChannelVO[]>(
    `/api/v1/deviceChannel/list/${encodeURIComponent(deviceId)}`,
  );
}

/* -------------------------------------------------------------------------- */
/*              §1.3 复用既有端点（协议台同源，勿重建）                          */
/* -------------------------------------------------------------------------- */

/** PTZ 控制（既有端点）。 */
export async function ptzControl(data: DeviceApi.PtzControlReq) {
  return requestClient.post<boolean>('/api/v1/ptz/control', data);
}

/** PTZ 停止。 */
export async function ptzStop(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/ptz/stop', { deviceId });
}

/** 查目录（回包后收 device.catalog）。 */
export async function queryCatalog(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/query-catalog', {
    deviceId,
  });
}

/** 查设备信息（回包后收 device.info）。 */
export async function queryDeviceInfo(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/query-info', {
    deviceId,
  });
}

/** 下发重启。 */
export async function rebootDevice(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/reboot', { deviceId });
}

/** 实时点播（返回 LivePlayVO，含 playUrls）。 */
export async function liveStart(data: DeviceApi.LiveStartReq) {
  return requestClient.post<DeviceApi.LivePlayVO>('/api/v1/live/start', data);
}

/** 停流（G4：统一走 /live/stop，传 streamId，与协议台一致）。 */
export async function liveStop(streamId: string) {
  return requestClient.post<boolean>('/api/v1/live/stop', { streamId });
}

/**
 * 查询流实时指标（媒体调试面板数据源）。
 * 面板按秒轮询，失败不弹全局提示——指标缺失只该让面板显示「流不在线」。
 */
export async function getLiveStats(streamId: string) {
  return requestClient.get<DeviceApi.LiveStatsVO>(
    `/api/v1/live/${encodeURIComponent(streamId)}/stats`,
    { suppressGlobalError: true },
  );
}

/* -------------------------------------------------------------------------- */
/*              §1.2 S2 新增支链（DeviceCmdController）                          */
/* -------------------------------------------------------------------------- */

/** 查设备状态（回包入 extendInfo.deviceStatus）。 */
export async function queryDeviceStatus(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/query-status', {
    deviceId,
  });
}

/** 查预置位（回包入 extendInfo.presets；G2：仅查询，不支持下发）。 */
export async function queryPreset(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/query-preset', {
    deviceId,
  });
}

/** 查移动位置订阅（interval 选填）。 */
export async function queryMobilePosition(deviceId: string, interval?: string) {
  return requestClient.post<boolean>(
    '/api/v1/device-cmd/query-mobile-position',
    { deviceId, interval },
  );
}

/** 下载配置（configType 后端 @NotBlank，缺失返回 400）。 */
export async function downloadConfig(deviceId: string, configType: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/config/download', {
    deviceId,
    configType,
  });
}

/** 下发配置（记 [AUDIT]）。 */
export async function setDeviceConfig(data: DeviceApi.SetConfigReq) {
  return requestClient.post<boolean>('/api/v1/device-cmd/config/set', data);
}

/** 开始录像（记 [AUDIT]）。 */
export async function controlRecordStart(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/record/start', {
    deviceId,
  });
}

/** 停止录像（记 [AUDIT]）。 */
export async function controlRecordStop(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/record/stop', {
    deviceId,
  });
}

/** 触发录像查询（G1：结果走 device.recordinfo 通知，列表本体暂无读端点）。 */
export async function queryRecord(data: DeviceApi.RecordQueryReq) {
  return requestClient.post<boolean>('/api/v1/device-cmd/record', data);
}

/** 查报警。 */
export async function queryAlarm(data: DeviceApi.AlarmQueryReq) {
  return requestClient.post<boolean>('/api/v1/device-cmd/alarm/query', data);
}

/** 报警复位（记 [AUDIT]）。 */
export async function controlAlarm(data: DeviceApi.AlarmControlReq) {
  return requestClient.post<boolean>('/api/v1/device-cmd/alarm/control', data);
}

/** 语音广播（记 [AUDIT]）。 */
export async function broadcast(deviceId: string) {
  return requestClient.post<boolean>('/api/v1/device-cmd/broadcast', {
    deviceId,
  });
}

/**
 * 开关设备订阅（GB28181-2022 §9.11：目录/位置/告警）。
 * 开关即下发/撤销 SUBSCRIBE。
 */
export async function toggleDeviceSubscription(
  deviceId: string,
  type: DeviceApi.SubscriptionType,
  enabled: boolean,
) {
  return requestClient.put<boolean>('/api/v1/device/subscription/toggle', {
    deviceId,
    type,
    enabled,
  });
}
