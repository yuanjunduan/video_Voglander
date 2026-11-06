<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { CascadeClientApi } from '#/api/cascade/client';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { Plus } from '@vben/icons';

import { Button, message, Modal } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteCascadeClient,
  getCascadeClientList,
  setDefaultCascadeClient,
  updateCascadeClient,
} from '#/api/cascade/client';
import { $t } from '#/locales';

import { useClientColumns, useClientGridFormSchema } from './data';
import Form from './modules/form.vue';

/**
 * 级联本端客户端管理。
 *
 * 这一页维护的是「本端以哪个身份向上级注册」：20 位平台编码 + Contact 头对外通告 IP。
 * 上级平台页面不再逐条填这两项，只在这个列表里选一条引用——
 * 改一次 IP，所有引用它的平台同时生效，不必再逐个平台重填。
 *
 * 端口不在这里：一个 SIP UA 只绑一个 socket，端口恒为 sip.client.port（事实不是选项）。
 */
const { hasAccessByCodes } = useAccess();

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useClientGridFormSchema(),
    submitOnChange: true,
  },
  gridOptions: {
    columns: useClientColumns(onActionClick, onStatusChange),
    emptyText: $t('cascade.client.empty.noClient'),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async (_params, formValues) => {
          /* 本端身份是小结果集（一般就一条），筛选在本地做：
             为两个输入框往列表接口加筛选参数，等于让每个调用方都替后端写一次条件 */
          const list = (await getCascadeClientList()) ?? [];
          const name = keyword(formValues?.clientName);
          const code = keyword(formValues?.clientId);
          const enabled = formValues?.enabled;
          const items = list.filter((item) => {
            const nameHit =
              !name || (item.clientName ?? '').toLowerCase().includes(name);
            const codeHit = !code || (item.clientId ?? '').includes(code);
            const enabledHit =
              enabled === undefined || enabled === null
                ? true
                : item.enabled === enabled;
            return nameHit && codeHit && enabledHit;
          });
          return { items, total: items.length };
        },
      },
      autoLoad: true,
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
  } as VxeTableGridOptions<CascadeClientApi.CascadeClientVO>,
});

function keyword(value?: string) {
  const text = (value ?? '').trim().toLowerCase();
  return text || undefined;
}

function onActionClick(
  e: OnActionClickParams<CascadeClientApi.CascadeClientVO>,
) {
  switch (e.code) {
    case 'delete': {
      onDelete(e.row);
      break;
    }
    case 'edit': {
      onEdit(e.row);
      break;
    }
    case 'setDefault': {
      onSetDefault(e.row);
      break;
    }
  }
}

/**
 * 启用/停用本端客户端。
 *
 * 停用只影响「新增平台下拉里还能不能选到它」，不会打断已在注册的平台——
 * 真要停注册请去平台页停用对应平台。
 */
async function onStatusChange(
  newStatus: number,
  row: CascadeClientApi.CascadeClientVO,
): Promise<boolean> {
  if (!hasAccessByCodes(['Cascade:Client:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return false;
  }
  try {
    await updateCascadeClient(row.id as number, { enabled: newStatus });
    message.success($t('device.msg.updateSuccess'));
    return true;
  } catch {
    return false;
  }
}

function onCreate() {
  if (!hasAccessByCodes(['Cascade:Client:Create'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  formDrawerApi.setData({}).open();
}

function onEdit(row: CascadeClientApi.CascadeClientVO) {
  if (!hasAccessByCodes(['Cascade:Client:Edit'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  formDrawerApi.setData(row).open();
}

async function onSetDefault(row: CascadeClientApi.CascadeClientVO) {
  if (!hasAccessByCodes(['Cascade:Client:SetDefault'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  try {
    await setDefaultCascadeClient(row.id as number);
    message.success($t('cascade.client.msg.setDefaultSuccess'));
    onRefresh();
  } catch (error) {
    console.error('设置默认客户端失败:', error);
  }
}

async function onDelete(row: CascadeClientApi.CascadeClientVO) {
  if (!hasAccessByCodes(['Cascade:Client:Delete'])) {
    message.error($t('device.msg.noPermission'));
    return;
  }
  try {
    await new Promise((resolve, reject) => {
      Modal.confirm({
        content: $t('cascade.client.msg.deleteConfirm', [
          row.clientName || row.clientId,
        ]),
        onCancel() {
          reject(new Error('已取消'));
        },
        onOk() {
          resolve(true);
        },
        title: $t('common.delete'),
      });
    });
  } catch {
    return;
  }
  try {
    await deleteCascadeClient(row.id as number);
    message.success($t('cascade.client.msg.deleteSuccess'));
    onRefresh();
  } catch (error) {
    console.error('删除本端客户端失败:', error);
  }
}

function onRefresh() {
  gridApi.query();
}
</script>

<template>
  <Page
    auto-content-height
    :description="$t('cascade.client.help.page')"
    :title="$t('cascade.client.title')"
  >
    <FormDrawer @success="onRefresh" />

    <Grid :table-title="$t('cascade.client.title')">
      <template #toolbar-tools>
        <Button type="primary" @click="onCreate">
          <Plus class="size-5" />
          {{ $t('ui.actionTitle.create', [$t('cascade.client.name')]) }}
        </Button>
      </template>
    </Grid>
  </Page>
</template>
