package io.github.lunasaw.voglander.manager.manager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
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
 * {@link DeviceRemovalManager} 集成测试：删设备必须连带删掉本地通道与级联发布关系。
 *
 * <p><b>为什么走真实的管理器而不是 mock</b>：这条规则的坑全在「顺序」上——发布层先清、本地通道再删、
 * 设备最后删，任何一步换序都会让通道变成选不中的孤儿；只有真库才能验证删完确实查不到。
 *
 * <p><b>为什么不需要清库</b>：基类事务在用例结束后回滚；{@code afterCommit} 的 DEL 事件不会触发，
 * 这里也不断言事件（事件侧由 {@code CascadeAssetCleanupTest} 覆盖）。
 *
 * <p><b>上级确认靠 {@link StubCascadeCatalogRevokeService} 桩</b>：删设备现在会先向上级取消上报并
 * 等其确认，确认后才动本地。桩默认放行，把结论换成拒绝 / 无响应即可验证「未确认则本地一条不删」。
 *
 * @author luna
 */
@DisplayName("DeviceRemovalManager 集成测试")
class DeviceRemovalManagerTest extends BaseTest {

    @Autowired
    private DeviceRemovalManager deviceRemovalManager;

    @Autowired
    private DeviceManager        deviceManager;

    @Autowired
    private DeviceChannelManager deviceChannelManager;

    @Autowired
    private CascadePublishManager cascadePublishManager;

    @Autowired
    private CascadePublishService cascadePublishService;

    /** 上级撤销通道的测试桩；默认「已确认」 */
    @Autowired
    private StubCascadeCatalogRevokeService revokeStub;

    /** 桩是全容器单例，结论改了必须恢复，否则会串到其它测试类 */
    @AfterEach
    void resetRevokeStub() {
        revokeStub.reset();
    }

    /** 造一台设备，返回设备编码 */
    private DeviceDTO createDevice() {
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(UniqueKeyFactory.deviceId());
        device.setIp("127.0.0.1");
        device.setPort(5060);
        device.setType(1);
        device.setServerIp("127.0.0.1");
        device.setName("dev-删除级联");
        deviceManager.add(device);
        return deviceManager.getDtoByDeviceId(device.getDeviceId());
    }

    private void createChannel(String deviceId, String channelId) {
        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(deviceId);
        channel.setChannelId(channelId);
        channel.setName("ch-删除级联");
        deviceChannelManager.add(channel);
    }

    /** 把设备与它的通道都发布到一个新平台，返回平台编码 */
    private String publish(String deviceId, List<String> channelIds) {
        String platformId = UniqueKeyFactory.deviceId();
        for (String channelId : channelIds) {
            CascadePublishDTO row = new CascadePublishDTO();
            row.setPlatformId(platformId);
            row.setObjectType(PublishObjectType.CHANNEL.name());
            row.setObjectKey(PublishKeys.channel(deviceId, channelId));
            /* 配了编码覆盖才能验证「发布行确实被删」，没覆盖的行也能删，但看不出差别 */
            row.setPublishCode("c" + channelId);
            row.setPublishName("ch-" + channelId);
            cascadePublishManager.publish(row);
        }
        CascadePublishDTO deviceRow = new CascadePublishDTO();
        deviceRow.setPlatformId(platformId);
        deviceRow.setObjectType(PublishObjectType.DEVICE.name());
        deviceRow.setObjectKey(deviceId);
        cascadePublishManager.publish(deviceRow);
        return platformId;
    }

    private List<CascadePublishDO> publishRowsOfPlatform(String platformId) {
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId);
        return cascadePublishService.list(qw);
    }

    @Test
    @DisplayName("删设备：本地通道与发布行一起消失，其它设备的通道不受影响")
    void should_remove_channels_and_publish_rows_with_device() {
        DeviceDTO device = createDevice();
        String channelA = UniqueKeyFactory.channelId();
        String channelB = UniqueKeyFactory.channelId();
        createChannel(device.getDeviceId(), channelA);
        createChannel(device.getDeviceId(), channelB);
        String platformId = publish(device.getDeviceId(), List.of(channelA, channelB));

        /* 另一台设备：它的通道与发布关系必须原样保留，用来证明删除条件带上了 deviceId */
        DeviceDTO other = createDevice();
        String otherChannel = UniqueKeyFactory.channelId();
        createChannel(other.getDeviceId(), otherChannel);
        String otherPlatformId = publish(other.getDeviceId(), List.of(otherChannel));

        assertTrue(deviceRemovalManager.removeById(device.getId()));

        assertNull(deviceManager.getDeviceDTOById(device.getId()), "设备本体必须删掉");
        assertTrue(deviceChannelManager.listByDeviceIds(List.of(device.getDeviceId())).isEmpty(),
            "设备名下的通道必须一起删，否则留下永远选不中的孤儿行");
        assertTrue(publishRowsOfPlatform(platformId).isEmpty(),
            "发布行是孤儿，留着会让上级目录残留节点、页面上还能看到指向不存在通道的记录");

        assertEquals(1, deviceChannelManager.listByDeviceIds(List.of(other.getDeviceId())).size(),
            "另一台设备的通道不能被误删");
        assertEquals(2, publishRowsOfPlatform(otherPlatformId).size(),
            "另一台设备的发布关系不能被误删");
    }

    @Test
    @DisplayName("批量删设备：整批一个事务，返回实删台数")
    void should_remove_batch() {
        DeviceDTO first = createDevice();
        DeviceDTO second = createDevice();
        List<String> ids = new ArrayList<>();
        for (DeviceDTO device : List.of(first, second)) {
            String channelId = UniqueKeyFactory.channelId();
            createChannel(device.getDeviceId(), channelId);
            publish(device.getDeviceId(), List.of(channelId));
            ids.add(device.getDeviceId());
        }
        assertEquals(2, deviceRemovalManager.removeByIds(List.of(first.getId(), second.getId())));
        for (String deviceId : ids) {
            assertTrue(deviceChannelManager.listByDeviceIds(List.of(deviceId)).isEmpty());
        }
    }

    @Test
    @DisplayName("设备不存在：返回 false 而不是抛异常，重复删除可幂等")
    void should_return_false_when_device_missing() {
        assertFalse(deviceRemovalManager.removeById(Long.MAX_VALUE));
        assertFalse(deviceRemovalManager.removeById(null));
        assertEquals(0, deviceRemovalManager.removeByIds(List.of()));
    }

    @Test
    @DisplayName("先撤销、确认后才删本地：撤销时设备与通道都还在")
    void should_revoke_before_touching_local_data() {
        DeviceDTO device = createDevice();
        String channelId = UniqueKeyFactory.channelId();
        createChannel(device.getDeviceId(), channelId);
        publish(device.getDeviceId(), List.of(channelId));

        revokeStub.setOnRevoke(invocation -> {
            assertNotNull(deviceManager.getDeviceDTOById(device.getId()), "上级确认前设备不能先删");
            assertEquals(1, deviceChannelManager.listByDeviceIds(List.of(device.getDeviceId())).size(),
                "上级确认前通道不能先删");
        });

        assertTrue(deviceRemovalManager.removeById(device.getId()));
        assertEquals(1, revokeStub.invocationCount(), "一台设备只一次撤销，通道与设备节点合并发出");
        assertEquals(2, revokeStub.getInvocations().get(0).nodeCount(), "一路通道 + 设备本体");
    }

    @Test
    @DisplayName("上级无响应：设备、通道、发布行一条都不删")
    void should_keep_everything_when_upstream_does_not_respond() {
        DeviceDTO device = createDevice();
        String channelId = UniqueKeyFactory.channelId();
        createChannel(device.getDeviceId(), channelId);
        String platformId = publish(device.getDeviceId(), List.of(channelId));
        revokeStub.noResponse();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> deviceRemovalManager.removeById(device.getId()));

        assertTrue(e.getMessage().contains("上级未确认取消上报"), e.getMessage());
        assertTrue(e.getMessage().contains(platformId), "要报出没确认的平台: " + e.getMessage());
        assertNotNull(deviceManager.getDeviceDTOById(device.getId()), "设备必须还在");
        assertEquals(1, deviceChannelManager.listByDeviceIds(List.of(device.getDeviceId())).size(), "通道必须还在");
        assertEquals(2, publishRowsOfPlatform(platformId).size(), "发布行要留着，重试还要靠它");
    }

    @Test
    @DisplayName("批量删除：任一台未被确认则整批本地不动")
    void batch_should_keep_all_devices_when_revoke_refused() {
        DeviceDTO first = createDevice();
        DeviceDTO second = createDevice();
        for (DeviceDTO device : List.of(first, second)) {
            String channelId = UniqueKeyFactory.channelId();
            createChannel(device.getDeviceId(), channelId);
            publish(device.getDeviceId(), List.of(channelId));
        }
        revokeStub.refuse(486);

        assertThrows(IllegalArgumentException.class,
            () -> deviceRemovalManager.removeByIds(List.of(first.getId(), second.getId())));

        assertNotNull(deviceManager.getDeviceDTOById(first.getId()), "第一台不能删");
        assertNotNull(deviceManager.getDeviceDTOById(second.getId()), "半成功比整批失败更难收拾");
    }
}
