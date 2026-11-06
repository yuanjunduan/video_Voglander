<script lang="ts" setup>
import type { CascadeOrgApi } from '#/api/cascade/org';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, message, Tag } from 'ant-design-vue';

import {
  disableCascadeOrg,
  disableCascadeOrgsBatch,
  previewCascadeOrgDisable,
} from '#/api/cascade/org';
import { $t } from '#/locales';

/**
 * 停用（原「取消同步」）确认弹窗，单个 / 批量共用。
 *
 * 为什么不能只用 Modal.confirm：停用会把整棵子树从**每个**已发布平台的目录里剪掉，
 * 而每个平台的通道处置策略（CASCADE 删通道 / REROUTE 改挂）是分开配的；
 * 上级离线或未订阅目录时 DEL 发出去就丢了、本地却已记为停用。
 * 用户必须在动手前看到「动多少节点」和「逐平台报文能不能送达」。
 */

interface RevokeModalData {
  /** 待停用的组织主键；批量时多条 */
  ids: number[];
  /** 单个模式下展示用的组织名 */
  orgName?: string;
}

const emit = defineEmits(['success']);

const data = ref<RevokeModalData>({ ids: [] });
const preview = ref<CascadeOrgApi.RevokePreviewVO>();
const loading = ref(false);

const isBatch = computed(() => data.value.ids.length > 1);
const orgId = computed(() => data.value.ids[0]);

const title = computed(() =>
  isBatch.value
    ? $t('cascade.org.revoke.titleBatch')
    : $t('cascade.org.revoke.title'),
);

/** 投递风险：报文发不出去，本地却已经记成停用了，两边永久错位 */
const riskWarnings = computed(() => {
  const warnings = preview.value?.warnings ?? [];
  return warnings.length > 0 ? warnings : [];
});

async function loadPreview() {
  if (isBatch.value || !orgId.value) {
    preview.value = undefined;
    return;
  }
  loading.value = true;
  try {
    preview.value = await previewCascadeOrgDisable(orgId.value);
  } catch (error) {
    console.error('停用影响面预览失败:', error);
    preview.value = undefined;
  } finally {
    loading.value = false;
  }
}

function deliveryTag(delivery: CascadeOrgApi.DeliveryVO) {
  return delivery.deliverable
    ? { color: 'success', text: $t('cascade.org.revoke.deliverable') }
    : { color: 'error', text: $t('cascade.org.revoke.undeliverable') };
}

function policyText(policy?: string) {
  return policy === 'REROUTE'
    ? $t('cascade.org.publish.policyReroute')
    : $t('cascade.org.publish.policyCascade');
}

async function onConfirm() {
  modalApi.lock();
  try {
    if (isBatch.value) {
      const result = await disableCascadeOrgsBatch(data.value.ids);
      reportBatch(result);
    } else if (orgId.value) {
      await disableCascadeOrg(orgId.value);
      message.success($t('cascade.org.revoke.success'));
    }
    modalApi.close();
    emit('success');
  } catch (error: any) {
    message.error(error?.message || $t('cascade.org.revoke.failed'));
  } finally {
    modalApi.lock(false);
  }
}

/** 批量结果是逐条的：成功的已经生效，失败的要说清是谁、为什么 */
function reportBatch(result: CascadeOrgApi.RevokeBatchResp) {
  if ((result.failed ?? 0) === 0) {
    message.success(
      $t('cascade.org.revoke.batchSuccess', [String(result.succeeded ?? 0)]),
    );
    return;
  }
  const detail = (result.items ?? [])
    .filter((item) => item.ok === false)
    .map((item) => `${item.orgName || item.id}: ${item.message || '-'}`)
    .join('；');
  message.warning(
    $t('cascade.org.revoke.partialFail', [
      String(result.failed),
      String(result.succeeded ?? 0),
      detail,
    ]),
    8,
  );
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    await onConfirm();
  },
  onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    data.value = modalApi.getData<RevokeModalData>() ?? { ids: [] };
    preview.value = undefined;
    void loadPreview();
  },
});
</script>

<template>
  <Modal :title="title" class="w-[680px]">
    <div class="flex flex-col gap-3">
      <!-- 投递风险：报文发不出去，本地却已经记成停用了 -->
      <Alert
        v-if="riskWarnings.length > 0"
        show-icon
        type="error"
        :message="$t('cascade.org.revoke.deliverRisk')"
      >
        <template #description>
          <ul class="m-0 list-disc pl-4">
            <li v-for="warning in riskWarnings" :key="warning">
              {{ warning }}
            </li>
          </ul>
        </template>
      </Alert>

      <!-- 单个模式：数量 + 节点清单 + 逐平台投递条件 -->
      <template v-if="!isBatch">
        <div v-if="loading" class="text-muted-foreground text-sm">
          {{ $t('cascade.org.revoke.loading') }}
        </div>
        <template v-else-if="preview">
          <div class="flex gap-6">
            <div>
              <div class="text-muted-foreground text-xs">
                {{ $t('cascade.org.revoke.orgCount') }}
              </div>
              <div class="text-xl font-semibold">{{ preview.orgCount }}</div>
            </div>
            <div>
              <div class="text-muted-foreground text-xs">
                {{ $t('cascade.org.revoke.channelCount') }}
              </div>
              <div class="text-xl font-semibold">{{ preview.channelCount }}</div>
            </div>
            <div>
              <div class="text-muted-foreground text-xs">
                {{ $t('cascade.org.revoke.deleteCount') }}
              </div>
              <div class="text-xl font-semibold">{{ preview.deleteCount }}</div>
            </div>
          </div>

          <div>
            <div class="mb-1 text-sm font-medium">
              {{ $t('cascade.org.revoke.deliveries') }}
            </div>
            <div class="revoke-nodes flex flex-col gap-2">
              <div
                v-for="delivery in preview.deliveries"
                :key="delivery.platformId"
                class="flex flex-wrap items-center gap-2 text-xs"
              >
                <span class="font-medium">{{ delivery.platformId }}</span>
                <Tag :bordered="false">{{ policyText(delivery.revokePolicy) }}</Tag>
                <Tag :color="delivery.platformOnline ? 'success' : 'error'">
                  {{
                    delivery.platformOnline
                      ? $t('cascade.org.revoke.online')
                      : $t('cascade.org.revoke.offline')
                  }}
                </Tag>
                <Tag :color="delivery.catalogSubscribed ? 'success' : 'error'">
                  {{
                    delivery.catalogSubscribed
                      ? $t('cascade.org.revoke.subscribed')
                      : $t('cascade.org.revoke.unsubscribed')
                  }}
                </Tag>
                <Tag :color="deliveryTag(delivery).color">
                  {{ deliveryTag(delivery).text }}
                </Tag>
              </div>
            </div>
            <div class="text-muted-foreground mt-1 text-xs">
              {{ $t('cascade.org.revoke.policyHint') }}
            </div>
          </div>

          <div>
            <div class="mb-1 text-sm font-medium">
              {{ $t('cascade.org.revoke.nodes') }}
            </div>
            <div class="revoke-nodes">
              <template
                v-if="preview.orgs.length === 0 && preview.channels.length === 0"
              >
                <span class="text-muted-foreground text-sm">
                  {{ $t('cascade.org.revoke.empty') }}
                </span>
              </template>
              <Tag v-for="node in preview.orgs" :key="`org-${node.cascadeId}`">
                {{ node.name }} · {{ node.cascadeId }}
              </Tag>
              <Tag
                v-for="node in preview.channels"
                :key="`ch-${node.cascadeId}`"
                color="blue"
              >
                {{ node.name }} · {{ node.cascadeId }}
              </Tag>
            </div>
          </div>
        </template>
        <Alert
          v-else
          show-icon
          type="warning"
          :message="$t('cascade.org.revoke.previewFailed')"
        />
      </template>

      <!-- 批量模式：预览只按单个组织算，这里只做总量提示 -->
      <template v-else>
        <Alert
          show-icon
          type="info"
          :message="$t('cascade.org.revoke.batchTip', [String(data.ids.length)])"
        />
      </template>

      <div class="text-muted-foreground text-xs">
        {{ $t('cascade.org.revoke.dataKept') }}
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.revoke-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 180px;
  padding: 8px;
  overflow-y: auto;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}
</style>
