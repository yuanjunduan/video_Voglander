<script lang="ts" setup>
import type { CascadeOrgApi } from '#/api/cascade/org';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, message } from 'ant-design-vue';

import { reapplyCascadeOrgCode } from '#/api/cascade/org';
import { $t } from '#/locales';

import { findCascadeOrg } from '../../shared/use-cascade-org';

/**
 * 「重算组织编码」试算 + 确认弹窗。
 *
 * 为什么不直接 PUT：重算会同时改整棵子树的 orgCode、orgPath、发布行的 objectKey
 * 与通道的 parentKey，并逐平台推 DEL 让上级重建。代价与改名不是一个量级，
 * 所以打开即先跑一次 dryRun，把「哪几个组织从什么编码变成什么编码」摆出来再让人确认。
 *
 * 为什么这里有三种「不能执行」：规则没开（一切照旧）、有冲突（已整批拒绝）、
 * 无变更（本来就是对的）。三种情况的下一步动作完全不同，混成一句「失败」用户没法处理。
 */

interface CodeReapplyData {
  org: CascadeOrgApi.CascadeOrgVO;
}

const emit = defineEmits(['success']);

const data = ref<CodeReapplyData>();
const preview = ref<CascadeOrgApi.CodeReapplyResult>();
const loading = ref(false);

const orgName = computed(
  () => data.value?.org.orgName || data.value?.org.orgCode || '',
);

const conflicts = computed(() => preview.value?.conflicts ?? []);
const changes = computed(() => preview.value?.changes ?? []);

/** 唯一可以真正落库的情形：规则开着、无冲突、确实有变更 */
const executable = computed(
  () =>
    Boolean(preview.value?.ruleEnabled) &&
    conflicts.value.length === 0 &&
    changes.value.length > 0,
);

/** 编码还是旧值，此时组织树索引里查得到名字；查不到就回落成编码本身 */
function changeLabel(change: CascadeOrgApi.CodeReapplyChange) {
  return findCascadeOrg(change.objectKey)?.orgName || change.objectKey || '-';
}

function syncConfirmText() {
  modalApi.setState({
    confirmText: executable.value
      ? $t('cascade.org.code.confirm')
      : $t('cascade.org.code.close'),
  });
}

async function loadPreview() {
  const id = data.value?.org.id;
  if (!id) {
    return;
  }
  loading.value = true;
  try {
    preview.value = await reapplyCascadeOrgCode(id, true);
  } catch (error) {
    console.error('组织编码重算试算失败:', error);
    preview.value = undefined;
  } finally {
    loading.value = false;
    syncConfirmText();
  }
}

async function onConfirm() {
  const id = data.value?.org.id;
  if (!id || !executable.value) {
    /* 规则未启用 / 有冲突 / 无变更：这里只是关闭，绝不落库 */
    modalApi.close();
    return;
  }
  modalApi.lock();
  try {
    const result = await reapplyCascadeOrgCode(id, false);
    message.success(
      result.note ||
        $t('cascade.org.code.applied', [String(result.changes?.length ?? 0)]),
    );
    modalApi.close();
    emit('success');
  } catch (error: any) {
    message.error(error?.message || $t('cascade.org.code.failed'));
  } finally {
    modalApi.lock(false);
  }
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    await onConfirm();
  },
  onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    data.value = modalApi.getData<CodeReapplyData>();
    preview.value = undefined;
    modalApi.setState({ confirmText: $t('cascade.org.code.confirm') });
    void loadPreview();
  },
});
</script>

<template>
  <Modal :title="$t('cascade.org.code.title', [orgName])" class="w-[760px]">
    <div class="flex flex-col gap-3">
      <div v-if="loading" class="text-muted-foreground text-sm">
        {{ $t('cascade.org.code.loading') }}
      </div>

      <template v-else-if="preview">
        <Alert
          v-if="!preview.ruleEnabled"
          show-icon
          type="warning"
          :message="$t('cascade.org.code.disabledTitle')"
          :description="preview.note || $t('cascade.org.code.disabledTip')"
        />

        <Alert
          v-else-if="conflicts.length > 0"
          show-icon
          type="error"
          :message="$t('cascade.org.code.conflictTitle')"
        >
          <template #description>
            <div>{{ $t('cascade.org.code.conflictTip') }}</div>
            <ul class="m-0 mt-1 list-disc pl-4">
              <li v-for="conflict in conflicts" :key="conflict">
                {{ conflict }}
              </li>
            </ul>
          </template>
        </Alert>

        <Alert
          v-else-if="changes.length === 0"
          show-icon
          type="info"
          :message="preview.note || $t('cascade.org.code.noChange')"
        />

        <template v-else>
          <Alert
            show-icon
            type="warning"
            :message="$t('cascade.org.code.warning')"
          />

          <div>
            <div class="mb-1 text-sm font-medium">
              {{ $t('cascade.org.code.changes', [String(changes.length)]) }}
            </div>
            <div class="code-changes">
              <div
                v-for="(change, index) in changes"
                :key="`${change.objectKey}-${index}`"
                class="code-change-row"
              >
                <span class="code-change-name">{{ changeLabel(change) }}</span>
                <span class="code-before">{{ change.before }}</span>
                <span class="code-arrow">→</span>
                <span class="code-after">{{ change.after }}</span>
              </div>
            </div>
            <div class="text-muted-foreground mt-1 text-xs">
              {{ $t('cascade.org.code.channelKept') }}
            </div>
          </div>
        </template>
      </template>

      <Alert
        v-else
        show-icon
        type="warning"
        :message="$t('cascade.org.code.previewFailed')"
      />
    </div>
  </Modal>
</template>

<style scoped>
.code-changes {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 260px;
  padding: 8px;
  overflow-y: auto;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.code-change-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  font-family:
    'JetBrains Mono', 'IBM Plex Mono', ui-monospace, 'SF Mono', monospace;
  font-size: 12px;
}

.code-change-name {
  min-width: 120px;
  color: hsl(var(--foreground) / 80%);
}

.code-before {
  color: hsl(var(--muted-foreground));
  text-decoration: line-through;
}

.code-arrow {
  color: hsl(var(--muted-foreground) / 60%);
}

.code-after {
  font-weight: 600;
  color: hsl(var(--primary));
}
</style>
