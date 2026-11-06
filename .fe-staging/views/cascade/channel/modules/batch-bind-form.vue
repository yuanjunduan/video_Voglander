<script lang="ts" setup>
import type { CascadeChannelApi } from '#/api/cascade/channel';
import type { DeviceApi } from '#/api/device';

import { computed, ref, watch } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import {
  Button,
  Checkbox,
  Empty,
  message,
  Modal,
  Select,
  Spin,
  Switch,
} from 'ant-design-vue';

import { batchPublishCascadeChannels } from '#/api/cascade/channel';
import { getCascadePlatformPage } from '#/api/cascade/platform';
import { getDeviceChannelPage } from '#/api/device';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

import OrgScopePanel from '../../../device/modules/org-scope-panel.vue';

/**
 * 批量推送：一次推给多个上级平台，目标通道**按组织树**挑。
 *
 * <p>为什么不再给一个「1000 条扁平下拉」：通道量级上千时在下拉里翻找等于没法用，而且
 * 用户心里想的从来是「把 A 栋的推上去」，不是「把 CH3402…、CH3403… 推上去」。
 * 左侧组织树与设备页/通道页共用同一个面板，选组织就是选范围。
 *
 * <p>两档目标来源，都建立在组织树上：
 * - 默认：右栏只列<b>当前组织</b>下的通道，逐条勾选（跨组织累加，切换树不丢已选）；
 * - 「推送整个组织」：不看勾选，直接把该组织下（按左下角含下级/仅本级）的全部通道推上去，
 *   解决「一个组织几百路、一路路点不现实」的情况。
 *
 * <p>按组织推的本质仍是给每路通道各建一条发布行——组织发布不会把通道带上去
 * （上级目录里的通道节点只认通道自己的发布行）。
 *
 * <p>与行内「推送配置」的分工：这里只决定「推给谁、推哪些」，编码与名称一律沿用本地值。
 */

interface Emits {
  (e: 'success'): void;
}

interface DrawerData {
  /** 列表里已勾选的通道，打开时预填 */
  channels?: DeviceApi.DeviceChannelVO[];
}

interface ChannelOption {
  /** `deviceId|channelId`：通道编码全局不唯一，必须带上设备段 */
  key: string;
  label: string;
  deviceId: string;
  channelId: string;
}

const emit = defineEmits<Emits>();

const platformOptions = ref<Array<{ label: string; value: string }>>([]);
const platformIds = ref<string[]>([]);
/** 左树选中的组织：undefined=全部资产，0=未归组织 */
const activeOrgId = ref<number | undefined>(undefined);
const includeChildren = ref(true);
const channelOptions = ref<ChannelOption[]>([]);
const selected = ref<string[]>([]);
const channelLoading = ref(false);
const wholeOrg = ref(false);
const result = ref<CascadeChannelApi.BatchPublishResult | null>(null);
const resultOpen = ref(false);

/** 是否有具体组织可选（「全部资产」不是一个能推的目标，它只是筛选态） */
const orgSelected = computed(() => activeOrgId.value !== undefined);
const useWholeOrg = computed(() => wholeOrg.value && orgSelected.value);

const visibleKeys = computed(() => channelOptions.value.map((item) => item.key));
/** 已选里有多少条不在当前组织下：切树后不能悄悄丢掉它们的选中态 */
const outsideCount = computed(
  () => selected.value.filter((key) => !visibleKeys.value.includes(key)).length,
);

async function loadPlatforms() {
  try {
    const result = await getCascadePlatformPage({
      enabled: 1,
      page: 1,
      size: 1000,
    });
    platformOptions.value = (result.items ?? [])
      .map((item) => item.platformId ?? '')
      .filter(Boolean)
      .map((platformId) => ({ label: platformId, value: platformId }));
  } catch (error) {
    console.error('加载上级平台失败:', error);
  }
}

/**
 * 右栏通道列表：跟着左树走。
 *
 * 组织筛选放在请求里而不是前端过滤：通道量级可能上千，一次拉全再筛会把「未归组织」这类
 * 边界情况漏掉（后端 orgId=0 走的是 `org_id IS NULL`）。
 */
async function loadChannels() {
  channelLoading.value = true;
  try {
    const body: DeviceApi.DeviceChannelQueryReq = {};
    if (activeOrgId.value !== undefined) {
      body.orgId = activeOrgId.value;
      body.includeChildren = includeChildren.value;
    }
    const result = await getDeviceChannelPage({ page: 1, size: 1000 }, body);
    channelOptions.value = (result.items ?? []).map((item) => ({
      key: `${item.deviceId}|${item.channelId}`,
      label: `${item.deviceId} / ${item.channelId}${
        item.name ? ` · ${item.name}` : ''
      }`,
      deviceId: item.deviceId ?? '',
      channelId: item.channelId ?? '',
    }));
  } catch (error) {
    console.error('加载本地通道失败:', error);
  } finally {
    channelLoading.value = false;
  }
}

function selectVisible() {
  const merged = new Set([...selected.value, ...visibleKeys.value]);
  selected.value = [...merged];
}

const selectedText = computed(() => {
  const outside =
    outsideCount.value > 0
      ? ` ${$t('cascade.channel.batchBind.selectedOutside', [
          String(outsideCount.value),
        ])}`
      : '';
  return `${$t('cascade.channel.batchBind.selectedSummary', [
    String(selected.value.length),
  ])}${outside}`;
});

const summaryText = computed(() => {
  const data = result.value;
  if (!data) {
    return '';
  }
  return $t('cascade.channel.batchBind.resultSummary', [
    String(data.channelCount ?? 0),
    String(data.platformCount ?? 0),
    String(data.created ?? 0),
    String(data.skippedEnabled ?? 0),
    String(data.skippedDisabled ?? 0),
  ]);
});

const failures = computed(() => result.value?.failures ?? []);

/* 切组织或改范围就重拉：右栏是该组织下的通道，留着旧组织的行会让用户勾错 */
watch([activeOrgId, includeChildren], () => {
  void loadChannels();
});

const { syncWidth } = useDrawerResize('vl-cascade-channel-batch-bind');

const [Drawer, drawerApi] = useVbenDrawer({
  // 默认半屏；打开后由 useDrawerResize 接管为可拖拽的内联宽度
  class: 'vl-cascade-channel-batch-bind w-[50vw]',
  onConfirm: onSubmit,
  onOpenChange(isOpen: boolean) {
    syncWidth();
    if (!isOpen) {
      return;
    }
    const data = drawerApi.getData<DrawerData>();
    platformIds.value = [];
    result.value = null;
    activeOrgId.value = undefined;
    includeChildren.value = true;
    wholeOrg.value = false;
    selected.value = (data?.channels ?? []).map(
      (item) => `${item.deviceId}|${item.channelId}`,
    );
    void loadPlatforms();
    void loadChannels();
  },
});

async function onSubmit() {
  if (platformIds.value.length === 0) {
    message.error($t('cascade.channel.batchBind.platformRequired'));
    return;
  }
  if (wholeOrg.value && !orgSelected.value) {
    message.error($t('cascade.channel.batchBind.orgRequired'));
    return;
  }
  if (!useWholeOrg.value && selected.value.length === 0) {
    message.error($t('cascade.channel.batchBind.channelsRequired'));
    return;
  }

  const payload: CascadeChannelApi.BatchPublishReq = {
    platformIds: platformIds.value,
  };
  if (useWholeOrg.value) {
    payload.orgId = activeOrgId.value;
    payload.includeChildren = includeChildren.value;
  } else {
    payload.channels = selected.value.map((key) => {
      const [localDeviceId, localChannelId] = key.split('|');
      return { localChannelId: localChannelId ?? '', localDeviceId };
    });
  }

  drawerApi.lock();
  try {
    result.value = await batchPublishCascadeChannels(payload);
    resultOpen.value = true;
  } finally {
    drawerApi.unlock();
  }
}

/** 结果看完再关抽屉：关掉就丢上下文，用户没法核对少掉的是哪几条 */
function closeResult() {
  resultOpen.value = false;
  drawerApi.close();
  emit('success');
}

function failureLine(failure: CascadeChannelApi.BatchPublishFailure) {
  const channel = `${failure.localDeviceId ?? '-'} / ${failure.localChannelId ?? '-'}`;
  const platform = failure.platformId ? ` → ${failure.platformId}` : '';
  return `${channel}${platform}：${failure.reason ?? ''}`;
}
</script>

<template>
  <Drawer :title="$t('cascade.channel.batchBind.title')">
    <div class="flex flex-col gap-4 px-4 pb-4">
      <div>
        <div class="mb-2 font-medium">
          {{ $t('cascade.channel.field.platformId') }}
        </div>
        <Select
          v-model:value="platformIds"
          class="w-full"
          mode="multiple"
          :options="platformOptions"
          :placeholder="$t('cascade.channel.placeholder.platformId')"
          show-search
        />
        <div class="text-muted-foreground mt-1 text-xs">
          {{ $t('cascade.channel.batchBind.platformTip') }}
        </div>
      </div>

      <div class="bind-browse">
        <OrgScopePanel
          v-model:include-children="includeChildren"
          v-model:org-id="activeOrgId"
        />

        <div class="bind-main">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="font-medium">
              {{ $t('cascade.channel.batchBind.channels') }}
              <span class="text-muted-foreground text-xs">
                ({{ channelOptions.length }})
              </span>
            </div>
            <div class="flex items-center gap-2">
              <Button
                :disabled="channelLoading || visibleKeys.length === 0"
                size="small"
                @click="selectVisible"
              >
                {{ $t('cascade.channel.batchBind.selectAll') }}
              </Button>
              <Button
                :disabled="selected.length === 0"
                size="small"
                @click="selected = []"
              >
                {{ $t('cascade.channel.batchBind.clearSelected') }}
              </Button>
            </div>
          </div>

          <div class="whole-org">
            <div class="flex items-center gap-2">
              <Switch v-model:checked="wholeOrg" size="small" />
              <span class="text-sm font-medium">
                {{ $t('cascade.channel.batchBind.wholeOrg') }}
              </span>
              <span
                v-if="!orgSelected"
                class="text-muted-foreground text-xs"
              >
                {{ $t('cascade.channel.batchBind.wholeOrgNeedOrg') }}
              </span>
            </div>
            <div class="text-muted-foreground mt-1 text-xs">
              {{ $t('cascade.channel.batchBind.wholeOrgTip') }}
            </div>
          </div>

          <div class="channel-picker" :class="{ 'is-muted': useWholeOrg }">
            <Spin :spinning="channelLoading" size="small">
              <Empty
                v-if="channelOptions.length === 0"
                :description="$t('cascade.channel.batchBind.channelsEmpty')"
              />
              <Checkbox.Group
                v-else
                v-model:value="selected"
                class="channel-list"
                :disabled="useWholeOrg"
              >
                <Checkbox
                  v-for="item in channelOptions"
                  :key="item.key"
                  :value="item.key"
                >
                  <span class="channel-label">{{ item.label }}</span>
                </Checkbox>
              </Checkbox.Group>
            </Spin>
          </div>

          <div class="text-muted-foreground text-xs">
            {{ selectedText }}
          </div>
        </div>
      </div>

      <div class="text-muted-foreground text-xs">
        {{ $t('cascade.channel.batchBind.help') }}
      </div>
    </div>

    <Modal
      :open="resultOpen"
      :footer="null"
      :mask-closable="false"
      :title="$t('cascade.channel.batchBind.resultTitle')"
      @cancel="closeResult"
    >
      <div class="flex flex-col gap-3">
        <div>{{ summaryText }}</div>

        <div
          v-if="(result?.skippedDisabled ?? 0) > 0"
          class="text-muted-foreground text-xs"
        >
          {{ $t('cascade.channel.batchBind.resultDisabledTip') }}
        </div>

        <div
          v-if="(result?.channelCount ?? 0) === 0"
          class="text-xs text-orange-500"
        >
          {{ $t('cascade.channel.batchBind.resultNothing') }}
        </div>

        <div v-if="failures.length > 0">
          <div class="mb-1 font-medium">
            {{ $t('cascade.channel.batchBind.resultFailures', [String(failures.length)]) }}
          </div>
          <ul class="fail-list">
            <li v-for="(failure, index) in failures" :key="index">
              {{ failureLine(failure) }}
            </li>
          </ul>
        </div>

        <div class="flex justify-end">
          <Button type="primary" @click="closeResult">
            {{ $t('cascade.channel.batchBind.resultOk') }}
          </Button>
        </div>
      </div>
    </Modal>
  </Drawer>
</template>

<style scoped>
/* 左树 + 右列表：与通道页、设备页同一套两栏比例，抽屉里给树固定高度 */
.bind-browse {
  display: flex;
  gap: 12px;
  min-height: 0;
}

.bind-browse :deep(.org-panel) {
  height: 420px;
}

.bind-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.whole-org {
  padding: 8px 10px;
  background: hsl(var(--muted) / 40%);
  border-radius: 8px;
}

.channel-picker {
  flex: 1;
  min-height: 160px;
  max-height: 300px;
  padding: 8px 10px;
  overflow: auto;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
  transition: opacity 0.16s;
}

/* 整组织推送时勾选不生效：置灰但仍可读，避免用户以为勾选丢了 */
.channel-picker.is-muted {
  opacity: 0.5;
}

.channel-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.channel-label {
  font-family: var(--font-family-mono, ui-monospace, monospace);
  font-size: 12px;
}

.fail-list {
  max-height: 220px;
  padding-left: 18px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.7;
  color: hsl(var(--muted-foreground));
  list-style: disc;
}
</style>
