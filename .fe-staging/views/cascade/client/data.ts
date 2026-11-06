import type { VbenFormSchema } from '#/adapter/form';
import type {
  OnActionClickFn,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { CascadeClientApi } from '#/api/cascade/client';

import { useAccess } from '@vben/access';

import { z } from '#/adapter/form';
import { $t } from '#/locales';

/** 20 位国标编码：全角数字拼出的串在上级报文里必然报错，前端先挡一层 */
const GB_ID_PATTERN = /^\d{20}$/;

/**
 * 本端客户端表单 Schema（新增/编辑）。
 *
 * 编码在编辑态禁用：换编码等于换了一个平台，上级侧已建的目录、订阅、点播
 * 会全部认不出来，而这个后果在界面上看不出来（后端也会拒绝）。
 */
export function useClientFormSchema(isEditMode = false): VbenFormSchema[] {
  return [
    // 隐藏字段：主键（编辑模式透传）
    {
      component: 'Input',
      dependencies: {
        show: () => false,
        triggerFields: ['id'],
      },
      fieldName: 'id',
      label: 'id',
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: $t('cascade.client.placeholder.clientId'),
        maxlength: 20,
        disabled: isEditMode,
      },
      fieldName: 'clientId',
      label: $t('cascade.client.field.clientId'),
      rules: z
        .string()
        .regex(GB_ID_PATTERN, $t('cascade.client.msg.clientIdFormat')),
      formItemClass: 'col-span-2 md:col-span-2',
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: $t('cascade.client.placeholder.clientName'),
        maxlength: 128,
      },
      fieldName: 'clientName',
      label: $t('cascade.client.field.clientName'),
      formItemClass: 'col-span-2 md:col-span-2',
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: $t('cascade.client.placeholder.clientIp'),
        maxlength: 15,
      },
      fieldName: 'clientIp',
      label: $t('cascade.client.field.clientIp'),
      formItemClass: 'col-span-2 md:col-span-2',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        buttonStyle: 'solid',
        optionType: 'button',
        options: [
          { label: $t('cascade.client.enabled.enabled'), value: 1 },
          { label: $t('cascade.client.enabled.disabled'), value: 0 },
        ],
      },
      defaultValue: 1,
      fieldName: 'enabled',
      label: $t('cascade.client.field.enabled'),
    },
    {
      component: 'RadioGroup',
      componentProps: {
        buttonStyle: 'solid',
        optionType: 'button',
        options: [
          { label: $t('cascade.client.default.yes'), value: 1 },
          { label: $t('cascade.client.default.no'), value: 0 },
        ],
      },
      defaultValue: 0,
      fieldName: 'isDefault',
      label: $t('cascade.client.field.isDefault'),
    },
    {
      component: 'Textarea',
      componentProps: {
        placeholder: $t('cascade.client.placeholder.remark'),
        maxlength: 255,
        rows: 2,
      },
      fieldName: 'remark',
      label: $t('cascade.client.field.remark'),
      formItemClass: 'col-span-2 md:col-span-2',
    },
  ];
}

/**
 * 顶部筛选条件 Schema。
 *
 * 列表是「全部客户端」这种小结果集（一条本端身份就够用），
 * 筛选在网格 query 里本地过滤，省掉一次后端接口扩展。
 */
export function useClientGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: {
        placeholder: $t('cascade.client.placeholder.clientName'),
      },
      fieldName: 'clientName',
      label: $t('cascade.client.field.clientName'),
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: $t('cascade.client.placeholder.clientId'),
      },
      fieldName: 'clientId',
      label: $t('cascade.client.field.clientId'),
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [
          { label: $t('cascade.client.enabled.enabled'), value: 1 },
          { label: $t('cascade.client.enabled.disabled'), value: 0 },
        ],
      },
      fieldName: 'enabled',
      label: $t('cascade.client.field.enabled'),
    },
  ];
}

/**
 * 表格列定义。
 *
 * 「引用平台」列是删除前的判据：>0 时删除会被后端拒绝，
 * 用户要能一眼看出该先改哪些平台。
 */
export function useClientColumns<T = CascadeClientApi.CascadeClientVO>(
  onActionClick: OnActionClickFn<T>,
  onStatusChange?: (newStatus: any, row: T) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions['columns'] {
  const { hasAccessByCodes } = useAccess();

  return [
    {
      field: 'clientName',
      title: $t('cascade.client.field.clientName'),
      width: 220,
      showOverflow: 'tooltip',
      // 名称选填：空值回落编码，避免整列空白认不出是哪个身份
      formatter: ({ row }) => row.clientName || row.clientId || '',
    },
    {
      field: 'clientId',
      title: $t('cascade.client.field.clientId'),
      width: 220,
      showOverflow: 'tooltip',
    },
    {
      field: 'clientIp',
      title: $t('cascade.client.field.clientIp'),
      width: 150,
      formatter: ({ row }) => row.clientIp || '-',
    },
    {
      field: 'isDefault',
      title: $t('cascade.client.field.isDefault'),
      width: 110,
      align: 'center',
      cellRender: {
        name: 'CellTag',
        options: [
          {
            label: $t('cascade.client.default.yes'),
            value: 1,
            type: 'success',
          },
          {
            label: $t('cascade.client.default.no'),
            value: 0,
            type: 'default',
          },
        ],
      },
    },
    {
      field: 'enabled',
      title: $t('cascade.client.field.enabled'),
      width: 100,
      align: 'center',
      cellRender: {
        attrs: {
          beforeChange: onStatusChange,
          disabled: () => !hasAccessByCodes(['Cascade:Client:Edit']),
        },
        name: onStatusChange ? 'CellSwitch' : 'CellTag',
        props: onStatusChange
          ? { checkedValue: 1, unCheckedValue: 0 }
          : undefined,
        options: onStatusChange
          ? undefined
          : [
              {
                label: $t('cascade.client.enabled.enabled'),
                value: 1,
                type: 'success',
              },
              {
                label: $t('cascade.client.enabled.disabled'),
                value: 0,
                type: 'error',
              },
            ],
      },
    },
    {
      field: 'platformRefCount',
      title: $t('cascade.client.field.platformRefCount'),
      width: 120,
      align: 'center',
      formatter: ({ row }) => row.platformRefCount ?? 0,
    },
    {
      field: 'remark',
      title: $t('cascade.client.field.remark'),
      minWidth: 160,
      showOverflow: 'tooltip',
    },
    {
      field: 'createTime',
      title: $t('cascade.client.field.createTime'),
      width: 170,
      formatter: 'formatDateTime',
    },
    {
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'clientId',
          nameTitle: $t('cascade.client.field.clientId'),
          onClick: onActionClick,
        },
        name: 'CellOperation',
        options: [
          {
            code: 'edit',
            text: $t('common.edit'),
            show: () => hasAccessByCodes(['Cascade:Client:Edit']),
          },
          {
            // 已是默认项再点一次没有意义：隐藏而不是点了报错
            code: 'setDefault',
            text: $t('cascade.client.action.setDefault'),
            show: (row: any) =>
              hasAccessByCodes(['Cascade:Client:SetDefault']) &&
              row?.isDefault !== 1,
          },
          {
            // 仍被平台引用时后端会拒绝：入口直接藏掉，别让用户白点一次
            code: 'delete',
            danger: true,
            text: $t('common.delete'),
            show: (row: any) =>
              hasAccessByCodes(['Cascade:Client:Delete']) &&
              !row?.platformRefCount,
          },
        ],
      },
      field: 'operation',
      fixed: 'right',
      title: $t('cascade.client.field.operation'),
      width: 220,
    },
  ];
}
