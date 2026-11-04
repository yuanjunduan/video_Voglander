package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.config.StubCascadeCatalogRevokeService;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRevokePreviewDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * {@link CascadeOrgManager} 集成测试。
 *
 * <p>组织是上级目录树里的中间层，编码与父子关系一旦下发就不能回退，
 * 因此这里重点守三条：<b>编码格式与递增</b>、<b>父子关系只增不改</b>、<b>停用连带剪枝</b>。
 *
 * <p><b>断言为什么都按自建数据过滤</b>：1.10.1 起组织是全局唯一的本地资产，
 * 不再像旧模型那样用 {@code platformId} 把每个用例的数据隔离开。同一个 SQLite 库里的
 * 历史行会累积，所以「整表有几条」这类断言改成本用例自己的那几个 id 上，
 * 否则用例之间互相污染、单跑绿全跑红。平台维度只在发布关系里出现，仍用
 * {@link UniqueKeyFactory#deviceId()} 生成唯一平台编码隔离。
 *
 * @author luna
 */
@DisplayName("CascadeOrgManager 集成测试")
class CascadeOrgManagerTest extends BaseTest {

    @Autowired
    private CascadeOrgManager cascadeOrgManager;

    @Autowired
    private CascadeChannelManager cascadeChannelManager;

    @Autowired
    private CascadePublishManager cascadePublishManager;

    @Autowired
    private DeviceManager         deviceManager;

    @Autowired
    private DeviceChannelManager  deviceChannelManager;

    /** 上级撤销通道的测试桩；默认「已确认」，需要验证严格阻断的用例才改成拒绝 / 无响应 */
    @Autowired
    private StubCascadeCatalogRevokeService revokeStub;

    /** 桩是全容器单例，结论改了必须恢复，否则会串到其它测试类 */
    @AfterEach
    void resetRevokeStub() {
        revokeStub.reset();
    }

    /** 造一个本地组织入参：组织不认平台，parentId 为空即根节点。 */
    private CascadeOrgDTO org(Long parentId, String name) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setParentId(parentId);
        dto.setOrgName(name);
        return dto;
    }

    /** 造一个「新增时就配好发布范围」的入参。 */
    private CascadeOrgDTO org(Long parentId, String name, String platformId) {
        CascadeOrgDTO dto = org(parentId, name);
        dto.setPlatformIds(List.of(platformId));
        return dto;
    }

    /** 把组织发布到平台；{@code code}/{@code policy} 为 null 表示沿用本地编码与默认策略。 */
    private void publish(String platformId, Long orgId, String code, String policy) {
        CascadePublishDTO dto = new CascadePublishDTO();
        dto.setPlatformId(platformId);
        dto.setObjectType(PublishObjectType.ORG.name());
        dto.setObjectKey(code(orgId));
        dto.setPublishCode(code);
        dto.setRevokePolicy(policy);
        cascadePublishManager.publish(dto);
    }

    /** 组织主键 → 业务键：发布关系按 {@code org_code} 存，不是自增 id。 */
    private String code(Long orgId) {
        CascadeOrgDTO org = cascadeOrgManager.getById(orgId);
        assertNotNull(org, "组织不存在，取不到业务键: id=" + orgId);
        return org.getOrgCode();
    }

    /** 整条链（含自身）都发布到该平台——目录要求祖先闭包，少一环就立不起来。 */
    private void publishChain(String platformId, Long... orgIds) {
        for (Long orgId : orgIds) {
            publish(platformId, orgId, null, null);
        }
    }

    private CascadeOrgDTO findById(List<CascadeOrgDTO> nodes, Long id) {
        return nodes.stream().filter(node -> id.equals(node.getId())).findFirst().orElse(null);
    }

    private CascadeOrgDTO findRoot(List<CascadeOrgDTO> roots, Long id) {
        CascadeOrgDTO node = findById(roots, id);
        assertNotNull(node, "根节点列表里应能找到自建的组织: id=" + id);
        return node;
    }

    /** 造一台本地设备；{@code orgId} 为空表示不归任何组织。返回 deviceId，通道要靠它关联。 */
    private String createDevice(Long orgId) {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO dto = new DeviceDTO();
        dto.setDeviceId(deviceId);
        dto.setIp("127.0.0.1");
        dto.setPort(5060);
        dto.setType(1);
        dto.setServerIp("127.0.0.1");
        dto.setName("dev-占用校验");
        dto.setOrgId(orgId);
        deviceManager.add(dto);
        return deviceId;
    }

    /** 在设备下造一条通道；{@code orgId} 为空表示不直挂，归属跟随设备。返回通道主键。 */
    private Long createChannel(String deviceId, Long orgId) {
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setDeviceId(deviceId);
        dto.setChannelId(UniqueKeyFactory.channelId());
        dto.setName("ch-占用校验");
        Long id = deviceChannelManager.add(dto);
        if (orgId != null) {
            DeviceChannelDTO assign = new DeviceChannelDTO();
            assign.setOrgId(orgId);
            deviceChannelManager.updateById(id, assign);
        }
        return id;
    }

    /**
     * 造一条「本地真实存在」的通道 + 它在某平台的上报配置。
     *
     * <p>目录来源已收敛到发布层，而发布行只认库里真有的通道：随便编两个 id 会被
     * {@code CascadePublishedAssetResolver} 当孤儿丢弃，用例就会误判成「目录里没有通道」。
     * 组织归属写到本地 {@code org_id}——通道的父节点是按本地归属推导的，只配发布行没用。
     */
    private CascadeChannelDTO channel(String platformId, String orgCode) {
        String deviceId = createDevice(null);
        String channelId = UniqueKeyFactory.channelId();
        DeviceChannelDTO local = new DeviceChannelDTO();
        local.setDeviceId(deviceId);
        local.setChannelId(channelId);
        local.setName("ch-目录预览");
        Long pk = deviceChannelManager.add(local);
        if (orgCode != null) {
            DeviceChannelDTO assign = new DeviceChannelDTO();
            assign.setOrgId(cascadeOrgManager.getByCode(orgCode).getId());
            deviceChannelManager.updateById(pk, assign);
        }
        CascadeChannelDTO ch = new CascadeChannelDTO();
        ch.setPlatformId(platformId);
        ch.setLocalDeviceId(deviceId);
        ch.setLocalChannelId(channelId);
        ch.setCascadeChannelId(channelId);
        ch.setCascadeOrgId(orgCode);
        ch.setEnabled(1);
        return ch;
    }

    @Nested
    @DisplayName("编码生成")
    class NextOrgCode {

        @Test
        @DisplayName("未配置前缀时用默认前缀，长度为 20 且类型位为 215")
        void should_use_default_prefix() {
            String code = cascadeOrgManager.nextOrgCode();

            assertNotNull(code);
            assertEquals(20, code.length(), code);
            assertTrue(code.startsWith("3402000000"), "默认前缀 3402000000: " + code);
            assertEquals("215", code.substring(10, 13), "类型位固定 215（业务分组）: " + code);
            assertEquals('0', code.charAt(13), "网络标识位固定 0: " + code);
        }

        @Test
        @DisplayName("已存在组织时序号递增，不会撞上库里的编码")
        void should_increment_serial() {
            CascadeOrgDTO first = org(null, "一层");
            cascadeOrgManager.add(first);

            String next = cascadeOrgManager.nextOrgCode();
            assertNotEquals(first.getOrgCode(), next, "新编码不能与刚生成的重复");
            assertTrue(next.compareTo(first.getOrgCode()) > 0, "序号只增：first=" + first.getOrgCode() + ", next=" + next);
            /* nextOrgCode 只是试算，不落库；真落库后再试算一次必须仍然不同 */
            cascadeOrgManager.add(org(null, "另一层"));
            assertNotEquals(next, cascadeOrgManager.nextOrgCode(), "连续两次试算之间不能互相踩");
        }
    }

    @Nested
    @DisplayName("add - 新增组织")
    class Add {

        @Test
        @DisplayName("编码由后端生成，长度 20 且类型位为 215")
        void should_generate_code() {
            CascadeOrgDTO dto = org(null, "一层");
            Long id = cascadeOrgManager.add(dto);

            assertNotNull(id);
            assertEquals(20, dto.getOrgCode().length());
            assertEquals("215", dto.getOrgCode().substring(10, 13));
            assertEquals(dto.getOrgCode(), cascadeOrgManager.getById(id).getOrgCode(), "落库值与回填值必须一致");
        }

        @Test
        @DisplayName("未指定 enabled/sortOrder 时默认启用、排序 0")
        void should_default_enabled_and_sort() {
            Long id = cascadeOrgManager.add(org(null, "一层"));

            CascadeOrgDTO saved = cascadeOrgManager.getById(id);
            assertEquals(1, saved.getEnabled());
            assertEquals(0, saved.getSortOrder());
            assertEquals(List.of(), saved.getPlatformIds() == null ? List.of() : saved.getPlatformIds(),
                "出参只读 publishedPlatformIds，入参 platformIds 不回显");
        }

        @Test
        @DisplayName("父组织不存在时拒绝，避免出现挂不上的孤儿节点")
        void should_reject_unknown_parent() {
            assertThrows(IllegalArgumentException.class,
                () -> cascadeOrgManager.add(org(Long.MAX_VALUE - 1, "二层")));
        }

        @Test
        @DisplayName("父组织停用时新组织落成停用，避免显示上报中却上级看不见")
        void should_persist_as_disabled_under_disabled_parent() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            assertTrue(cascadeOrgManager.disable(rootId));

            Long childId = cascadeOrgManager.add(org(rootId, "二层"));

            assertEquals(0, cascadeOrgManager.getById(childId).getEnabled(),
                "父链停用，新组织不能是上报状态");
        }

        @Test
        @DisplayName("新增时带上 platformIds 会同时建好发布行")
        void should_create_publish_rows_on_add() {
            String platformId = UniqueKeyFactory.deviceId();
            Long id = cascadeOrgManager.add(org(null, "一层", platformId));

            List<String> platforms = cascadePublishManager.platformIdsOf(PublishObjectType.ORG.name(), code(id));
            assertEquals(List.of(platformId), platforms);
            assertEquals(List.of(platformId), findRoot(cascadeOrgManager.tree(), id).getPublishedPlatformIds(),
                "树形出参要能看到已发布平台（getById 不做批量发布行填充，只回库内字段）");
        }
    }

    @Nested
    @DisplayName("tree - 树形出参")
    class Tree {

        @Test
        @DisplayName("子组织挂在父节点的 children 上")
        void should_build_hierarchy() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            cascadeOrgManager.add(org(rootId, "二层"));

            CascadeOrgDTO root = findRoot(cascadeOrgManager.tree(), rootId);

            assertEquals(1, root.getChildren().size());
            assertEquals("二层", root.getChildren().get(0).getOrgName());
        }

        @Test
        @DisplayName("直属通道数与跟随设备归属的通道都计入该组织")
        void should_count_direct_channels() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            String deviceInOrg = createDevice(rootId);
            String deviceFree = createDevice(null);

            createChannel(deviceInOrg, null);   // 通道自己不填组织，归属跟随设备
            createChannel(deviceFree, rootId);  // 设备不归组织，通道自己挂

            assertEquals(2, findRoot(cascadeOrgManager.tree(), rootId).getChannelCount(),
                "两种归属都要算进该组织，否则删组织时会把占用漏成可删");
        }

        @Test
        @DisplayName("子组织排序值更小、排在父组织之前时仍能正确挂树")
        void should_attach_child_listed_before_parent() {
            CascadeOrgDTO parent = org(null, "一层");
            parent.setSortOrder(9);
            Long parentId = cascadeOrgManager.add(parent);
            CascadeOrgDTO child = org(parentId, "二层");
            child.setSortOrder(0);
            cascadeOrgManager.add(child);

            CascadeOrgDTO parentNode = findRoot(cascadeOrgManager.tree(), parentId);

            assertEquals(1, parentNode.getChildren().size(), "子组织必须挂在父节点下，不能因遍历顺序丢失");
        }

        @Test
        @DisplayName("树节点带出已发布平台，含停用的发布行")
        void should_expose_published_platforms() {
            String on = UniqueKeyFactory.deviceId();
            String off = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            publishChain(on, rootId);
            publish(off, rootId, null, null);
            cascadePublishManager.setEnabled(off, PublishObjectType.ORG.name(), code(rootId), 0);

            List<String> platforms = findRoot(cascadeOrgManager.tree(), rootId).getPublishedPlatformIds();

            assertTrue(platforms.contains(on));
            assertTrue(platforms.contains(off), "配了但没开的发布行也要列出来，页面要区分「未配置」与「已停用」");
        }
    }

    @Nested
    @DisplayName("delete - 删除组织")
    class Delete {

        @Test
        @DisplayName("还有子组织时拒绝删除")
        void should_reject_when_child_exists() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            cascadeOrgManager.add(org(rootId, "二层"));

            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.delete(rootId));
        }

        @Test
        @DisplayName("还有直挂组织的通道时拒绝删除，报错要指名是通道占用")
        void should_reject_when_channel_bound() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            /* 设备不归组织：否则会先命中设备占用，通道这条断言永远跑不到 */
            String deviceId = createDevice(null);
            createChannel(deviceId, rootId);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cascadeOrgManager.delete(rootId));
            assertTrue(ex.getMessage().contains("1 路通道"), "设备侧为 0，报错必须指向通道占用: " + ex.getMessage());
        }

        @Test
        @DisplayName("组织下挂着设备时拒绝删除，报错要指名是设备占用")
        void should_reject_when_device_bound() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            createDevice(rootId);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cascadeOrgManager.delete(rootId));
            assertTrue(ex.getMessage().contains("1 台设备"), "要报出占用数量，而不是笼统说不能删: " + ex.getMessage());
            assertNotNull(cascadeOrgManager.getById(rootId), "占用没清空之前一条数据都不能动");
        }

        @Test
        @DisplayName("空组织可以删除，发布行一并清理")
        void should_delete_leaf() {
            String platformId = UniqueKeyFactory.deviceId();
            Long id = cascadeOrgManager.add(org(null, "一层", platformId));
            String orgCode = cascadeOrgManager.getById(id).getOrgCode();

            assertTrue(cascadeOrgManager.delete(id));
            assertNull(cascadeOrgManager.getById(id));
            assertTrue(cascadePublishManager.platformIdsOf(PublishObjectType.ORG.name(), orgCode).isEmpty(),
                "组织没了，指向它的发布行不能留");
        }

        @Test
        @DisplayName("先向上级取消上报、拿到确认后才删本地组织")
        void should_revoke_before_deleting_local_org() {
            String platformId = UniqueKeyFactory.deviceId();
            Long id = cascadeOrgManager.add(org(null, "一层", platformId));

            /* 撤销时刻组织必须还在树上：先删本地再通知上级会留下「本地已无、上级仍有」的空壳节点 */
            revokeStub.setOnRevoke(invocation -> assertNotNull(cascadeOrgManager.getById(id),
                "上级确认前组织不能先删"));

            assertTrue(cascadeOrgManager.delete(id));
            assertEquals(1, revokeStub.invocationCount(), "只撤组织自己这一个节点");
            assertEquals(platformId, revokeStub.getInvocations().get(0).platformId());
            assertEquals(1, revokeStub.getInvocations().get(0).nodeCount());
        }

        @Test
        @DisplayName("上级未确认撤销：组织不删，报错里要带是哪个平台")
        void should_block_when_upstream_refuses_revoke() {
            String platformId = UniqueKeyFactory.deviceId();
            Long id = cascadeOrgManager.add(org(null, "一层", platformId));
            String orgCode = cascadeOrgManager.getById(id).getOrgCode();
            revokeStub.refuse(403);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cascadeOrgManager.delete(id));

            assertTrue(ex.getMessage().contains("上级未确认取消上报"), ex.getMessage());
            assertTrue(ex.getMessage().contains(platformId), "要报出没确认的平台: " + ex.getMessage());
            assertNotNull(cascadeOrgManager.getById(id), "上级没确认就不能删本地组织");
            assertEquals(1, cascadePublishManager.platformIdsOf(PublishObjectType.ORG.name(), orgCode).size(),
                "发布行要留着，重试还要靠它");
        }
    }

    @Nested
    @DisplayName("listEffective / isEnabledOrg - 本地生效判定")
    class Effective {

        @Test
        @DisplayName("父组织停用时子组织一并剪掉，避免上级出现孤儿节点")
        void should_prune_subtree_when_parent_disabled() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long childId = cascadeOrgManager.add(org(rootId, "二层"));

            assertTrue(cascadeOrgManager.disable(rootId));

            assertNull(findById(cascadeOrgManager.listEffective(), childId), "父节点停用后子孙都不能下发");
            /* 子组织自身仍是 enabled=1（isEnabledOrg 只管自己），是被祖先链判定剪掉的 */
            assertTrue(cascadeOrgManager.isEnabledOrg(cascadeOrgManager.getById(childId).getOrgCode()),
                "子组织自身仍处于启用状态");
        }

        @Test
        @DisplayName("子组织单独停用不影响父组织生效")
        void should_keep_parent_effective() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long childId = cascadeOrgManager.add(org(rootId, "二层"));

            assertTrue(cascadeOrgManager.disable(childId));

            assertNotNull(findById(cascadeOrgManager.listEffective(), rootId));
            assertNull(findById(cascadeOrgManager.listEffective(), childId));
        }

        @Test
        @DisplayName("isEffectivelyEnabled 看整条祖先链，findDisabledInChain 指出停在哪个组织")
        void should_resolve_effective_by_ancestors() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            String leafCode = cascadeOrgManager.getById(leafId).getOrgCode();
            assertTrue(cascadeOrgManager.isEffectivelyEnabled(leafCode), "整条链都在报");
            assertNull(cascadeOrgManager.findDisabledInChain(leafCode));

            assertTrue(cascadeOrgManager.disable(midId));

            assertTrue(cascadeOrgManager.isEnabledOrg(leafCode), "自身开关仍是开着");
            assertFalse(cascadeOrgManager.isEffectivelyEnabled(leafCode), "但祖先停报后上级看不到");
            assertEquals(midId, cascadeOrgManager.findDisabledInChain(leafCode).getId(),
                "要指出停在中间那层，而不是笼统说不可见");
            assertFalse(cascadeOrgManager.isEffectivelyEnabled("34020000002159999999"),
                "组织不存在一律按不可见处理");
        }
    }

    @Nested
    @DisplayName("disable / enable - 本地启停")
    class Disable {

        /** 造一棵三层树，叶子组织挂一条通道，返回 [rootId, midId, leafId] */
        private Long[] buildTreeWithChannel(String platformId) {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            /* 先发布再挂通道：未发布的组织在该平台目录里不存在，通道写入口会直接拒绝 */
            publishChain(platformId, rootId, midId, leafId);
            cascadeChannelManager.add(channel(platformId, cascadeOrgManager.getById(leafId).getOrgCode()));
            return new Long[] {rootId, midId, leafId};
        }

        @Test
        @DisplayName("停用只改 enabled，组织与通道一条不删")
        void disable_should_keep_data() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] ids = buildTreeWithChannel(platformId);

            assertTrue(cascadeOrgManager.disable(ids[0]));

            assertEquals(0, cascadeOrgManager.getById(ids[0]).getEnabled());
            assertNotNull(cascadeOrgManager.getById(ids[1]), "子组织不动");
            assertNull(findById(cascadeOrgManager.listEffective(), ids[0]), "停用后不再进本地有效集");
        }

        @Test
        @DisplayName("停用幂等：重复调用不报错")
        void disable_should_be_idempotent() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] ids = buildTreeWithChannel(platformId);

            assertTrue(cascadeOrgManager.disable(ids[0]));
            assertTrue(cascadeOrgManager.disable(ids[0]));
            assertEquals(0, cascadeOrgManager.getById(ids[0]).getEnabled());
        }

        @Test
        @DisplayName("恢复上报：祖先仍停用时拒绝，避免产生挂不上去的子树")
        void enable_should_reject_when_ancestor_disabled() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] ids = buildTreeWithChannel(platformId);
            assertTrue(cascadeOrgManager.disable(ids[0]));

            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.enable(ids[1]));
        }

        @Test
        @DisplayName("恢复上报后整棵子树重新进入有效集")
        void enable_should_restore() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] ids = buildTreeWithChannel(platformId);
            assertTrue(cascadeOrgManager.disable(ids[0]));

            assertTrue(cascadeOrgManager.enable(ids[0]));

            List<CascadeOrgDTO> effective = cascadeOrgManager.listEffective();
            assertEquals(1, cascadeOrgManager.getById(ids[0]).getEnabled());
            for (Long id : ids) {
                assertNotNull(findById(effective, id), "整棵子树一起回来: id=" + id);
            }
        }

        @Test
        @DisplayName("编辑接口把 enabled 改回 1 时同样要求父及所有上级在报")
        void update_should_reject_restore_when_ancestor_disabled() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long childId = cascadeOrgManager.add(org(rootId, "二层"));
            assertTrue(cascadeOrgManager.disable(childId));
            assertTrue(cascadeOrgManager.disable(rootId));

            CascadeOrgDTO patch = new CascadeOrgDTO();
            patch.setId(childId);
            patch.setEnabled(1);

            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.update(patch),
                "绕过 enable() 直接改字段也必须被拦下");
            assertEquals(0, cascadeOrgManager.getById(childId).getEnabled(), "被拒绝时不能留下改动");
        }

        @Test
        @DisplayName("祖先都在报时编辑接口可以恢复上报")
        void update_should_allow_restore_when_ancestors_enabled() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long childId = cascadeOrgManager.add(org(rootId, "二层"));
            assertTrue(cascadeOrgManager.disable(childId));

            CascadeOrgDTO patch = new CascadeOrgDTO();
            patch.setId(childId);
            patch.setEnabled(1);

            assertTrue(cascadeOrgManager.update(patch));
            assertEquals(1, cascadeOrgManager.getById(childId).getEnabled());
        }
    }

    @Nested
    @DisplayName("previewDisable - 停用影响面")
    class Preview {

        /** 三层树 + 叶子下一条通道，整条链发布到同一个平台，返回 [rootId, midId, leafId] */
        private Long[] buildPublishedTree(String platformId) {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            publishChain(platformId, rootId, midId, leafId);
            cascadeChannelManager.add(channel(platformId, cascadeOrgManager.getById(leafId).getOrgCode()));
            return new Long[] {rootId, midId, leafId};
        }

        @Test
        @DisplayName("预览按后序（叶子→根）给出组织清单，通道单列")
        void preview_should_list_post_order() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] ids = buildPublishedTree(platformId);

            CascadeRevokePreviewDTO preview = cascadeOrgManager.previewDisable(ids[0]);

            List<String> codes = preview.getOrgs().stream()
                .map(io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent.Node::cascadeId)
                .collect(java.util.stream.Collectors.toList());
            assertEquals(List.of(
                cascadeOrgManager.getById(ids[2]).getOrgCode(),
                cascadeOrgManager.getById(ids[1]).getOrgCode(),
                cascadeOrgManager.getById(ids[0]).getOrgCode()), codes,
                "先子后父：反过来会让上级先丢父节点，子节点变孤儿");
            assertEquals(3, preview.orgCount());
            assertEquals(1, preview.channelCount());
            assertEquals(List.of(platformId), preview.getPublishedPlatformIds());
            assertEquals("CASCADE", preview.getRevokePolicyByPlatform().get(platformId),
                "没显式配策略时按 CASCADE 落库");
            assertEquals(4, preview.deleteCount(), "通道 + 三个组织");
        }

        @Test
        @DisplayName("REROUTE 平台：通道仍列出（要改挂）但不算进删除报文数")
        void preview_reroute_counts_only_orgs_as_deletions() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] ids = buildPublishedTree(platformId);
            /* 策略挂在发布关系上：同一个组织推给不同平台可以各配一种 */
            assertTrue(cascadePublishManager.setRevokePolicy(platformId, PublishObjectType.ORG.name(),
                code(ids[0]), "REROUTE"));

            CascadeRevokePreviewDTO preview = cascadeOrgManager.previewDisable(ids[0]);

            assertEquals("REROUTE", preview.getRevokePolicyByPlatform().get(platformId));
            assertEquals(3, preview.orgCount());
            assertEquals(1, preview.channelCount(), "需要告知用户有 1 条通道会被改挂");
            assertEquals(3, preview.deleteCount(), "REROUTE 不发通道 DEL，删除报文只算组织");
        }

        @Test
        @DisplayName("两个平台各配一种策略时按平台累加报文数")
        void preview_sums_deletions_per_platform() {
            String cascadePlatform = UniqueKeyFactory.deviceId();
            String reroutePlatform = UniqueKeyFactory.deviceId();
            Long[] ids = buildPublishedTree(cascadePlatform);
            publishChain(reroutePlatform, ids[0], ids[1], ids[2]);
            assertTrue(cascadePublishManager.setRevokePolicy(reroutePlatform, PublishObjectType.ORG.name(),
                code(ids[0]), "REROUTE"));

            CascadeRevokePreviewDTO preview = cascadeOrgManager.previewDisable(ids[0]);

            assertEquals(2, preview.getPublishedPlatformIds().size());
            assertEquals(4 + 3, preview.deleteCount(), "CASCADE 平台 4 条 + REROUTE 平台 3 条");
            assertEquals(1, preview.channelCount(), "两平台同一条通道，合并展示只算一条");
        }

        @Test
        @DisplayName("没发布到任何平台时不会产生报文")
        void preview_without_publication_has_no_delivery() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));

            CascadeRevokePreviewDTO preview = cascadeOrgManager.previewDisable(rootId);

            assertTrue(preview.getPublishedPlatformIds().isEmpty());
            assertEquals(0, preview.deleteCount());
        }
    }

    @Nested
    @DisplayName("platformOrgView - 某平台眼里的目录")
    class PlatformView {

        @Test
        @DisplayName("只发布叶子时叶子立不起来：祖先闭包不完整")
        void leaf_only_publish_is_not_in_catalog() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            publish(platformId, leafId, null, null);

            CascadePlatformOrgView view = cascadeOrgManager.platformOrgView(platformId);

            assertNull(findCatalog(view, leafId), "父节点不在目录里，叶子的 ParentID 指向不存在的节点");
            assertTrue(view.reroutedLocalCodes().isEmpty(), "默认 CASCADE，通道一起不上报");
        }

        @Test
        @DisplayName("整条链都发布后才进目录，父编码按平台解析")
        void full_chain_publish_enters_catalog() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            publishChain(platformId, rootId, midId, leafId);

            CascadePlatformOrgView view = cascadeOrgManager.platformOrgView(platformId);

            assertEquals(3, view.catalogOrgs().size());
            assertNull(findCatalog(view, rootId).getParentPlatformCode(), "根组织的父级由发送方回落域编码");
            assertEquals(cascadeOrgManager.getById(rootId).getOrgCode(),
                findCatalog(view, midId).getParentPlatformCode());
            assertEquals(cascadeOrgManager.getById(midId).getOrgCode(),
                findCatalog(view, leafId).getParentPlatformCode());
        }

        @Test
        @DisplayName("发布行可覆盖编码：目录用覆盖值，本地编码保持不变")
        void publish_code_override_shadows_local_code() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            String localCode = cascadeOrgManager.getById(midId).getOrgCode();
            publishChain(platformId, rootId, midId);
            publish(platformId, midId, "34020000002150009999", null);

            CascadePlatformOrgView view = cascadeOrgManager.platformOrgView(platformId);

            CascadePlatformOrgDTO mid = findCatalog(view, midId);
            assertEquals("34020000002150009999", mid.getPlatformCode());
            assertEquals(localCode, mid.getLocalCode(), "通道仍按本地编码归属，两者必须同时拿得到");
            assertEquals("34020000002150009999", findCatalog(view, midId).getPlatformCode());
            assertEquals(cascadeOrgManager.getById(rootId).getOrgCode(),
                cascadeOrgManager.getById(rootId).getOrgCode());
        }

        @Test
        @DisplayName("阻断节点配 REROUTE 时其下通道改挂域节点继续上报")
        void reroute_on_blocker_keeps_channels() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            publishChain(platformId, rootId, midId);
            /* 叶子还有发布行但已停用，且行上留着 REROUTE：等效「没发布」，但策略必须还能读到 */
            publish(platformId, leafId, null, "REROUTE");
            cascadePublishManager.setEnabled(platformId, PublishObjectType.ORG.name(), code(leafId), 0);

            CascadePlatformOrgView view = cascadeOrgManager.platformOrgView(platformId);

            assertNull(findCatalog(view, leafId), "发布行停用等同没发布，叶子不在目录里");
            assertTrue(view.reroutedLocalCodes().contains(cascadeOrgManager.getById(leafId).getOrgCode()),
                "阻断节点（叶子自己）的发布行策略也算数，它的通道要上浮");
        }

        @Test
        @DisplayName("祖先停用但配了 REROUTE：整支子树都被标记为上浮")
        void disabled_ancestor_with_reroute_reroutes_descendants() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            publishChain(platformId, rootId, midId);
            publish(platformId, rootId, null, "REROUTE");
            assertTrue(cascadeOrgManager.disable(rootId));

            CascadePlatformOrgView view = cascadeOrgManager.platformOrgView(platformId);

            assertTrue(view.catalogOrgs().isEmpty(), "根被停用，整支都不在目录里");
            assertTrue(view.reroutedLocalCodes().contains(cascadeOrgManager.getById(midId).getOrgCode()));
        }

        @Test
        @DisplayName("祖先停用且策略为 CASCADE：子树直接消失，通道也不上浮")
        void disabled_ancestor_with_cascade_hides_subtree() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            publishChain(platformId, rootId, midId);
            assertTrue(cascadeOrgManager.disable(rootId));

            CascadePlatformOrgView view = cascadeOrgManager.platformOrgView(platformId);

            assertTrue(view.catalogOrgs().isEmpty());
            assertTrue(view.reroutedLocalCodes().isEmpty());
        }

        @Test
        @DisplayName("发布行停用等同于没发布：该节点不再进目录")
        void disabled_publish_row_hides_org() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            publishChain(platformId, rootId);
            cascadePublishManager.setEnabled(platformId, PublishObjectType.ORG.name(), code(rootId), 0);

            CascadePlatformOrgView view = cascadeOrgManager.platformOrgView(platformId);

            assertTrue(view.catalogOrgs().isEmpty(), "本地仍启用，只是不推这个平台");
            assertEquals(1, cascadeOrgManager.getById(rootId).getEnabled(), "本地启用状态不能被发布启停改掉");
        }

        @Test
        @DisplayName("平台没有任何发布配置时是空视图，而不是全量可见")
        void empty_publication_yields_empty_view() {
            assertTrue(cascadeOrgManager.platformOrgView(UniqueKeyFactory.deviceId()).catalogOrgs().isEmpty());
            assertTrue(cascadeOrgManager.platformOrgView(null).catalogOrgs().isEmpty());
        }

        private CascadePlatformOrgDTO findCatalog(CascadePlatformOrgView view, Long orgId) {
            return view.catalogOrgs().stream()
                .filter(node -> orgId.equals(node.getId()))
                .findFirst()
                .orElse(null);
        }
    }

    @Nested
    @DisplayName("orgPath - 物化路径")
    class OrgPath {

        @Test
        @DisplayName("根组织路径为 /编码/，子组织路径以父编码开头")
        void should_build_path() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long childId = cascadeOrgManager.add(org(rootId, "二层"));

            CascadeOrgDTO root = cascadeOrgManager.getById(rootId);
            CascadeOrgDTO child = cascadeOrgManager.getById(childId);

            assertEquals("/" + root.getOrgCode() + "/", root.getOrgPath());
            assertEquals(root.getOrgPath() + child.getOrgCode() + "/", child.getOrgPath());
        }

        @Test
        @DisplayName("三层嵌套的路径层层包含，层数可由斜杠数推算")
        void should_nest_path_by_depth() {
            Long l1 = cascadeOrgManager.add(org(null, "一层"));
            Long l2 = cascadeOrgManager.add(org(l1, "二层"));
            Long l3 = cascadeOrgManager.add(org(l2, "三层"));

            String path = cascadeOrgManager.getById(l3).getOrgPath();
            assertTrue(path.startsWith(cascadeOrgManager.getById(l1).getOrgPath()), path);
            assertTrue(path.startsWith(cascadeOrgManager.getById(l2).getOrgPath()), path);
            assertEquals(3, path.chars().filter(c -> c == '/').count() - 1, "斜杠数 - 1 = 深度");
        }
    }

    @Nested
    @DisplayName("listSubtree - 子树查询")
    class Subtree {

        /** 造一棵三层树，返回 [root, mid, leaf] 的编码 */
        private String[] buildThreeLevels() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            return new String[] {
                cascadeOrgManager.getById(rootId).getOrgCode(),
                cascadeOrgManager.getById(midId).getOrgCode(),
                cascadeOrgManager.getById(leafId).getOrgCode()
            };
        }

        @Test
        @DisplayName("一次取全任意层级（十几层也不需要递归 SQL）")
        void should_fetch_all_descendants() {
            String[] codes = buildThreeLevels();

            List<CascadeOrgDTO> subtree = cascadeOrgManager.listSubtree(codes[0], true);

            assertEquals(3, subtree.size(), "三层都要在结果里");
            assertEquals(3, cascadeOrgManager.listSubtreeCodes(codes[0], true).size());
        }

        @Test
        @DisplayName("includeSelf=false 时不返回子树根自身")
        void should_exclude_self() {
            String[] codes = buildThreeLevels();

            List<CascadeOrgDTO> children = cascadeOrgManager.listSubtree(codes[0], false);

            assertEquals(2, children.size());
            assertTrue(children.stream().noneMatch(o -> codes[0].equals(o.getOrgCode())));
        }

        @Test
        @DisplayName("从中间层取子树不会带上父层")
        void should_not_include_ancestors() {
            String[] codes = buildThreeLevels();

            List<CascadeOrgDTO> subtree = cascadeOrgManager.listSubtree(codes[1], true);

            assertEquals(2, subtree.size(), "中间层 + 叶子");
            assertTrue(subtree.stream().noneMatch(o -> codes[0].equals(o.getOrgCode())));
        }

        @Test
        @DisplayName("组织不存在时返回空列表，不抛异常")
        void should_return_empty_for_unknown_org() {
            assertTrue(cascadeOrgManager.listSubtree("34020000002159999999", true).isEmpty());
        }

        @Test
        @DisplayName("treeSubtree 只返回该支，子树根成为顶层节点")
        void should_build_subtree_tree() {
            String[] codes = buildThreeLevels();

            List<CascadeOrgDTO> roots = cascadeOrgManager.treeSubtree(codes[1]);

            assertEquals(1, roots.size());
            assertEquals(codes[1], roots.get(0).getOrgCode());
            assertEquals(1, roots.get(0).getChildren().size());
            assertEquals(codes[2], roots.get(0).getChildren().get(0).getOrgCode());
        }
    }

    @Nested
    @DisplayName("通道按子树过滤")
    class ChannelSubtree {

        @Test
        @DisplayName("includeSubOrg=true 能查到子组织下的通道")
        void should_include_child_org_channels() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long childId = cascadeOrgManager.add(org(rootId, "二层"));
            String rootCode = cascadeOrgManager.getById(rootId).getOrgCode();
            String childCode = cascadeOrgManager.getById(childId).getOrgCode();
            publishChain(platformId, rootId, childId);

            cascadeChannelManager.add(channel(platformId, rootCode));
            cascadeChannelManager.add(channel(platformId, childCode));

            CascadeChannelDTO query = new CascadeChannelDTO();
            query.setPlatformId(platformId);
            query.setCascadeOrgId(rootCode);

            assertEquals(1, cascadeChannelManager.getPage(query, 1, 10, false).getTotal(), "默认只取直属");
            assertEquals(2, cascadeChannelManager.getPage(query, 1, 10, true).getTotal(), "递归取整棵子树");
        }

        @Test
        @DisplayName("组织不存在时返回空页，绝不退化成不过滤")
        void should_return_empty_page_for_unknown_org() {
            CascadeChannelDTO query = new CascadeChannelDTO();
            query.setPlatformId(UniqueKeyFactory.deviceId());
            query.setCascadeOrgId("34020000002159999999");

            assertEquals(0, cascadeChannelManager.getPage(query, 1, 10, true).getTotal());
        }
    }

    @Nested
    @DisplayName("move - 移动组织")
    class Move {

        @Test
        @DisplayName("整棵子树的父节点与物化路径一起重写，编码不变")
        void should_rewrite_whole_subtree() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            Long targetId = cascadeOrgManager.add(org(null, "另一棵树的根"));

            CascadeOrgDTO rootBefore = cascadeOrgManager.getById(rootId);
            String midCode = cascadeOrgManager.getById(midId).getOrgCode();
            String leafCode = cascadeOrgManager.getById(leafId).getOrgCode();
            String targetPath = cascadeOrgManager.getById(targetId).getOrgPath();

            assertTrue(cascadeOrgManager.move(midId, targetId));

            CascadeOrgDTO mid = cascadeOrgManager.getById(midId);
            CascadeOrgDTO leaf = cascadeOrgManager.getById(leafId);
            assertEquals(targetId, mid.getParentId());
            assertEquals(midCode, mid.getOrgCode(), "编码是上级建树的键，移动时绝不能变");
            assertEquals(targetPath + midCode + "/", mid.getOrgPath());
            assertEquals(mid.getOrgPath() + leafCode + "/", leaf.getOrgPath(), "孙节点路径必须跟着重写");
            assertEquals(rootBefore.getOrgPath(), cascadeOrgManager.getById(rootId).getOrgPath(),
                "原父那一支的路径不受影响");
        }

        @Test
        @DisplayName("移到根节点：父节点置空，路径回到 /编码/")
        void should_move_to_root() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            String midCode = cascadeOrgManager.getById(midId).getOrgCode();
            String leafCode = cascadeOrgManager.getById(leafId).getOrgCode();

            assertTrue(cascadeOrgManager.move(midId, null));

            CascadeOrgDTO mid = cascadeOrgManager.getById(midId);
            assertNull(mid.getParentId());
            assertEquals("/" + midCode + "/", mid.getOrgPath());
            assertEquals(mid.getOrgPath() + leafCode + "/", cascadeOrgManager.getById(leafId).getOrgPath());
            assertEquals(2, cascadeOrgManager.listSubtreeCodes(midCode, true).size());
            assertEquals(1, cascadeOrgManager.listSubtreeCodes(
                cascadeOrgManager.getById(rootId).getOrgCode(), true).size(), "原父下只剩自己");
        }

        @Test
        @DisplayName("不能移到自身或自己的子孙下，失败不留脏数据")
        void should_reject_cycle() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(midId, "三层"));
            String rootPath = cascadeOrgManager.getById(rootId).getOrgPath();

            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.move(rootId, rootId));
            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.move(rootId, leafId));

            assertEquals(rootPath, cascadeOrgManager.getById(rootId).getOrgPath(), "校验失败不能改路径");
            assertEquals(rootId, cascadeOrgManager.getById(midId).getParentId());
        }

        @Test
        @DisplayName("目标父与当前父相同时拒绝，不做无意义的重写")
        void should_reject_same_target() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));

            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.move(midId, rootId));
            assertEquals(rootId, cascadeOrgManager.getById(midId).getParentId());
        }

        @Test
        @DisplayName("通道跟随设备归属，移动组织后无需重新绑定")
        void should_keep_channel_under_moved_org() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));
            Long targetId = cascadeOrgManager.add(org(null, "另一棵树的根"));
            createChannel(createDevice(midId), null);

            assertTrue(cascadeOrgManager.move(midId, targetId));

            CascadeOrgDTO target = findRoot(cascadeOrgManager.tree(), targetId);
            assertEquals(1, target.getChildren().size());
            assertEquals(1, target.getChildren().get(0).getChannelCount(), "通道随组织走，不用重新绑定");
        }

        @Test
        @DisplayName("编辑接口拒绝改父节点，不再静默忽略")
        void update_should_reject_parent_change() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long midId = cascadeOrgManager.add(org(rootId, "二层"));

            CascadeOrgDTO patch = new CascadeOrgDTO();
            patch.setId(midId);
            patch.setParentId(rootId);

            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.update(patch));
            assertEquals(rootId, cascadeOrgManager.getById(midId).getParentId());
        }
    }

    @Nested
    @DisplayName("resolveParentCode - 本地视角的 ParentID")
    class ResolveParentCode {

        @Test
        @DisplayName("根组织父节点是域编码，子组织父节点是父组织编码")
        void should_resolve_parent_code() {
            Long rootId = cascadeOrgManager.add(org(null, "一层"));
            Long childId = cascadeOrgManager.add(org(rootId, "二层"));

            var byId = cascadeOrgManager.indexById(cascadeOrgManager.listAll());
            CascadeOrgDTO root = byId.get(rootId);
            CascadeOrgDTO child = byId.get(childId);

            assertEquals("34020000002000000001",
                cascadeOrgManager.resolveParentCode(root, byId, "34020000002000000001"));
            assertEquals(root.getOrgCode(),
                cascadeOrgManager.resolveParentCode(child, byId, "34020000002000000001"));
        }
    }
}
