<script lang="ts" setup>
import type { CascadeChannelApi } from '#/api/cascade/channel';
import type { DeviceApi } from '#/api/device';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { Button, message, Modal, Radio, Select, Switch, TreeSelect } from 'ant-design-vue';

import { batchPublishCascadeChannels } from '#/api/cascade/channel';
import { getCascadePlatformPage } from '#/api/cascade/platform';
import { getDeviceChannelPage } from '#/api/device';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

import {
  buildCascadeOrgTreeNodes,
  ensureCascadeOrgTree,
} from '../../shared/use-cascade-org';

/**
 * 批量推送：一次推给多个上级平台，目标通道可以是「列表里勾选的那几路」或「某个组织下的全部」。
 *
 * <p>为什么要有「按组织」这一档：通道的组织归属跟随设备，一个组织下往往几十上百路，
 * 在勾选框里逐个翻等于没解决。按组织推的本质仍是给每路通道各建一条发布行——
 * 组织发布不会把通道带上去（上级目录里的通道节点只认通道自己的发布行）。
 *
 * <p>与行内「推送配置」的分工：这里只决定「推给谁、推哪些」，编码与名称一律沿用本地值；
 * 要逐平台改编码/名称/开关请用行内的推送配置。
 */

interface Emits {
  (e: 'success'): void;
}

interface DrawerData {
  /** 列表里已勾选的通道，打开时预填 */
  channels?: DeviceApi.DeviceChannelVO[];
}

/** 目标通道来源：列表勾选 / 按组织 */
type ScopeMode = 'checked' | 'org';

const emit = defineEmits<Emits>();

const platformOptions = ref<Array<{ label: string; value: string }>>([]);
const platformIds = ref<string[]>([]);
const scopeMode = ref<ScopeMode>('checked');
const channelOptions = ref<Array<{ label: string; value: string }>>([]);
const selected = ref<string[]>([]);
const channelLoading = ref(false);
const orgTreeData = ref<any[]>([]);
const orgId = ref<number | undefined>(undefined);
const includeChildren = ref(true);
const result = ref<CascadeChannelApi.BatchPublishResult | null>(null);
const resultOpen = ref(false);

/** 选项 value 用 `deviceId|channelId`：通道编码全局不唯一，必须带上设备段 */
function channelKey(channel: DeviceApi.DeviceChannelVO) {
  return `${channel.deviceId}|${channel.channelId}`;
}

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

/** 本地通道候选项：一次拉够（通道量级在千内，分页选择器体验更差） */
async function loadChannels() {
  channelLoading.value = true;
  try {
    const result = await getDeviceChannelPage({ page: 1, size: 1000 }, {});
    channelOptions.value = (result.items ?? []).map((item) => ({
      label: `${item.deviceId} / ${item.channelId}${
        item.name ? ` · ${item.name}` : ''
      }`,
      value: channelKey(item),
    }));
  } catch (error) {
    console.error('加载本地通道失败:', error);
  } finally {
    channelLoading.value = false;
  }
}

/**
 * 组织树：value 取组织主键（后端按主键算设备集合）。
 *
 * 本地链路立不起来的组织置灰——它们的通道推上去也只会被后端拒绝，
 * 与其让用户提交后看一屏失败，不如在选择时就拦住。
 */
async function loadOrgTree() {
  await ensureCascadeOrgTree();
  orgTreeData.value = buildCascadeOrgTreeNodes(undefined, {
    disableStopped: true,
    valueAs: 'id',
  });
}

const checkedCount = computed(() => selected.value.length);

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
    orgId.value = undefined;
    includeChildren.value = true;
    selected.value = (data?.channels ?? []).map((item) => channelKey(item));
    /* 什么都没勾就是奔着「按组织」来的，别让用户多点一下 */
    scopeMode.value = selected.value.length > 0 ? 'checked' : 'org';
    void loadPlatforms();
    void loadChannels();
    void loadOrgTree();
  },
});

async function onSubmit() {
  if (platformIds.value.length === 0) {
    message.error($t('cascade.channel.batchBind.platformRequired'));
    return;
  }
  if (scopeMode.value === 'org') {
    if (orgId.value === undefined || orgId.value === null) {
      message.error($t('cascade.channel.batchBind.orgRequired'));
      return;
    }
  } else if (selected.value.length === 0) {
    message.error($t('cascade.channel.batchBind.channelsRequired'));
    return;
  }

  const payload: CascadeChannelApi.BatchPublishReq = {
    platformIds: platformIds.value,
  };
  if (scopeMode.value === 'org') {
    payload.orgId = orgId.value;
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

      <div>
        <div class="mb-2 font-medium">
          {{ $t('cascade.channel.batchBind.scope') }}
        </div>
        <Radio.Group v-model:value="scopeMode" button-style="solid">
          <Radio.Button value="checked">
            {{ $t('cascade.channel.batchBind.scopeChecked') }}
          </Radio.Button>
          <Radio.Button value="org">
            {{ $t('cascade.channel.batchBind.scopeOrg') }}
          </Radio.Button>
        </Radio.Group>
      </div>

      <div v-if="scopeMode === 'checked'">
        <div class="mb-2 font-medium">
          {{ $t('cascade.channel.batchBind.channels') }}
        </div>
        <Select
          v-model:value="selected"
          class="w-full"
          :loading="channelLoading"
          mode="multiple"
          :options="channelOptions"
          :placeholder="$t('cascade.channel.batchBind.channelsPlaceholder')"
          show-search
        />
        <div class="text-muted-foreground mt-1 text-xs">
          {{ $t('cascade.channel.batchBind.checkedTip', [String(checkedCount)]) }}
        </div>
      </div>

      <div v-else>
        <div class="mb-2 font-medium">
          {{ $t('cascade.channel.batchBind.org') }}
        </div>
        <TreeSelect
          v-model:value="orgId"
          allow-clear
          class="w-full"
          :placeholder="$t('cascade.channel.batchBind.orgPlaceholder')"
          show-search
          :tree-data="orgTreeData"
          tree-node-filter-prop="label"
        />
        <div class="mt-2 flex items-center gap-2">
          <Switch v-model:checked="includeChildren" size="small" />
          <span class="text-sm">
            {{ $t('cascade.channel.batchBind.includeChildren') }}
          </span>
        </div>
        <div class="text-muted-foreground mt-1 text-xs">
          {{ $t('cascade.channel.batchBind.orgTip') }}
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
