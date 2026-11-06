<script lang="ts" setup>
import type { CascadeOrgApi } from '#/api/cascade/org';

import { computed, onMounted, ref } from 'vue';

import { Tree } from 'ant-design-vue';

import { getCascadeOrgTree } from '#/api/cascade/org';
import { $t } from '#/locales';

/**
 * 左侧组织目录：按组织浏览设备/通道的入口。
 *
 * 与「在查询条件里选组织树」的区别：组织选一次，设备与通道两个 Tab 共用同一份筛选，
 * 不用来回切页再重选。选中态由父组件持有（便于写进 URL、刷新后保持）。
 *
 * 树里混着两个虚拟节点：「全部资产」（orgId=undefined）与「未归组织」（orgId=0）。
 * 后者是 SIP 自动注册设备的合法状态，不能藏起来——否则那些设备在所有组织下都看不到。
 */

interface OrgTreeNode {
  children?: OrgTreeNode[];
  /** a-tree 的选中键：`org:<id>` / `all` / `unassigned` */
  key: string;
  title: string;
}

const props = withDefaults(
  defineProps<{
    /** 是否包含下级组织 */
    includeChildren?: boolean;
    /** 选中组织主键：undefined=全部资产，0=未归组织 */
    orgId?: number;
  }>(),
  { includeChildren: true, orgId: undefined },
);

const emit = defineEmits<{
  (e: 'update:includeChildren', value: boolean): void;
  (e: 'update:orgId', value: number | undefined): void;
}>();

const ALL_KEY = 'all';
const UNASSIGNED_KEY = 'unassigned';
const ORG_PREFIX = 'org:';

const collapsed = ref(false);
const loading = ref(false);
const orgNodes = ref<OrgTreeNode[]>([]);
const expandedKeys = ref<string[]>([ALL_KEY]);

const selectedKeys = computed<string[]>(() => {
  if (props.orgId === undefined) {
    return [ALL_KEY];
  }
  if (props.orgId === 0) {
    return [UNASSIGNED_KEY];
  }
  return [`${ORG_PREFIX}${props.orgId}`];
});

const treeData = computed<OrgTreeNode[]>(() => [
  { key: ALL_KEY, title: $t('device.orgPanel.all') },
  ...orgNodes.value,
  { key: UNASSIGNED_KEY, title: $t('device.orgPanel.unassigned') },
]);

function toNodes(list: CascadeOrgApi.CascadeOrgVO[]): OrgTreeNode[] {
  return list.map((org) => ({
    key: `${ORG_PREFIX}${org.id}`,
    title: org.orgName || org.orgCode || `#${org.id}`,
    children: org.children?.length ? toNodes(org.children) : undefined,
  }));
}

function collectKeys(nodes: OrgTreeNode[]): string[] {
  const keys: string[] = [];
  for (const node of nodes) {
    keys.push(node.key);
    if (node.children) {
      keys.push(...collectKeys(node.children));
    }
  }
  return keys;
}

async function loadTree() {
  loading.value = true;
  try {
    orgNodes.value = toNodes((await getCascadeOrgTree()) ?? []);
    // 展开态是受控的：数据异步到位后再一次性铺开，否则默认展开对后加载的节点不生效
    expandedKeys.value = [ALL_KEY, ...collectKeys(orgNodes.value)];
  } finally {
    loading.value = false;
  }
}

function onSelect(keys: (number | string)[]) {
  const key = String(keys[0] ?? ALL_KEY);
  if (key === ALL_KEY) {
    emit('update:orgId', undefined);
    return;
  }
  if (key === UNASSIGNED_KEY) {
    emit('update:orgId', 0);
    return;
  }
  if (!key.startsWith(ORG_PREFIX)) {
    return;
  }
  const id = Number(key.slice(ORG_PREFIX.length));
  emit('update:orgId', Number.isFinite(id) ? id : undefined);
}

onMounted(loadTree);
</script>

<template>
  <aside class="org-panel" :class="{ 'is-collapsed': collapsed }">
    <header class="panel-head">
      <span v-if="!collapsed" class="panel-title">
        {{ $t('device.orgPanel.title') }}
      </span>
      <button
        class="panel-toggle"
        type="button"
        :title="
          collapsed ? $t('device.orgPanel.expand') : $t('device.orgPanel.collapse')
        "
        @click="collapsed = !collapsed"
      >
        {{ collapsed ? '›' : '‹' }}
      </button>
    </header>

    <div v-show="!collapsed" class="panel-body">
      <Tree
        v-model:expanded-keys="expandedKeys"
        :selected-keys="selectedKeys"
        :tree-data="treeData"
        block-node
        @select="onSelect"
      />
      <div v-if="loading" class="panel-hint">
        {{ $t('device.orgPanel.loading') }}
      </div>
    </div>

    <footer v-show="!collapsed" class="panel-foot">
      <span class="foot-label">{{ $t('device.orgPanel.scope') }}</span>
      <div class="scope-switch" role="group">
        <button
          type="button"
          :class="{ on: includeChildren }"
          @click="emit('update:includeChildren', true)"
        >
          {{ $t('device.orgPanel.withChildren') }}
        </button>
        <button
          type="button"
          :class="{ on: !includeChildren }"
          @click="emit('update:includeChildren', false)"
        >
          {{ $t('device.orgPanel.onlySelf') }}
        </button>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
/* 与设备页的 Fleet Console 共用同一套令牌：发丝边框 + 等宽标题 + 窄面板 */
.org-panel {
  --mono: 'JetBrains Mono', 'IBM Plex Mono', ui-monospace, 'SF Mono', monospace;
  --hair: hsl(var(--border));

  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: 228px;
  height: 100%;
  overflow: hidden;
  background: hsl(var(--card));
  border: 1px solid var(--hair);
  border-radius: calc(var(--radius) + 2px);
  transition:
    width 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    min-width 0.22s cubic-bezier(0.22, 1, 0.36, 1);
}

.org-panel.is-collapsed {
  width: 40px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 8px 10px 14px;
  border-bottom: 1px solid var(--hair);
}

.org-panel.is-collapsed .panel-head {
  justify-content: center;
  padding: 10px 0;
}

.panel-title {
  font-family: var(--mono);
  font-size: 11px;
  font-weight: 600;
  color: hsl(var(--muted-foreground));
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.panel-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  font-size: 15px;
  line-height: 1;
  color: hsl(var(--muted-foreground));
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  transition:
    color 0.16s,
    background 0.16s;
}

.panel-toggle:hover {
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 8%);
}

.panel-body {
  flex: 1;
  min-height: 0;
  padding: 6px 6px 2px;
  overflow: auto;
}

/* antd Tree 默认行高偏大，压到与表格同一密度 */
.panel-body :deep(.ant-tree) {
  font-size: 13px;
  background: transparent;
}

.panel-body :deep(.ant-tree-node-content-wrapper) {
  border-radius: 6px;
}

.panel-hint {
  padding: 6px 8px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.panel-foot {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px 12px;
  border-top: 1px solid var(--hair);
}

.foot-label {
  font-family: var(--mono);
  font-size: 10px;
  color: hsl(var(--muted-foreground));
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.scope-switch {
  display: flex;
  padding: 2px;
  background: hsl(var(--muted) / 60%);
  border-radius: 7px;
}

.scope-switch button {
  flex: 1;
  padding: 4px 0;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 5px;
  transition:
    color 0.16s,
    background 0.16s;
}

.scope-switch button.on {
  color: hsl(var(--foreground));
  background: hsl(var(--card));
  box-shadow: 0 1px 2px hsl(var(--foreground) / 12%);
}
</style>
