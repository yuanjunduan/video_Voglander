import type { VbenFormSchema } from '#/adapter/form';
import type { OnActionClickFn, VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DeviceApi } from '#/api/device';

import { useAccess } from '@vben/access';

import { $t } from '#/locales';

/**
 * 顶部筛选条件 Schema。
 *
 * 全部作用在**本地通道**（tb_device_channel）上：平台名下的上报编码、启停、
 * 父节点都是发布层的配置，不属于本地资产，故本页不再有 platformId 筛选。
 *
 * 「归属组织」不在这里：组织筛选由左侧组织目录（OrgScopePanel）持有，
 * 与设备列表页同一份树、同一套「含下级 / 仅本级」语义。放进表单会出现
 * 「树选 A、表单选 B」两份真相互相打架。
 */
export function useChannelGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: {
        placeholder: $t('cascade.channel.placeholder.localDeviceId'),
      },
      fieldName: 'deviceId',
      label: $t('cascade.channel.field.localDeviceId'),
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: $t('cascade.channel.placeholder.localChannelId'),
      },
      fieldName: 'channelId',
      label: $t('cascade.channel.field.localChannelId'),
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: $t('cascade.channel.placeholder.channelName'),
      },
      fieldName: 'name',
      label: $t('cascade.channel.field.channelName'),
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [
          { label: $t('cascade.channel.status.online'), value: 1 },
          { label: $t('cascade.channel.status.offline'), value: 0 },
        ],
        placeholder: $t('cascade.channel.placeholder.status'),
      },
      fieldName: 'status',
      label: $t('cascade.channel.field.status'),
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [
          { label: $t('cascade.channel.localEnabled.enabled'), value: 1 },
          { label: $t('cascade.channel.localEnabled.disabled'), value: 0 },
        ],
        placeholder: $t('cascade.channel.placeholder.localEnabled'),
      },
      fieldName: 'enabled',
      label: $t('cascade.channel.field.localEnabled'),
    },
  ];
}

/**
 * 表格列定义（本地通道 + 已推送平台）。
 *
 * 「已推送平台」只是一列只读聚合，真正的逐平台配置在推送抽屉里；
 * 本地启停开关改的是 tb_device_channel.enabled，会同时影响所有平台。
 */
export function useChannelColumns<T = DeviceApi.DeviceChannelVO>(options: {
  /**
   * 本地启停开关的前置处理（vxe CellSwitch 入参顺序是 newStatus 在前）。
   * 返回 false 表示用户取消或失败，开关回弹。
   */
  beforeChangeEnabled: (newStatus: number, row: T) => Promise<boolean>;
  onActionClick: OnActionClickFn<T>;
}): VxeTableGridOptions['columns'] {
  const { beforeChangeEnabled, onActionClick } = options;
  const { hasAccessByCodes } = useAccess();
  const canToggleLocal = hasAccessByCodes(['Cascade:Channel:Edit']);

  return [
    {
      // 批量推送用：勾选后一次性推给同一个平台
      type: 'checkbox',
      fixed: 'left',
      width: 44,
    },
    {
      field: 'deviceId',
      showOverflow: 'tooltip',
      title: $t('cascade.channel.field.localDeviceId'),
      width: 200,
    },
    {
      field: 'channelId',
      showOverflow: 'tooltip',
      title: $t('cascade.channel.field.localChannelId'),
      width: 200,
    },
    {
      field: 'name',
      minWidth: 160,
      showOverflow: 'tooltip',
      title: $t('cascade.channel.field.channelName'),
    },
    {
      align: 'center',
      field: 'status',
      slots: { default: 'status' },
      title: $t('cascade.channel.field.status'),
      width: 100,
    },
    {
      // 归属组织存的是主键，列里翻成名称；空表示跟随设备归属
      field: 'orgId',
      slots: { default: 'org' },
      title: $t('cascade.channel.field.orgId'),
      width: 200,
    },
    {
      align: 'center',
      cellRender: {
        attrs: { beforeChange: beforeChangeEnabled },
        name: 'CellSwitch',
        props: {
          checkedChildren: $t('cascade.channel.localEnabled.enabled'),
          unCheckedChildren: $t('cascade.channel.localEnabled.disabled'),
          // 停用会把该通道从所有平台目录里摘掉，无权限时保留状态可见但不可操作
          disabled: !canToggleLocal,
        },
      },
      field: 'enabled',
      title: $t('cascade.channel.field.localEnabled'),
      width: 110,
    },
    {
      field: 'publishedPlatforms',
      slots: { default: 'published' },
      title: $t('cascade.channel.field.publishedPlatforms'),
      width: 260,
    },
    {
      field: 'createTime',
      formatter: 'formatDateTime',
      title: $t('cascade.channel.field.createTime'),
      width: 170,
    },
    {
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'name',
          nameTitle: $t('cascade.channel.field.channelName'),
          onClick: onActionClick,
        },
        name: 'CellOperation',
        options: [
          {
            code: 'push',
            text: $t('cascade.channel.action.push'),
            show: () => hasAccessByCodes(['Cascade:Channel:Edit']),
          },
          {
            code: 'assign',
            text: $t('cascade.channel.action.assign'),
            show: () => hasAccessByCodes(['Cascade:Channel:Edit']),
          },
          {
            // 删的是本地通道（列表主键），发布关系与上级目录节点由后端一并处理
            code: 'delete',
            danger: true,
            text: $t('cascade.channel.action.delete'),
            show: () => hasAccessByCodes(['Cascade:Channel:Delete']),
          },
        ],
      },
      field: 'operation',
      fixed: 'right',
      showOverflow: false,
      title: $t('cascade.channel.field.operation'),
      // 三个入口（推送配置 / 归属组织 / 删除）比原两个宽一档
      width: 260,
    },
  ];
}
