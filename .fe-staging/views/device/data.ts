import type { VbenFormSchema } from '#/adapter/form';
import type { OnActionClickFn, VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DeviceApi } from '#/api/device';
import type { LabEvent } from '#/composables/useSseEvents';

import { useAccess } from '@vben/access';

import { getCascadeOrgTree } from '#/api/cascade/org';
import { $t } from '#/locales';

/**
 * 设备列表页的 Schema / 列定义 + 纯函数（§4.2）。
 *
 * 纯函数（buildDevicePageBody / mergeDeviceEvents）抽出便于单测，
 * list.vue 只做组装与副作用（query / SSE 订阅 / 抽屉）。
 */

/** 格式化 Unix 毫秒为本地时间串，空值回退 "-"。 */
function formatMs(cellValue?: number): string {
  return cellValue ? new Date(cellValue).toLocaleString() : '-';
}

/**
 * 从设备扩展信息中解析厂商名。
 *
 * 后端 DeviceVO 无顶层厂商字段——厂商埋在 extendInfo.deviceInfo（设备信息应答
 * DeviceInfo 的 JSON 串，含 manufacturer/model/firmware）。该快照仅在设备应答
 * 「查设备信息」后才有，故大多数行可能为空 → 统一回退 "-"。
 */
export function resolveManufacturer(row: DeviceApi.DeviceVO): string {
  const raw = row.extendInfo?.deviceInfo;
  if (!raw) {
    return '-';
  }
  try {
    const info = JSON.parse(raw) as { manufacturer?: string };
    return info.manufacturer?.trim() || '-';
  } catch {
    return '-'; // deviceInfo 非合法 JSON，容错回退
  }
}

/**
 * 把查询表单值转为后端 DevicePageReq：
 * RangePicker 字段（keepaliveTime / registerTime）输出 [start,end]，
 * 转为 *TimeStart / *TimeEnd（Unix 毫秒）并删除原 range 字段（§4.2 时间铁律）。
 */
export function buildDevicePageBody(
  formValues: Record<string, any>,
): DeviceApi.DevicePageReq {
  const ranges: Array<[string, string, string]> = [
    ['keepaliveTime', 'keepaliveTimeStart', 'keepaliveTimeEnd'],
    ['registerTime', 'registerTimeStart', 'registerTimeEnd'],
  ];
  // 收集需要剔除的 RangePicker 原字段，重建 body 时排除（避免动态 delete）。
  const rangeFields = new Set(ranges.map(([field]) => field));
  const body: Record<string, any> = {};
  for (const [key, value] of Object.entries(formValues)) {
    if (!rangeFields.has(key)) {
      body[key] = value;
    }
  }
  for (const [rangeField, startField, endField] of ranges) {
    const range = formValues[rangeField];
    if (Array.isArray(range) && range.length === 2) {
      body[startField] = +new Date(range[0]);
      body[endField] = +new Date(range[1]);
    }
  }
  return body as DeviceApi.DevicePageReq;
}

/**
 * 按 SSE device.* 事件对当前页行做 upsert（在线态 / 心跳 / 通道数）。
 *
 * 服务端分页：只更新当前页已有的 deviceId，新设备等下次 query 才出现。
 * 返回新数组（不原地改），便于 vxe reloadData 触发刷新。
 */
export function mergeDeviceEvents<T extends DeviceApi.DeviceVO>(
  rows: T[],
  events: LabEvent[],
): T[] {
  if (events.length === 0) {
    return [...rows];
  }
  const byId = new Map(rows.map((r) => [r.deviceId, { ...r }]));
  for (const ev of events) {
    const id = ev.data?.deviceId;
    if (!id) {
      continue;
    }
    const row = byId.get(id);
    if (!row) {
      continue; // 不在当前页，忽略
    }
    switch (ev.topic) {
      case 'device.catalog': {
        row.channelCount = ev.data?.channelCount;
        break;
      }
      case 'device.keepalive': {
        row.keepaliveTime = ev.ts;
        row.status = 1;
        row.statusName = $t('device.status.online');
        break;
      }
      case 'device.offline': {
        row.status = 0;
        row.statusName = $t('device.status.offline');
        break;
      }
      case 'device.online':
      case 'device.register': {
        row.status = 1;
        row.statusName = $t('device.status.online');
        break;
      }
      // 其它 topic 不改行
    }
  }
  return rows.map((r) => byId.get(r.deviceId) ?? r);
}

/** 协议类型选项（DeviceAgreementEnum：1=GB28181 IPC / 2=NVR / 3=ONVIF / 4=RTSP 拉流）。 */
const DEVICE_TYPE_OPTIONS = [
  { label: 'GB28181 IPC', value: 1 },
  { label: 'GB28181 NVR', value: 2 },
  { label: 'ONVIF IPC', value: 3 },
  { label: 'RTSP IPC', value: 4 },
];

/**
 * 设备编辑表单字段（§4.2 编辑支链）。
 *
 * deviceId 为唯一索引，编辑时只读（仅展示，不可改）；id 走隐藏字段透传。
 * 其余字段（name/ip/port/type/serverIp/status）对应 PUT /device/update 可更新项。
 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      dependencies: {
        show: () => false,
        triggerFields: ['id'],
      },
      fieldName: 'id',
      label: 'id',
    },
    {
      component: 'Input',
      componentProps: { disabled: true },
      fieldName: 'deviceId',
      label: $t('device.field.deviceId'),
    },
    {
      component: 'Input',
      fieldName: 'name',
      label: $t('device.field.name'),
    },
    {
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        // ApiTreeSelect 会把内部参数传给 api，必须包一层，否则 rootOrgCode 被污染
        api: () => getCascadeOrgTree(),
        childrenField: 'children',
        class: 'w-full',
        labelField: 'orgName',
        valueField: 'id',
      },
      // 编辑时非必填：自动注册进来的设备本就没有组织，改个名字不该被拦；
      // 清空即退回「未归组织」，由表单提交时补 0 落地。
      fieldName: 'orgId',
      // 组织树路径可能很长，整行展示；同时保住下半宽字段两两成对
      formItemClass: 'col-span-2 md:col-span-2',
      help: $t('device.field.orgHelp'),
      label: $t('device.field.org'),
    },
    {
      component: 'Select',
      componentProps: { options: DEVICE_TYPE_OPTIONS },
      fieldName: 'type',
      // 选项是完整协议名（GB28181 IPC / ONVIF IPC），半列宽会被截成「GB28181 I...」
      formItemClass: 'col-span-2 md:col-span-2',
      label: $t('device.field.type'),
    },
    {
      component: 'Input',
      fieldName: 'ip',
      label: $t('device.field.ip'),
    },
    {
      component: 'InputNumber',
      componentProps: { max: 65_535, min: 0, precision: 0 },
      fieldName: 'port',
      label: $t('device.field.port'),
    },
    {
      component: 'Input',
      fieldName: 'serverIp',
      label: $t('device.field.serverIp'),
    },
    {
      component: 'Select',
      componentProps: {
        options: [
          { label: $t('device.status.online'), value: 1 },
          { label: $t('device.status.offline'), value: 0 },
        ],
      },
      // 状态跟着注册节点（都是 SIP 侧信息）：下面的协议类型与两个码流地址改成整行后，
      // 半宽字段必须两两成对，否则两列网格会留下半行空洞。
      fieldName: 'status',
      label: $t('device.field.status'),
    },
    {
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: 'rtsp://user:password@192.168.1.64:554/Streaming/Channels/101',
      },
      // 仅 RTSP(4) / ONVIF(3) 协议设备需要拉流地址；GB28181 走 INVITE 寻址，无此字段。
      dependencies: {
        show: (values) => values.type === 3 || values.type === 4,
        triggerFields: ['type'],
      },
      fieldName: 'extendInfo.rtspUrl',
      // RTSP 地址带账号密码与通道号，半列宽根本看不全，整行展示
      formItemClass: 'col-span-2 md:col-span-2',
      label: $t('device.field.rtspUrl'),
      help: $t('device.field.rtspUrlHelp'),
    },
    {
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder:
          'rtsp://user:password@192.168.1.64:554/Streaming/Channels/102',
      },
      // 辅码流选填：只有设备确实开了第二路编码时才填；留空即只有主码流可看。
      dependencies: {
        show: (values) => values.type === 3 || values.type === 4,
        triggerFields: ['type'],
      },
      fieldName: 'extendInfo.rtspSubUrl',
      // 与主码流同宽，上下对照着填
      formItemClass: 'col-span-2 md:col-span-2',
      label: $t('device.field.rtspSubUrl'),
      help: $t('device.field.rtspSubUrlHelp'),
    },
  ];
}

/** 顶部筛选条件（§1.1：仅 type 维度，禁用派生字段 subType/protocol）。 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'deviceId',
      label: $t('device.field.deviceId'),
    },
    {
      component: 'Input',
      fieldName: 'name',
      label: $t('device.field.name'),
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [
          { label: $t('device.status.online'), value: 1 },
          { label: $t('device.status.offline'), value: 0 },
        ],
      },
      fieldName: 'status',
      label: $t('device.field.status'),
    },
    {
      component: 'Input',
      fieldName: 'ip',
      label: $t('device.field.ip'),
    },
    {
      component: 'RangePicker',
      componentProps: {
        showTime: true,
      },
      fieldName: 'keepaliveTime',
      label: $t('device.field.keepaliveTime'),
    },
  ];
}

/**
 * 通道浏览（跨设备）的查询 body：表单值叠加当前组织筛选。
 *
 * 组织筛选由左侧组织目录控制，不在表单里：表单填入的 orgId 会被目录覆盖，
 * 避免「树选 A、表单选 B」两份真相互相打架。
 */
export function buildChannelBrowseBody(
  formValues: Record<string, any>,
  orgId?: number,
  includeChildren?: boolean,
): DeviceApi.DeviceChannelQueryReq {
  const body = { ...formValues } as DeviceApi.DeviceChannelQueryReq;
  if (orgId !== undefined) {
    body.orgId = orgId;
    body.includeChildren = includeChildren;
  }
  return body;
}

/**
 * 跨设备通道浏览列：在设备页的「通道」Tab 里用。
 *
 * 与通道钻取页（channel/data.ts）的区别：这里一张表混着多台设备的通道，
 * 所以必须有「所属设备」「组织」两列，否则光看通道名分不清归属；
 * 操作也只留「看设备通道」——点播/删除等重操作仍回钻取页做，避免一页两套权限入口。
 */
export function useChannelBrowseColumns<T = DeviceApi.DeviceChannelVO>(
  onActionClick: OnActionClickFn<T>,
): VxeTableGridOptions['columns'] {
  const { hasAccessByCodes } = useAccess();

  return [
    {
      field: 'name',
      title: $t('device.field.name'),
      minWidth: 160,
      className: 'device-cell-name',
    },
    {
      field: 'channelId',
      title: $t('device.field.channelId'),
      minWidth: 200,
      className: 'device-cell-mono',
    },
    {
      field: 'deviceName',
      title: $t('device.field.device'),
      width: 170,
      // 设备名可点：直接钻到那台设备的通道页（带完整操作能力）
      slots: { default: 'deviceName' },
    },
    {
      field: 'orgName',
      title: $t('device.field.org'),
      width: 140,
      slots: { default: 'orgName' },
    },
    {
      field: 'statusName',
      title: $t('device.field.status'),
      width: 90,
      align: 'center',
      cellRender: {
        name: 'CellTag',
        options: [
          { color: 'success', label: $t('device.status.online'), value: '在线' },
          { color: 'error', label: $t('device.status.offline'), value: '离线' },
        ],
      },
    },
    // 编码/分辨率取自 extendInfo，虚拟列：值由 formatter 从 row 取，不参与后端排序
    {
      field: 'videoCodec',
      title: $t('device.channel.codec'),
      width: 92,
      align: 'center',
      className: 'device-cell-mono',
      formatter: ({ row }: { row: DeviceApi.DeviceChannelVO }) =>
        row.extendInfo?.videoCodec || '—',
    },
    {
      field: 'resolution',
      title: $t('device.channel.resolution'),
      width: 118,
      align: 'center',
      className: 'device-cell-mono',
      formatter: ({ row }: { row: DeviceApi.DeviceChannelVO }) => {
        const width = row.extendInfo?.videoWidth;
        const height = row.extendInfo?.videoHeight;
        return width && height ? `${width}×${height}` : '—';
      },
    },
    {
      field: 'createTime',
      title: $t('device.field.createTime'),
      width: 170,
      formatter: ({ cellValue }: { cellValue?: number }) => formatMs(cellValue),
    },
    {
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'name',
          nameTitle: $t('device.field.name'),
          onClick: onActionClick,
        },
        name: 'CellOperation',
        options: [
          {
            code: 'openChannels',
            text: $t('device.action.openChannels'),
            show: () => hasAccessByCodes(['Device:Device:Query']),
          },
        ],
      },
      field: 'operation',
      fixed: 'right',
      title: $t('device.field.operation'),
      width: 130,
    },
  ];
}

/** 列定义（含 channelCount / statusName，操作列 detail / edit / delete）。 */
export function useColumns<T = DeviceApi.DeviceVO>(
  onActionClick: OnActionClickFn<T>,
): VxeTableGridOptions['columns'] {
  const { hasAccessByCodes } = useAccess();

  return [
    {
      type: 'checkbox',
      width: 48,
      align: 'center',
      fixed: 'left',
    },
    {
      field: 'deviceId',
      title: $t('device.field.deviceId'),
      width: 220,
      className: 'device-cell-mono',
    },
    {
      field: 'name',
      title: $t('device.field.name'),
      minWidth: 140,
      className: 'device-cell-name',
    },
    {
      field: 'orgName',
      title: $t('device.field.org'),
      width: 150,
      // 未归组织是合法状态（SIP 自动注册的设备）：用橙色 tag 提醒补录，不能显示成空白
      slots: { default: 'orgName' },
    },
    {
      field: 'statusName',
      title: $t('device.field.status'),
      width: 90,
      align: 'center',
      cellRender: {
        name: 'CellTag',
        options: [
          {
            color: 'success',
            label: $t('device.status.online'),
            value: '在线',
          },
          { color: 'error', label: $t('device.status.offline'), value: '离线' },
        ],
      },
    },
    {
      field: 'typeName',
      title: $t('device.field.type'),
      width: 120,
    },
    {
      field: 'manufacturer',
      title: $t('device.field.manufacturer'),
      width: 120,
      // 厂商来自 extendInfo.deviceInfo JSON（设备应答快照），用 formatter 现解析。
      formatter: ({ row }: { row: DeviceApi.DeviceVO }) =>
        resolveManufacturer(row),
    },
    {
      field: 'ip',
      title: $t('device.field.ip'),
      width: 130,
      className: 'device-cell-mono',
    },
    {
      field: 'channelCount',
      title: $t('device.field.channelCount'),
      width: 90,
      align: 'center',
      className: 'device-cell-count',
      slots: { default: 'channelCount' }, // list.vue 提供具名插槽（可点击钻取通道列表）
    },
    {
      field: 'keepaliveTime',
      title: $t('device.field.keepaliveTime'),
      width: 170,
      formatter: ({ cellValue }: { cellValue?: number }) => formatMs(cellValue),
    },
    {
      field: 'registerTime',
      title: $t('device.field.registerTime'),
      width: 170,
      formatter: ({ cellValue }: { cellValue?: number }) => formatMs(cellValue),
    },
    {
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'name',
          nameTitle: $t('device.field.name'),
          onClick: onActionClick,
        },
        name: 'CellOperation',
        options: [
          {
            code: 'detail',
            text: $t('device.action.detail'),
            show: () => hasAccessByCodes(['Device:Device:Query']),
          },
          {
            code: 'edit',
            text: $t('device.action.edit'),
            show: () => hasAccessByCodes(['Device:Device:Edit']),
          },
          {
            code: 'delete',
            danger: true,
            text: $t('device.action.delete'),
            show: () => hasAccessByCodes(['Device:Device:Delete']),
          },
        ],
      },
      field: 'operation',
      fixed: 'right',
      title: $t('device.field.operation'),
      width: 200,
    },
  ];
}
