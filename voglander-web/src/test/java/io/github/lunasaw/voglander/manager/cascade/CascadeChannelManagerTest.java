package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * CascadeChannelManager Stage 1 集成测试（TDD 先红后绿）。
 *
 * <p><b>组织为什么建的时候就带 {@code platformIds}</b>：1.10.1 起组织是本地资产，
 * 通道写入前要校验「归属组织会不会出现在该平台的目录里」，而目录由发布关系决定。
 * 测试里的平台编码由 {@link UniqueKeyFactory} 逐用例生成、全局唯一，因此不会互相污染。
 */
@DisplayName("CascadeChannelManager 集成测试")
class CascadeChannelManagerTest extends BaseTest {

    @Autowired
    private CascadeChannelManager cascadeChannelManager;

    @Autowired
    private CascadeOrgManager cascadeOrgManager;

    @Autowired
    private DeviceManager         deviceManager;

    @Autowired
    private DeviceChannelManager  deviceChannelManager;

    private CascadeOrgDTO org(String platformId, Long parentId, String name) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setParentId(parentId);
        dto.setOrgName(name);
        /* 建组织时就把发布范围定下来：不发布的组织在上级目录里不存在，通道挂上去一定会被拦 */
        dto.setPlatformIds(platformId == null ? null : List.of(platformId));
        return dto;
    }

    /**
     * 造一条「本地真实存在」的通道 + 它的发布配置。
     *
     * <p>发布行只认库里真有的通道：随便编个 deviceId 会被
     * {@code CascadePublishedAssetResolver} 当孤儿丢弃，反查与分页全部拿不到数据。
     */
    private CascadeChannelDTO buildDto(String platformId, String localChannelId) {
        String deviceId = createLocalChannel(localChannelId);
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setPlatformId(platformId);
        dto.setLocalDeviceId(deviceId);
        dto.setLocalChannelId(localChannelId);
        dto.setCascadeChannelId(localChannelId); // 默认同 localChannelId
        return dto;
    }

    /** 按指定通道编码造一台设备 + 一条通道，返回设备编码（发布行业务键的设备段）。 */
    private String createLocalChannel(String channelId) {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(deviceId);
        device.setIp("127.0.0.1");
        device.setPort(5060);
        device.setType(1);
        device.setServerIp("127.0.0.1");
        device.setName("dev-通道发布");
        deviceManager.add(device);

        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(deviceId);
        channel.setChannelId(channelId);
        channel.setName("ch-通道发布");
        deviceChannelManager.add(channel);
        return deviceId;
    }

    @Nested
    @DisplayName("add - 新增级联通道")
    class Add {

        @Test
        @DisplayName("最小合法 DTO 应返回 ID")
        void should_return_id() {
            Long id = cascadeChannelManager.add(buildDto(UniqueKeyFactory.deviceId(), UniqueKeyFactory.channelId()));
            assertNotNull(id);
            assertTrue(id > 0);
        }

        @Test
        @DisplayName("新增后 enabled=1 默认上报")
        void should_default_enabled() {
            String platformId = UniqueKeyFactory.deviceId();
            String channelId = UniqueKeyFactory.channelId();
            cascadeChannelManager.add(buildDto(platformId, channelId));

            CascadeChannelDTO saved = cascadeChannelManager.getByPlatformAndChannel(platformId, channelId);
            assertNotNull(saved);
            assertEquals(1, saved.getEnabled());
        }

        @Test
        @DisplayName("编码留空时按编码规则派生，当前等价于本地通道编码")
        void should_derive_code_when_blank() {
            String platformId = UniqueKeyFactory.deviceId();
            String channelId = UniqueKeyFactory.channelId();
            String deviceId = createLocalChannel(channelId);

            CascadeChannelDTO dto = new CascadeChannelDTO();
            dto.setPlatformId(platformId);
            dto.setLocalDeviceId(deviceId);
            dto.setLocalChannelId(channelId);
            cascadeChannelManager.add(dto);

            CascadeChannelDTO saved = cascadeChannelManager.getByPlatformAndChannel(platformId, channelId);
            assertNotNull(saved);
            assertEquals(channelId, saved.getCascadeChannelId(), "规则未启用时上报编码应等于本地通道编码");
        }

        @Test
        @DisplayName("(platformId, localChannelId) 重复应抛出异常")
        void should_throw_on_duplicate() {
            String platformId = UniqueKeyFactory.deviceId();
            String channelId = UniqueKeyFactory.channelId();
            CascadeChannelDTO first = buildDto(platformId, channelId);
            cascadeChannelManager.add(first);
            /* 同一本地通道再配一次：业务键相同，上报编码也相同，两条唯一键都会拦 */
            CascadeChannelDTO second = new CascadeChannelDTO();
            second.setPlatformId(platformId);
            second.setLocalDeviceId(first.getLocalDeviceId());
            second.setLocalChannelId(channelId);
            assertThrows(Exception.class, () -> cascadeChannelManager.add(second));
        }
    }

    @Nested
    @DisplayName("listEnabledChannels - 查询平台目录里会上报的通道")
    class ListByPlatform {

        @Test
        @DisplayName("应返回同一 platformId 下所有 enabled 通道")
        void should_list_by_platform() {
            String platformId = UniqueKeyFactory.deviceId();
            cascadeChannelManager.add(buildDto(platformId, UniqueKeyFactory.channelId()));
            cascadeChannelManager.add(buildDto(platformId, UniqueKeyFactory.channelId()));
            // 不同 platform 的不应被查到
            cascadeChannelManager.add(buildDto(UniqueKeyFactory.deviceId(), UniqueKeyFactory.channelId()));

            java.util.List<CascadeChannelDTO> list = cascadeChannelManager.listEnabledChannels(platformId);
            assertEquals(2, list.size());
        }
    }

    @Nested
    @DisplayName("revoke / publish - 单条通道取消与恢复同步")
    class Revoke {

        @Test
        @DisplayName("取消同步保留记录，只把 enabled 置 0")
        void revoke_should_keep_record() {
            String platformId = UniqueKeyFactory.deviceId();
            String channelId = UniqueKeyFactory.channelId();
            Long id = cascadeChannelManager.add(buildDto(platformId, channelId));

            assertTrue(cascadeChannelManager.revoke(id));

            CascadeChannelDTO saved = cascadeChannelManager.getByPlatformAndChannel(platformId, channelId);
            assertNotNull(saved, "取消同步不是删除，记录必须还在");
            assertEquals(0, saved.getEnabled());
        }

        @Test
        @DisplayName("取消同步幂等：重复调用不报错")
        void revoke_should_be_idempotent() {
            Long id = cascadeChannelManager.add(buildDto(UniqueKeyFactory.deviceId(), UniqueKeyFactory.channelId()));

            assertTrue(cascadeChannelManager.revoke(id));
            assertTrue(cascadeChannelManager.revoke(id));
            assertEquals(0, cascadeChannelManager.getById(id).getEnabled());
        }

        @Test
        @DisplayName("恢复同步：无组织归属时直接恢复上报")
        void publish_should_restore_without_org() {
            Long id = cascadeChannelManager.add(buildDto(UniqueKeyFactory.deviceId(), UniqueKeyFactory.channelId()));
            cascadeChannelManager.revoke(id);

            assertTrue(cascadeChannelManager.publish(id));

            assertEquals(1, cascadeChannelManager.getById(id).getEnabled());
        }

        @Test
        @DisplayName("update：enabled 1→0 与 revoke 等价，只改字段不删记录")
        void update_should_toggle_enabled_without_deleting() {
            String platformId = UniqueKeyFactory.deviceId();
            String channelId = UniqueKeyFactory.channelId();
            Long id = cascadeChannelManager.add(buildDto(platformId, channelId));

            CascadeChannelDTO patch = new CascadeChannelDTO();
            patch.setId(id);
            patch.setEnabled(0);
            assertTrue(cascadeChannelManager.update(patch));

            assertNotNull(cascadeChannelManager.getByPlatformAndChannel(platformId, channelId));
            assertEquals(0, cascadeChannelManager.getById(id).getEnabled());
        }
    }

    @Nested
    @DisplayName("组织停报 - 通道上报前必须确认所属组织在上级可见")
    class OrgGate {

        /** 一层根组织 → 二层子组织，返回 [rootId, childId] */
        private Long[] twoLevelOrg(String platformId) {
            Long rootId = cascadeOrgManager.add(org(platformId, null, "一层"));
            Long childId = cascadeOrgManager.add(org(platformId, rootId, "二层"));
            return new Long[] {rootId, childId};
        }

        @Test
        @DisplayName("组织自身停报时拒绝恢复上报，记录保持停报")
        void publish_should_reject_when_org_revoked() {
            String platformId = UniqueKeyFactory.deviceId();
            Long orgId = cascadeOrgManager.add(org(platformId, null, "一层"));

            CascadeChannelDTO dto = buildDto(platformId, UniqueKeyFactory.channelId());
            dto.setCascadeOrgId(cascadeOrgManager.getById(orgId).getOrgCode());
            Long channelId = cascadeChannelManager.add(dto);
            cascadeChannelManager.revoke(channelId);
            cascadeOrgManager.disable(orgId);

            assertThrows(IllegalArgumentException.class, () -> cascadeChannelManager.publish(channelId));
            assertEquals(0, cascadeChannelManager.getById(channelId).getEnabled(),
                "被拒绝的恢复不能悄悄把开关改成上报");
        }

        @Test
        @DisplayName("上级组织停报时同样拒绝：子组织开关仍是 1，但在上级目录已被剪掉")
        void publish_should_reject_when_ancestor_revoked() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] orgs = twoLevelOrg(platformId);
            String childCode = cascadeOrgManager.getById(orgs[1]).getOrgCode();

            CascadeChannelDTO dto = buildDto(platformId, UniqueKeyFactory.channelId());
            dto.setCascadeOrgId(childCode);
            Long channelId = cascadeChannelManager.add(dto);
            cascadeChannelManager.revoke(channelId);
            cascadeOrgManager.disable(orgs[0]);

            assertEquals(1, cascadeOrgManager.getById(orgs[1]).getEnabled(), "前提：子组织自身开关还是上报");
            assertThrows(IllegalArgumentException.class, () -> cascadeChannelManager.publish(channelId));
            assertEquals(0, cascadeChannelManager.getById(channelId).getEnabled());
        }

        @Test
        @DisplayName("update 把 enabled 从 0 改回 1 走同一条校验")
        void update_should_reject_restore_when_ancestor_revoked() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] orgs = twoLevelOrg(platformId);

            CascadeChannelDTO dto = buildDto(platformId, UniqueKeyFactory.channelId());
            dto.setCascadeOrgId(cascadeOrgManager.getById(orgs[1]).getOrgCode());
            Long channelId = cascadeChannelManager.add(dto);
            cascadeChannelManager.revoke(channelId);
            cascadeOrgManager.disable(orgs[0]);

            CascadeChannelDTO patch = new CascadeChannelDTO();
            patch.setId(channelId);
            patch.setEnabled(1);
            assertThrows(IllegalArgumentException.class, () -> cascadeChannelManager.update(patch));
            assertEquals(0, cascadeChannelManager.getById(channelId).getEnabled());
        }

        @Test
        @DisplayName("新增通道绑定到链上停报的组织时拒绝")
        void add_should_reject_channel_bound_to_revoked_org() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] orgs = twoLevelOrg(platformId);
            cascadeOrgManager.disable(orgs[0]);

            CascadeChannelDTO dto = buildDto(platformId, UniqueKeyFactory.channelId());
            dto.setCascadeOrgId(cascadeOrgManager.getById(orgs[1]).getOrgCode());

            assertThrows(IllegalArgumentException.class, () -> cascadeChannelManager.add(dto));
            assertNull(cascadeChannelManager.getByPlatformAndChannel(platformId, dto.getLocalChannelId()),
                "拒绝后不能留下半条记录");
        }

        @Test
        @DisplayName("整条链都在报时绑定与恢复都放行")
        void should_allow_when_chain_published() {
            String platformId = UniqueKeyFactory.deviceId();
            Long[] orgs = twoLevelOrg(platformId);

            CascadeChannelDTO dto = buildDto(platformId, UniqueKeyFactory.channelId());
            dto.setCascadeOrgId(cascadeOrgManager.getById(orgs[1]).getOrgCode());
            Long channelId = cascadeChannelManager.add(dto);
            cascadeChannelManager.revoke(channelId);

            assertTrue(cascadeChannelManager.publish(channelId));
            assertEquals(1, cascadeChannelManager.getById(channelId).getEnabled());
        }
    }

    @Nested
    @DisplayName("deleteOne - 删除")
    class Delete {

        @Test
        @DisplayName("按 platformId+localChannelId 删除后查不到")
        void should_delete() {
            String platformId = UniqueKeyFactory.deviceId();
            String channelId = UniqueKeyFactory.channelId();
            cascadeChannelManager.add(buildDto(platformId, channelId));

            CascadeChannelDTO dto = new CascadeChannelDTO();
            dto.setPlatformId(platformId);
            dto.setLocalChannelId(channelId);
            assertTrue(cascadeChannelManager.deleteOne(dto));

            assertNull(cascadeChannelManager.getByPlatformAndChannel(platformId, channelId));
        }
    }
}
