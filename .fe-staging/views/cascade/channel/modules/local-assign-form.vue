<script lang="ts" setup>
import type { DeviceApi } from '#/api/device';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { Input, message } from 'ant-design-vue';

import { updateDeviceChannel } from '#/api/device';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

/**
 * 本地通道：只改 tb_device_channel 的 name。
 *
 * <b>组织归属不在这里</b>：通道是设备的子资源，位置只由所属设备的组织决定。通道行上的
 * org_id 已停写、发布侧也不再读——留一个组织选择器只会让人以为「通道能单独挪位置」，
 * 而实际改动不会生效，所有平台的目录仍按设备组织挂载。要换位置请改设备归属。
 */

const emit = defineEmits(['success']);

const channel = ref<DeviceApi.DeviceChannelVO>();
const name = ref('');
const saving = ref(false);

const title = computed(() => $t('cascade.channel.assign.title'));

async function submit() {
  const current = channel.value;
  if (!current?.id) {
    return;
  }
  saving.value = true;
  try {
    /* 组织归属跟随设备，这里没有可提交的归属字段 */
    await updateDeviceChannel({
      id: current.id,
      name: name.value.trim() || undefined,
    });
    message.success($t('device.msg.updateSuccess'));
    drawerApi.close();
    emit('success');
  } catch (error: any) {
    message.error(error?.message || $t('cascade.channel.assign.failed'));
  } finally {
    saving.value = false;
  }
}

const { syncWidth } = useDrawerResize('vl-cascade-channel-local-assign');

const [Drawer, drawerApi] = useVbenDrawer({
  // 默认半屏；打开后由 useDrawerResize 接管为可拖拽的内联宽度
  class: 'vl-cascade-channel-local-assign w-[50vw]',
  onConfirm: submit,
  onOpenChange(isOpen: boolean) {
    syncWidth();
    if (!isOpen) {
      return;
    }
    const data = drawerApi.getData<DeviceApi.DeviceChannelVO>();
    channel.value = data;
    name.value = data?.name ?? '';
  },
});
</script>

<template>
  <Drawer :title="title">
    <div class="flex flex-col gap-4 px-4 pb-4">
      <div>
        <div class="mb-1 text-xs text-muted-foreground">
          {{ $t('cascade.channel.field.localChannel') }}
        </div>
        <div class="font-mono text-sm">
          {{ channel?.deviceId }} / {{ channel?.channelId }}
        </div>
      </div>
      <div>
        <div class="mb-1 text-sm font-medium">
          {{ $t('cascade.channel.field.channelName') }}
        </div>
        <Input v-model:value="name" :placeholder="channel?.channelId" />
      </div>
      <div>
        <div class="mb-1 text-sm font-medium">
          {{ $t('cascade.channel.field.orgId') }}
        </div>
        <div class="text-xs text-muted-foreground">
          {{ $t('cascade.channel.assign.orgTip') }}
        </div>
      </div>
    </div>
  </Drawer>
</template>
