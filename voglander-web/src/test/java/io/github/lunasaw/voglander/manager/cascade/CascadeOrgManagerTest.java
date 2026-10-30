package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * {@link CascadeOrgManager} 集成测试。
 *
 * <p>组织是上级目录树里的中间层，编码与父子关系一旦下发就不能回退，
 * 因此这里重点守三条：<b>编码格式与递增</b>、<b>父子关系只增不改</b>、<b>停用连带剪枝</b>。
 *
 * @author luna
 */
@DisplayName("CascadeOrgManager 集成测试")
class CascadeOrgManagerTest extends BaseTest {

    /** 平台在库里不存在时 {@code resolvePrefix} 回退到默认前缀，首个组织编码即此值 */
    private static final String DEFAULT_FIRST_CODE = "34020000002150000001";

    @Autowired
    private CascadeOrgManager cascadeOrgManager;

    @Autowired
    private CascadeChannelManager cascadeChannelManager;

    private CascadeOrgDTO org(String platformId, Long parentOrgId, String name) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setPlatformId(platformId);
        dto.setParentOrgId(parentOrgId);
        dto.setOrgName(name);
        return dto;
    }

    @Nested
    @DisplayName("编码生成")
    class NextOrgCode {

        @Test
        @DisplayName("平台未配置时用默认前缀，首个编码为 34020000002150000001")
        void should_use_default_prefix() {
            assertEquals(DEFAULT_FIRST_CODE, cascadeOrgManager.nextOrgCode(UniqueKeyFactory.deviceId()));
        }

        @Test
        @DisplayName("同平台第二个组织序号递增")
        void should_increment_serial() {
            String platformId = UniqueKeyFactory.deviceId();
            cascadeOrgManager.add(org(platformId, null, "一层"));

            assertEquals("34020000002150000002", cascadeOrgManager.nextOrgCode(platformId));
        }

        @Test
        @DisplayName("不同平台各自从 1 开始计数，互不影响")
        void should_isolate_by_platform() {
            cascadeOrgManager.add(org(UniqueKeyFactory.deviceId(), null, "一层"));

            assertEquals(DEFAULT_FIRST_CODE, cascadeOrgManager.nextOrgCode(UniqueKeyFactory.deviceId()));
        }
    }

    @Nested
    @DisplayName("add - 新增组织")
    class Add {

        @Test
        @DisplayName("编码由后端生成，长度 20 且类型位为 215")
        void should_generate_code() {
            CascadeOrgDTO dto = org(UniqueKeyFactory.deviceId(), null, "一层");
            Long id = cascadeOrgManager.add(dto);

            assertNotNull(id);
            assertEquals(DEFAULT_FIRST_CODE, dto.getCascadeOrgId());
            assertEquals(20, dto.getCascadeOrgId().length());
            assertEquals("215", dto.getCascadeOrgId().substring(10, 13));
        }

        @Test
        @DisplayName("未指定 enabled/sortOrder 时默认上报、排序 0")
        void should_default_enabled_and_sort() {
            CascadeOrgDTO dto = org(UniqueKeyFactory.deviceId(), null, "一层");
            Long id = cascadeOrgManager.add(dto);

            CascadeOrgDTO saved = cascadeOrgManager.getById(id);
            assertEquals(1, saved.getEnabled());
            assertEquals(0, saved.getSortOrder());
        }

        @Test
        @DisplayName("父组织不属于同一上级平台时拒绝")
        void should_reject_cross_platform_parent() {
            Long rootId = cascadeOrgManager.add(org(UniqueKeyFactory.deviceId(), null, "一层"));

            assertThrows(IllegalArgumentException.class,
                () -> cascadeOrgManager.add(org(UniqueKeyFactory.deviceId(), rootId, "二层")));
        }
    }

    @Nested
    @DisplayName("tree - 树形出参")
    class Tree {

        @Test
        @DisplayName("子组织挂在父节点的 children 上")
        void should_build_hierarchy() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            cascadeOrgManager.add(org(platformId, rootId, "二层"));

            List<CascadeOrgDTO> roots = cascadeOrgManager.tree(platformId);

            assertEquals(1, roots.size(), "只有一个根组织");
            CascadeOrgDTO root = roots.get(0);
            assertEquals(rootId, root.getId());
            assertEquals(1, root.getChildren().size());
            assertEquals("二层", root.getChildren().get(0).getOrgName());
        }

        @Test
        @DisplayName("直属通道数按组织编码统计")
        void should_count_direct_channels() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            CascadeOrgDTO root = cascadeOrgManager.getById(rootId);

            CascadeChannelDTO ch = new CascadeChannelDTO();
            ch.setPlatformId(platformId);
            ch.setLocalDeviceId(UniqueKeyFactory.deviceId());
            ch.setLocalChannelId(UniqueKeyFactory.channelId());
            ch.setCascadeOrgId(root.getCascadeOrgId());
            cascadeChannelManager.add(ch);

            assertEquals(1, cascadeOrgManager.tree(platformId).get(0).getChannelCount());
        }
    }

    @Nested
    @DisplayName("delete - 删除组织")
    class Delete {

        @Test
        @DisplayName("还有子组织时拒绝删除")
        void should_reject_when_child_exists() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            cascadeOrgManager.add(org(platformId, rootId, "二层"));

            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.delete(rootId));
        }

        @Test
        @DisplayName("还有绑定通道时拒绝删除")
        void should_reject_when_channel_bound() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            String orgCode = cascadeOrgManager.getById(rootId).getCascadeOrgId();

            CascadeChannelDTO ch = new CascadeChannelDTO();
            ch.setPlatformId(platformId);
            ch.setLocalDeviceId(UniqueKeyFactory.deviceId());
            ch.setLocalChannelId(UniqueKeyFactory.channelId());
            ch.setCascadeOrgId(orgCode);
            cascadeChannelManager.add(ch);

            assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.delete(rootId));
        }

        @Test
        @DisplayName("空组织可以删除")
        void should_delete_leaf() {
            Long id = cascadeOrgManager.add(org(UniqueKeyFactory.deviceId(), null, "一层"));

            assertTrue(cascadeOrgManager.delete(id));
            assertNull(cascadeOrgManager.getById(id));
        }
    }

    @Nested
    @DisplayName("listEffective / isEnabledOrg - 生效判定")
    class Effective {

        @Test
        @DisplayName("父组织停用时子组织一并剪掉，避免上级出现孤儿节点")
        void should_prune_subtree_when_parent_disabled() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            Long childId = cascadeOrgManager.add(org(platformId, rootId, "二层"));

            CascadeOrgDTO off = new CascadeOrgDTO();
            off.setId(rootId);
            off.setEnabled(0);
            assertTrue(cascadeOrgManager.update(off));

            List<CascadeOrgDTO> effective = cascadeOrgManager.listEffective(platformId);
            assertTrue(effective.isEmpty(), "父节点停用后子孙都不能下发");
            /* 子组织自身仍是 enabled=1（isEnabledOrg 只管自己），是被祖先链判定剪掉的 */
            assertTrue(cascadeOrgManager.isEnabledOrg(platformId, cascadeOrgManager.getById(childId).getCascadeOrgId()),
                "子组织自身仍处于启用状态");
            assertTrue(effective.stream().noneMatch(o -> childId.equals(o.getId())), "但因祖先停用而不下发");
        }

        @Test
        @DisplayName("子组织单独停用不影响父组织生效")
        void should_keep_parent_effective() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            Long childId = cascadeOrgManager.add(org(platformId, rootId, "二层"));

            CascadeOrgDTO off = new CascadeOrgDTO();
            off.setId(childId);
            off.setEnabled(0);
            cascadeOrgManager.update(off);

            List<CascadeOrgDTO> effective = cascadeOrgManager.listEffective(platformId);
            assertEquals(1, effective.size());
            assertEquals(rootId, effective.get(0).getId());
        }
    }

    @Nested
    @DisplayName("orgPath - 物化路径")
    class OrgPath {

        @Test
        @DisplayName("根组织路径为 /编码/，子组织路径以父编码开头")
        void should_build_path() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            Long childId = cascadeOrgManager.add(org(platformId, rootId, "二层"));

            CascadeOrgDTO root = cascadeOrgManager.getById(rootId);
            CascadeOrgDTO child = cascadeOrgManager.getById(childId);

            assertEquals("/" + root.getCascadeOrgId() + "/", root.getOrgPath());
            assertEquals(root.getOrgPath() + child.getCascadeOrgId() + "/", child.getOrgPath());
        }

        @Test
        @DisplayName("三层嵌套的路径层层包含，层数可由斜杠数推算")
        void should_nest_path_by_depth() {
            String platformId = UniqueKeyFactory.deviceId();
            Long l1 = cascadeOrgManager.add(org(platformId, null, "一层"));
            Long l2 = cascadeOrgManager.add(org(platformId, l1, "二层"));
            Long l3 = cascadeOrgManager.add(org(platformId, l2, "三层"));

            String path = cascadeOrgManager.getById(l3).getOrgPath();
            assertTrue(path.startsWith(cascadeOrgManager.getById(l1).getOrgPath()), path);
            assertTrue(path.startsWith(cascadeOrgManager.getById(l2).getOrgPath()), path);
            assertEquals(3, path.chars().filter(c -> c == '/').count() - 1, "斜杠数 - 1 = 深度");
        }
    }

    @Nested
    @DisplayName("listSubtree - 子树查询")
    class Subtree {

        /** 造一棵 三层 的树：root -> mid -> leaf，并返回编码 */
        private String[] buildThreeLevels(String platformId) {
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            Long midId = cascadeOrgManager.add(org(platformId, rootId, "二层"));
            Long leafId = cascadeOrgManager.add(org(platformId, midId, "三层"));
            return new String[] {
                cascadeOrgManager.getById(rootId).getCascadeOrgId(),
                cascadeOrgManager.getById(midId).getCascadeOrgId(),
                cascadeOrgManager.getById(leafId).getCascadeOrgId()
            };
        }

        @Test
        @DisplayName("一次取全任意层级（十几层也不需要递归 SQL）")
        void should_fetch_all_descendants() {
            String platformId = UniqueKeyFactory.deviceId();
            String[] codes = buildThreeLevels(platformId);

            List<CascadeOrgDTO> subtree = cascadeOrgManager.listSubtree(platformId, codes[0], true);

            assertEquals(3, subtree.size(), "三层都要在结果里");
            assertEquals(3, cascadeOrgManager.listSubtreeCodes(platformId, codes[0], true).size());
        }

        @Test
        @DisplayName("includeSelf=false 时不返回子树根自身")
        void should_exclude_self() {
            String platformId = UniqueKeyFactory.deviceId();
            String[] codes = buildThreeLevels(platformId);

            List<CascadeOrgDTO> children = cascadeOrgManager.listSubtree(platformId, codes[0], false);

            assertEquals(2, children.size());
            assertTrue(children.stream().noneMatch(o -> codes[0].equals(o.getCascadeOrgId())));
        }

        @Test
        @DisplayName("从中间层取子树不会带上父层")
        void should_not_include_ancestors() {
            String platformId = UniqueKeyFactory.deviceId();
            String[] codes = buildThreeLevels(platformId);

            List<CascadeOrgDTO> subtree = cascadeOrgManager.listSubtree(platformId, codes[1], true);

            assertEquals(2, subtree.size(), "中间层 + 叶子");
            assertTrue(subtree.stream().noneMatch(o -> codes[0].equals(o.getCascadeOrgId())));
        }

        @Test
        @DisplayName("组织不存在时返回空列表，不抛异常")
        void should_return_empty_for_unknown_org() {
            assertTrue(cascadeOrgManager.listSubtree(UniqueKeyFactory.deviceId(), "34020000002159999999", true).isEmpty());
        }

        @Test
        @DisplayName("tree(rootOrgId) 只返回该支，子树根成为顶层节点")
        void should_build_subtree_tree() {
            String platformId = UniqueKeyFactory.deviceId();
            String[] codes = buildThreeLevels(platformId);

            List<CascadeOrgDTO> roots = cascadeOrgManager.tree(platformId, codes[1]);

            assertEquals(1, roots.size());
            assertEquals(codes[1], roots.get(0).getCascadeOrgId());
            assertEquals(1, roots.get(0).getChildren().size());
            assertEquals(codes[2], roots.get(0).getChildren().get(0).getCascadeOrgId());
        }
    }

    @Nested
    @DisplayName("通道按子树过滤")
    class ChannelSubtree {

        private CascadeChannelDTO channel(String platformId, String orgCode) {
            CascadeChannelDTO ch = new CascadeChannelDTO();
            ch.setPlatformId(platformId);
            ch.setLocalDeviceId(UniqueKeyFactory.deviceId());
            ch.setLocalChannelId(UniqueKeyFactory.channelId());
            ch.setCascadeOrgId(orgCode);
            return ch;
        }

        @Test
        @DisplayName("includeSubOrg=true 能查到子组织下的通道")
        void should_include_child_org_channels() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            Long childId = cascadeOrgManager.add(org(platformId, rootId, "二层"));
            String rootCode = cascadeOrgManager.getById(rootId).getCascadeOrgId();
            String childCode = cascadeOrgManager.getById(childId).getCascadeOrgId();

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
    @DisplayName("resolveParentCode - 上级看到的 ParentID")
    class ResolveParentCode {

        @Test
        @DisplayName("根组织父节点是域编码，子组织父节点是父组织编码")
        void should_resolve_parent_code() {
            String platformId = UniqueKeyFactory.deviceId();
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            Long childId = cascadeOrgManager.add(org(platformId, rootId, "二层"));

            List<CascadeOrgDTO> all = cascadeOrgManager.listByPlatformId(platformId);
            Map<Long, CascadeOrgDTO> byId = cascadeOrgManager.indexById(all);
            CascadeOrgDTO root = byId.get(rootId);
            CascadeOrgDTO child = byId.get(childId);

            assertEquals("34020000002000000001",
                cascadeOrgManager.resolveParentCode(root, byId, "34020000002000000001"));
            assertEquals(root.getCascadeOrgId(),
                cascadeOrgManager.resolveParentCode(child, byId, "34020000002000000001"));
        }
    }
}
