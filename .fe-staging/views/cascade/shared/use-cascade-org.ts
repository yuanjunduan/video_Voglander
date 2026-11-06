import type { CascadeOrgApi } from '#/api/cascade/org';

import { ref } from 'vue';

import { getCascadeOrgTree } from '#/api/cascade/org';

/** TreeSelect 节点（antd TreeSelect 默认 fieldNames: label / value / children）。 */
export interface CascadeOrgTreeNode {
  label: string;
  /** 组织编码用字符串，本地通道按组织主键筛选时用数字 */
  value: number | string;
  disabled?: boolean;
  children?: CascadeOrgTreeNode[];
}

/** 组织编码 → 组织，用于列表列的名称反查（O(1)，不逐行请求）。 */
export type CascadeOrgIndex = Record<string, CascadeOrgApi.CascadeOrgVO>;

const EMPTY_TREE: CascadeOrgApi.CascadeOrgVO[] = [];
const EMPTY_INDEX: CascadeOrgIndex = {};

/**
 * 组织树全局只缓存一份。
 *
 * 组织是本地资产，全库只有一棵树（没有平台维度）；「某平台的组织树」是这棵树按
 * `publishedPlatformIds` 过滤后的视图，不需要按平台各存一份。
 * 组织量级小（百级以内），一次拉全比分页/懒加载更省往返。并发调用只发一次请求。
 */
const rawTree = ref<CascadeOrgApi.CascadeOrgVO[]>(EMPTY_TREE);
const flatIndex = ref<CascadeOrgIndex>(EMPTY_INDEX);
/** 组织主键 → 组织：本地通道只带 orgId（数字），要在页面上翻成名称与编码 */
const byId = ref<Record<number, CascadeOrgApi.CascadeOrgVO>>({});
let loaded = false;
let inflight: null | Promise<void> = null;

function walk(
  list: CascadeOrgApi.CascadeOrgVO[],
  sink: CascadeOrgIndex,
  idSink: Record<number, CascadeOrgApi.CascadeOrgVO>,
): void {
  for (const item of list) {
    if (item.orgCode) {
      sink[item.orgCode] = item;
    }
    if (item.id !== undefined) {
      idSink[item.id] = item;
    }
    if (item.children?.length) {
      walk(item.children, sink, idSink);
    }
  }
}

/**
 * 确保组织树已加载（并发去重，失败后允许重试）。
 *
 * @param force 组织增删改后强制重取；否则命中缓存即返回
 */
export async function ensureCascadeOrgTree(force = false): Promise<void> {
  if (loaded && !force) {
    return;
  }
  if (inflight) {
    return inflight;
  }
  const task = getCascadeOrgTree()
    .then((tree) => {
      const list = tree ?? [];
      const index: CascadeOrgIndex = {};
      const idMap: Record<number, CascadeOrgApi.CascadeOrgVO> = {};
      walk(list, index, idMap);
      rawTree.value = list;
      flatIndex.value = index;
      byId.value = idMap;
      loaded = true;
    })
    .catch((error) => {
      /* 拉不到组织树不该让页面整块白掉：列表照常渲染，只是名称回落成编码 */
      console.error('加载级联组织树失败:', error);
    })
    .finally(() => {
      inflight = null;
    });
  inflight = task;
  return task;
}

/**
 * 组织增删改后精确失效：下次 ensure 会重新拉取。
 *
 * 名称/排序/发布范围都可能变，没有按组织粒度失效的必要。
 */
export function invalidateCascadeOrgTree() {
  loaded = false;
  inflight = null;
  rawTree.value = EMPTY_TREE;
  flatIndex.value = EMPTY_INDEX;
  byId.value = {};
}

/**
 * 原始组织树（带 children / channelCount），供树表直接使用
 */
export function getCascadeOrgRawTree() {
  return rawTree.value;
}

/**
 * 组织编码索引（编码 → 组织）
 */
export function getCascadeOrgIndex(): CascadeOrgIndex {
  return flatIndex.value;
}

/**
 * 组织主键索引（id → 组织）
 */
export function getCascadeOrgIdIndex(): Record<number, CascadeOrgApi.CascadeOrgVO> {
  return byId.value;
}

/**
 * 按编码或主键查组织（两个标识在页面上混用：通道带编码，本地通道带 id）
 */
export function findCascadeOrg(
  orgCodeOrId?: null | number | string,
): CascadeOrgApi.CascadeOrgVO | undefined {
  if (orgCodeOrId === undefined || orgCodeOrId === null || orgCodeOrId === '') {
    return undefined;
  }
  if (typeof orgCodeOrId === 'number') {
    return byId.value[orgCodeOrId];
  }
  return flatIndex.value[orgCodeOrId];
}

/**
 * 组织名称：编码或主键 → 名称，查不到回落为标识本身（比空字符串更利于排查）
 */
export function cascadeOrgName(orgCodeOrId?: null | number | string) {
  const org = findCascadeOrg(orgCodeOrId);
  if (org?.orgName) {
    return org.orgName;
  }
  return orgCodeOrId === undefined || orgCodeOrId === null
    ? ''
    : String(orgCodeOrId);
}

/**
 * 该组织是否会出现在某平台的目录里。
 *
 * 判据是「本地可达（自身与祖先链都启用）」+「已发布到该平台」，与后端
 * `platformOrgView.catalogOrgs` 同口径；REROUTE 的组织不在目录里，不列入候选项。
 */
export function isCascadeOrgInPlatform(
  org: CascadeOrgApi.CascadeOrgVO,
  platformId?: string,
) {
  if (!platformId) {
    return true;
  }
  return (
    org.effective !== false &&
    (org.publishedPlatformIds ?? []).includes(platformId)
  );
}

interface TreeBuildOptions {
  /** 表单用途：本地不可达的组织置灰，避免提交后才收到「组织不会出现在上级目录里」 */
  disableStopped?: boolean;
  /** 只保留会在该平台目录里出现的组织（选父节点用） */
  platformId?: string;
  /** 排除的组织编码及其整棵子树（移动组织时不能挂到自己下面） */
  excludeOrgCode?: string;
  /**
   * 节点 value 取什么。
   *
   * 发布层（通道推送、组织父节点）收的是组织编码；本地通道的组织归属存的是
   * `tb_device_channel.org_id`（数字），筛选时必须以主键作 value，否则选完筛不出来。
   */
  valueAs?: 'code' | 'id';
}

/**
 * 原始树 → TreeSelect 节点
 *
 * `platformId` 给了就只保留该平台目录里会出现的组织：通道的父节点只能是它们，
 * 其余组织选了也只会得到一条挂不住的孤儿。
 */
export function buildCascadeOrgTreeNodes(
  list: CascadeOrgApi.CascadeOrgVO[] = rawTree.value,
  options: TreeBuildOptions = {},
): CascadeOrgTreeNode[] {
  const excludedPrefix = options.excludeOrgCode
    ? `${options.excludeOrgCode}/`
    : undefined;
  const nodes: CascadeOrgTreeNode[] = [];
  for (const item of list) {
    if (
      excludedPrefix &&
      (item.orgPath ?? '').includes(excludedPrefix)
    ) {
      /* orgPath 存的是编码链，包含「被排除组织 + /」即为其子孙 */
      continue;
    }
    if (item.orgCode === options.excludeOrgCode) {
      continue;
    }
    if (!isCascadeOrgInPlatform(item, options.platformId)) {
      continue;
    }
    const node: CascadeOrgTreeNode = {
      label: item.orgName || item.orgCode || '-',
      value:
        options.valueAs === 'id' && item.id !== undefined
          ? item.id
          : (item.orgCode ?? ''),
    };
    if (options.disableStopped === true && item.effective === false) {
      node.disabled = true;
    }
    if (item.children?.length) {
      const children = buildCascadeOrgTreeNodes(item.children, options);
      if (children.length > 0) {
        node.children = children;
      }
    }
    nodes.push(node);
  }
  return nodes;
}

/**
 * 层级：物化路径（`/A/B/C/`）去空段后的段数
 */
export function cascadeOrgLevel(orgPath?: string) {
  if (!orgPath) {
    return 0;
  }
  return orgPath.split('/').filter(Boolean).length;
}

/**
 * 面包屑：把物化路径的**编码链**翻成名称链（`/A/B/` → `总部 / 杭州`）
 */
export function cascadeOrgPathText(
  orgPath: string | undefined,
  separator = ' / ',
) {
  if (!orgPath) {
    return '';
  }
  const index = flatIndex.value;
  return orgPath
    .split('/')
    .filter(Boolean)
    .map((code) => index[code]?.orgName ?? code)
    .join(separator);
}
