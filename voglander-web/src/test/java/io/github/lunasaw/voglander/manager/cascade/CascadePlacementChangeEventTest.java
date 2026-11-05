package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PlacementScope;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.event.LocalCascadePlacementChangeEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.manager.service.DeviceChannelService;
import io.github.lunasaw.voglander.manager.service.DeviceService;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 「设备 / 通道改归属组织」的<b>发事件</b>侧验证。
 *
 * <p><b>只钉发与不发</b>：两个管理器都在高频路径上（心跳、入站响应回填都走
 * {@code updateById}），只要归属没变就一条事件都不能发；变了则必须带上变更前后的组织主键，
 * 否则监听器算不出旧父节点。旧父节点如何还原由 {@code CascadePlacementChangeListenerTest} 覆盖。
 *
 * <p><b>为什么用 {@code NOT_SUPPORTED}</b>：与 {@code CascadeOrgMoveEventTest} 同理——监听器挂在
 * {@code AFTER_COMMIT}，套在测试基类的回滚事务里永远不会执行；这里虽然只断言发送侧，但让整条链路
 * 真跑一遍才能证明「事件发出去以后不会把请求炸掉」（监听器整体 try-catch）。
 *
 * <p><b>为什么组织主键用不存在的值</b>：本用例只关心事件里的两个主键是否原样带出。用的是不存在的
 * 组织，监听器查不到编码就会静默跳过，正好顺带验证「配置缺失时保存仍然成功」。
 *
 * @author luna
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RecordApplicationEvents
@DisplayName("设备/通道归属变更事件")
class CascadePlacementChangeEventTest extends BaseTest {

    /** 变更前的组织主键：库里没有这个组织，监听器不会真去发报文 */
    private static final Long   ORG_A = 900001L;
    /** 变更后的组织主键 */
    private static final Long   ORG_B = 900002L;

    @Autowired
    private DeviceManager        deviceManager;

    @Autowired
    private DeviceChannelManager deviceChannelManager;

    @Autowired
    private DeviceService        deviceService;

    @Autowired
    private DeviceChannelService deviceChannelService;

    @Autowired
    private ApplicationEvents    applicationEvents;

    /** 每个用例独占一台设备，清理按它批量删（真提交落库，不会随事务回滚） */
    private String               deviceId;

    @AfterEach
    void cleanup() {
        if (deviceId == null) {
            return;
        }
        LambdaQueryWrapper<DeviceChannelDO> channelQw = new LambdaQueryWrapper<>();
        channelQw.eq(DeviceChannelDO::getDeviceId, deviceId);
        deviceChannelService.remove(channelQw);

        LambdaQueryWrapper<DeviceDO> deviceQw = new LambdaQueryWrapper<>();
        deviceQw.eq(DeviceDO::getDeviceId, deviceId);
        deviceService.remove(deviceQw);
        deviceId = null;
    }

    // ------------------------------------------------------------------ 设备

    @Test
    @DisplayName("设备换组织：发出 DEVICE 事件，带上变更前后的组织主键")
    void device_org_change_publishes_event() {
        Long id = createDevice(ORG_A);

        DeviceDTO patch = new DeviceDTO();
        patch.setOrgId(ORG_B);
        deviceManager.updateById(id, patch);

        LocalCascadePlacementChangeEvent event = singleEvent(deviceId);
        assertEquals(PlacementScope.DEVICE, event.getScope());
        assertEquals(ORG_A, event.getPreviousOrgId());
        assertEquals(ORG_B, event.getCurrentOrgId());
    }

    @Test
    @DisplayName("设备其他字段变化：不发事件（心跳、响应回填都走这条路）")
    void device_update_without_org_change_publishes_nothing() {
        Long id = createDevice(ORG_A);

        DeviceDTO patch = new DeviceDTO();
        patch.setName("改了名字");
        deviceManager.updateById(id, patch);

        assertTrue(eventsOf(deviceId).isEmpty(), "归属没变就不该碰级联目录");
    }

    @Test
    @DisplayName("设备清空归属（orgId=0）：事件里的新归属是 null，不是 0")
    void device_clear_org_publishes_null_current() {
        Long id = createDevice(ORG_A);

        DeviceDTO patch = new DeviceDTO();
        patch.setOrgId(0L);
        deviceManager.updateById(id, patch);

        LocalCascadePlacementChangeEvent event = singleEvent(deviceId);
        assertEquals(PlacementScope.DEVICE, event.getScope());
        assertEquals(ORG_A, event.getPreviousOrgId());
        assertNull(event.getCurrentOrgId(), "0 是「清空」的入参约定，事件里必须归一成 null");
    }

    // ------------------------------------------------------------------ 通道

    @Test
    @DisplayName("通道换组织：发出 CHANNEL 事件，业务键是 deviceId:channelId")
    void channel_org_change_publishes_event() {
        createDevice(ORG_A);
        String channelId = createChannel("前门");

        DeviceChannelDTO query = new DeviceChannelDTO();
        query.setChannelId(channelId);
        DeviceChannelDTO patch = new DeviceChannelDTO();
        patch.setOrgId(ORG_B);
        deviceChannelManager.update(query, patch);

        LocalCascadePlacementChangeEvent event = singleEvent(PublishKeys.channel(deviceId, channelId));
        assertEquals(PlacementScope.CHANNEL, event.getScope());
        assertNull(event.getPreviousOrgId(), "通道原本未归组织，新归属是 B");
        assertEquals(ORG_B, event.getCurrentOrgId());
    }

    @Test
    @DisplayName("通道其他字段变化：不发事件")
    void channel_update_without_org_change_publishes_nothing() {
        createDevice(ORG_A);
        String channelId = createChannel("前门");

        DeviceChannelDTO query = new DeviceChannelDTO();
        query.setChannelId(channelId);
        DeviceChannelDTO patch = new DeviceChannelDTO();
        patch.setName("后门");
        deviceChannelManager.update(query, patch);

        assertTrue(eventsOf(PublishKeys.channel(deviceId, channelId)).isEmpty());
    }

    // ------------------------------------------------------------------ 夹具

    private Long createDevice(Long orgId) {
        deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(deviceId);
        device.setIp("127.0.0.1");
        device.setPort(5060);
        device.setType(1);
        device.setServerIp("127.0.0.1");
        device.setName("dev-归属变更");
        device.setOrgId(orgId);
        Long id = deviceManager.add(device);
        assertNotNull(id);
        return id;
    }

    private String createChannel(String name) {
        String channelId = UniqueKeyFactory.channelId();
        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(deviceId);
        channel.setChannelId(channelId);
        channel.setName(name);
        deviceChannelManager.add(channel);
        return channelId;
    }

    private List<LocalCascadePlacementChangeEvent> eventsOf(String objectKey) {
        return applicationEvents.stream(LocalCascadePlacementChangeEvent.class)
            .filter(e -> objectKey.equals(e.getObjectKey()))
            .collect(Collectors.toList());
    }

    private LocalCascadePlacementChangeEvent singleEvent(String objectKey) {
        List<LocalCascadePlacementChangeEvent> events = eventsOf(objectKey);
        assertEquals(1, events.size(), "一次归属变更只发一条事件: " + objectKey);
        return events.get(0);
    }
}
