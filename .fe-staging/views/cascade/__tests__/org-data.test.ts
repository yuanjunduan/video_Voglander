import { describe, expect, it, vi } from 'vitest';

import {
  collectOrgCodeRule,
  ORG_CODE_SEGMENTS,
  useOrgColumns,
  useOrgFormSchema,
} from '../org/data';

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

/* 共享组织树模块会拉组织接口；这里只测 Schema，不需要真实请求（也就不会拖进 store/sessionStorage） */
vi.mock('#/api/cascade/org', () => ({
  getCascadeOrgTree: async () => [],
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));

/**
 * cascade/org/data.ts —— 组织表单与操作列。
 *
 * R1 之后组织是本地资产，没有平台维度：新增时挂父节点、可顺手选推送平台；
 * 编辑只改名称/排序，改层级只能走「移动到…」。发布范围与停用都走独立入口。
 */
describe('cascade/org/data', () => {
  describe('useOrgFormSchema', () => {
    it('新增表单含 orgName/sortOrder/enabled/platformIds', () => {
      const fields = useOrgFormSchema().map((s) => s.fieldName);
      expect(fields).toContain('orgName');
      expect(fields).toContain('sortOrder');
      expect(fields).toContain('enabled');
      expect(fields).toContain('platformIds');
    });

    it('编辑模式不给启停与发布范围（启停会动整棵子树，发布范围走独立抽屉）', () => {
      const fields = useOrgFormSchema(true).map((s) => s.fieldName);
      expect(fields).toContain('orgName');
      expect(fields).not.toContain('enabled');
      expect(fields).not.toContain('platformIds');
    });

    it('任何模式都没有父组织字段（改父节点只走移动接口）', () => {
      expect(useOrgFormSchema().map((s) => s.fieldName)).not.toContain(
        'parentId',
      );
      expect(useOrgFormSchema(true).map((s) => s.fieldName)).not.toContain(
        'parentId',
      );
    });

    it('没有平台字段（组织不再属于某个平台）', () => {
      expect(useOrgFormSchema().map((s) => s.fieldName)).not.toContain(
        'platformId',
      );
    });

    it('新增与编辑都带七段配置（新建时就能按规则出码，不必建完再重算）', () => {
      const codeFields = [
        'codeRuleEnabled',
        ...ORG_CODE_SEGMENTS.map((s) => s.field),
        'codeStartSeq',
      ];
      for (const fields of [
        useOrgFormSchema().map((s) => s.fieldName),
        useOrgFormSchema(true).map((s) => s.fieldName),
      ]) {
        for (const field of codeFields) {
          expect(fields).toContain(field);
        }
      }
    });

    it('整行字段在 md 以上也占两列（vben 的 formItemClass 只拼接不合并，单写 col-span-2 会被 md:col-span-1 覆盖）', () => {
      const create = useOrgFormSchema();
      for (const field of ['orgName', 'platformIds', 'codeRuleEnabled']) {
        expect(
          create.find((s) => s.fieldName === field)?.formItemClass,
        ).toContain('md:col-span-2');
      }
      const edit = useOrgFormSchema(true);
      for (const field of ['orgName', 'codeRuleEnabled']) {
        expect(
          edit.find((s) => s.fieldName === field)?.formItemClass,
        ).toContain('md:col-span-2');
      }
    });

    it('六段随 codeRuleEnabled 联动显隐（关闭时不展示无效配置）', () => {
      const schema = useOrgFormSchema(true);
      for (const { field } of ORG_CODE_SEGMENTS) {
        const item = schema.find((s) => s.fieldName === field);
        expect(item?.dependencies?.triggerFields).toContain('codeRuleEnabled');
        expect(
          (item?.dependencies as any)?.show({ codeRuleEnabled: true }),
        ).toBe(true);
        expect(
          (item?.dependencies as any)?.show({ codeRuleEnabled: false }),
        ).toBe(false);
      }
    });
  });

  describe('collectOrgCodeRule', () => {
    const full = {
      codeCity: '01',
      codeCounty: '0101',
      codeIndustry: '03',
      codeNetwork: '0',
      codeProvince: '11',
      codeRuleEnabled: true,
      codeStartSeq: 1000,
      codeType: '215',
    };

    it('开关关闭时整组不提交（后端 null = 不改，不能把已有段位清空）', () => {
      const { error, payload } = collectOrgCodeRule({
        ...full,
        codeRuleEnabled: false,
      });
      expect(error).toBeUndefined();
      expect(payload).toEqual({ codeRuleEnabled: 0 });
      expect(payload.codeProvince).toBeUndefined();
    });

    it('开关打开时提交七段与号段下界', () => {
      const { payload } = collectOrgCodeRule(full);
      expect(payload).toEqual({
        codeCity: '01',
        codeCounty: '0101',
        codeIndustry: '03',
        codeNetwork: '0',
        codeProvince: '11',
        codeRuleEnabled: 1,
        codeStartSeq: 1000,
        codeType: '215',
      });
    });

    it('空段提交空串（后端 trimToNull 后回落父/全局），不影响其余段位', () => {
      const { error, payload } = collectOrgCodeRule({
        ...full,
        codeCounty: '  ',
      });
      expect(error).toBeUndefined();
      expect(payload.codeCounty).toBe('');
      expect(payload.codeProvince).toBe('11');
    });

    it('位宽不符直接拒绝（后端 join() 抛错只能变成 500）', () => {
      expect(
        collectOrgCodeRule({ ...full, codeProvince: '1' }).error,
      ).toBeTruthy();
      expect(
        collectOrgCodeRule({ ...full, codeCounty: '01011' }).error,
      ).toBeTruthy();
      expect(collectOrgCodeRule({ ...full, codeType: '21x' }).error).toBeTruthy();
    });

    it('号段起点留空或 0 都落成 0（不设下界），不能是 null', () => {
      expect(
        collectOrgCodeRule({ ...full, codeStartSeq: undefined }).payload
          .codeStartSeq,
      ).toBe(0);
      expect(
        collectOrgCodeRule({ ...full, codeStartSeq: null }).payload
          .codeStartSeq,
      ).toBe(0);
    });
  });

  describe('useOrgColumns', () => {
    const build = () =>
      useOrgColumns({
        beforeChangeEnabled: vi.fn(),
        isDeleteBlocked: () => false,
        onActionClick: vi.fn(),
      }) ?? [];

    it('操作列含 append/edit/publish/move/delete，且 move 排在 delete 之前', () => {
      const cols = build();
      const opCol = cols.find((c: any) => c.field === 'operation');
      const codes = ((opCol as any).cellRender?.options ?? []).map(
        (o: any) => o.code,
      );
      expect(codes).toContain('publish');
      expect(codes).toContain('move');
      expect(codes.indexOf('move')).toBeLessThan(codes.indexOf('delete'));
    });

    it('操作列含 reapplyCode（重算编码走独立弹窗，不能一键执行），且排在 delete 之前', () => {
      const cols = build();
      const opCol = cols.find((c: any) => c.field === 'operation');
      const codes = ((opCol as any).cellRender?.options ?? []).map(
        (o: any) => o.code,
      );
      expect(codes).toContain('reapplyCode');
      expect(codes.indexOf('reapplyCode')).toBeLessThan(
        codes.indexOf('delete'),
      );
    });

    it('编码列用 orgCode（本地组织编码，不再叫 cascadeOrgId）', () => {
      const cols = build();
      const col = cols.find((c: any) => c.field === 'orgCode');
      expect((col as any)?.slots?.default).toBe('orgCode');
    });

    it('含已推送平台列（反查入口）', () => {
      const cols = build();
      const col = cols.find((c: any) => c.field === 'publishedPlatformIds');
      expect((col as any)?.slots?.default).toBe('published');
    });
  });
});
