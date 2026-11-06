<script lang="ts" setup>
import type { CascadeOrgApi } from '#/api/cascade/org';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { Button, message, Modal, Select, Switch, Tag } from 'ant-design-vue';

import { configureCascadeOrgPublishBatch } from '#/api/cascade/org';
import { getCascadePlatformPage } from '#/api/cascade/platform';
import { useDrawerResize } from '#/composables/useDrawerResize';
import { $t } from '#/locales';

/**
 * 批量推送：把表格里勾选的一批组织一次推到选中的上级平台。
 *
 * <p>为什么必须有这个入口：级联上线往往是整棵树一起推，而逐个组织点「推送配置」是一屏
 * 填不完的活；更麻烦的是漏推一级不报错——子组织在上级目录里立不起来会被组装器静默剔除，
 * 页面显示「已推送」，上级一条收不到。
 *
 * <p>「自动补推上级组织」默认开：只勾叶子时后端按父链把祖先补进来，并按层级从浅到深执行，
 * 保证父级先写发布行、子级才可能立起来。关掉它就退化成「只推你勾的那几个」，
 * 被祖先链挡住的组织会逐条返回原因。
 *
 * <p>与单条「推送配置」的分工：这里只决定「推给谁」，不带编码/名称覆盖与通道处置策略——
 * 那些逐平台的值回单条抽屉里改，避免批量入口把用户填过的覆盖抹成本地值。
 */

interface Emits {
  (e: 'success'): void;
}

interface DrawerData {
  /** 表格里勾选的组织 */
  orgs?: CascadeOrgApi.CascadeOrgVO[];
}

const emit = defineEmits<Emits>();

const orgs = ref<CascadeOrgApi.CascadeOrgVO[]>([]);
const platformOptions = ref<Array<{ label: string; value: string }>>([]);
const platformIds = ref<string[]>([]);
const includeAncestors = ref(true);
const submitting = ref(false);
const result = ref<CascadeOrgApi.OrgPublishBatchResp | null>(null);
const resultOpen = ref(false);
const loadingPlatforms = ref(false);

const orgCount = computed(() => orgs.value.length);

const summaryText = computed(() => {
  const data = result.value;
  if (!data) {
    return '';
  }
  return $t('cascade.org.batchPublish.resultSummary', [
    String(data.total ?? 0),
    String(data.ancestorsAdded ?? 0),
    String(data.succeeded ?? 0),
    String(data.failed ?? 0),
  ]);
});

/** 失败的行排前面：结果里用户要处理的就是这些 */
const resultItems = computed(() => {
  const items = result.value?.items ?? [];
  return [...items].sort((a, b) => Number(a.ok ?? false) - Number(b.ok ?? false));
});

async function loadPlatforms() {
  loadingPlatforms.value = true;
  try {
    /* 只列启用中的平台：推给一个停用的上级等于白配一条发布行 */
    const page = await getCascadePlatformPage({ enabled: 1, page: 1, size: 1000 });
    platformOptions.value = (page.items ?? [])
      .map((item) => item.platformId ?? '')
      .filter(Boolean)
      .map((platformId) => ({ label: platformId, value: platformId }));
  } catch (error) {
    console.error('加载上级平台失败:', error);
  } finally {
    loadingPlatforms.value = false;
  }
}

async function onSubmit() {
  if (orgs.value.length === 0) {
    message.error($t('cascade.org.batchPublish.orgsRequired'));
    return;
  }
  if (platformIds.value.length === 0) {
    message.error($t('cascade.org.batchPublish.platformRequired'));
    return;
  }

  submitting.value = true;
  drawerApi.lock();
  try {
    result.value = await configureCascadeOrgPublishBatch({
      ids: orgs.value
        .map((org) => org.id)
        .filter((id): id is number => typeof id === 'number'),
      includeAncestors: includeAncestors.value,
      platformIds: platformIds.value,
    });
    resultOpen.value = true;
  } catch (error: any) {
    message.error(error?.message || $t('cascade.org.batchPublish.failed'));
  } finally {
    submitting.value = false;
    drawerApi.unlock();
  }
}

/** 结果看完再关抽屉：关早了用户没机会核对哪一级失败了 */
function closeResult() {
  resultOpen.value = false;
  drawerApi.close();
  emit('success');
}

const { syncWidth } = useDrawerResize('vl-cascade-org-batch-publish');

const [Drawer, drawerApi] = useVbenDrawer({
  class: 'vl-cascade-org-batch-publish w-[46vw]',
  footer: false,
  onOpenChange(isOpen: boolean) {
    syncWidth();
    if (!isOpen) {
      return;
    }
    orgs.value = drawerApi.getData<DrawerData>()?.orgs ?? [];
    platformIds.value = [];
    includeAncestors.value = true;
    result.value = null;
    void loadPlatforms();
  },
});
</script>

<template>
  <Drawer :title="$t('cascade.org.batchPublish.title')">
    <div class="flex flex-col gap-4 px-4 pb-4">
      <div>
        <div class="mb-2 font-medium">
          {{ $t('cascade.org.batchPublish.orgs') }}
          <span class="text-muted-foreground text-xs">({{ orgCount }})</span>
        </div>
        <div v-if="orgCount === 0" class="text-muted-foreground text-sm">
          {{ $t('cascade.org.batchPublish.orgsEmpty') }}
        </div>
        <ul v-else class="org-list">
          <li v-for="org in orgs" :key="org.id" class="org-item">
            <span class="org-name">{{ org.orgName || org.orgCode }}</span>
            <span class="org-code">{{ org.orgCode }}</span>
          </li>
        </ul>
        <div class="text-muted-foreground mt-1 text-xs">
          {{ $t('cascade.org.batchPublish.orgsTip') }}
        </div>
      </div>

      <div>
        <div class="mb-2 font-medium">
          {{ $t('cascade.org.batchPublish.platforms') }}
        </div>
        <Select
          v-model:value="platformIds"
          class="w-full"
          :loading="loadingPlatforms"
          mode="multiple"
          :options="platformOptions"
          :placeholder="$t('cascade.org.batchPublish.platformsPlaceholder')"
          show-search
        />
        <div class="text-muted-foreground mt-1 text-xs">
          {{ $t('cascade.org.batchPublish.platformsTip') }}
        </div>
      </div>

      <div class="rounded-md bg-muted/40 px-3 py-2">
        <div class="flex items-center gap-2">
          <Switch v-model:checked="includeAncestors" size="small" />
          <span class="text-sm font-medium">
            {{ $t('cascade.org.batchPublish.includeAncestors') }}
          </span>
        </div>
        <div class="text-muted-foreground mt-1 text-xs">
          {{ $t('cascade.org.batchPublish.includeAncestorsTip') }}
        </div>
      </div>

      <div class="flex justify-end gap-2">
        <Button :disabled="submitting" @click="drawerApi.close()">
          {{ $t('common.cancel') }}
        </Button>
        <Button type="primary" :loading="submitting" @click="onSubmit">
          {{ $t('cascade.org.batchPublish.submit') }}
        </Button>
      </div>
    </div>

    <Modal
      :open="resultOpen"
      :footer="null"
      :mask-closable="false"
      :title="$t('cascade.org.batchPublish.resultTitle')"
      @cancel="closeResult"
    >
      <div class="flex flex-col gap-3">
        <div>{{ summaryText }}</div>

        <div
          v-if="(result?.failed ?? 0) === 0"
          class="text-xs text-green-600"
        >
          {{ $t('cascade.org.batchPublish.resultAllOk') }}
        </div>

        <ul class="result-list">
          <li v-for="item in resultItems" :key="item.id">
            <Tag :color="item.ok ? 'success' : 'error'" :bordered="false">
              {{ item.ok ? $t('cascade.org.batchPublish.resultOk') : $t('cascade.org.batchPublish.resultFailed') }}
            </Tag>
            <span class="result-name">{{ item.orgName || item.orgCode }}</span>
            <Tag v-if="item.ancestor" :bordered="false">
              {{ $t('cascade.org.batchPublish.ancestorTag') }}
            </Tag>
            <span v-if="item.message" class="result-message">
              {{ item.message }}
            </span>
          </li>
        </ul>

        <div class="flex justify-end">
          <Button type="primary" @click="closeResult">
            {{ $t('cascade.org.batchPublish.gotIt') }}
          </Button>
        </div>
      </div>
    </Modal>
  </Drawer>
</template>

<style scoped>
.org-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 180px;
  padding: 8px 10px;
  overflow: auto;
  font-size: 12px;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.org-item {
  display: flex;
  gap: 8px;
  align-items: center;
}

.org-name {
  font-weight: 500;
}

.org-code {
  font-family:
    'JetBrains Mono', 'IBM Plex Mono', ui-monospace, 'SF Mono', monospace;
  color: hsl(var(--muted-foreground));
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 260px;
  padding: 0;
  overflow: auto;
  font-size: 12px;
  list-style: none;
}

.result-list li {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.result-name {
  font-weight: 500;
}

.result-message {
  width: 100%;
  color: hsl(var(--muted-foreground));
}
</style>
