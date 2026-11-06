import type { VbenFormSchema } from '#/adapter/form';
import type { OnActionClickFn, VxeTableGridOptions } from '#/adapter/vxe-table';
import type { CascadeOrgApi } from '#/api/cascade/org';

import { useAccess } from '@vben/access';

import { $t } from '#/locales';

import { cascadeOrgLevel } from '../shared/use-cascade-org';

/**
 * 六段编码的位宽：省(2) 市(2) 县(4) 行业(2) 类型(3) 网络(1)。
 *
 * 序号不在这里：它只作号池下界，不参与拼位。
 * 位宽单独抽成常量是为了让「表单控件长度」「提交前校验」「单测」共用一份口径——
 * 写错一位不会当场报错，而是等到后端 join() 才抛，那时只能给用户一个 500。
 */
export const ORG_CODE_SEGMENTS = [
  { field: 'codeProvince', width: 2 },
  { field: 'codeCity', width: 2 },
  { field: 'codeCounty', width: 4 },
  { field: 'codeIndustry', width: 2 },
  { field: 'codeType', width: 3 },
  { field: 'codeNetwork', width: 1 },
] as const;

export type OrgCodeSegmentField = (typeof ORG_CODE_SEGMENTS)[number]['field'];

/** 六段的 label key，与 {@link ORG_CODE_SEGMENTS} 一一对应 */
const SEGMENT_LABEL_KEYS: Record<OrgCodeSegmentField, string> = {
  codeCity: 'cascade.org.code.rule.city',
  codeCounty: 'cascade.org.code.rule.county',
  codeIndustry: 'cascade.org.code.rule.industry',
  codeNetwork: 'cascade.org.code.rule.network',
  codeProvince: 'cascade.org.code.rule.province',
  codeType: 'cascade.org.code.rule.type',
};

/**
 * 段位字段的 label 宽度。
 *
 * 表单默认 label 宽 100px，「网络标识（1 位）」正好越过边界折成两行，
 * 与右侧控件的基线也会跟着错位；给这一段单独放宽到 132px 后组内标签对齐。
 */
const SEGMENT_LABEL_WIDTH = 132;

/**
 * 开关那一行的 label 宽。
 *
 * 比段位多 20px：「按规则生成编码」后面还跟着一个 help 图标，132px 里挤不下，
 * 会把「编码」两字折到第二行，与右侧开关的竖直中线错开。
 */
const SWITCH_LABEL_WIDTH = 152;

/**
 * 把表单值整理成提交体里的编码段部分。
 *
 * 三条语义与后端严格对齐，写错就是一个「看起来生效、实际没生效」的坑：
 * 1. 开关关闭时整组不传（`undefined` 会被 JSON.stringify 丢掉）：后端 `null` = 不改，
 *    否则用户随手一关就会把已配好的段位清空；
 * 2. 开关打开时空段传空串：后端 `trimToNull` 后当作未配置，按段回落父组织 / 全局默认；
 * 3. 位宽在提交前校验：后端 `join()` 对前 14 位既查长度也查数字，报错发生在写入之后，
 *    只能变成一个 500，用户看不出是哪一段填错了。
 *
 * @returns `error` 非空时调用方直接提示，不要提交
 */
export function collectOrgCodeRule(values: Record<string, any>): {
  error?: string;
  payload: CascadeOrgApi.OrgCodeRuleReq;
} {
  const enabled = Boolean(values.codeRuleEnabled);
  if (!enabled) {
    return { payload: { codeRuleEnabled: 0 } };
  }

  const payload: CascadeOrgApi.OrgCodeRuleReq = { codeRuleEnabled: 1 };
  for (const { field, width } of ORG_CODE_SEGMENTS) {
    const raw = String(values[field] ?? '').trim();
    if (raw !== '' && !new RegExp(`^\\d{${width}}$`).test(raw)) {
      return {
        error: $t('cascade.org.code.rule.invalidWidth', [String(width)]),
        payload: {},
      };
    }
    payload[field] = raw;
  }

  /* 号段起点只作下界：留空或 0 都表示不设下界，不能用 null——null 会被当成「不改」 */
  const seq = Number(values.codeStartSeq ?? 0);
  payload.codeStartSeq = Number.isInteger(seq) && seq > 0 ? seq : 0;
  return { payload };
}

/**
 * 组织表单 Schema（新增 / 编辑）。
 *
 * 组织是本地资产，没有平台维度：新增时只决定「挂在哪、叫什么」，要推给哪些上级
 * 走独立的发布配置（创建时可选，之后在「已推送平台」里维护）。
 *
 * 组织编码由后端生成且不可直接填写；父节点决定树上位置，改层级只能走「移动到…」——
 * 改了等于在上级那边删旧建新，已挂在该组织下的通道会变孤儿。编码本身只能走
 * 「重算编码」：它按当前段位配置整棵子树重算，代价也是上级重建，所以同样带预览。
 *
 * 编辑模式不给启停：启停会把整棵子树进出所有平台的目录，必须走带影响面预览的
 * 停用弹窗（树上的开关），不能从表单里悄悄提交。
 *
 * 编码段两种模式都给：新增时打开规则会直接用段位生成编码（省一次「建完再重算」），
 * 编辑时只能改配置，生效仍需再点一次「重算编码」——保存不动任何已上报的编码。
 */
export function useOrgFormSchema(
  isEditMode = false,
  platformOptions: Array<{ label: string; value: string }> = [],
): VbenFormSchema[] {
  const schema: VbenFormSchema[] = [
    {
      component: 'Input',
      componentProps: {
        maxLength: 60,
        placeholder: $t('cascade.org.placeholder.orgName'),
        showCount: true,
      },
      fieldName: 'orgName',
      formItemClass: 'col-span-2 md:col-span-2',
      label: $t('cascade.org.field.orgName'),
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        max: 9999,
        min: 0,
        placeholder: $t('cascade.org.placeholder.sortOrder'),
      },
      defaultValue: 0,
      fieldName: 'sortOrder',
      label: $t('cascade.org.field.sortOrder'),
    },
  ];
  if (isEditMode) {
    schema.push(...useOrgCodeRuleSchema());
    return schema;
  }
  schema.push({
    component: 'RadioGroup',
    componentProps: {
      buttonStyle: 'solid',
      optionType: 'button',
      options: [
        { label: $t('cascade.org.enabled.enabled'), value: 1 },
        { label: $t('cascade.org.enabled.disabled'), value: 0 },
      ],
    },
    defaultValue: 1,
    fieldName: 'enabled',
    help: $t('cascade.org.help.enabled'),
    label: $t('cascade.org.field.enabled'),
  });
  schema.push({
    component: 'Select',
    componentProps: {
      allowClear: true,
      mode: 'multiple',
      options: platformOptions,
      placeholder: $t('cascade.org.placeholder.platformIds'),
    },
    fieldName: 'platformIds',
    formItemClass: 'col-span-2 md:col-span-2',
    help: $t('cascade.org.help.platformIds'),
    label: $t('cascade.org.field.platformIds'),
  });
  schema.push(...useOrgCodeRuleSchema());
  return schema;
}

/**
 * 「按规则生成编码」开关 + 七段配置。
 *
 * 六段只有在开关打开时才出现：规则关闭时它们不但不生效，还会让人误以为「配了就会用」。
 * 段位一律字符串，不用 InputNumber——省/县/行业段都以 0 开头（如 0101、03），
 * 数字控件会把前导 0 吃掉，拼出来的编码就少一位。
 */
function useOrgCodeRuleSchema(): VbenFormSchema[] {
  const show = (values: Record<string, any>) => Boolean(values.codeRuleEnabled);
  const whenRuleOn = {
    show,
    triggerFields: ['codeRuleEnabled'],
  };

  const schema: VbenFormSchema[] = [
    {
      component: 'Divider',
      fieldName: 'codeRuleDivider',
      formItemClass: 'col-span-2 mt-2 md:col-span-2',
      label: $t('cascade.org.code.rule.section'),
    },
    {
      component: 'Switch',
      defaultValue: false,
      fieldName: 'codeRuleEnabled',
      formItemClass: 'col-span-2 md:col-span-2',
      help: $t('cascade.org.code.rule.enabledHelp'),
      label: $t('cascade.org.code.rule.enabled'),
      labelWidth: SWITCH_LABEL_WIDTH,
    },
  ];

  for (const { field, width } of ORG_CODE_SEGMENTS) {
    if (field === 'codeType') {
      schema.push({
        component: 'Select',
        componentProps: {
          allowClear: true,
          class: 'w-full',
          options: [
            { label: $t('cascade.org.code.rule.typeBusiness'), value: '215' },
            { label: $t('cascade.org.code.rule.typeVirtual'), value: '216' },
          ],
        },
        dependencies: whenRuleOn,
        fieldName: field,
        label: $t(SEGMENT_LABEL_KEYS[field]),
        labelWidth: SEGMENT_LABEL_WIDTH,
      });
      continue;
    }
    schema.push({
      component: 'Input',
      componentProps: {
        class: 'w-full',
        maxLength: width,
        placeholder: $t('cascade.org.code.rule.segmentPlaceholder', [
          String(width),
        ]),
      },
      dependencies: whenRuleOn,
      fieldName: field,
      label: $t(SEGMENT_LABEL_KEYS[field]),
      labelWidth: SEGMENT_LABEL_WIDTH,
    });
  }

  schema.push({
    component: 'InputNumber',
    componentProps: {
      class: 'w-full',
      max: 999_999,
      min: 0,
      precision: 0,
    },
    defaultValue: 0,
    dependencies: whenRuleOn,
    fieldName: 'codeStartSeq',
    formItemClass: 'col-span-2 md:col-span-1',
    help: $t('cascade.org.code.rule.startSeqHelp'),
    label: $t('cascade.org.code.rule.startSeq'),
    labelWidth: SEGMENT_LABEL_WIDTH,
  });
  return schema;
}

/**
 * 表格列定义
 */
export function useOrgColumns(options: {
  /**
   * 启停开关的前置处理（vxe CellSwitch 的入参顺序是 newStatus 在前）。
   * 返回 false 表示用户取消或失败，开关回弹。
   */
  beforeChangeEnabled: (
    newStatus: number,
    row: CascadeOrgApi.CascadeOrgVO,
  ) => Promise<boolean>;
  /** 删除前置校验：仍有子组织或已绑定通道时禁用删除按钮 */
  isDeleteBlocked: (row: CascadeOrgApi.CascadeOrgVO) => boolean;
  onActionClick: OnActionClickFn<CascadeOrgApi.CascadeOrgVO>;
}): VxeTableGridOptions<CascadeOrgApi.CascadeOrgVO>['columns'] {
  const { beforeChangeEnabled, isDeleteBlocked, onActionClick } = options;
  const { hasAccessByCodes } = useAccess();
  const canToggleStatus = hasAccessByCodes(['Cascade:Org:Status']);
  /**
   * 发布范围复用编辑权限码，与行内「推送配置」同一口径。
   *
   * 勾选列由两件事共用：批量停用（Cascade:Org:Status）与批量推送（Cascade:Org:Edit）。
   * 只认前一个码的话，有编辑权的人看不到勾选框，批量推送按钮点了永远是「没勾选」。
   */
  const canPublish = hasAccessByCodes(['Cascade:Org:Edit']);

  return [
    {
      // 批量停用 / 批量推送共用：逐行勾选后一次性提交，不必逐个点开
      type: 'checkbox',
      fixed: 'left',
      visible: canToggleStatus || canPublish,
      width: 44,
    },
    {
      align: 'left',
      field: 'orgName',
      fixed: 'left',
      minWidth: 240,
      showOverflow: 'tooltip',
      title: $t('cascade.org.field.orgName'),
      treeNode: true,
    },
    {
      field: 'orgCode',
      showOverflow: false,
      slots: { default: 'orgCode' },
      title: $t('cascade.org.field.orgCode'),
      width: 220,
    },
    {
      align: 'center',
      field: 'level',
      formatter: ({ row }) => String(cascadeOrgLevel(row.orgPath)),
      title: $t('cascade.org.field.level'),
      width: 80,
    },
    {
      align: 'center',
      field: 'channelCount',
      slots: { default: 'channelCount' },
      title: $t('cascade.org.field.channelCount'),
      width: 120,
    },
    {
      align: 'center',
      field: 'sortOrder',
      title: $t('cascade.org.field.sortOrder'),
      width: 80,
    },
    {
      // 已推送平台（含已停用的发布行）：点「已推送平台」列旁的入口维护
      field: 'publishedPlatformIds',
      slots: { default: 'published' },
      title: $t('cascade.org.field.publishedPlatforms'),
      width: 220,
    },
    {
      align: 'center',
      cellRender: {
        attrs: { beforeChange: beforeChangeEnabled },
        name: 'CellSwitch',
        props: {
          // 表格里的开关是 small 尺寸，三个字放不下会溢出药丸，用两字（与通道页同口径）
          checkedChildren: $t('cascade.org.enabled.on'),
          unCheckedChildren: $t('cascade.org.enabled.off'),
          // 无启停权限时保留状态可见，但不可操作
          disabled: !canToggleStatus,
        },
      },
      field: 'enabled',
      title: $t('cascade.org.field.enabled'),
      width: 120,
    },
    {
      // 本地开关只看自己：祖先停用时本行仍是「启用中」，但所有平台目录里早就没这个节点
      align: 'center',
      field: 'effective',
      slots: { default: 'effective' },
      title: $t('cascade.org.field.effective'),
      width: 110,
    },
    {
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'orgName',
          nameTitle: $t('cascade.org.name'),
          onClick: onActionClick,
        },
        name: 'CellOperation',
        options: [
          {
            code: 'append',
            text: $t('cascade.org.addChild'),
            show: () => hasAccessByCodes(['Cascade:Org:Create']),
          },
          {
            code: 'edit',
            text: $t('common.edit'),
            show: () => hasAccessByCodes(['Cascade:Org:Edit']),
          },
          {
            // 发布范围复用编辑权限：权限码是登录时的快照，新增一个码要让所有人重登才生效
            code: 'publish',
            text: $t('cascade.org.action.publish'),
            show: () => hasAccessByCodes(['Cascade:Org:Edit']),
          },
          {
            // 移动复用编辑权限，理由同上
            code: 'move',
            text: $t('cascade.org.move.action'),
            show: () => hasAccessByCodes(['Cascade:Org:Edit']),
          },
          {
            // 重算编码同样复用编辑权限：它改的是上级目录结构，不是权限模型
            code: 'reapplyCode',
            text: $t('cascade.org.code.action'),
            show: () => hasAccessByCodes(['Cascade:Org:Edit']),
          },
          {
            code: 'delete',
            danger: true,
            disabled: isDeleteBlocked,
            text: $t('common.delete'),
            show: () => hasAccessByCodes(['Cascade:Org:Delete']),
          },
        ],
      },
      field: 'operation',
      fixed: 'right',
      showOverflow: false,
      title: $t('cascade.org.field.operation'),
      // 6 个按钮实测要 415px，360 会挤出边界，给到 440
      width: 440,
    },
  ];
}
