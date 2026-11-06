<script lang="ts" setup>
import type { CascadeOrgApi } from '#/api/cascade/org';

import { computed, ref } from 'vue';

import { useVbenDrawer, useVbenForm } from '@vben/common-ui';

import { breakpointsTailwind, useBreakpoints } from '@vueuse/core';
import { message } from 'ant-design-vue';

import { createCascadeOrg, updateCascadeOrg } from '#/api/cascade/org';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

import {
  cascadeOrgPathText,
  invalidateCascadeOrgTree,
} from '../../shared/use-cascade-org';
import { collectOrgCodeRule, useOrgFormSchema } from '../data';

interface Emits {
  (e: 'success'): void;
}

/** 抽屉入参：新增时带父组织与平台候选，编辑时带整行 */
interface OrgDrawerPayload {
  id?: number;
  /** 新增时的父组织（空 = 根组织） */
  parent?: CascadeOrgApi.CascadeOrgVO;
  orgName?: string;
  orgCode?: string;
  orgPath?: string;
  sortOrder?: number;
  platformOptions?: Array<{ label: string; value: string }>;
  /* 编码段七列：编辑时从行数据回显，新增时为空 */
  codeProvince?: string;
  codeCity?: string;
  codeCounty?: string;
  codeIndustry?: string;
  codeType?: string;
  codeNetwork?: string;
  codeStartSeq?: number;
  codeRuleEnabled?: number;
}

const emit = defineEmits<Emits>();

const payload = ref<OrgDrawerPayload>({});

const breakpoints = useBreakpoints(breakpointsTailwind);
const isHorizontal = computed(() => breakpoints.greaterOrEqual('md').value);

const isEditMode = computed(() => Boolean(payload.value.id));

/** 新增展示「挂到哪」，编辑展示「现在在哪」 */
const locationText = computed(() => {
  const current = payload.value;
  if (current.id) {
    return cascadeOrgPathText(current.orgPath) || current.orgName || '';
  }
  return (
    cascadeOrgPathText(current.parent?.orgPath) ||
    $t('cascade.channel.tip.rootNode')
  );
});

const drawerTitle = computed(() => {
  if (isEditMode.value) {
    return $t('cascade.org.edit');
  }
  return payload.value.parent
    ? $t('cascade.org.addChild')
    : $t('cascade.org.add');
});

const [Form, formApi] = useVbenForm({
  commonConfig: {
    colon: true,
    formItemClass: 'col-span-2 md:col-span-1',
  },
  schema: useOrgFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-2 gap-x-4',
});

const { syncWidth } = useDrawerResize('vl-cascade-org-form');

const [Drawer, drawerApi] = useVbenDrawer({
  // 默认半屏；打开后由 useDrawerResize 接管为可拖拽的内联宽度
  class: 'vl-cascade-org-form w-[50vw]',
  onConfirm: onSubmit,
  onOpenChange(isOpen: boolean) {
    syncWidth();
    if (!isOpen) {
      return;
    }
    const data = drawerApi.getData<OrgDrawerPayload>();
    payload.value = data ?? {};

    /* 编辑模式不提供启停：换启停会动整棵子树，只能走树上的停用弹窗 */
    formApi.setState({
      schema: useOrgFormSchema(Boolean(data?.id), data?.platformOptions ?? []),
    });

    if (data?.id) {
      /* 先 reset 再回填：schema 是整份替换的，不清会把上一行组织的段位带过来 */
      formApi.resetForm();
      formApi.setValues({
        codeCity: data.codeCity ?? '',
        codeCounty: data.codeCounty ?? '',
        codeIndustry: data.codeIndustry ?? '',
        codeNetwork: data.codeNetwork ?? '',
        codeProvince: data.codeProvince ?? '',
        codeRuleEnabled: data.codeRuleEnabled === 1,
        /* null 回填成 undefined，InputNumber 才显示空而不是 0 */
        codeStartSeq: data.codeStartSeq ?? undefined,
        codeType: data.codeType ?? '',
        orgName: data.orgName,
        sortOrder: data.sortOrder ?? 0,
      });
      return;
    }
    formApi.resetForm();
    formApi.setValues({
      codeRuleEnabled: false,
      enabled: 1,
      orgName: '',
      platformIds: [],
      sortOrder: 0,
    });
  },
});

async function onSubmit() {
  const { valid } = await formApi.validate();
  if (!valid) {
    return;
  }
  const current = payload.value;
  const values = await formApi.getValues();
  const orgName = String(values.orgName ?? '').trim();
  if (!orgName) {
    message.error($t('cascade.org.msg.orgNameRequired'));
    return;
  }
  const sortOrder = Number(values.sortOrder ?? 0);
  /* 段位格式在提交前统一校验：留空表示回落父/全局，不能用 required 规则挡住 */
  const { error: codeRuleError, payload: codeRule } =
    collectOrgCodeRule(values);
  if (codeRuleError) {
    message.error(codeRuleError);
    return;
  }

  drawerApi.lock();
  try {
    if (current.id) {
      /* 不带 enabled：编辑不改启停，避免静默触发「停用并剪掉整棵子树」 */
      await updateCascadeOrg(current.id, {
        ...codeRule,
        id: current.id,
        orgName,
        sortOrder,
      });
      message.success($t('device.msg.updateSuccess'));
    } else {
      await createCascadeOrg({
        ...codeRule,
        enabled: Number(values.enabled ?? 1),
        orgName,
        parentId: current.parent?.id,
        platformIds: (values.platformIds as string[] | undefined) ?? [],
        sortOrder,
      });
      message.success($t('ui.actionMessage.operationSuccess'));
    }
    invalidateCascadeOrgTree();
    drawerApi.close();
    emit('success');
  } finally {
    drawerApi.unlock();
  }
}
</script>

<template>
  <Drawer :title="drawerTitle">
    <div
      class="mx-4 mb-3 flex flex-col gap-1.5 rounded-md border border-dashed px-3 py-2 text-xs"
    >
      <div class="flex gap-2">
        <span class="text-muted-foreground shrink-0">
          {{
            isEditMode
              ? $t('cascade.org.move.current')
              : $t('cascade.org.field.parentId')
          }}
        </span>
        <span class="truncate">{{ locationText }}</span>
      </div>
      <div v-if="isEditMode" class="flex gap-2">
        <span class="text-muted-foreground shrink-0">
          {{ $t('cascade.org.field.orgCode') }}
        </span>
        <!-- 回显当前编码：下面的段位改了也不会立刻变，用户需要能对照着看 -->
        <span class="truncate font-mono">{{ payload.orgCode }}</span>
      </div>
      <div class="text-muted-foreground">
        {{
          isEditMode
            ? $t('cascade.org.help.parentId')
            : $t('cascade.org.help.create')
        }}
      </div>
    </div>
    <Form class="mx-4" :layout="isHorizontal ? 'horizontal' : 'vertical'" />
  </Drawer>
</template>
