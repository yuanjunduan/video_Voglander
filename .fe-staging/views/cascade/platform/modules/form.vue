<script lang="ts" setup>
import type { CascadeClientApi } from '#/api/cascade/client';
import type { CascadePlatformApi } from '#/api/cascade/platform';

import { computed, ref } from 'vue';

import { useVbenDrawer, useVbenForm } from '@vben/common-ui';

import { breakpointsTailwind, useBreakpoints } from '@vueuse/core';
import { message } from 'ant-design-vue';

import { getCascadeClientList } from '#/api/cascade/client';
import {
  createCascadePlatform,
  updateCascadePlatform,
} from '#/api/cascade/platform';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

import { usePlatformFormSchema } from '../data';

interface Emits {
  (e: 'success'): void;
}

const emit = defineEmits<Emits>();

const localFormData = ref<CascadePlatformApi.CascadePlatformVO>(
  {} as CascadePlatformApi.CascadePlatformVO,
);
const isEditMode = ref(false);
const clientOptions = ref<Array<{ label: string; value: number }>>([]);

const breakpoints = useBreakpoints(breakpointsTailwind);
const isHorizontal = computed(() => breakpoints.greaterOrEqual('md').value);

const [Form, formApi] = useVbenForm({
  commonConfig: {
    colon: true,
    formItemClass: 'col-span-2 md:col-span-1',
  },
  schema: usePlatformFormSchema(false),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-2 gap-x-4',
});

const { syncWidth } = useDrawerResize('vl-cascade-platform-drawer');

const [Drawer, drawerApi] = useVbenDrawer({
  // 默认半屏；打开后由 useDrawerResize 接管为可拖拽的内联宽度
  class: 'vl-cascade-platform-drawer w-[50vw]',
  onConfirm: onSubmit,
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      return;
    }
    syncWidth();
    const data = drawerApi.getData<CascadePlatformApi.CascadePlatformVO>();
    const editMode = Boolean(data?.id);
    /* 选项必须先到再建 schema：Select 找不到对应 value 的选项时只回显一个数字 id，
       看上去像「客户端名字丢了」 */
    const clients = await loadClientOptions(editMode);
    if (editMode) {
      localFormData.value = data as CascadePlatformApi.CascadePlatformVO;
      isEditMode.value = true;

      // 根据编辑模式设置schema（选项随 schema 一起换掉）
      formApi.setState({
        schema: usePlatformFormSchema(true, clientOptions.value),
      });

      // 回填表单数据
      formApi.setValues(data as CascadePlatformApi.CascadePlatformVO);
    } else {
      // 创建模式设置schema
      formApi.setState({
        schema: usePlatformFormSchema(false, clientOptions.value),
      });
      formApi.resetForm();
      localFormData.value = {} as CascadePlatformApi.CascadePlatformVO;
      isEditMode.value = false;
      preselectDefaultClient(clients);
    }
  },
});

async function onSubmit() {
  const { valid } = await formApi.validate();
  if (valid) {
    drawerApi.lock();
    try {
      const formValues = await formApi.getValues();

      if (localFormData.value?.id) {
        // 编辑模式
        await updateCascadePlatform(localFormData.value.id, {
          id: localFormData.value.id,
          ...formValues,
        } as CascadePlatformApi.PlatformUpdateReq);
        message.success($t('device.msg.updateSuccess'));
      } else {
        // 新增模式
        await createCascadePlatform(
          formValues as CascadePlatformApi.PlatformCreateReq,
        );
        message.success($t('ui.actionMessage.operationSuccess'));
      }

      drawerApi.close();
      emit('success');
    } finally {
      drawerApi.unlock();
    }
  }
}

/**
 * 加载本端客户端下拉选项。
 *
 * @param includeDisabled 编辑态取全量：停用的身份不该再被新平台引用，但正在引用
 *                        它的平台打开时若列表里没有它，下拉只会显示一个数字 id
 * @return 客户端原始列表（新增态复用其 isDefault 标记做预选）
 */
async function loadClientOptions(includeDisabled = false) {
  try {
    const list = (await getCascadeClientList(!includeDisabled)) ?? [];
    clientOptions.value = list
      .filter((item) => item.id !== undefined)
      .map((item) => ({
        label: `${item.clientName || item.clientId}${item.clientIp ? ` · ${item.clientIp}` : ''}`,
        value: item.id as number,
      }));
    return list;
  } catch (error) {
    /* 拉不到选项时表单仍可打开，但会看不到已有客户端：错误留在控制台而不是吞掉 */
    console.error('加载本端客户端下拉失败:', error);
    clientOptions.value = [];
    return [];
  }
}

/**
 * 新增时预选默认本端客户端。
 *
 * 不预选的话下拉是空的，用户会以为「没有可选客户端」，而实际上前置条件是
 * 先去本端客户端页建一条——这个条件在表单里看不出来，只能由提示补上。
 */
function preselectDefaultClient(list: CascadeClientApi.CascadeClientVO[]) {
  if (list.length === 0) {
    message.warning($t('cascade.platform.msg.clientMissing'));
    return;
  }
  const preferred = list.find((item) => item.isDefault === 1) ?? list[0];
  if (preferred?.id) {
    formApi.setFieldValue('clientRefId', preferred.id);
  }
}

const getDrawerTitle = computed(() =>
  localFormData.value?.id
    ? $t('cascade.platform.edit')
    : $t('cascade.platform.add'),
);
</script>

<template>
  <Drawer :title="getDrawerTitle">
    <Form class="mx-4" :layout="isHorizontal ? 'horizontal' : 'vertical'" />
  </Drawer>
</template>
