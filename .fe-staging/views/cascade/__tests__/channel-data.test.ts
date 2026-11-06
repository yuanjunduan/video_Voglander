import { describe, expect, it, vi } from 'vitest';

import {
  useChannelColumns,
  useChannelGridFormSchema,
} from '../channel/data';

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
 * cascade/channel/data.ts —— 本地通道列表的筛选与列。
 *
 * R5 之后列表主键是**本地通道**：平台维度已经下沉到每行的推送抽屉里，
 * 因此筛选不再有 platformId，列里出现的是「已推送平台」聚合。
 */
describe('cascade/channel/data', () => {
  describe('useChannelGridFormSchema', () => {
    it('筛选作用在本地通道字段上', () => {
      const fields = useChannelGridFormSchema().map((s) => s.fieldName);
      for (const f of [
        'deviceId',
        'channelId',
        'name',
        'status',
        'enabled',
        'orgId',
      ]) {
        expect(fields).toContain(f);
      }
    });

    it('不再按平台筛选（平台维度已下沉到推送抽屉）', () => {
      const fields = useChannelGridFormSchema().map((s) => s.fieldName);
      expect(fields).not.toContain('platformId');
    });

    it('组织筛选按主键取值（deviceChannel 收的是数字 orgId）', () => {
      const field = useChannelGridFormSchema().find((s) => s.fieldName === 'orgId');
      expect(field?.component).toBe('TreeSelect');
    });
  });

  describe('useChannelColumns', () => {
    it('操作列含 push/assign/delete 三个入口（delete 单独受删除权限码控制）', () => {
      const cols = useChannelColumns({ beforeChangeEnabled: vi.fn(), onActionClick: vi.fn() }) ?? [];
      const opCol = cols.find((c: any) => c.field === 'operation');
      const codes = ((opCol as any).cellRender?.options ?? []).map(
        (o: any) => o.code,
      );
      expect(codes).toContain('push');
      expect(codes).toContain('assign');
      expect(codes).toContain('delete');
    });

    it('含已推送平台列（逐平台配置的入口提示）', () => {
      const cols = useChannelColumns({ beforeChangeEnabled: vi.fn(), onActionClick: vi.fn() }) ?? [];
      const col = cols.find((c: any) => c.field === 'publishedPlatforms');
      expect((col as any)?.slots?.default).toBe('published');
    });

    it('本地启停用 CellSwitch（改的是 tb_device_channel.enabled）', () => {
      const cols = useChannelColumns({ beforeChangeEnabled: vi.fn(), onActionClick: vi.fn() }) ?? [];
      const statusCol = cols.find((c: any) => c.field === 'enabled');
      expect((statusCol as any)?.cellRender?.name).toBe('CellSwitch');
    });

    it('不再有安装地址等目录元数据列（这些是发布层字段，不在本地台账上）', () => {
      const cols = useChannelColumns({ beforeChangeEnabled: vi.fn(), onActionClick: vi.fn() }) ?? [];
      expect(cols.some((c: any) => c.field === 'address')).toBe(false);
    });
  });
});
