import { describe, expect, it, vi } from 'vitest';

import { useClientColumns, useClientFormSchema } from '../client/data';

// $t 桩：回显 key，便于断言 i18n 命名空间
vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

// useAccess 桩：放行全部权限（操作列 show 回调依赖）
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));

/**
 * cascade/client/data.ts —— 本端客户端的表单/表格 Schema。
 *
 * 钉住两条容易回退的约束：
 * 1) 端口不在这张表里（一个 SIP UA 只绑一个 socket，端口恒为 sip.client.port）；
 * 2) 编码创建后不可改（换编码等于换平台身份，上级侧已建对象会全部认不出来）。
 */
describe('cascade/client/data', () => {
  describe('useClientFormSchema —— 新增/编辑表单', () => {
    it('包含编码/名称/对外通告IP/启用/默认/备注，且不含端口字段', () => {
      const fields = useClientFormSchema(false).map((s) => s.fieldName);
      for (const f of [
        'clientId',
        'clientName',
        'clientIp',
        'enabled',
        'isDefault',
        'remark',
      ]) {
        expect(fields).toContain(f);
      }
      // 端口是事实不是选项：出现在表单里就等于允许配出一个绑不上的端口
      for (const bad of ['clientPort', 'localPort', 'port']) {
        expect(fields).not.toContain(bad);
      }
    });

    it('编辑模式下编码只读', () => {
      const schema = useClientFormSchema(true);
      const field = schema.find((s) => s.fieldName === 'clientId');
      expect((field?.componentProps as any)?.disabled).toBe(true);
    });

    it('编码必须是 20 位数字', () => {
      const schema = useClientFormSchema(false);
      const rules = schema.find((s) => s.fieldName === 'clientId')?.rules as any;
      expect(rules).toBeTruthy();
      expect(rules.safeParse('34020000002000000002').success).toBe(true);
      expect(rules.safeParse('3402000000200000000').success).toBe(false);
      expect(rules.safeParse('3402000000200000000a').success).toBe(false);
    });
  });

  describe('useClientColumns —— 表格列', () => {
    it('名称列空值回落显示编码', () => {
      const cols = useClientColumns(vi.fn()) ?? [];
      const nameCol = cols.find((c: any) => c.field === 'clientName');
      const formatter = (nameCol as any).formatter;
      expect(formatter({ row: { clientId: '34020000002000000002' } })).toBe(
        '34020000002000000002',
      );
    });

    it('仍被平台引用时不给删除入口（后端也会拒绝，别让用户白点）', () => {
      const cols = useClientColumns(vi.fn()) ?? [];
      const opCol = cols.find((c: any) => c.field === 'operation');
      const del = ((opCol as any).cellRender?.options ?? []).find(
        (o: any) => o.code === 'delete',
      );
      expect(del.show({ platformRefCount: 1 })).toBe(false);
      expect(del.show({ platformRefCount: 0 })).toBe(true);
      expect(del.show({})).toBe(true);
    });

    it('已是默认项时不再出现「设为默认」', () => {
      const cols = useClientColumns(vi.fn()) ?? [];
      const opCol = cols.find((c: any) => c.field === 'operation');
      const setDefault = ((opCol as any).cellRender?.options ?? []).find(
        (o: any) => o.code === 'setDefault',
      );
      expect(setDefault.show({ isDefault: 1 })).toBe(false);
      expect(setDefault.show({ isDefault: 0 })).toBe(true);
    });
  });
});
