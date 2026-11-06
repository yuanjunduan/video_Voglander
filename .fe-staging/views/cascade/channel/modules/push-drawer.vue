<script lang="ts" setup>
import type { CascadeChannelApi } from '#/api/cascade/channel';
import type { DeviceApi } from '#/api/device';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import {
  Button,
  Empty,
  Input,
  message,
  Popconfirm,
  Switch,
  Tag,
} from 'ant-design-vue';

import {
  createCascadeChannel,
  deleteCascadeChannel,
  getCascadeChannelPage,
  updateCascadeChannel,
} from '#/api/cascade/channel';
import { getCascadePlatformPage } from '#/api/cascade/platform';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

/**
 * 推送抽屉：一条本地通道 × 每个上级平台一行。
 *
 * 数据来源是发布层，不是本地表——
 * 「这路通道已经推给了哪些平台」= `getCascadeChannelPage` **不传 platformId**，
 * 只给 localDeviceId + localChannelId，后端按 localDeviceId/localChannelId 精确命中
 * `tb_cascade_publish` 的全部平台行（含已停报）。逐平台的行则与平台列表左连接，
 * 没有发布行的平台显示为「未配置」，保存时才 create。
 *
 * 编码/名称/启停是**该平台独有**的覆盖：同一通道推给两个上级时互不影响。
 * 挂载位置不在这里配：目录里挂在哪个父节点只看本地通道/设备的组织归属。
 */

interface PushRow {
  platformId: string;
  /** 平台名称；空串=未命名，展示时回落 platformId */
  platformName: string;
  /** 平台本身已停用：配置仍可维护，但报文发不出去 */
  platformDisabled: boolean;
  /** 发布行主键；空表示该平台还没配置 */
  publishId?: number;
  code: string;
  name: string;
  enabled: number;
  saving: boolean;
}

const emit = defineEmits(['success']);

const channel = ref<DeviceApi.DeviceChannelVO>();
const rows = ref<PushRow[]>([]);
const loading = ref(false);

const title = computed(() => $t('cascade.channel.push.title'));

/** 本地通道身份：设备段 + 通道段，抽屉里只读展示，避免选错行 */
const localKey = computed(
  () => `${channel.value?.deviceId ?? '-'} / ${channel.value?.channelId ?? '-'}`,
);

function toRow(
  platformId: string,
  platformName: string,
  platformDisabled: boolean,
  current: DeviceApi.DeviceChannelVO,
  publish?: CascadeChannelApi.CascadeChannelVO,
): PushRow {
  return {
    platformId,
    platformName,
    platformDisabled,
    publishId: publish?.id,
    /* 没配过就按本地值预填：绝大多数通道继承本地编码与名称 */
    code: publish?.cascadeChannelId || current.channelId || '',
    name: publish?.cascadeName || current.name || '',
    enabled: publish?.enabled ?? 1,
    saving: false,
  };
}

async function load() {
  const current = channel.value;
  if (!current?.deviceId || !current.channelId) {
    rows.value = [];
    return;
  }
  loading.value = true;
  try {
    const [platforms, published] = await Promise.all([
      getCascadePlatformPage({ page: 1, size: 1000 }),
      getCascadeChannelPage({
        localChannelId: current.channelId,
        localDeviceId: current.deviceId,
        page: 1,
        size: 200,
      }),
    ]);

    const publishByPlatform = new Map<
      string,
      CascadeChannelApi.CascadeChannelVO
    >();
    for (const item of published.items ?? []) {
      if (item.platformId) {
        publishByPlatform.set(item.platformId, item);
      }
    }

    const list: PushRow[] = [];
    const seen = new Set<string>();
    for (const platform of platforms.items ?? []) {
      const platformId = platform.platformId ?? '';
      if (!platformId) {
        continue;
      }
      seen.add(platformId);
      list.push(
        toRow(
          platformId,
          platform.platformName ?? '',
          platform.enabled === 0,
          current,
          publishByPlatform.get(platformId),
        ),
      );
    }
    /* 平台已被删掉、发布行却还在：照样列出来，否则这条配置永远没有入口可清 */
    for (const [platformId, publish] of publishByPlatform) {
      if (!seen.has(platformId)) {
        /* 平台记录已删，名称无从得知：回落显示 platformId */
        list.push(toRow(platformId, '', true, current, publish));
      }
    }
    rows.value = list;
  } catch (error) {
    console.error('加载通道推送配置失败:', error);
  } finally {
    loading.value = false;
  }
}

async function save(row: PushRow) {
  const current = channel.value;
  if (!current?.id) {
    return;
  }
  const code = row.code.trim() || current.channelId || '';
  if (!code) {
    message.error($t('cascade.channel.msg.cascadeChannelIdRequired'));
    return;
  }
  row.saving = true;
  try {
    if (row.publishId) {
      await updateCascadeChannel(row.publishId, {
        cascadeChannelId: code,
        cascadeName: row.name.trim(),
        enabled: row.enabled,
        id: row.publishId,
      });
    } else {
      const id = await createCascadeChannel({
        cascadeChannelId: code,
        cascadeName: row.name.trim() || undefined,
        localChannelId: current.channelId,
        localDeviceId: current.deviceId,
        platformId: row.platformId,
      });
      /* 新增入参不收启停（后端默认 1）：用户直接选了「停报」时补一次更新 */
      if (row.enabled !== 1) {
        await updateCascadeChannel(id, { enabled: row.enabled, id });
      }
    }
    message.success($t('cascade.channel.push.saved', [row.platformId]));
    emit('success');
    await load();
  } catch (error: any) {
    message.error(error?.message || $t('cascade.channel.push.saveFailed'));
  } finally {
    row.saving = false;
  }
}

async function remove(row: PushRow) {
  if (!row.publishId) {
    return;
  }
  row.saving = true;
  try {
    await deleteCascadeChannel(row.publishId);
    message.success($t('cascade.channel.push.removed', [row.platformId]));
    emit('success');
    await load();
  } catch (error: any) {
    message.error(error?.message || $t('cascade.channel.push.removeFailed'));
  } finally {
    row.saving = false;
  }
}

const { syncWidth } = useDrawerResize('vl-cascade-channel-push');

const [Drawer, drawerApi] = useVbenDrawer({
  // 默认半屏；打开后由 useDrawerResize 接管为可拖拽的内联宽度
  class: 'vl-cascade-channel-push w-[50vw]',
  /* 每行各自保存：没有「一次性提交整张表」的语义，底部确认按钮反而会误导 */
  footer: false,
  onOpenChange(isOpen: boolean) {
    syncWidth();
    if (!isOpen) {
      return;
    }
    channel.value = drawerApi.getData<DeviceApi.DeviceChannelVO>();
    rows.value = [];
    void load();
  },
});
</script>

<template>
  <Drawer :title="title">
    <div class="flex flex-col gap-4 px-4 pb-4">
      <div
        class="flex flex-col gap-1 rounded-md border border-dashed px-3 py-2 text-xs"
      >
        <div class="flex gap-2">
          <span class="text-muted-foreground shrink-0">
            {{ $t('cascade.channel.field.localChannel') }}
          </span>
          <span class="truncate font-mono">{{ localKey }}</span>
        </div>
        <div class="flex gap-2">
          <span class="text-muted-foreground shrink-0">
            {{ $t('cascade.channel.field.orgId') }}
          </span>
          <span class="truncate">
            {{ channel?.orgName || $t('cascade.channel.tip.followDevice') }}
          </span>
        </div>
        <div class="text-muted-foreground">
          {{ $t('cascade.channel.push.localTip') }}
        </div>
      </div>

      <div v-if="loading" class="text-muted-foreground py-6 text-center text-sm">
        {{ $t('cascade.channel.push.loading') }}
      </div>
      <Empty v-else-if="rows.length === 0" :description="$t('cascade.channel.push.noPlatform')" />

      <div v-for="row in rows" :key="row.platformId" class="push-row">
        <div class="flex items-center justify-between gap-2">
          <div class="flex items-center gap-2">
            <span class="font-medium">{{
              row.platformName || row.platformId
            }}</span>
            <span
              v-if="row.platformName"
              class="text-muted-foreground text-xs"
            >
              {{ row.platformId }}
            </span>
            <Tag v-if="!row.publishId" :bordered="false">
              {{ $t('cascade.channel.push.unconfigured') }}
            </Tag>
            <Tag v-else-if="row.enabled === 1" color="success">
              {{ $t('cascade.channel.push.publishing') }}
            </Tag>
            <Tag v-else color="warning">
              {{ $t('cascade.channel.push.stopped') }}
            </Tag>
            <Tag v-if="row.platformDisabled" color="error">
              {{ $t('cascade.channel.push.platformDisabled') }}
            </Tag>
          </div>
        </div>

        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <div>
            <div class="push-label">
              {{ $t('cascade.channel.field.cascadeChannelId') }}
            </div>
            <Input
              v-model:value="row.code"
              :placeholder="channel?.channelId"
            />
          </div>
          <div>
            <div class="push-label">
              {{ $t('cascade.channel.field.cascadeName') }}
            </div>
            <Input
              v-model:value="row.name"
              :placeholder="channel?.name || channel?.channelId"
            />
          </div>
        </div>

        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-2">
            <Switch
              :checked="row.enabled === 1"
              size="small"
              @change="(checked: any) => (row.enabled = checked ? 1 : 0)"
            />
            <span class="text-xs">
              {{ $t('cascade.channel.field.enabled') }}
            </span>
            <span class="text-muted-foreground text-xs">
              {{ $t('cascade.channel.push.enabledTip') }}
            </span>
          </div>
          <div class="flex items-center gap-2">
            <Popconfirm
              v-if="row.publishId"
              :title="$t('cascade.channel.push.removeConfirm', [row.platformId])"
              @confirm="remove(row)"
            >
              <Button danger size="small" :loading="row.saving">
                {{ $t('cascade.channel.push.remove') }}
              </Button>
            </Popconfirm>
            <Button
              size="small"
              type="primary"
              :loading="row.saving"
              @click="save(row)"
            >
              {{ $t('cascade.common.save') }}
            </Button>
          </div>
        </div>
      </div>
    </div>
  </Drawer>
</template>

<style scoped>
.push-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.push-label {
  margin-bottom: 4px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
</style>
