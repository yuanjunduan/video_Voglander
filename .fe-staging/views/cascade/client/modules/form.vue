<script lang="ts" setup>
import type { CascadeClientApi } from '#/api/cascade/client';

import { computed, ref } from 'vue';

import { useVbenDrawer, useVbenForm } from '@vben/common-ui';

import { breakpointsTailwind, useBreakpoints } from '@vueuse/core';
import { message } from 'ant-design-vue';

import {
  createCascadeClient,
  updateCascadeClient,
} from '#/api/cascade/client';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

import { useClientFormSchema } from '../data';

interface Emits {
  (e: 'success'): void;
}

const emit = defineEmits<Emits>();

const localFormData = ref<CascadeClientApi.CascadeClientVO>(
  {} as CascadeClientApi.CascadeClientVO,
);

const breakpoints = useBreakpoints(breakpointsTailwind);
const isHorizontal = computed(() => breakpoints.greaterOrEqual('md').value);

const [Form, formApi] = useVbenForm({
  commonConfig: {
    colon: true,
    formItemClass: 'col-span-2 md:col-span-1',
  },
  schema: useClientFormSchema(false),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-2 gap-x-4',
});

const { syncWidth } = useDrawerResize('vl-cascade-client-drawer');

const [Drawer, drawerApi] = useVbenDrawer({
  class: 'vl-cascade-client-drawer w-[50vw]',
  onConfirm: onSubmit,
  onOpenChange(isOpen: boolean) {
    if (isOpen) {
      syncWidth();
      const data = drawerApi.getData<CascadeClientApi.CascadeClientVO>();
      if (data?.id) {
        localFormData.value = data;
        formApi.setState({ schema: useClientFormSchema(true) });
        formApi.setValues(data);
      } else {
        formApi.setState({ schema: useClientFormSchema(false) });
        formApi.resetForm();
        localFormData.value = {} as CascadeClientApi.CascadeClientVO;
      }
    }
  },
});

async function onSubmit() {
  const { valid } = await formApi.validate();
  if (!valid) {
    return;
  }
  drawerApi.lock();
  try {
    const formValues = (await formApi.getValues()) as Record<string, any>;
    if (localFormData.value?.id) {
      /* 编码不可改：后端不接受该字段，前端也不送，避免「看着改了其实没变」 */
      const payload = { ...formValues } as Record<string, any>;
      delete payload.clientId;
      delete payload.id;
      await updateCascadeClient(
        localFormData.value.id,
        payload as CascadeClientApi.ClientUpdateReq,
      );
      message.success($t('device.msg.updateSuccess'));
    } else {
      await createCascadeClient(
        formValues as CascadeClientApi.ClientCreateReq,
      );
      message.success($t('ui.actionMessage.operationSuccess'));
    }
    drawerApi.close();
    emit('success');
  } finally {
    drawerApi.unlock();
  }
}

const getDrawerTitle = computed(() =>
  localFormData.value?.id
    ? $t('cascade.client.edit')
    : $t('cascade.client.add'),
);
</script>

<template>
  <Drawer :title="getDrawerTitle">
    <Form class="mx-4" :layout="isHorizontal ? 'horizontal' : 'vertical'" />
  </Drawer>
</template>
