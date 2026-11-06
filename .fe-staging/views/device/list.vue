<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { DeviceApi } from '#/api/device';

import { computed, nextTick, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';

import { Button, message, Modal, Tag } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteDevice,
  deleteDeviceBatch,
  getDeviceChannelPage,
  getDevicePage,
} from '#/api/device';
import { useSseEvents } from '#/composables/useSseEvents';
import { $t } from '#/locales';

import { useGridFormSchema as useChannelGridFormSchema } from './channel/data';
import {
  buildChannelBrowseBody,
  buildDevicePageBody,
  mergeDeviceEvents,
  useChannelBrowseColumns,
  useColumns,
  useGridFormSchema,
} from './data';
import DeviceCreateForm from './modules/device-create-form.vue';
import DeviceDetail from './modules/device-detail.vue';
import DeviceForm from './modules/device-form.vue';
import OrgScopePanel from './modules/org-scope-panel.vue';

/**
 * 设备管理列表页（§4.2）——「Fleet Console 机群指挥台」皮肤。
 *
 * - VxeGrid 条件筛选 + 持久化分页（POST /device/getPage）
 * - SSE 订阅 device.* → 增量刷新当前页行的在线态 / 心跳 / 通道数
 * - 顶部态势带：脉冲雷达 + 等宽统计读数（总数/在线/离线/通道），随 query 与 SSE 实时跳数，
 *   与详情面板（device-operations.vue）共享指挥台视觉语言（--mono / HSL 令牌 / 自适应明暗）
 * - 操作列：详情（打开操作面板抽屉）/ 实时点播（复用 /live/start）
 */
const { hasAccessByCodes } = useAccess();
const router = useRouter();

// 态势带统计源：total 取服务端总数，在线/离线/通道按「当前页」统计（与用户所见一致）。
const totalDevices = ref(0);
const pageRows = ref<DeviceApi.DeviceVO[]>([]);

const onlineCount = computed(
  () => pageRows.value.filter((r) => r.status === 1).length,
);
const offlineCount = computed(
  () => pageRows.value.filter((r) => r.status !== 1).length,
);
const channelTotal = computed(() =>
  pageRows.value.reduce((sum, r) => sum + (r.channelCount ?? 0), 0),
);
const hasOnline = computed(() => onlineCount.value > 0);

/**
 * 组织筛选（左侧目录控制，与两个 Tab 共用）。
 *
 * undefined = 全部资产；0 = 未归组织（SIP 自动注册设备的合法归属）。
 * 含下级默认打开：设备总挂在叶子组织上，不含下级时选上级组织会一台都看不到。
 */
const activeOrgId = ref<number | undefined>(undefined);
const includeChildren = ref(true);

/** 当前 Tab：设备表 / 跨设备通道表 */
const activeTab = ref<'channel' | 'device'>('device');

const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  connectedComponent: DeviceDetail,
  destroyOnClose: true,
});

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: DeviceForm,
  destroyOnClose: true,
});

const [CreateDrawer, createDrawerApi] = useVbenDrawer({
  connectedComponent: DeviceCreateForm,
  destroyOnClose: true,
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    // 紧凑检索：去掉每项底部校验占位，纵向更收拢。
    compact: true,
    // 标签收窄给输入框让位，避免 100px 默认标签把窄列里的输入挤小。
    commonConfig: { labelWidth: 72 },
    // 按钮区始终占栅格最后一列并右对齐（rowEnd → col-[-2/-1] + justify-end）。
    actionLayout: 'rowEnd',
    schema: useGridFormSchema(),
    submitOnChange: false,
    // 大屏 3 列、超大屏 4 列：列数克制 → 输入框更宽、列间空隙更小，整体更紧凑耐看。
    wrapperClass: 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4',
  },
  gridOptions: {
    columns: useColumns(onActionClick),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const body = buildDevicePageBody(formValues ?? {});
          // 组织筛选不在表单里：由左侧组织目录持有，这里只做叠加
          if (activeOrgId.value !== undefined) {
            body.orgId = activeOrgId.value;
            body.includeChildren = includeChildren.value;
          }
          const resp = await getDevicePage(
            { page: page.currentPage, size: page.pageSize },
            body,
          );
          // 同步态势带统计（服务端分页：在线/离线/通道为当前页口径）。
          totalDevices.value = resp?.total ?? 0;
          pageRows.value = resp?.items ?? [];
          return resp;
        },
      },
    },
    rowConfig: {
      keyField: 'id',
    },
    checkboxConfig: {
      highlight: true,
      range: true,
    },
    scrollX: {
      enabled: true,
    },
    scrollY: {
      enabled: true,
    },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: { code: 'query' },
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<DeviceApi.DeviceVO>,
});

/**
 * 通道 Tab 的跨设备表格：同一份组织筛选下，看该组织范围里所有设备的通道。
 *
 * 与通道钻取页（/device/channel/:deviceId）的分工：这里是「按组织浏览」，
 * 钻取页才是单台设备的完整操作面板——所以本表只留「看设备通道」，
 * 点播/删除仍回钻取页做，避免一页两套权限入口。
 */
const [ChannelGrid, channelGridApi] = useVbenVxeGrid({
  formOptions: {
    compact: true,
    commonConfig: { labelWidth: 72 },
    actionLayout: 'rowEnd',
    schema: useChannelGridFormSchema(),
    submitOnChange: false,
    wrapperClass: 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4',
  },
  gridOptions: {
    columns: useChannelBrowseColumns(onChannelActionClick),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const body = buildChannelBrowseBody(
            formValues ?? {},
            activeOrgId.value,
            includeChildren.value,
          );
          return await getDeviceChannelPage(
            { page: page.currentPage, size: page.pageSize },
            body,
          );
        },
      },
    },
    rowConfig: {
      keyField: 'id',
    },
    scrollX: {
      enabled: true,
    },
    scrollY: {
      enabled: true,
    },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: { code: 'query' },
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<DeviceApi.DeviceChannelVO>,
});

/**
 * 组织/范围变了，两个 Tab 一起重查。
 *
 * 不动缓存的结果：同一个组织在两个 Tab 里是两份数据源，交回各表格自己的 query
 * 才能保证分页与表单筛选不串。未渲染的 Tab 不提前查——它的表格还没挂载。
 */
watch([activeOrgId, includeChildren], () => {
  gridApi.query();
  if (activeTab.value === 'channel') {
    channelGridApi.query();
  }
});

/**
 * 切换 Tab。
 *
 * 两个表格用 v-show 共存（保住筛选条件与滚动位置），但隐藏期间容器尺寸为 0，
 * 切回来必须重算列宽，否则表头会错位。
 */
async function switchTab(tab: 'channel' | 'device') {
  if (activeTab.value === tab) {
    return;
  }
  activeTab.value = tab;
  await nextTick();
  const api = tab === 'channel' ? channelGridApi : gridApi;
  api.grid?.recalculate?.();
  api.query();
}

// SSE 实时刷新：device.* 生命周期 / 心跳 / 目录 → 当前页行增量更新 + 态势带跳数。
const { events } = useSseEvents(() => [
  'device.register',
  'device.online',
  'device.offline',
  'device.keepalive',
  'device.catalog',
]);

watch(events, (list) => {
  const rows = gridApi.grid?.getData?.() as DeviceApi.DeviceVO[] | undefined;
  if (!rows || rows.length === 0) {
    return;
  }
  const next = mergeDeviceEvents(rows, list);
  gridApi.setGridOptions({ data: next });
  pageRows.value = next; // 态势带随事件实时重算
});

function onActionClick(e: OnActionClickParams<DeviceApi.DeviceVO>) {
  switch (e.code) {
    case 'delete': {
      onDelete(e.row);
      break;
    }
    case 'detail': {
      onDetail(e.row);
      break;
    }
    case 'edit': {
      onEdit(e.row);
      break;
    }
  }
}

function onDetail(row: DeviceApi.DeviceVO) {
  if (!hasAccessByCodes(['Device:Device:Query'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  detailDrawerApi.setData(row).open();
}

/**
 * 新增拉流设备（RTSP/ONVIF）。
 *
 * GB28181 设备由 SIP 注册自动入库，故本入口只覆盖无注册报文的拉流设备。
 */
function onCreate() {
  if (!hasAccessByCodes(['Device:Device:Add'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  createDrawerApi.open();
}

function onEdit(row: DeviceApi.DeviceVO) {
  if (!hasAccessByCodes(['Device:Device:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  formDrawerApi.setData(row).open();
}

function onDelete(row: DeviceApi.DeviceVO) {
  if (!hasAccessByCodes(['Device:Device:Delete'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  // 二次确认由 CellOperation 内置 Popconfirm 承载，此处直接执行删除。
  const hideLoading = message.loading({
    content: $t('device.msg.deleting', [row.name || row.deviceId]),
    duration: 0,
    key: 'device_delete_msg',
  });
  // 通道与级联发布关系由后端一起删（DeviceRemovalManager）：
  // 这里再单发一次通道删除会让 DEL 拆成两个事件，异步执行器不保证顺序。
  deleteDevice(row.id)
    .then(() => {
      message.success({
        content: $t('device.msg.deleteSuccess'),
        key: 'device_delete_msg',
      });
      onRefresh();
    })
    .catch(() => {
      hideLoading();
    });
}

function onBatchDelete() {
  if (!hasAccessByCodes(['Device:Device:Delete'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  const records =
    (gridApi.grid?.getCheckboxRecords?.() as DeviceApi.DeviceVO[]) ?? [];
  if (records.length === 0) {
    message.warning($t('device.msg.batchDeleteEmpty'));
    return;
  }
  const ids = records.map((r) => r.id);
  Modal.confirm({
    content: $t('device.msg.batchDeleteConfirm', [ids.length]),
    title: $t('device.action.batchDelete'),
    onOk: async () => {
      // 通道与级联发布关系由后端批量级联删除，前端不再逐台补一步。
      await deleteDeviceBatch(ids);
      message.success($t('device.msg.deleteSuccess'));
      onRefresh();
    },
  });
}

function onRefresh() {
  gridApi.query();
}

/** 跨设备通道表的操作：只一个「看设备通道」，重操作回钻取页 */
function onChannelActionClick(
  e: OnActionClickParams<DeviceApi.DeviceChannelVO>,
) {
  if (e.code === 'openChannels') {
    onDeviceChannels(e.row.deviceId, e.row.deviceName);
  }
}

/** 钻到某台设备的通道页（设备名经 query 透传供身份带展示） */
function onDeviceChannels(deviceId: string, deviceName?: string) {
  if (!hasAccessByCodes(['Device:Device:Query'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  router.push({
    path: `/device/channel/${deviceId}`,
    query: { deviceName: deviceName || deviceId },
  });
}

/**
 * 通道数钻取（§4.3）——path 导航到独立通道列表页，绕开 backend route name 耦合
 * （[[backend-access-mode-route-names]]）。deviceName 经 query 透传供身份带展示。
 */
function onChannelClick(row: DeviceApi.DeviceVO) {
  if (!hasAccessByCodes(['Device:Device:Query'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  router.push({
    path: `/device/channel/${row.deviceId}`,
    query: { deviceName: row.name || row.deviceId },
  });
}
</script>

<template>
  <Page auto-content-height>
    <DetailDrawer @success="onRefresh" />
    <FormDrawer @success="onRefresh" />
    <CreateDrawer @success="onRefresh" />

    <div class="fleet" :class="{ 'is-active': hasOnline }">
      <!-- 机群态势带（记忆点）：脉冲雷达 + 网格纹理 + 等宽统计读数 -->
      <header class="fleet-band">
        <div class="band-grid" aria-hidden="true"></div>
        <div class="band-scan" aria-hidden="true"></div>

        <div class="band-id">
          <span class="radar" :class="{ live: hasOnline }" aria-hidden="true">
            <span class="radar-core"></span>
            <span class="radar-wave"></span>
          </span>
          <div class="band-text">
            <div class="band-title">
              {{ $t('device.fleet.heading') }}
              <span class="band-live">
                <span class="live-dot"></span>{{ $t('device.fleet.live') }}
              </span>
            </div>
            <div class="band-sub">{{ $t('device.fleet.subtitle') }}</div>
          </div>
        </div>

        <div class="band-stats">
          <div class="stat">
            <div class="stat-val">{{ totalDevices }}</div>
            <div class="stat-key">{{ $t('device.fleet.total') }}</div>
          </div>
          <div class="stat stat-online">
            <div class="stat-val">{{ onlineCount }}</div>
            <div class="stat-key">{{ $t('device.fleet.online') }}</div>
          </div>
          <div class="stat stat-offline">
            <div class="stat-val">{{ offlineCount }}</div>
            <div class="stat-key">{{ $t('device.fleet.offline') }}</div>
          </div>
          <div class="stat">
            <div class="stat-val">{{ channelTotal }}</div>
            <div class="stat-key">{{ $t('device.fleet.channels') }}</div>
          </div>
        </div>
      </header>

      <!-- 浏览区：左侧组织目录（选一次，两个页签共用）+ 右侧双页签 -->
      <div class="fleet-body">
        <OrgScopePanel
          v-model:include-children="includeChildren"
          v-model:org-id="activeOrgId"
        />

        <div class="fleet-main">
          <nav class="scope-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              :aria-selected="activeTab === 'device'"
              :class="{ on: activeTab === 'device' }"
              @click="switchTab('device')"
            >
              {{ $t('device.fleet.roster') }}
              <span class="tab-count">{{ totalDevices }}</span>
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="activeTab === 'channel'"
              :class="{ on: activeTab === 'channel' }"
              @click="switchTab('channel')"
            >
              {{ $t('device.fleet.rosterChannels') }}
            </button>
          </nav>

          <!-- 两张表用 v-show 共存：切回来时筛选条件与滚动位置还在 -->
          <div v-show="activeTab === 'device'" class="fleet-grid">
            <Grid :table-title="$t('device.fleet.roster')">
              <template #toolbar-tools>
                <Button
                  v-if="hasAccessByCodes(['Device:Device:Add'])"
                  type="primary"
                  @click="onCreate"
                >
                  {{ $t('device.action.add') }}
                </Button>
                <Button
                  v-if="hasAccessByCodes(['Device:Device:Delete'])"
                  danger
                  type="primary"
                  @click="onBatchDelete"
                >
                  {{ $t('device.action.batchDelete') }}
                </Button>
              </template>
              <!-- 未归组织是合法状态（SIP 自动注册的设备进库时没有表单），用橙色 tag 提醒补录 -->
              <template #orgName="{ row }">
                <span v-if="row.orgName">{{ row.orgName }}</span>
                <Tag v-else color="orange">
                  {{ $t('device.field.unassignedOrg') }}
                </Tag>
              </template>
              <!-- 通道数可点击钻取：0 也可点——拉流类设备（RTSP/ONVIF）新增后通道为空，
                   而「同步通道」按钮只在通道页里，这里是那条链路的入口 -->
              <template #channelCount="{ row }">
                <a
                  :class="
                    (row.channelCount ?? 0) > 0
                      ? 'channel-link'
                      : 'channel-zero-link'
                  "
                  @click="onChannelClick(row)"
                >
                  {{ row.channelCount ?? 0 }}
                </a>
              </template>
            </Grid>
          </div>

          <!-- 跨设备通道表：同一份组织筛选下看该范围里所有设备的通道 -->
          <div v-show="activeTab === 'channel'" class="fleet-grid">
            <ChannelGrid :table-title="$t('device.fleet.rosterChannels')">
              <template #deviceName="{ row }">
                <a
                  class="device-link"
                  @click="onDeviceChannels(row.deviceId, row.deviceName)"
                >
                  {{ row.deviceName || row.deviceId }}
                </a>
              </template>
              <template #orgName="{ row }">
                <span v-if="row.orgName">{{ row.orgName }}</span>
                <Tag v-else color="orange">
                  {{ $t('device.field.unassignedOrg') }}
                </Tag>
              </template>
            </ChannelGrid>
          </div>
        </div>
      </div>
    </div>
  </Page>
</template>

<style scoped>
/* ============================ Fleet Console ============================
   机群指挥台：与详情面板（device-operations.vue）共享视觉语言——脉冲雷达、
   网格纹理、等宽读数、发丝边框；全部基于主题令牌 hsl(var(--*))，自适应明暗。
   ===================================================================== */
.fleet {
  --mono: 'JetBrains Mono', 'IBM Plex Mono', ui-monospace, 'SF Mono', monospace;
  --hair: hsl(var(--border));

  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  min-height: 0;
}

/* ---- 入场：态势带淡入下沉 ---- */
@keyframes band-in {
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ---- 浏览区：左组织目录 + 右双页签 ---- */
.fleet-body {
  display: flex;
  flex: 1;
  gap: 14px;
  min-height: 0;
}

.fleet-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 0;
}

/* 页签：与态势带同一套发丝边框 / 等宽大写标签，选中项抬成卡片底 */
.scope-tabs {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
  padding: 3px;
  background: hsl(var(--muted) / 45%);
  border: 1px solid var(--hair);
  border-radius: calc(var(--radius) + 2px);
}

.scope-tabs button {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  padding: 6px 16px;
  font-family: var(--mono);
  font-size: 12px;
  font-weight: 600;
  color: hsl(var(--muted-foreground));
  text-transform: uppercase;
  letter-spacing: 0.1em;
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: var(--radius);
  transition:
    color 0.16s,
    background 0.16s;
}

.scope-tabs button:hover {
  color: hsl(var(--foreground));
}

.scope-tabs button.on {
  color: hsl(var(--foreground));
  background: hsl(var(--card));
  box-shadow: 0 1px 2px hsl(var(--foreground) / 12%);
}

.tab-count {
  padding: 0 6px;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 70%);
  border-radius: 999px;
}

/* 跨设备通道表里的「所属设备」：可点钻到那台设备的通道页 */
.device-link {
  color: hsl(var(--primary));
  cursor: pointer;
  border-bottom: 1px dashed hsl(var(--primary) / 45%);
  transition: border-color 0.16s;
}

.device-link:hover {
  border-bottom-color: hsl(var(--primary));
}

/* ============================ 态势带 ============================ */
.fleet-band {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  gap: 16px 28px;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px;
  overflow: hidden;
  background:
    radial-gradient(
      120% 180% at 0% 0%,
      hsl(var(--success) / 12%),
      transparent 52%
    ),
    linear-gradient(
      135deg,
      hsl(var(--card)) 0%,
      hsl(var(--background-deep, var(--background))) 100%
    );
  border: 1px solid var(--hair);
  border-radius: calc(var(--radius) + 4px);
  box-shadow:
    inset 0 1px 0 hsl(var(--foreground) / 6%),
    0 20px 44px -30px hsl(var(--foreground) / 50%);
  opacity: 0;
  transform: translateY(-8px);
  animation: band-in 0.5s cubic-bezier(0.22, 1, 0.36, 1) 0.04s forwards;
}

/* 无在线设备：去掉绿调，回落中性 */
.fleet:not(.is-active) .fleet-band {
  background:
    radial-gradient(
      120% 180% at 0% 0%,
      hsl(var(--muted-foreground) / 10%),
      transparent 52%
    ),
    linear-gradient(
      135deg,
      hsl(var(--card)) 0%,
      hsl(var(--background-deep, var(--background))) 100%
    );
}

.band-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(hsl(var(--foreground) / 4%) 1px, transparent 1px),
    linear-gradient(90deg, hsl(var(--foreground) / 4%) 1px, transparent 1px);
  background-size: 24px 24px;
  mask-image: linear-gradient(90deg, #000, #000 30%, transparent 92%);
}

/* 扫描线：自左向右扫过的高光，强化「实时监控」语义 */
.band-scan {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(
    90deg,
    transparent,
    hsl(var(--success) / 14%) 50%,
    transparent
  );
  opacity: 0;
}

.fleet.is-active .band-scan {
  animation: scan 6s linear infinite;
}

@keyframes scan {
  0% {
    opacity: 0;
    transform: translateX(-100%);
  }

  10%,
  60% {
    opacity: 1;
  }

  100% {
    opacity: 0;
    transform: translateX(100%);
  }
}

/* ---- 身份区 ---- */
.band-id {
  position: relative;
  z-index: 1;
  display: flex;
  gap: 14px;
  align-items: center;
  min-width: 0;
}

.radar {
  position: relative;
  display: inline-flex;
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

.radar-core {
  position: absolute;
  inset: 4px;
  background: hsl(var(--muted-foreground));
  border-radius: 50%;
}

.radar.live .radar-core {
  background: hsl(var(--success));
  box-shadow: 0 0 12px hsl(var(--success) / 80%);
}

.radar-wave {
  position: absolute;
  inset: 0;
  border: 1px solid hsl(var(--success));
  border-radius: 50%;
  opacity: 0;
}

.radar.live .radar-wave {
  animation: ping 1.8s cubic-bezier(0, 0, 0.2, 1) infinite;
}

@keyframes ping {
  0% {
    opacity: 0.7;
    transform: scale(0.55);
  }

  70%,
  100% {
    opacity: 0;
    transform: scale(2.4);
  }
}

.band-title {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 17px;
  font-weight: 680;
  line-height: 1.2;
  color: hsl(var(--foreground));
}

.band-live {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 2px 8px;
  font-family: var(--mono);
  font-size: 10px;
  font-weight: 600;
  color: hsl(var(--muted-foreground));
  letter-spacing: 0.14em;
  border: 1px solid var(--hair);
  border-radius: 999px;
}

.fleet.is-active .band-live {
  color: hsl(var(--success));
  background: hsl(var(--success) / 10%);
  border-color: hsl(var(--success) / 35%);
}

.live-dot {
  width: 6px;
  height: 6px;
  background: currentcolor;
  border-radius: 50%;
}

.fleet.is-active .live-dot {
  animation: blink 1.4s steps(2, jump-none) infinite;
}

@keyframes blink {
  50% {
    opacity: 0.25;
  }
}

.band-sub {
  margin-top: 3px;
  font-family: var(--mono);
  font-size: 11px;
  color: hsl(var(--muted-foreground));
  letter-spacing: 0.08em;
}

/* ---- 统计读数 ---- */
.band-stats {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.stat {
  position: relative;
  min-width: 92px;
  padding: 10px 16px;
  background: hsl(var(--card) / 60%);
  border: 1px solid var(--hair);
  border-radius: var(--radius);
  backdrop-filter: blur(2px);
}

.stat::before {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 0;
  width: 2px;
  content: '';
  background: hsl(var(--muted-foreground) / 45%);
  border-radius: 2px;
}

.stat-online::before {
  background: hsl(var(--success));
}

.stat-offline::before {
  background: hsl(var(--destructive) / 70%);
}

.stat-val {
  font-family: var(--mono);
  font-size: 24px;
  font-weight: 680;
  font-variant-numeric: tabular-nums;
  line-height: 1;
  color: hsl(var(--foreground));
}

.stat-online .stat-val {
  color: hsl(var(--success));
}

.stat-offline .stat-val {
  color: hsl(var(--destructive));
}

.stat-key {
  margin-top: 6px;
  font-family: var(--mono);
  font-size: 10px;
  color: hsl(var(--muted-foreground));
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

/* ============================ 控制台化表格 ============================ */
.fleet-grid {
  flex: 1;
  min-height: 0;
}

/* 表头：等宽 + 字距，呼应态势带读数 */
.fleet-grid :deep(.vxe-header--column .vxe-cell--title) {
  font-family: var(--mono);
  font-size: 11px;
  font-weight: 600;
  color: hsl(var(--muted-foreground));
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

/* 设备编号 / IP：等宽读数 */
.fleet-grid :deep(.device-cell-mono .vxe-cell) {
  font-family: var(--mono);
  font-size: 12.5px;
  color: hsl(var(--foreground) / 90%);
  letter-spacing: 0.02em;
}

.fleet-grid :deep(.device-cell-name .vxe-cell) {
  font-weight: 560;
}

/* 通道数：等宽 tabular 数字，列对齐稳定 */
.fleet-grid :deep(.device-cell-count .vxe-cell) {
  font-family: var(--mono);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* 通道数可点击链接：发丝下划线，悬停高亮 */
.channel-link {
  font-family: var(--mono);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--primary));
  cursor: pointer;
  border-bottom: 1px dashed hsl(var(--primary) / 50%);
  transition:
    color 0.16s,
    border-color 0.16s;
}

.channel-link:hover {
  color: hsl(var(--primary));
  border-bottom-color: hsl(var(--primary));
}

/* 通道数为 0 的入口：保留弱化灰色读数，但仍可点——
   拉流类设备新增后通道就是空的，不给入口就进不去通道页、也就没法点「同步通道」 */
.channel-zero-link {
  font-family: var(--mono);
  font-variant-numeric: tabular-nums;
  color: hsl(var(--muted-foreground));
  cursor: pointer;
  border-bottom: 1px dashed hsl(var(--border));
  transition:
    color 0.16s,
    border-color 0.16s;
}

.channel-zero-link:hover {
  color: hsl(var(--primary));
  border-bottom-color: hsl(var(--primary) / 50%);
}

/* 行悬停：左侧发丝高亮条 + 轻微抬升感 */
.fleet-grid :deep(.vxe-body--row) {
  transition: background-color 0.16s;
}

.fleet-grid :deep(.vxe-body--row:hover) {
  background: hsl(var(--accent, var(--muted)) / 55%);
}

/* ============================ Filter Console（检索台） ============================
   重塑 vxe 内置搜索表单（schema 驱动，与 proxyConfig 联动）为指挥台检索面板：
   发丝边框卡片 + 左侧标记条 + 浮签序号标签 + 等宽大写字段名 + 终端式输入框/按键。
   仅作用于本视图（scoped + .fleet-grid 下），不影响其它列表页。 */
.fleet-grid :deep(.vxe-grid--form-wrapper) {
  position: relative;
  padding: 16px 18px 14px;
  margin-bottom: 12px;
  background: linear-gradient(
    135deg,
    hsl(var(--card)) 0%,
    hsl(var(--background-deep, var(--background))) 130%
  );
  border: 1px solid hsl(var(--border));
  border-radius: calc(var(--radius) + 2px);
  box-shadow: inset 0 1px 0 hsl(var(--foreground) / 5%);
}

/* 左侧竖向标记条，呼应态��带 stat 的 ::before */
.fleet-grid :deep(.vxe-grid--form-wrapper)::before {
  position: absolute;
  top: 16px;
  bottom: 14px;
  left: 0;
  width: 2px;
  content: '';
  background: linear-gradient(
    hsl(var(--success) / 70%),
    hsl(var(--success) / 0%)
  );
  border-radius: 2px;
}

/* 顶部浮签序号标签（绝对定位不占位） */
.fleet-grid :deep(.vxe-grid--form-wrapper)::after {
  position: absolute;
  top: -9px;
  left: 14px;
  padding: 1px 8px;
  font-family: var(--mono);
  font-size: 10px;
  font-weight: 600;
  color: hsl(var(--muted-foreground));
  text-transform: uppercase;
  letter-spacing: 0.18em;
  content: 'FILTER · 检索';
  background: hsl(var(--background-deep, var(--background)));
  border: 1px solid hsl(var(--border));
  border-radius: 999px;
}

/* 字段标签：等宽大写小字距，弱化为次级读数 */
.fleet-grid :deep(.vxe-grid--form-wrapper label),
.fleet-grid :deep(.vxe-grid--form-wrapper [class*='form-label']) {
  font-family: var(--mono);
  font-size: 10.5px;
  font-weight: 600;
  color: hsl(var(--muted-foreground));
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

/* 输入 / 选择器 / 日期框：终端式发丝边框 + 聚焦柔光环 */
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-input),
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-input-affix-wrapper),
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-select-selector),
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-picker) {
  font-family: var(--mono);
  font-size: 12.5px;
  background: hsl(var(--card));
  border-color: hsl(var(--border));
  transition:
    border-color 0.18s,
    box-shadow 0.18s;
}

.fleet-grid :deep(.vxe-grid--form-wrapper .ant-input:hover),
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-input-affix-wrapper:hover),
.fleet-grid
  :deep(.vxe-grid--form-wrapper .ant-select:hover .ant-select-selector),
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-picker:hover) {
  border-color: hsl(var(--foreground) / 35%);
}

.fleet-grid :deep(.vxe-grid--form-wrapper .ant-input:focus),
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-input-affix-wrapper-focused),
.fleet-grid
  :deep(.vxe-grid--form-wrapper .ant-select-focused .ant-select-selector),
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-picker-focused) {
  border-color: hsl(var(--success) / 60%);
  box-shadow: 0 0 0 3px hsl(var(--success) / 12%);
}

/* 检索 / 重置按钮：终端按键观感 */
.fleet-grid :deep(.vxe-grid--form-wrapper .ant-btn) {
  font-family: var(--mono);
  font-size: 12px;
  letter-spacing: 0.04em;
}

.fleet-grid :deep(.vxe-grid--form-wrapper .ant-btn-primary) {
  font-weight: 600;
  box-shadow: 0 8px 22px -12px hsl(var(--primary) / 80%);
}

@media (prefers-reduced-motion: reduce) {
  .fleet-band {
    opacity: 1;
    transform: none;
    animation: none;
  }

  .radar.live .radar-wave,
  .fleet.is-active .band-scan,
  .fleet.is-active .live-dot {
    animation: none;
  }
}
</style>
