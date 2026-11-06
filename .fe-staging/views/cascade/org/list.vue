<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { CascadeOrgApi } from '#/api/cascade/org';

import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { Copy, Plus } from '@vben/icons';

import { Alert, Button, message, Select, Space, Tag } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { deleteCascadeOrg, enableCascadeOrg } from '#/api/cascade/org';
import { getCascadePlatformPage } from '#/api/cascade/platform';
import { $t } from '#/locales';

import PlatformOrgCatalogPanel from '../shared/PlatformOrgCatalogPanel.vue';
import {
  ensureCascadeOrgTree,
  getCascadeOrgRawTree,
  invalidateCascadeOrgTree,
} from '../shared/use-cascade-org';
import { useOrgColumns } from './data';
import CodeReapplyModal from './modules/code-reapply-modal.vue';
import Form from './modules/form.vue';
import MoveModal from './modules/move-modal.vue';
import PublishDrawer from './modules/publish-drawer.vue';
import RevokeModal from './modules/revoke-modal.vue';

/**
 * 级联组织树：**全库唯一一棵**，没有平台维度。
 *
 * 「某平台上组织树长什么样」是本地这棵树按 `publishedPlatformIds` 过滤后的视图，
 * 所以本页不再有平台切换；每个组织推给了哪些上级由「已推送平台」反查。
 */

const { hasAccessByCodes } = useAccess();
const router = useRouter();

const platformOptions = ref<Array<{ label: string; value: string }>>([]);
const platformLoaded = ref(false);
const noPlatform = computed(
  () => platformLoaded.value && platformOptions.value.length === 0,
);

/**
 * 平台视角：把组织页切成「某上级平台这一刻的目录」。
 *
 * 只读——目录里的编码、名称、父节点都可能是该平台的发布覆盖值，就地编辑会分不清
 * 改的是本地组织还是覆盖值；要改就切回本地视角。数据与平台页的「已推送组织」
 * 来自同一次后端计算，两边永远对得上。
 */
const viewPlatform = ref<string | undefined>();
const platformViewActive = computed(() => Boolean(viewPlatform.value));

function exitPlatformView() {
  viewPlatform.value = undefined;
}

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [PublishDrawerComp, publishDrawerApi] = useVbenDrawer({
  connectedComponent: PublishDrawer,
  destroyOnClose: true,
});

const [RevokeModalComp, revokeModalApi] = useVbenModal({
  connectedComponent: RevokeModal,
  destroyOnClose: true,
});

const [MoveModalComp, moveModalApi] = useVbenModal({
  connectedComponent: MoveModal,
  destroyOnClose: true,
});

const [CodeReapplyModalComp, codeReapplyModalApi] = useVbenModal({
  connectedComponent: CodeReapplyModal,
  destroyOnClose: true,
});

/**
 * vben 的 toolbar 刷新按钮会把点击事件当参数传给 proxy 的 query
 * （见 packages/effects/plugins/src/vxe-table/extends.ts），只有这种调用
 * 是用户主动要新数据，其余调用方都会在改完数据后自行失效缓存。
 */
function isToolbarRefresh(params?: unknown) {
  return typeof PointerEvent !== 'undefined' && params instanceof PointerEvent;
}

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    columns: useOrgColumns({
      beforeChangeEnabled,
      isDeleteBlocked,
      onActionClick,
    }),
    emptyText: $t('cascade.org.empty.noOrg'),
    height: 'auto',
    keepSource: true,
    pagerConfig: {
      enabled: false,
    },
    proxyConfig: {
      ajax: {
        /**
         * 列表本身就是组织树，命中缓存只为省一次往返；点刷新按钮必须重取，
         * 否则「刷新」只是把内存里的旧树再渲染一遍。
         */
        query: async (params?: unknown) => {
          await ensureCascadeOrgTree(isToolbarRefresh(params));
          return getCascadeOrgRawTree();
        },
      },
      autoLoad: false,
    },
    rowConfig: {
      keyField: 'id',
    },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: { code: 'query' },
      zoom: true,
    },
    treeConfig: {
      childrenField: 'children',
      parentField: 'parentId',
      rowField: 'id',
      transform: false,
    },
  } as VxeTableGridOptions<CascadeOrgApi.CascadeOrgVO>,
});

onMounted(async () => {
  await loadPlatformOptions();
  await gridApi.query();
});

async function loadPlatformOptions() {
  try {
    // 不带 enabled 过滤：组织是给平台配的，平台停用后仍需维护它的组织
    const result = await getCascadePlatformPage({ page: 1, size: 1000 });
    platformOptions.value = (result.items ?? [])
      .map((item) => item.platformId ?? '')
      .filter(Boolean)
      .map((platformId) => ({ label: platformId, value: platformId }));
  } catch (error) {
    console.error('加载上级平台失败:', error);
  } finally {
    platformLoaded.value = true;
  }
}

function onActionClick({
  code,
  row,
}: OnActionClickParams<CascadeOrgApi.CascadeOrgVO>) {
  switch (code) {
    case 'append': {
      openCreate(row);
      break;
    }
    case 'delete': {
      onDelete(row);
      break;
    }
    case 'edit': {
      onEdit(row);
      break;
    }
    case 'move': {
      onMove(row);
      break;
    }
    case 'publish': {
      openPublish(row);
      break;
    }
    case 'reapplyCode': {
      openCodeReapply(row);
      break;
    }
  }
}

function openCreate(parent?: CascadeOrgApi.CascadeOrgVO) {
  if (!hasAccessByCodes(['Cascade:Org:Create'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  formDrawerApi
    .setData({
      parent,
      platformOptions: platformOptions.value,
    })
    .open();
}

function onEdit(row: CascadeOrgApi.CascadeOrgVO) {
  if (!hasAccessByCodes(['Cascade:Org:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  formDrawerApi
    .setData({ ...row, platformOptions: platformOptions.value })
    .open();
}

/**
 * 打开「已推送平台」反查抽屉。
 *
 * 不做成表格里的一列多选：发布范围一改就是一批 ADD/DEL，还会带上编码与策略覆盖，
 * 需要在抽屉里逐平台确认后再一次性提交。
 */
function openPublish(row: CascadeOrgApi.CascadeOrgVO) {
  if (!hasAccessByCodes(['Cascade:Org:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  publishDrawerApi.setData({ ...row }).open();
}

/**
 * 打开移动弹窗。
 *
 * 不做成表单里的下拉：移动一次就是各平台目录里的一棵子树重建，必须让用户先看到
 * 「跟着走多少组织/通道」再确认；行数据里已带 children 与 channelCount，直接用。
 */
function onMove(row: CascadeOrgApi.CascadeOrgVO) {
  if (!hasAccessByCodes(['Cascade:Org:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  moveModalApi.setData({ org: row }).open();
}

/**
 * 打开「重算编码」弹窗。
 *
 * 不做成一键执行：重算会同时改写整棵子树的 orgCode、orgPath、发布行 objectKey
 * 与通道 parentKey，并让每个已发布平台先删旧再重推。弹窗里先跑 dryRun，
 * 让人看清哪几个组织从什么编码变成什么编码，再决定是否提交。
 */
function openCodeReapply(row: CascadeOrgApi.CascadeOrgVO) {
  if (!hasAccessByCodes(['Cascade:Org:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  codeReapplyModalApi.setData({ org: row }).open();
}

/** 仍有子组织或已绑定通道时禁用删除，避免「点了才报错」 */
function isDeleteBlocked(row: CascadeOrgApi.CascadeOrgVO) {
  return (row.children?.length ?? 0) > 0 || (row.channelCount ?? 0) > 0;
}

async function onDelete(row: CascadeOrgApi.CascadeOrgVO) {
  if (!hasAccessByCodes(['Cascade:Org:Delete'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  message.loading({
    content: $t('ui.actionMessage.deleting', [row.orgName]),
    duration: 0,
    key: 'action_process_msg',
  });
  try {
    await deleteCascadeOrg(row.id as number);
    message.success({
      content: $t('cascade.org.msg.deleteSuccess'),
      key: 'action_process_msg',
    });
    invalidateCascadeOrgTree();
    await gridApi.query();
  } catch (error) {
    console.error('删除组织失败:', error);
    message.destroy('action_process_msg');
  }
}

async function beforeChangeEnabled(
  newStatus: number,
  row: CascadeOrgApi.CascadeOrgVO,
) {
  if (!hasAccessByCodes(['Cascade:Org:Status'])) {
    message.error($t('device.msg.noPermission'));
    return false;
  }
  /* 1 → 0 不是改字段：整棵子树要从每个平台目录里消失，必须走带预览的停用弹窗 */
  if (newStatus === 0) {
    openRevoke([row]);
    return false;
  }
  try {
    await enableCascadeOrg(row.id as number);
    message.success($t('cascade.org.msg.publishSuccess'));
    invalidateCascadeOrgTree();
    await gridApi.query();
    return true;
  } catch (error) {
    console.error('恢复组织上报失败:', error);
    return false;
  }
}

/**
 * 打开停用弹窗。
 *
 * 已经停用的组织不必再发 DEL（上级早就没有了），勾选时一并过滤掉，
 * 否则批量结果里会出现一堆「本来就已停用」的假失败。
 */
function openRevoke(rows: CascadeOrgApi.CascadeOrgVO[]) {
  const targets = rows.filter((row) => row.id && row.enabled !== 0);
  if (targets.length === 0) {
    message.warning($t('cascade.org.revoke.nothingToRevoke'));
    return;
  }
  revokeModalApi
    .setData({
      ids: targets.map((row) => row.id as number),
      orgName: targets.length === 1 ? targets[0]?.orgName : undefined,
    })
    .open();
}

function onBatchRevoke() {
  if (!hasAccessByCodes(['Cascade:Org:Status'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  const rows =
    (gridApi.grid?.getCheckboxRecords?.() as CascadeOrgApi.CascadeOrgVO[]) ??
    [];
  openRevoke(rows);
}

async function onRevokeSuccess() {
  invalidateCascadeOrgTree();
  await gridApi.query();
}

/** 移动后组织树整体换了形状：缓存与表格都要重取 */
async function onMoveSuccess() {
  invalidateCascadeOrgTree();
  await gridApi.query();
}

/** 重编码后 orgCode 与 orgPath 全变了，缓存的组织索引也跟着失真，同样要重取 */
async function onCodeReapplySuccess() {
  invalidateCascadeOrgTree();
  await gridApi.query();
}

async function copyCode(code?: string) {
  if (!code) {
    return;
  }
  await navigator.clipboard.writeText(code);
  message.success($t('cascade.org.msg.copySuccess'));
}

/** 直属通道数 → 钻取到通道页（只含直属，和列名语义一致） */
function onChannelClick(row: CascadeOrgApi.CascadeOrgVO) {
  if (!row.id) {
    return;
  }
  router.push({
    path: '/device/cascade/channel',
    query: { orgId: String(row.id) },
  });
}

/**
 * 不可见时的悬停提示：说清楚是自己停用，还是被哪一级祖先连带。
 *
 * 两种情况用户的下一步动作不同：自己停用就去恢复自己，被连带则必须先去恢复那个组织。
 */
function effectiveTip(row: CascadeOrgApi.CascadeOrgVO) {
  if (row.effective) {
    return '';
  }
  return row.blockedByOrgName
    ? $t('cascade.org.effective.blockedBy', [row.blockedByOrgName])
    : $t('cascade.org.effective.blocked');
}

function expandAll(expanded: boolean) {
  gridApi.grid?.setAllTreeExpand(expanded);
}

/**
 * 抽屉提交成功后的刷新。
 *
 * 必须先把组织树缓存置为失效：proxy 的 query 读的是缓存树，只调 query
 * 等于把提交前的树再渲染一遍（发布范围改完仍显示「未推送」就是这么来的）。
 */
async function onRefresh() {
  invalidateCascadeOrgTree();
  await gridApi.query();
}

function goPlatform() {
  router.push('/device/cascade/platform');
}
</script>

<template>
  <Page
    auto-content-height
    :description="$t('cascade.org.help.deleteRule')"
    :title="$t('cascade.org.title')"
  >
    <FormDrawer @success="onRefresh" />
    <PublishDrawerComp @success="onRefresh" />
    <RevokeModalComp @success="onRevokeSuccess" />
    <MoveModalComp @success="onMoveSuccess" />
    <CodeReapplyModalComp @success="onCodeReapplySuccess" />

    <div class="flex h-full min-h-0 flex-col gap-3">
      <Alert
        v-if="noPlatform"
        show-icon
        type="warning"
        :message="$t('cascade.org.empty.noPlatform')"
        :description="$t('cascade.org.empty.noPlatformTip')"
      >
        <template #action>
          <Button size="small" type="primary" @click="goPlatform">
            {{ $t('cascade.org.empty.goPlatform') }}
          </Button>
        </template>
      </Alert>

      <!-- 视角切换常驻在表格之外：本地表格在平台视角下是 v-show 隐藏的，
           放进它的工具栏会连切换入口一起消失 -->
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-muted-foreground text-sm">
          {{ $t('cascade.org.platformView.platform') }}
        </span>
        <Select
          v-model:value="viewPlatform"
          allow-clear
          class="w-[240px]"
          :disabled="platformOptions.length === 0"
          :options="platformOptions"
          :placeholder="$t('cascade.org.platformView.switchPlaceholder')"
        />
        <Button v-if="platformViewActive" @click="exitPlatformView">
          {{ $t('cascade.org.platformView.backToLocal') }}
        </Button>
      </div>

      <!-- 平台视角下本地表格只隐藏不销毁：否则列宽、展开状态与已选行都会丢。
           v-show 包一层 div：直接挂在 Grid 组件上会触发「非元素根节点使用指令」告警 -->
      <div v-show="!platformViewActive" class="min-h-0 flex-1">
        <Grid :table-title="$t('cascade.org.title')">
        <template #toolbar-tools>
          <Space>
            <Button type="primary" @click="openCreate()">
              <Plus class="size-5" />
              {{ $t('ui.actionTitle.create', [$t('cascade.org.name')]) }}
            </Button>
            <Button @click="expandAll(true)">
              {{ $t('cascade.org.action.expandAll') }}
            </Button>
            <Button @click="expandAll(false)">
              {{ $t('cascade.org.action.collapseAll') }}
            </Button>
            <Button @click="onBatchRevoke">
              {{ $t('cascade.org.revoke.batchAction') }}
            </Button>
          </Space>
        </template>

        <!-- 组织编码：等宽 + 点击复制，方便直接贴到上级平台 -->
        <template #orgCode="{ row }">
          <span
            v-if="row.orgCode"
            class="org-code"
            :title="$t('cascade.org.action.copyCode')"
            @click="copyCode(row.orgCode)"
          >
            {{ row.orgCode }}
            <Copy class="org-code-icon size-3.5" />
          </span>
          <!-- 启用了七段规则的才标：「重算编码」对未启用的组织是空转，不标出来分不清 -->
          <Tag
            v-if="row.codeRuleEnabled === 1"
            class="org-code-rule"
            :bordered="false"
            color="processing"
            :title="$t('cascade.org.code.rule.badgeTip')"
          >
            {{ $t('cascade.org.code.rule.badge') }}
          </Tag>
        </template>

        <!-- 已推送平台（含停用行）：停用行也列出来，否则「配了没开」看起来像「没配」 -->
        <template #published="{ row }">
          <div class="pub-tags">
            <template v-if="(row.publishedPlatformIds ?? []).length > 0">
              <Tag
                v-for="platformId in row.publishedPlatformIds"
                :key="platformId"
                :bordered="false"
              >
                {{ platformId }}
              </Tag>
            </template>
            <span v-else class="text-muted-foreground">
              {{ $t('cascade.channel.tip.notPublished') }}
            </span>
          </div>
        </template>

        <!-- 可见性：本地开关只看自己，祖先停用后本行仍显示启用，但所有平台目录里已没有它 -->
        <template #effective="{ row }">
          <Tag v-if="row.effective" color="success">
            {{ $t('cascade.org.effective.visible') }}
          </Tag>
          <Tag v-else color="error" :title="effectiveTip(row)">
            {{ $t('cascade.org.effective.invisible') }}
          </Tag>
        </template>

        <!-- 直属通道数：>0 可点，钻取到通道页并按该组织过滤 -->
        <template #channelCount="{ row }">
          <a
            v-if="(row.channelCount ?? 0) > 0"
            class="channel-link"
            :title="$t('cascade.org.tip.channelCount')"
            @click="onChannelClick(row)"
          >
            {{ row.channelCount }}
          </a>
          <span v-else class="channel-zero">
            {{ row.channelCount ?? 0 }}
          </span>
        </template>
        </Grid>
      </div>

      <div
        v-if="platformViewActive"
        class="min-h-0 flex-1 overflow-auto rounded-md bg-card"
      >
        <PlatformOrgCatalogPanel :platform-id="viewPlatform" />
      </div>
    </div>
  </Page>
</template>

<style scoped>
.org-code {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  justify-content: center;
  font-family:
    'JetBrains Mono', 'IBM Plex Mono', ui-monospace, 'SF Mono', monospace;
  font-size: 12px;
  color: hsl(var(--foreground) / 80%);
  cursor: pointer;
}

.org-code-icon {
  opacity: 0;
  transition: opacity 0.15s ease-in-out;
}

.org-code:hover {
  color: hsl(var(--primary));
}

.org-code:hover .org-code-icon {
  opacity: 1;
}

.org-code-rule {
  margin-left: 6px;
  font-size: 11px;
  line-height: 16px;
}

.channel-link {
  font-variant-numeric: tabular-nums;
}

.channel-zero {
  color: hsl(var(--muted-foreground) / 60%);
}

.pub-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
</style>
