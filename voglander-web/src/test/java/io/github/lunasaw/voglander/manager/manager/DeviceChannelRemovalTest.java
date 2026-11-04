package io.github.lunasaw.voglander.manager.manager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.config.StubCascadeCatalogRevokeService;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.service.CascadePublishService;
import io.github.lunasaw.voglander.repository.entity.CascadePublishDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * {@link DeviceChannelManager} 删除链路集成测试：主动删必须先向上级取消上报并拿到确认，
 * 被动删（跟随设备上报的 Catalog {@code DEL}）不能被上级状态挡住。
 *
 * <p><b>为什么主动与被动要分开验</b>：两者只有「谁发起」不同，代价却完全相反。
 * 主动不阻断 → 上级目录留下本地已不存在的节点，还能对着它发起点播；
 * 被动阻断 → 设备已经把通道从台账删了，本端却删不掉，与设备台账长期不一致。
 *
 * <p><b>为什么用 {@link StubCascadeCatalogRevokeService} 而不是真实出站通道</b>：
 * {@code BaseTest} 启用了 {@code sip.client.enabled}，真实实现会真发 UDP 并等满确认超时，
 * 测试里没有上级应答，所有删除都会变成「上级未响应」。桩默认放行，
 * 结论换成拒绝 / 无响应即可复现严格阻断。
 *
 * <p><b>为什么不断言 {@code DEL} 报文</b>：报文与 Call-ID 结算由 {@code CascadeNotifyAckTrackerTest}
 * 与 {@code CascadeNotifyPublisherTest} 覆盖，这里只验业务口径：删了什么、什么时候删、谁没确认。
 *
 * @author luna
 */
@DisplayName("DeviceChannelManager 删除链路集成测试")
class DeviceChannelRemovalTest extends BaseTest {

    @Autowired
    private DeviceChannelManager  deviceChannelManager;

    @Autowired
    private DeviceManager         deviceManager;

    @Autowired
    private CascadePublishManager cascadePublishManager;

    @Autowired
    private CascadePublishService cascadePublishService;

    @Autowired
    private StubCascadeCatalogRevokeService revokeStub;

    /** 桩是全容器单例，结论改了必须恢复，否则会串到其它测试类 */
    @AfterEach
    void resetRevokeStub() {
        revokeStub.reset();
    }

    /** 造一台本地设备，返回设备编码 */
    private String createDevice() {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(deviceId);
        device.setIp("127.0.0.1");
        device.setPort(5060);
        device.setType(1);
        device.setServerIp("127.0.0.1");
        device.setName("dev-通道删除");
        deviceManager.add(device);
        return deviceId;
    }

    /** 在设备下造一条通道，返回通道主键 */
    private Long createChannelRow(String deviceId, String channelId) {
        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(deviceId);
        channel.setChannelId(channelId);
        channel.setName("ch-通道删除");
        return deviceChannelManager.add(channel);
    }

    /** 在设备下造一条通道，返回通道编码 */
    private String createChannel(String deviceId) {
        String channelId = UniqueKeyFactory.channelId();
        createChannelRow(deviceId, channelId);
        return channelId;
    }

    /** 把通道发布到一个新平台，返回平台编码 */
    private String publishChannel(String deviceId, String... channelIds) {
        String platformId = UniqueKeyFactory.deviceId();
        for (String channelId : channelIds) {
            CascadePublishDTO row = new CascadePublishDTO();
            row.setPlatformId(platformId);
            row.setObjectType(PublishObjectType.CHANNEL.name());
            row.setObjectKey(PublishKeys.channel(deviceId, channelId));
            row.setPublishCode("c" + channelId);
            row.setPublishName("ch-" + channelId);
            row.setEnabled(1);
            cascadePublishManager.publish(row);
        }
        return platformId;
    }

    private boolean channelExists(String deviceId, String channelId) {
        return deviceChannelManager.getByDeviceId(deviceId, channelId) != null;
    }

    private boolean publishRowExists(String platformId, String deviceId, String channelId) {
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId)
            .eq(CascadePublishDO::getObjectType, PublishObjectType.CHANNEL.name())
            .eq(CascadePublishDO::getObjectKey, PublishKeys.channel(deviceId, channelId));
        return !cascadePublishService.list(qw).isEmpty();
    }

    private DeviceChannelDTO queryById(Long id) {
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setId(id);
        return dto;
    }

    @Test
    @DisplayName("主动删除：上级确认后，本地通道与发布行一起消失")
    void active_delete_should_remove_local_after_confirmation() {
        String deviceId = createDevice();
        String channelId = createChannel(deviceId);
        String platformId = publishChannel(deviceId, channelId);

        assertTrue(deviceChannelManager.deleteDeviceChannel(deviceId, channelId));

        assertFalse(channelExists(deviceId, channelId));
        assertFalse(publishRowExists(platformId, deviceId, channelId), "上级目录里的节点已经撤掉，发布行不能留");
        assertEquals(1, revokeStub.invocationCount());
        assertEquals(platformId, revokeStub.getInvocations().get(0).platformId());
        assertEquals(1, revokeStub.getInvocations().get(0).nodeCount());
    }

    @Test
    @DisplayName("主动删除：撤销发生在本地删除之前")
    void active_delete_should_revoke_before_removing_local_channel() {
        String deviceId = createDevice();
        String channelId = createChannel(deviceId);
        publishChannel(deviceId, channelId);

        revokeStub.setOnRevoke(invocation -> assertTrue(channelExists(deviceId, channelId),
            "上级确认前本地通道不能先删，否则失败时连重试要撤哪个节点都查不到了"));

        assertTrue(deviceChannelManager.deleteDeviceChannel(deviceId, channelId));
    }

    @Test
    @DisplayName("主动删除：上级拒绝时抛异常，本地通道与发布行原封不动")
    void active_delete_should_block_when_upstream_refuses() {
        String deviceId = createDevice();
        String channelId = createChannel(deviceId);
        String platformId = publishChannel(deviceId, channelId);
        revokeStub.refuse(403);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> deviceChannelManager.deleteDeviceChannel(deviceId, channelId));

        assertTrue(e.getMessage().contains("上级未确认取消上报"), e.getMessage());
        assertTrue(e.getMessage().contains(platformId), "要报出没确认的平台: " + e.getMessage());
        assertTrue(e.getMessage().contains("403"), "响应码要带上: " + e.getMessage());
        assertTrue(channelExists(deviceId, channelId), "上级没确认就不能删本地通道");
        assertTrue(publishRowExists(platformId, deviceId, channelId), "发布行要留着，重试还要靠它");
    }

    @Test
    @DisplayName("主动删除未上报过的通道：不去打扰上级，直接删本地")
    void active_delete_should_skip_revoke_when_never_published() {
        String deviceId = createDevice();
        String channelId = createChannel(deviceId);

        assertTrue(deviceChannelManager.deleteDeviceChannel(deviceId, channelId));

        assertFalse(channelExists(deviceId, channelId));
        assertEquals(0, revokeStub.invocationCount(), "没推上去过就不该发撤销报文");
    }

    @Test
    @DisplayName("deleteOne 同样先撤销：上级无响应时本地通道还在")
    void delete_one_should_block_when_upstream_silent() {
        String deviceId = createDevice();
        String channelId = createChannel(deviceId);
        String platformId = publishChannel(deviceId, channelId);
        revokeStub.noResponse();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> deviceChannelManager.deleteOne(queryById(
                deviceChannelManager.getByDeviceId(deviceId, channelId).getId())));

        assertTrue(e.getMessage().contains("上级未响应撤销请求"), "撤销原因不能被洗成通用失败: " + e.getMessage());
        assertTrue(channelExists(deviceId, channelId));
        assertTrue(publishRowExists(platformId, deviceId, channelId));
    }

    @Test
    @DisplayName("deleteOne 确认通过后删本地")
    void delete_one_should_remove_after_confirmation() {
        String deviceId = createDevice();
        String channelId = UniqueKeyFactory.channelId();
        Long pk = createChannelRow(deviceId, channelId);
        publishChannel(deviceId, channelId);

        assertTrue(deviceChannelManager.deleteOne(queryById(pk)));

        assertNull(deviceChannelManager.getDeviceChannelDTOById(pk), "通道行必须真删了");
        assertEquals(1, revokeStub.invocationCount());
    }

    @Test
    @DisplayName("批量删除：任一平台未确认则整批通道不动")
    void delete_batch_should_block_whole_batch_when_revoke_unconfirmed() {
        String deviceId = createDevice();
        String channelA = createChannel(deviceId);
        String channelB = createChannel(deviceId);
        String platformId = publishChannel(deviceId, channelA, channelB);
        revokeStub.noResponse();

        DeviceChannelDTO del = new DeviceChannelDTO();
        del.setDeviceId(deviceId);
        assertThrows(IllegalArgumentException.class, () -> deviceChannelManager.deleteBatch(del));

        assertTrue(channelExists(deviceId, channelA), "半成功会让用户看不出哪些已经删干净");
        assertTrue(channelExists(deviceId, channelB));
        LambdaQueryWrapper<CascadePublishDO> remain = new LambdaQueryWrapper<>();
        remain.eq(CascadePublishDO::getPlatformId, platformId);
        assertEquals(2, cascadePublishService.list(remain).size(), "两条发布行都要留着");
    }

    @Test
    @DisplayName("批量删除：确认后整批通道与发布行一起消失")
    void delete_batch_should_remove_after_confirmation() {
        String deviceId = createDevice();
        String channelA = createChannel(deviceId);
        String channelB = createChannel(deviceId);
        String platformId = publishChannel(deviceId, channelA, channelB);

        DeviceChannelDTO del = new DeviceChannelDTO();
        del.setDeviceId(deviceId);
        assertTrue(deviceChannelManager.deleteBatch(del));

        assertEquals(0, deviceChannelManager.listByDeviceIds(List.of(deviceId)).size());
        assertFalse(publishRowExists(platformId, deviceId, channelA));
        assertFalse(publishRowExists(platformId, deviceId, channelB));
        assertEquals(1, revokeStub.invocationCount(), "同一台设备的通道合并成一次撤销");
        assertEquals(2, revokeStub.getInvocations().get(0).nodeCount());
    }

    @Test
    @DisplayName("被动删除（跟随设备上报 DEL）：上级无响应也照删，本地不能卡住")
    void passive_delete_should_never_block() {
        String deviceId = createDevice();
        String channelId = createChannel(deviceId);
        String platformId = publishChannel(deviceId, channelId);
        revokeStub.noResponse();

        assertTrue(deviceChannelManager.deleteDeviceChannelUpstreamDeleted(deviceId, channelId));

        assertFalse(channelExists(deviceId, channelId), "设备台账里已经没有这路通道了，本端必须跟上");
        assertFalse(publishRowExists(platformId, deviceId, channelId), "发布行同样要清掉，否则留下指向不存在通道的记录");
        assertEquals(0, revokeStub.invocationCount(), "被动路径不做同步撤销，通知走提交后的事件链路");
    }

    @Test
    @DisplayName("通道不存在：抛业务异常，不去打扰上级")
    void delete_missing_channel_should_not_revoke() {
        String deviceId = createDevice();

        assertThrows(RuntimeException.class,
            () -> deviceChannelManager.deleteDeviceChannel(deviceId, UniqueKeyFactory.channelId()));

        assertEquals(0, revokeStub.invocationCount());
    }
}
