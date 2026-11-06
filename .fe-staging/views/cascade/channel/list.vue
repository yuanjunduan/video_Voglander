<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { CascadeChannelApi } from '#/api/cascade/channel';
import type { DeviceApi } from '#/api/device';

import { onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { Link2 } from '@vben/icons';

import { Button, message, Modal, Tag } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { getCascadeChannelPage } from '#/api/cascade/channel';
import {
  deleteDeviceChannelOne,
  getDeviceChannelPage,
  updateDeviceChannel,
} from '#/api/device';
import { $t } from '#/locales';

import OrgScopePanel from '../../device/modules/org-scope-panel.vue';
import {
  cascadeOrgName,
  ensureCascadeOrgTree,
} from '../shared/use-cascade-org';
import { useChannelColumns, useChannelGridFormSchema } from './data';
import BatchBindForm from './modules/batch-bind-form.vue';
import LocalAssignForm from './modules/local-assign-form.vue';
import PushDrawer from './modules/push-drawer.vue';

/**
 * 级联通道管理 = **本地通道列表** + 每行推送抽屉。
 *
 * 为什么主键换成通道而不是「平台 × 通道」：组织/设备/通道是本地资产，同一路通道
 * 可以推给多个上级、各自配不同编码与父节点。以平台为主键会让同一路通道在列表里
 * 出现多行，用户反而看不出「这条通道到底推给了谁」。
 */

const { hasAccessByCodes } = useAccess();
const route = useRoute();
const router = useRouter();

const [PushDrawerComp, pushDrawerApi] = useVbenDrawer({
  connectedComponent: PushDrawer,
  destroyOnClose: true,
});

const [AssignDrawer, assignDrawerApi] = useVbenDrawer({
  connectedComponent: LocalAssignForm,
  destroyOnClose: true,
});

const [BatchBindDrawer, batchBindDrawerApi] = useVbenDrawer({
  connectedComponent: BatchBindForm,
  destroyOnClose: true,
});

/**
 * 本地通道 → 已推送平台（含已停报行）。
 *
 * 一次跨平台查询（不传 platformId）拿全部发布行，比逐行请求省 N 次往返；
 * 抽屉里再按 localDeviceId + localChannelId 精确取该通道的逐平台配置。
 */
const publishIndex = ref<Map<string, CascadeChannelApi.CascadeChannelVO[]>>(
  new Map(),
);

/**
 * 当前组织筛选：由左侧组织目录（OrgScopePanel）持有，与设备列表页同一份树。
 *
 * undefined = 全部资产；0 = 未归组织（SIP 自动注册设备的合法归属）。
 * 含下级默认打开：通道的组织跟着设备走，而设备总挂在叶子组织上，
 * 不含下级时选中上级组织会一条通道都看不到。
 */
const activeOrgId = ref<number>();
const includeChildren = ref(true);

function publishKey(deviceId?: string, channelId?: string) {
  return `${deviceId ?? ''}|${channelId ?? ''}`;
}

function publishesOf(row: DeviceApi.DeviceChannelVO) {
  return publishIndex.value.get(publishKey(row.deviceId, row.channelId)) ?? [];
}

/** 跨平台拉一次发布行，建「本地通道 → 平台配置」索引（含停报行） */
async function refreshPublishIndex() {
  try {
    const result = await getCascadeChannelPage({ page: 1, size: 2000 });
    const map = new Map<string, CascadeChannelApi.CascadeChannelVO[]>();
    for (const item of result.items ?? []) {
      const key = publishKey(item.localDeviceId, item.localChannelId);
      const list = map.get(key) ?? [];
      list.push(item);
      map.set(key, list);
    }
    publishIndex.value = map;
  } catch (error) {
    /* 推送索引只是展示增强：拿不到不该让主列表失败 */
    console.error('加载已推送平台失败:', error);
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useChannelGridFormSchema(),
    submitOnChange: true,
  },
  gridOptions: {
    columns: useChannelColumns({
      beforeChangeEnabled,
      onActionClick,
    }),
    emptyText: $t('cascade.channel.empty.noChannel'),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const body: DeviceApi.DeviceChannelQueryReq = {
            channelId: formValues?.channelId || undefined,
            deviceId: formValues?.deviceId || undefined,
            enabled: formValues?.enabled ?? undefined,
            name: formValues?.name || undefined,
            status: formValues?.status ?? undefined,
          };
          /* 组织筛选不在表单里：左树是唯一真相，这里只做叠加 */
          if (activeOrgId.value !== undefined) {
            body.orgId = activeOrgId.value;
            body.includeChildren = includeChildren.value;
          }
          const result = await getDeviceChannelPage(
            { page: page.currentPage, size: page.pageSize },
            body,
          );
          await refreshPublishIndex();
          return result;
        },
      },
      autoLoad: false,
    },
    rowConfig: { keyField: 'id' },
    scrollX: { enabled: true },
    scrollY: { enabled: true },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: { code: 'query' },
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<DeviceApi.DeviceChannelVO>,
});

/** 路由带来的组织筛选（组织页「直属通道」钻取） */
function routeOrgId() {
  const raw = route.query.orgId;
  const value = Array.isArray(raw) ? raw[0] : raw;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

/** 左树换了组织，URL 上从组织页带过来的 orgId 就过期了，顺手清掉 */
async function dropRouteOrgId() {
  if (route.query.orgId === undefined) {
    return;
  }
  const query = { ...route.query };
  delete query.orgId;
  await router.replace({ query });
}

/*
 * 组织/范围变了就重查：分页与表单筛选都交回表格自己的 query，
 * 保证「左树 + 表单」两个来源不串。左树是唯一真相，不再镜像进表单。
 */
watch([activeOrgId, includeChildren], () => {
  void dropRouteOrgId();
  gridApi.query();
});

onMounted(async () => {
  /* 组织树到位后「归属组织」列才有名字可显示 */
  await ensureCascadeOrgTree();
  const orgId = routeOrgId();
  if (orgId === undefined) {
    await gridApi.query();
    return;
  }
  /* 组织页「直属通道」钻取进来：让左树选中该组织，查询交给上面的 watch */
  activeOrgId.value = orgId;
});

/* 已在通道页时再从组织页点一次，只有 query 变化不会重建组件 */
watch(
  () => route.query.orgId,
  () => {
    const orgId = routeOrgId();
    if (orgId !== undefined) {
      activeOrgId.value = orgId;
    }
  },
);

function onActionClick({ code, row }: OnActionClickParams<DeviceApi.DeviceChannelVO>) {
  switch (code) {
    case 'assign': {
      openAssign(row);
      break;
    }
    case 'delete': {
      onDelete(row);
      break;
    }
    case 'push': {
      openPush(row);
      break;
    }
  }
}

function openPush(row: DeviceApi.DeviceChannelVO) {
  if (!hasAccessByCodes(['Cascade:Channel:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  pushDrawerApi.setData({ ...row }).open();
}

function openAssign(row: DeviceApi.DeviceChannelVO) {
  if (!hasAccessByCodes(['Cascade:Channel:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  assignDrawerApi.setData({ ...row }).open();
}

function onBatchPush() {
  if (!hasAccessByCodes(['Cascade:Channel:Create'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  const rows =
    (gridApi.grid?.getCheckboxRecords?.() as DeviceApi.DeviceChannelVO[]) ?? [];
  batchBindDrawerApi.setData({ channels: rows }).open();
}

function confirm(content: string, title: string) {
  return new Promise((resolve, reject) => {
    Modal.confirm({
      content,
      onCancel() {
        reject(new Error('cancelled'));
      },
      onOk() {
        resolve(true);
      },
      title,
    });
  });
}

/**
 * 本地启停：改的是 tb_device_channel.enabled，**所有平台**一起生效。
 *
 * 与推送抽屉里的上报开关不是一回事：前者是「这路通道还有没有这个资产」，
 * 后者是「这个平台上要不要它」。
 */
async function beforeChangeEnabled(
  newStatus: number,
  row: DeviceApi.DeviceChannelVO,
): Promise<boolean> {
  if (!hasAccessByCodes(['Cascade:Channel:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return false;
  }
  try {
    if (newStatus === 0) {
      await confirm(
        $t('cascade.channel.msg.localDisableConfirm', [
          row.name || row.channelId,
        ]),
        $t('cascade.channel.field.localEnabled'),
      );
    }
    await updateDeviceChannel({ enabled: newStatus, id: row.id });
    message.success($t('device.msg.updateSuccess'));
    return true;
  } catch (error: any) {
    if (error?.message !== 'cancelled') {
      console.error('切换通道本地启停失败:', error);
    }
    return false;
  }
}

function onRefresh() {
  gridApi.query();
}

/**
 * 删除本地通道。
 *
 * 发布关系与上级目录节点由后端一并处理：
 * {@code DeviceChannelManager#deleteOne} 会清掉这台通道在各级平台的发布行，
 * 并在事务提交后按平台推一次 {@code DEL}；前端不再自己拼两步调用。
 */
async function onDelete(row: DeviceApi.DeviceChannelVO) {
  if (!hasAccessByCodes(['Cascade:Channel:Delete'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  try {
    await confirm(
      $t('cascade.channel.msg.deleteConfirm', [row.name || row.channelId]),
      $t('cascade.channel.action.delete'),
    );
  } catch {
    /* 用户取消：confirm 以 reject 传递 */
    return;
  }
  try {
    await deleteDeviceChannelOne(row.id);
    message.success($t('device.msg.deleteSuccess'));
    onRefresh();
  } catch (error) {
    console.error('删除通道失败:', error);
  }
}
</script>

<template>
  <Page
    auto-content-height
    :description="$t('cascade.channel.help.page')"
    :title="$t('cascade.channel.title')"
  >
    <PushDrawerComp @success="onRefresh" />
    <AssignDrawer @success="onRefresh" />
    <BatchBindDrawer @success="onRefresh" />

    <!-- 与设备列表页同构：左侧组织目录 + 右侧通道表，组织选一次、列表跟着收窄 -->
    <div class="channel-browse">
      <OrgScopePanel
        v-model:include-children="includeChildren"
        v-model:org-id="activeOrgId"
      />

      <div class="channel-main">
        <Grid :table-title="$t('cascade.channel.title')">
          <template #toolbar-tools>
            <Button type="primary" @click="onBatchPush">
              <Link2 class="size-5" />
              {{ $t('cascade.channel.batchBind.title') }}
            </Button>
          </template>

          <template #status="{ row }">
            <Tag :color="row.status === 1 ? 'success' : 'default'">
              {{ row.statusName || '-' }}
            </Tag>
          </template>

          <!-- 归属组织：本地资产字段，空表示跟随设备归属 -->
          <template #org="{ row }">
            <span v-if="row.orgId">{{ cascadeOrgName(row.orgId) }}</span>
            <span v-else class="text-muted-foreground">
              {{ $t('cascade.channel.tip.followDevice') }}
            </span>
          </template>

          <!-- 已推送平台：只读聚合，点进去才是逐平台配置 -->
          <template #published="{ row }">
            <div class="pub-tags">
              <template v-if="publishesOf(row).length > 0">
                <Tag
                  v-for="publish in publishesOf(row)"
                  :key="publish.id"
                  :color="publish.enabled === 1 ? 'success' : 'default'"
                >
                  {{ publish.platformId }}
                </Tag>
              </template>
              <span v-else class="text-muted-foreground">
                {{ $t('cascade.channel.tip.notPublished') }}
              </span>
            </div>
          </template>
        </Grid>
      </div>
    </div>
  </Page>
</template>

<style scoped>
/* 与设备列表页同构：左树定宽、右侧表格吃掉剩余宽度并撑满高度 */
.channel-browse {
  display: flex;
  flex: 1;
  gap: 14px;
  height: 100%;
  min-height: 0;
}

.channel-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.pub-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
</style>
