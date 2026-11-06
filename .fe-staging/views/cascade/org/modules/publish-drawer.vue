<script lang="ts" setup>
import type { CascadeOrgApi } from '#/api/cascade/org';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { Button, Checkbox, Input, message, Radio, Tag } from 'ant-design-vue';

import {
  configureCascadeOrgPublish,
  getCascadeOrgPublish,
} from '#/api/cascade/org';
import { getCascadePlatformPage } from '#/api/cascade/platform';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

/**
 * 「已推送平台」反查抽屉：这个组织推给了哪些上级，各平台的编码/名称/通道处置策略。
 *
 * 数据来源是发布层（tb_cascade_publish，object_type=ORG）：
 * - `GET  /cascade/org/{id}/publish` 返回该组织的全部发布行，**含已停用的**，
 *   页面要能区分「配了但没开」与「根本没配」；
 * - `PUT  /cascade/org/{id}/publish` 是范围配置：未勾选的平台置停用，
 *   **不删行**，编码与名称覆盖都保留，重新勾选时不用重填。
 */

interface PublishRow {
  platformId: string;
  /** 平台名称；空串=未命名，展示时回落 platformId */
  platformName: string;
  platformDisabled: boolean;
  /** 已有发布行（含停用） */
  configured: boolean;
  checked: boolean;
  publishCode: string;
  publishName: string;
  revokePolicy: CascadeOrgApi.RevokePolicy;
}

const emit = defineEmits(['success']);

const org = ref<CascadeOrgApi.CascadeOrgVO>();
const rows = ref<PublishRow[]>([]);
const loading = ref(false);
const saving = ref(false);

const title = computed(() =>
  $t('cascade.org.publish.title', [org.value?.orgName ?? '']),
);

async function load() {
  const current = org.value;
  if (!current?.id) {
    return;
  }
  loading.value = true;
  try {
    const [platforms, publishes] = await Promise.all([
      getCascadePlatformPage({ page: 1, size: 1000 }),
      getCascadeOrgPublish(current.id),
    ]);
    const publishByPlatform = new Map<string, CascadeOrgApi.OrgPublishVO>();
    for (const item of publishes ?? []) {
      if (item.platformId) {
        publishByPlatform.set(item.platformId, item);
      }
    }
    const list: PublishRow[] = [];
    const seen = new Set<string>();
    for (const platform of platforms.items ?? []) {
      const platformId = platform.platformId ?? '';
      if (!platformId) {
        continue;
      }
      seen.add(platformId);
      const publish = publishByPlatform.get(platformId);
      list.push({
        checked: publish?.enabled === 1,
        configured: Boolean(publish),
        platformDisabled: platform.enabled === 0,
        platformId,
        platformName: platform.platformName ?? '',
        publishCode: publish?.publishCode ?? '',
        publishName: publish?.publishName ?? '',
        revokePolicy: normalizePolicy(publish?.revokePolicy),
      });
    }
    /* 平台已被删掉、发布行却还在：照样列出来，否则这条配置永远没有入口可清 */
    for (const [platformId, publish] of publishByPlatform) {
      if (seen.has(platformId)) {
        continue;
      }
      list.push({
        checked: publish.enabled === 1,
        configured: true,
        platformDisabled: true,
        platformId,
        /* 平台记录已删，名称无从得知：回落显示 platformId */
        platformName: '',
        publishCode: publish.publishCode ?? '',
        publishName: publish.publishName ?? '',
        revokePolicy: normalizePolicy(publish.revokePolicy),
      });
    }
    rows.value = list;
  } catch (error) {
    console.error('加载组织发布配置失败:', error);
  } finally {
    loading.value = false;
  }
}

function normalizePolicy(policy?: string): CascadeOrgApi.RevokePolicy {
  return policy === 'REROUTE' ? 'REROUTE' : 'CASCADE';
}

async function submit() {
  const current = org.value;
  if (!current?.id) {
    return;
  }
  const checked = rows.value.filter((row) => row.checked);
  const publishCodeByPlatform: Record<string, string> = {};
  const publishNameByPlatform: Record<string, string> = {};
  const revokePolicyByPlatform: Record<string, CascadeOrgApi.RevokePolicy> = {};
  for (const row of checked) {
    if (row.publishCode.trim()) {
      publishCodeByPlatform[row.platformId] = row.publishCode.trim();
    }
    if (row.publishName.trim()) {
      publishNameByPlatform[row.platformId] = row.publishName.trim();
    }
    revokePolicyByPlatform[row.platformId] = row.revokePolicy;
  }

  saving.value = true;
  try {
    await configureCascadeOrgPublish(current.id, {
      platformIds: checked.map((row) => row.platformId),
      publishCodeByPlatform,
      publishNameByPlatform,
      revokePolicyByPlatform,
    });
    message.success($t('cascade.org.publish.success'));
    drawerApi.close();
    emit('success');
  } catch (error: any) {
    message.error(error?.message || $t('cascade.org.publish.failed'));
  } finally {
    saving.value = false;
  }
}

const { syncWidth } = useDrawerResize('vl-cascade-org-publish');

const [Drawer, drawerApi] = useVbenDrawer({
  // 默认半屏；打开后由 useDrawerResize 接管为可拖拽的内联宽度
  class: 'vl-cascade-org-publish w-[50vw]',
  footer: false,
  onOpenChange(isOpen: boolean) {
    syncWidth();
    if (!isOpen) {
      return;
    }
    org.value = drawerApi.getData<CascadeOrgApi.CascadeOrgVO>();
    rows.value = [];
    void load();
  },
});
</script>

<template>
  <Drawer :title="title">
    <div class="flex flex-col gap-4 px-4 pb-4">
      <div
        class="flex flex-col gap-1 rounded-md border border-dashed px-3 py-2 text-xs"
      >
        <div class="flex gap-2">
          <span class="text-muted-foreground shrink-0">
            {{ $t('cascade.org.field.orgCode') }}
          </span>
          <span class="truncate font-mono">{{ org?.orgCode }}</span>
        </div>
        <div class="text-muted-foreground">
          {{ $t('cascade.org.publish.tip') }}
        </div>
      </div>

      <div v-if="loading" class="text-muted-foreground py-6 text-center text-sm">
        {{ $t('cascade.org.publish.loading') }}
      </div>

      <template v-else>
        <div v-for="row in rows" :key="row.platformId" class="publish-row">
          <div class="flex items-center justify-between gap-2">
            <Checkbox v-model:checked="row.checked">
              <span class="font-medium">{{
                row.platformName || row.platformId
              }}</span>
              <span v-if="row.platformName" class="text-muted-foreground text-xs">
                {{ row.platformId }}
              </span>
            </Checkbox>
            <div class="flex items-center gap-2">
              <Tag v-if="row.configured && !row.checked" :bordered="false">
                {{ $t('cascade.org.publish.configuredStopped') }}
              </Tag>
              <Tag v-else-if="row.checked" color="success">
                {{ $t('cascade.org.publish.publishing') }}
              </Tag>
              <Tag v-else :bordered="false">
                {{ $t('cascade.org.publish.unconfigured') }}
              </Tag>
              <Tag v-if="row.platformDisabled" color="error">
                {{ $t('cascade.channel.push.platformDisabled') }}
              </Tag>
            </div>
          </div>

          <template v-if="row.checked">
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
              <div>
                <div class="publish-label">
                  {{ $t('cascade.org.field.publishCode') }}
                </div>
                <Input
                  v-model:value="row.publishCode"
                  :placeholder="org?.orgCode"
                />
              </div>
              <div>
                <div class="publish-label">
                  {{ $t('cascade.org.field.publishName') }}
                </div>
                <Input
                  v-model:value="row.publishName"
                  :placeholder="org?.orgName"
                />
              </div>
            </div>
            <div>
              <div class="publish-label">
                {{ $t('cascade.org.publish.revokePolicy') }}
              </div>
              <Radio.Group v-model:value="row.revokePolicy" size="small">
                <Radio value="CASCADE">
                  {{ $t('cascade.org.publish.policyCascade') }}
                </Radio>
                <Radio value="REROUTE">
                  {{ $t('cascade.org.publish.policyReroute') }}
                </Radio>
              </Radio.Group>
              <div class="text-muted-foreground mt-1 text-xs">
                {{ $t('cascade.org.publish.revokePolicyTip') }}
              </div>
            </div>
          </template>
        </div>
      </template>

      <div class="flex justify-end gap-2">
        <Button :disabled="saving" @click="drawerApi.close()">
          {{ $t('common.cancel') }}
        </Button>
        <Button type="primary" :loading="saving" @click="submit">
          {{ $t('cascade.common.save') }}
        </Button>
      </div>
    </div>
  </Drawer>
</template>

<style scoped>
.publish-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.publish-label {
  margin-bottom: 4px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
</style>
