package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeClientDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeClientManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * CascadeClientManager 集成测试：本端客户端的唯一性、默认项互斥与被引用保护。
 *
 * <p>这些约束都是「破坏后界面上看不出错」的类型：编码重复会让两个平台抢同一个身份，
 * 两个默认项会让新增平台时选错身份，删掉被引用的客户端会让注册一律失败。
 */
@DisplayName("CascadeClientManager 集成测试")
class CascadeClientManagerTest extends BaseTest {

    @Autowired
    private CascadeClientManager   cascadeClientManager;

    @Autowired
    private CascadePlatformManager cascadePlatformManager;

    private CascadeClientDTO buildClient(String clientId) {
        CascadeClientDTO dto = new CascadeClientDTO();
        dto.setClientId(clientId);
        dto.setClientName("  测试本端  ");
        dto.setClientIp("192.168.118.7");
        return dto;
    }

    @Nested
    @DisplayName("add - 新增本端客户端")
    class Add {

        @Test
        @DisplayName("新增返回自增 ID，名称 trim 后落库")
        void should_add_and_trim_name() {
            Long id = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));

            assertNotNull(id);
            assertTrue(id > 0);
            CascadeClientDTO saved = cascadeClientManager.getById(id);
            assertEquals("测试本端", saved.getClientName());
            assertEquals("192.168.118.7", saved.getClientIp());
            assertEquals(1, saved.getEnabled());
        }

        @Test
        @DisplayName("编码重复应拒绝")
        void should_reject_duplicate_client_id() {
            String clientId = UniqueKeyFactory.gbId();
            cascadeClientManager.add(buildClient(clientId));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cascadeClientManager.add(buildClient(clientId)));
            assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
        }

        @Test
        @DisplayName("编码非 20 位数字应拒绝")
        void should_reject_malformed_client_id() {
            assertThrows(IllegalArgumentException.class,
                () -> cascadeClientManager.add(buildClient(UniqueKeyFactory.deviceId())));
        }

        @Test
        @DisplayName("新增默认项应清掉原有默认标记（全局唯一）")
        void should_keep_single_default() {
            CascadeClientDTO first = buildClient(UniqueKeyFactory.gbId());
            first.setIsDefault(1);
            Long firstId = cascadeClientManager.add(first);

            CascadeClientDTO second = buildClient(UniqueKeyFactory.gbId());
            second.setIsDefault(1);
            Long secondId = cascadeClientManager.add(second);

            assertEquals(0, cascadeClientManager.getById(firstId).getIsDefault());
            assertEquals(1, cascadeClientManager.getById(secondId).getIsDefault());
        }
    }

    @Nested
    @DisplayName("update - 更新本端客户端")
    class Update {

        @Test
        @DisplayName("修改平台编码应拒绝（换编码等于换身份）")
        void should_reject_changing_client_id() {
            String clientId = UniqueKeyFactory.gbId();
            Long id = cascadeClientManager.add(buildClient(clientId));

            CascadeClientDTO patch = new CascadeClientDTO();
            patch.setId(id);
            patch.setClientId(UniqueKeyFactory.gbId());
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cascadeClientManager.update(patch));
            assertTrue(ex.getMessage().contains("不可修改"), ex.getMessage());
            assertEquals(clientId, cascadeClientManager.getById(id).getClientId());
        }

        @Test
        @DisplayName("只提交 IP 时改名不动名称（patch 语义）")
        void should_patch_ip_only() {
            Long id = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));

            CascadeClientDTO patch = new CascadeClientDTO();
            patch.setId(id);
            patch.setClientIp("10.0.0.5");
            assertTrue(cascadeClientManager.update(patch));

            CascadeClientDTO saved = cascadeClientManager.getById(id);
            assertEquals("10.0.0.5", saved.getClientIp());
            assertEquals("测试本端", saved.getClientName());
        }

        @Test
        @DisplayName("提交空串应清空名称")
        void should_clear_name_with_empty_string() {
            Long id = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));

            CascadeClientDTO patch = new CascadeClientDTO();
            patch.setId(id);
            patch.setClientName("");
            cascadeClientManager.update(patch);

            assertEquals("", cascadeClientManager.getById(id).getClientName());
        }
    }

    @Nested
    @DisplayName("setDefault - 切换默认项")
    class SetDefault {

        @Test
        @DisplayName("切换后只有一个默认项")
        void should_switch_default_exclusively() {
            Long a = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));
            Long b = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));
            assertTrue(cascadeClientManager.setDefault(a));

            assertEquals(1, cascadeClientManager.getById(a).getIsDefault());
            assertEquals(0, cascadeClientManager.getById(b).getIsDefault());

            cascadeClientManager.setDefault(b);
            assertEquals(0, cascadeClientManager.getById(a).getIsDefault());
            assertEquals(1, cascadeClientManager.getById(b).getIsDefault());
        }

        @Test
        @DisplayName("列表里默认项排最前")
        void should_list_default_first() {
            Long plain = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));
            Long defaulted = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));
            cascadeClientManager.setDefault(defaulted);

            List<CascadeClientDTO> all = cascadeClientManager.listAll(false);
            assertEquals(defaulted, all.get(0).getId());
            assertTrue(all.stream().anyMatch(c -> c.getId().equals(plain)));
        }
    }

    @Nested
    @DisplayName("delete - 删除本端客户端")
    class Delete {

        @Test
        @DisplayName("仍被平台引用时拒绝删除")
        void should_refuse_delete_when_referenced() {
            Long clientRefId = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));

            CascadePlatformDTO platform = new CascadePlatformDTO();
            platform.setPlatformId(UniqueKeyFactory.deviceId());
            platform.setPlatformIp("192.168.1.100");
            platform.setPlatformPort(5060);
            /* platform_domain 是 NOT NULL：新增平台时由 Web 层兜底成 platformId 前 10 位，这里直接给值 */
            platform.setPlatformDomain("3402000000");
            platform.setClientRefId(clientRefId);
            cascadePlatformManager.add(platform);

            assertEquals(1, cascadeClientManager.countPlatformRefs(clientRefId));
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> cascadeClientManager.delete(clientRefId));
            assertTrue(ex.getMessage().contains("引用"), ex.getMessage());
            assertNotNull(cascadeClientManager.getById(clientRefId));
        }

        @Test
        @DisplayName("无引用时可删除")
        void should_delete_when_unreferenced() {
            Long id = cascadeClientManager.add(buildClient(UniqueKeyFactory.gbId()));

            assertTrue(cascadeClientManager.delete(id));
            assertNull(cascadeClientManager.getById(id));
        }
    }

    @Nested
    @DisplayName("getByClientId - 按编码反查")
    class GetByClientId {

        @Test
        @DisplayName("按编码命中，首尾空白被忽略")
        void should_hit_by_client_id() {
            String clientId = UniqueKeyFactory.gbId();
            cascadeClientManager.add(buildClient(clientId));

            CascadeClientDTO found = cascadeClientManager.getByClientId("  " + clientId + "  ");
            assertNotNull(found);
            assertEquals(clientId, found.getClientId());
        }

        @Test
        @DisplayName("编码不存在时返回 null")
        void should_return_null_for_unknown_client_id() {
            assertNull(cascadeClientManager.getByClientId(UniqueKeyFactory.gbId()));
        }
    }
}
