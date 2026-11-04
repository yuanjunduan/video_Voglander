package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishedAssetResolver;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;

/**
 * {@link CascadePublishedAssetResolver} 单元测试。
 *
 * <p>这个组件是「发布层 → 目录视图」的唯一读取入口，它放行或过滤一条通道，直接决定上级看不看得到。
 * 因此用例只钉过滤口径：发布行停用、本地通道停用、设备停用、本地对象已不存在——四种情况都必须跳过。
 * 名称/编码的覆盖优先级同样钉住，否则平台改过编码的通道会悄悄回落到本地编码。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadePublishedAssetResolver 单元测试")
class CascadePublishedAssetResolverTest {

    private static final String PLATFORM_ID = "34020000002000000001";
    private static final String DEVICE_ID   = "34020000001320000001";
    private static final String CHANNEL_ID  = "34020000001310000001";
    private static final String OBJECT_KEY  = DEVICE_ID + ":" + CHANNEL_ID;

    @Mock
    private CascadePublishManager cascadePublishManager;
    @Mock
    private DeviceChannelManager  deviceChannelManager;
    @Mock
    private DeviceManager         deviceManager;
    @Mock
    private CascadeOrgService     cascadeOrgService;

    private CascadePublishedAssetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CascadePublishedAssetResolver(cascadePublishManager, deviceChannelManager,
            deviceManager, cascadeOrgService);
        when(cascadeOrgService.list()).thenReturn(List.of());
    }

    @Test
    @DisplayName("发布行的编码与名称覆盖本地值，本地启停与在线状态照样参与")
    void publishOverridesLocalFields() {
        stubPublish(List.of(publish(CHANNEL_ID, "前门")));
        stubLocalChannel(channel(null, 1));
        stubDevice(device(1));

        List<CascadeChannelDTO> channels = resolver.listChannels(PLATFORM_ID);

        assertEquals(1, channels.size());
        CascadeChannelDTO ch = channels.get(0);
        assertEquals(CHANNEL_ID, ch.getCascadeChannelId(), "发布行有编码覆盖时以覆盖值为准");
        assertEquals("前门", ch.getCascadeName());
        assertEquals(DEVICE_ID, ch.getLocalDeviceId());
        assertEquals(CHANNEL_ID, ch.getLocalChannelId(), "本地通道标识原样带回，上级点播反查靠它");
    }

    @Test
    @DisplayName("本地通道停用后，它在所有平台都不出现")
    void locallyDisabledChannelIsSkipped() {
        stubPublish(List.of(publish(CHANNEL_ID, "前门")));
        stubLocalChannel(channel(null, 0));
        stubDevice(device(1));

        assertTrue(resolver.listChannels(PLATFORM_ID).isEmpty());
    }

    @Test
    @DisplayName("设备停用后，其下通道一并消失（通道是设备的子资源）")
    void channelOfDisabledDeviceIsSkipped() {
        stubPublish(List.of(publish(CHANNEL_ID, "前门")));
        stubLocalChannel(channel(null, 1));
        stubDevice(device(0));

        assertTrue(resolver.listChannels(PLATFORM_ID).isEmpty());
    }

    @Test
    @DisplayName("发布行指向的本地通道已不存在时丢弃，不留挂不住的孤儿")
    void orphanPublishRowIsSkipped() {
        stubPublish(List.of(publish(CHANNEL_ID, "前门")));
        when(deviceChannelManager.listByDeviceIds(any())).thenReturn(List.of());
        stubDevice(device(1));

        assertTrue(resolver.listChannels(PLATFORM_ID).isEmpty());
    }

    @Test
    @DisplayName("组织归属按 org_id 翻成编码，通道与设备各取各的")
    void orgCodesAreResolvedPerOwner() {
        stubPublish(List.of(publish(CHANNEL_ID, "前门")));
        stubLocalChannel(channel(2L, 1));
        stubDevice(device(1));
        when(cascadeOrgService.list()).thenReturn(List.of(org(1L, "ORG-DEVICE"), org(2L, "ORG-CHANNEL")));

        CascadeChannelDTO ch = resolver.listChannels(PLATFORM_ID).get(0);

        assertEquals("ORG-CHANNEL", ch.getCascadeOrgId(), "通道自己的归属优先");
        assertEquals("ORG-DEVICE", ch.getDeviceOrgCode(), "设备归属留给组装器决定挂设备还是挂组织");
    }

    @Test
    @DisplayName("设备发布行翻成目录设备节点，编码缺省回落本地设备 ID")
    void devicesAreProjectedWithFallbacks() {
        CascadePublishDTO row = new CascadePublishDTO();
        row.setPlatformId(PLATFORM_ID);
        row.setObjectKey(DEVICE_ID);
        when(cascadePublishManager.listByPlatform(PLATFORM_ID, "DEVICE", true)).thenReturn(List.of(row));
        stubDevice(device(1));

        List<CascadePlatformDeviceDTO> devices = resolver.listDevices(PLATFORM_ID);

        assertEquals(1, devices.size());
        CascadePlatformDeviceDTO view = devices.get(0);
        assertEquals(DEVICE_ID, view.getPlatformCode(), "没有编码覆盖时用本地设备 ID");
        assertEquals(1, view.getStatus(), "在线状态只影响报文 Status");
    }

    // ------------------------------------------------------------------ 夹具

    private CascadePublishDTO publish(String code, String name) {
        CascadePublishDTO dto = new CascadePublishDTO();
        dto.setPlatformId(PLATFORM_ID);
        dto.setObjectKey(OBJECT_KEY);
        dto.setPublishCode(code);
        dto.setPublishName(name);
        dto.setEnabled(1);
        return dto;
    }

    private void stubPublish(List<CascadePublishDTO> rows) {
        when(cascadePublishManager.listByPlatform(PLATFORM_ID, "CHANNEL", true)).thenReturn(rows);
    }

    private void stubLocalChannel(DeviceChannelDO channel) {
        when(deviceChannelManager.listByDeviceIds(any())).thenReturn(List.of(channel));
    }

    private void stubDevice(DeviceDO device) {
        when(deviceManager.mapByDeviceIds(any())).thenAnswer(inv -> {
            Map<String, DeviceDO> index = new HashMap<>();
            index.put(device.getDeviceId(), device);
            return index;
        });
    }

    private DeviceChannelDO channel(Long orgId, int enabled) {
        DeviceChannelDO channel = new DeviceChannelDO();
        channel.setDeviceId(DEVICE_ID);
        channel.setChannelId(CHANNEL_ID);
        channel.setName("本地前门");
        channel.setOrgId(orgId);
        channel.setEnabled(enabled);
        return channel;
    }

    private DeviceDO device(int enabled) {
        DeviceDO device = new DeviceDO();
        device.setDeviceId(DEVICE_ID);
        device.setName("NVR");
        device.setOrgId(1L);
        device.setEnabled(enabled);
        device.setStatus(1);
        return device;
    }

    private CascadeOrgDO org(Long id, String code) {
        CascadeOrgDO org = new CascadeOrgDO();
        org.setId(id);
        org.setOrgCode(code);
        return org;
    }
}
