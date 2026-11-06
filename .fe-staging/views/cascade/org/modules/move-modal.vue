<script lang="ts" setup>
import type { CascadeOrgApi } from '#/api/cascade/org';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, message, TreeSelect } from 'ant-design-vue';

import { moveCascadeOrg } from '#/api/cascade/org';
import { $t } from '#/locales';

import {
  cascadeOrgPathText,
  ensureCascadeOrgTree,
  getCascadeOrgRawTree,
  invalidateCascadeOrgTree,
} from '../../shared/use-cascade-org';

/**
 * 移动组织（改父节点）确认弹窗。
 *
 * 为什么不能做成编辑表单里的一个下拉：上级目录没有「移动节点」语义，快照对已存在的
 * 节点只做 upsert、不迁移父组织（海康实测），所以后端会先按旧父逐条 DEL 再按新父重推。
 * 对上级而言这是一次重建，跟改个名字的代价完全不同——必须在动手前把
 * 「跟着走多少组织、多少通道」和「上级会重建」讲清楚。
 */

interface MoveModalData {
  /** 树表里的行，children / channelCount 已填充，用来算影响面 */
  org?: CascadeOrgApi.CascadeOrgVO;
}

interface TargetNode {
  label: string;
  value: number;
  disabled?: boolean;
  children?: TargetNode[];
}

const emit = defineEmits(['success']);

const data = ref<MoveModalData>();
const targetParentId = ref<number>();
const treeData = ref<TargetNode[]>([]);
/** 候选位置的名称链（id → 总部 / 杭州），选中后直接展示，不必再回查 */
const pathById = ref<Map<number, string>>(new Map());
const loading = ref(false);

const org = computed(() => data.value?.org);

/** 跟随移动的组织数与直属通道数：用户点确认前必须看到这个量级 */
const impact = computed(() => {
  let orgs = 0;
  let channels = 0;
  const walk = (node?: CascadeOrgApi.CascadeOrgVO) => {
    if (!node) {
      return;
    }
    orgs += 1;
    channels += node.channelCount ?? 0;
    for (const child of node.children ?? []) {
      walk(child);
    }
  };
  walk(org.value);
  return { channels, orgs };
});

const currentText = computed(
  () => cascadeOrgPathText(org.value?.orgPath) || '-',
);

/** 未选目标 = 移到平台根节点，这在后端是合法语义，不是「没填」 */
const targetText = computed(() => {
  const id = targetParentId.value;
  if (!id) {
    return $t('cascade.channel.tip.rootNode');
  }
  return pathById.value.get(id) ?? '-';
});

/** 自己与全部下级都要置灰：移进去会让整棵子树从树上脱落 */
function collectDisabledIds(
  node: CascadeOrgApi.CascadeOrgVO,
  sink: Set<number>,
) {
  if (node.id) {
    sink.add(node.id);
  }
  for (const child of node.children ?? []) {
    collectDisabledIds(child, sink);
  }
}

function buildTargetNodes(
  list: CascadeOrgApi.CascadeOrgVO[],
  disabledIds: Set<number>,
  prefix: string[],
  sink: Map<number, string>,
): TargetNode[] {
  return list.map((item) => {
    const label = item.orgName || item.orgCode || '-';
    const chain = [...prefix, label];
    const node: TargetNode = {
      label,
      value: item.id as number,
      disabled: item.id ? disabledIds.has(item.id) : undefined,
    };
    if (item.id) {
      sink.set(item.id, chain.join(' / '));
    }
    if (item.children?.length) {
      node.children = buildTargetNodes(item.children, disabledIds, chain, sink);
    }
    return node;
  });
}

async function prepare() {
  const current = modalApi.getData<MoveModalData>();
  data.value = current;
  targetParentId.value = undefined;
  treeData.value = [];
  pathById.value = new Map();
  loading.value = true;
  try {
    await ensureCascadeOrgTree();
    const disabledIds = new Set<number>();
    if (current?.org) {
      collectDisabledIds(current.org, disabledIds);
    }
    const labels = new Map<number, string>();
    treeData.value = buildTargetNodes(
      getCascadeOrgRawTree(),
      disabledIds,
      [],
      labels,
    );
    pathById.value = labels;
  } finally {
    loading.value = false;
  }
}

async function onConfirm() {
  const current = data.value;
  if (!current?.org?.id) {
    return;
  }
  /* 没换父节点就什么都不用发：后端也会拒绝，但在这里拦掉能少一次误操作 */
  if ((current.org.parentId ?? undefined) === targetParentId.value) {
    message.warning($t('cascade.org.move.sameParent'));
    return;
  }
  modalApi.lock();
  try {
    await moveCascadeOrg(current.org.id, targetParentId.value);
    message.success($t('cascade.org.move.success'));
    invalidateCascadeOrgTree();
    modalApi.close();
    emit('success');
  } catch (error: any) {
    message.error(error?.message || $t('cascade.org.move.failed'));
  } finally {
    modalApi.lock(false);
  }
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    await onConfirm();
  },
  onOpenChange(isOpen) {
    if (isOpen) {
      void prepare();
    }
  },
});
</script>

<template>
  <Modal class="w-[640px]" :title="$t('cascade.org.move.title')">
    <div class="flex flex-col gap-3">
      <Alert
        show-icon
        type="warning"
        :message="$t('cascade.org.move.warning')"
      />

      <div class="flex gap-6">
        <div>
          <div class="text-muted-foreground text-xs">
            {{ $t('cascade.org.move.subtreeOrgs') }}
          </div>
          <div class="text-xl font-semibold">{{ impact.orgs }}</div>
        </div>
        <div>
          <div class="text-muted-foreground text-xs">
            {{ $t('cascade.org.move.subtreeChannels') }}
          </div>
          <div class="text-xl font-semibold">{{ impact.channels }}</div>
        </div>
      </div>

      <div>
        <div class="mb-1 text-sm font-medium">
          {{ $t('cascade.org.move.current') }}
        </div>
        <div class="move-path">{{ currentText }}</div>
      </div>

      <div>
        <div class="mb-1 text-sm font-medium">
          {{ $t('cascade.org.move.target') }}
        </div>
        <TreeSelect
          v-model:value="targetParentId"
          allow-clear
          class="w-full"
          :loading="loading"
          :placeholder="$t('cascade.org.move.placeholder')"
          show-search
          :tree-data="treeData"
          tree-default-expand-all
          tree-node-filter-prop="label"
        />
        <div class="text-muted-foreground mt-1 text-xs">
          {{ $t('cascade.org.move.hint') }}
        </div>
      </div>

      <div>
        <div class="mb-1 text-sm font-medium">
          {{ $t('cascade.org.move.targetTitle') }}
        </div>
        <div class="move-path">{{ targetText }}</div>
      </div>

      <div class="text-muted-foreground text-xs">
        {{ $t('cascade.org.move.dataKept') }}
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.move-path {
  padding: 6px 10px;
  font-size: 13px;
  word-break: break-all;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}
</style>
