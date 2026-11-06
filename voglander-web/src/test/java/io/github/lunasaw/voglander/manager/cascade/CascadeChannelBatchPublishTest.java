package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelBatchPublishDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelBatchPublishResultDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 批量推送：一批本地通道 × 一批上级平台。
 *
 * <p>守四条语义（都是「用户看不出来就只能靠猜」的地方）：
 * <ul>
 *   <li>多平台：一次请求要真的落到每个平台的发布行上，而不是只推第一个；</li>
 *   <li>组织范围：{@code includeChildren} 决定要不要带下级组织的设备——通道行上的 {@code org_id}
 *       已停写（恒 NULL），只能先按设备组织算设备集合；</li>
 *   <li>已存在只跳过：正在上报的算「跳过」，已停报的单独计数且<b>不自动改回上报</b>；</li>
 *   <li>失败要归因：组织不在该平台目录里、本地通道不存在，都要逐条回原因，不能只回一个数字。</li>
 * </ul>
 *
 * @author luna
 */
@DisplayName("CascadeChannelManager 批量推送")
class CascadeChannelBatchPublishTest extends BaseTest {

    @Autowired
    private CascadeChannelManager cascadeChannelManager;

    @Autowired
    private CascadeOrgManager     cascadeOrgManager;

    @Autowired
    private DeviceManager         deviceManager;

    @Autowired
    private DeviceChannelManager  deviceChannelManager;

    // ------------------------------------------------------------------ 用例

    @Nested
    @DisplayName("平台多选")
    class MultiplePlatforms {

        @Test
        @DisplayName("一次请求推给两个平台，两边都要有发布行")
        void should_publish_to_every_platform() {
            String platformA = UniqueKeyFactory.deviceId();
            String platformB = UniqueKeyFactory.deviceId();
            Channel channel = createChannelWithDevice(null);

            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(
                request(List.of(platformA, platformB), List.of(ref(channel))));

            assertEquals(1, result.getChannelCount());
            assertEquals(2, result.getPlatformCount());
            assertEquals(2, result.getCreated(), "两个平台各建一条发布行");
            assertTrue(result.getFailures().isEmpty(), "不该有失败: " + result.getFailures());
            assertNotNull(cascadeChannelManager.getByPlatformAndChannel(platformA, channel.channelId()));
            assertNotNull(cascadeChannelManager.getByPlatformAndChannel(platformB, channel.channelId()));
        }

        @Test
        @DisplayName("重复的平台编码只处理一次")
        void should_dedupe_platforms() {
            String platformId = UniqueKeyFactory.deviceId();
            Channel channel = createChannelWithDevice(null);

            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(
                request(List.of(platformId, platformId, " "), List.of(ref(channel))));

            assertEquals(1, result.getPlatformCount());
            assertEquals(1, result.getCreated());
            assertTrue(result.getFailures().isEmpty(), "同平台处理两次会撞重复校验: " + result.getFailures());
        }
    }

    @Nested
    @DisplayName("已存在的发布行")
    class ExistingRows {

        @Test
        @DisplayName("已在上报的跳过、缺的补上")
        void should_skip_existing_and_create_missing() {
            String platformA = UniqueKeyFactory.deviceId();
            String platformB = UniqueKeyFactory.deviceId();
            Channel channel = createChannelWithDevice(null);
            cascadeChannelManager.add(channelDto(platformA, channel));

            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(
                request(List.of(platformA, platformB), List.of(ref(channel))));

            assertEquals(1, result.getCreated());
            assertEquals(1, result.getSkippedEnabled());
            assertEquals(0, result.getSkippedDisabled());
            assertTrue(result.getFailures().isEmpty());
            assertNotNull(cascadeChannelManager.getByPlatformAndChannel(platformB, channel.channelId()));
        }

        @Test
        @DisplayName("已停报的只跳过，不静默改回上报")
        void should_not_reenable_revoked_row() {
            String platformId = UniqueKeyFactory.deviceId();
            Channel channel = createChannelWithDevice(null);
            Long publishId = cascadeChannelManager.add(channelDto(platformId, channel));
            cascadeChannelManager.revoke(publishId);

            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(
                request(List.of(platformId), List.of(ref(channel))));

            assertEquals(0, result.getCreated());
            assertEquals(1, result.getSkippedDisabled());
            assertEquals(0, cascadeChannelManager.getById(publishId).getEnabled(),
                "停报往往是上级要求下线，批量推送不能替用户把它改回来");
        }

        @Test
        @DisplayName("同一通道重复提交只处理一次")
        void should_dedupe_channels() {
            String platformId = UniqueKeyFactory.deviceId();
            Channel channel = createChannelWithDevice(null);

            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(
                request(List.of(platformId), List.of(ref(channel), ref(channel))));

            assertEquals(1, result.getChannelCount());
            assertEquals(1, result.getCreated());
            assertTrue(result.getFailures().isEmpty(), "第二次会撞重复校验: " + result.getFailures());
        }
    }

    @Nested
    @DisplayName("按组织取通道")
    class OrgScope {

        @Test
        @DisplayName("含下级：父组织与子组织设备的通道一起推")
        void should_include_descendant_channels() {
            String platformId = UniqueKeyFactory.deviceId();
            Long parentOrgId = createOrg(platformId, null);
            Long childOrgId = createOrg(platformId, parentOrgId);
            Channel parentChannel = createChannelWithDevice(parentOrgId);
            Channel childChannel = createChannelWithDevice(childOrgId);

            CascadeChannelBatchPublishDTO request = request(List.of(platformId), null);
            request.setOrgId(parentOrgId);
            request.setIncludeChildren(true);
            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(request);

            assertEquals(2, result.getChannelCount());
            assertEquals(2, result.getCreated());
            assertTrue(result.getFailures().isEmpty(), "组织链都在报，不该被拦: " + result.getFailures());
            assertNotNull(cascadeChannelManager.getByPlatformAndChannel(platformId, parentChannel.channelId()));
            assertNotNull(cascadeChannelManager.getByPlatformAndChannel(platformId, childChannel.channelId()));
        }

        @Test
        @DisplayName("仅本级：下级组织的通道不参与")
        void should_exclude_descendant_channels_when_self_only() {
            String platformId = UniqueKeyFactory.deviceId();
            Long parentOrgId = createOrg(platformId, null);
            Long childOrgId = createOrg(platformId, parentOrgId);
            Channel parentChannel = createChannelWithDevice(parentOrgId);
            Channel childChannel = createChannelWithDevice(childOrgId);

            CascadeChannelBatchPublishDTO request = request(List.of(platformId), null);
            request.setOrgId(parentOrgId);
            request.setIncludeChildren(false);
            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(request);

            assertEquals(1, result.getChannelCount());
            assertEquals(1, result.getCreated());
            assertNotNull(cascadeChannelManager.getByPlatformAndChannel(platformId, parentChannel.channelId()));
            assertNull(cascadeChannelManager.getByPlatformAndChannel(platformId, childChannel.channelId()),
                "「仅本级」不该把下级组织的通道推上去");
        }

        @Test
        @DisplayName("组织下没有设备时返回零，不抛异常")
        void should_return_empty_when_org_has_no_channel() {
            String platformId = UniqueKeyFactory.deviceId();
            Long orgId = createOrg(platformId, null);

            CascadeChannelBatchPublishDTO request = request(List.of(platformId), null);
            request.setOrgId(orgId);
            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(request);

            assertEquals(0, result.getChannelCount());
            assertEquals(0, result.getCreated());
            assertTrue(result.getFailures().isEmpty());
        }
    }

    @Nested
    @DisplayName("失败归因")
    class Failures {

        @Test
        @DisplayName("组织不在该平台目录里：失败一条，其余通道照常推送")
        void should_report_channel_whose_org_is_not_published() {
            String platformId = UniqueKeyFactory.deviceId();
            String otherPlatformId = UniqueKeyFactory.deviceId();
            Long visibleOrgId = createOrg(platformId, null);
            /* 组织只发布到另一个平台：在 platformId 的目录里立不起来 */
            Long hiddenOrgId = createOrg(otherPlatformId, null);
            Channel visible = createChannelWithDevice(visibleOrgId);
            Channel hidden = createChannelWithDevice(hiddenOrgId);

            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(
                request(List.of(platformId), List.of(ref(visible), ref(hidden))));

            assertEquals(1, result.getCreated(), "同批里能推的照常推");
            assertEquals(1, result.getFailures().size());
            CascadeChannelBatchPublishResultDTO.Failure failure = result.getFailures().get(0);
            assertEquals(platformId, failure.getPlatformId());
            assertEquals(hidden.channelId(), failure.getLocalChannelId());
            assertTrue(failure.getReason().contains("不会出现在平台"),
                "原因要能指向组织发布，而不是一句空话: " + failure.getReason());
            assertNull(cascadeChannelManager.getByPlatformAndChannel(platformId, hidden.channelId()),
                "被拒的通道不能留下半条发布行");
        }

        @Test
        @DisplayName("本地通道不存在：逐条报「本地通道不存在」，不造孤儿发布行")
        void should_report_missing_local_channel() {
            String platformId = UniqueKeyFactory.deviceId();
            Channel valid = createChannelWithDevice(null);
            CascadeChannelBatchPublishDTO.ChannelRef ghost = new CascadeChannelBatchPublishDTO.ChannelRef();
            ghost.setLocalChannelId(UniqueKeyFactory.channelId());

            CascadeChannelBatchPublishResultDTO result = cascadeChannelManager.batchPublish(
                request(List.of(platformId), List.of(ref(valid), ghost)));

            assertEquals(1, result.getChannelCount(), "只算真实存在的通道");
            assertEquals(1, result.getCreated());
            assertEquals(1, result.getFailures().size());
            CascadeChannelBatchPublishResultDTO.Failure failure = result.getFailures().get(0);
            assertNull(failure.getPlatformId(), "与平台无关的失败不带平台");
            assertEquals("本地通道不存在", failure.getReason());
        }

        @Test
        @DisplayName("没给平台、也没给目标通道时直接拒绝")
        void should_reject_empty_request() {
            assertThrows(IllegalArgumentException.class, () -> cascadeChannelManager.batchPublish(
                request(List.of(), null)));
            assertThrows(IllegalArgumentException.class, () -> cascadeChannelManager.batchPublish(
                request(List.of(UniqueKeyFactory.deviceId()), List.of())));
        }
    }

    // ------------------------------------------------------------------ 夹具

    /** 一条本地通道与它所属的设备（发布行业务键需要设备段）。 */
    private record Channel(String deviceId, String channelId) {
    }

    private CascadeChannelBatchPublishDTO request(List<String> platformIds,
        List<CascadeChannelBatchPublishDTO.ChannelRef> channels) {
        CascadeChannelBatchPublishDTO request = new CascadeChannelBatchPublishDTO();
        request.setPlatformIds(platformIds);
        request.setChannels(channels);
        return request;
    }

    private CascadeChannelBatchPublishDTO.ChannelRef ref(Channel channel) {
        CascadeChannelBatchPublishDTO.ChannelRef ref = new CascadeChannelBatchPublishDTO.ChannelRef();
        ref.setLocalDeviceId(channel.deviceId());
        ref.setLocalChannelId(channel.channelId());
        return ref;
    }

    private CascadeChannelDTO channelDto(String platformId, Channel channel) {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setPlatformId(platformId);
        dto.setLocalDeviceId(channel.deviceId());
        dto.setLocalChannelId(channel.channelId());
        /* 编码留空 → 走编码规则派生，与批量推送的入参形状一致 */
        return dto;
    }

    /** 组织建的时候就带上发布范围：不发布的组织在上级目录里立不起来，批量推送会被拦。 */
    private Long createOrg(String platformId, Long parentId) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setParentId(parentId);
        dto.setOrgName("批量推送-" + UniqueKeyFactory.deviceId());
        dto.setPlatformIds(platformId == null ? null : List.of(platformId));
        return cascadeOrgManager.add(dto);
    }

    /**
     * 造一台设备（可挂组织）与它名下的一条通道。
     *
     * @param orgId 设备归属组织；{@code null} 表示不归组织（通道会挂域节点）
     */
    private Channel createChannelWithDevice(Long orgId) {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(deviceId);
        device.setIp("127.0.0.1");
        device.setPort(5060);
        device.setType(1);
        device.setServerIp("127.0.0.1");
        device.setName("dev-批量推送");
        device.setOrgId(orgId);
        deviceManager.add(device);

        String channelId = UniqueKeyFactory.channelId();
        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(deviceId);
        channel.setChannelId(channelId);
        channel.setName("ch-批量推送");
        deviceChannelManager.add(channel);
        return new Channel(deviceId, channelId);
    }
}
