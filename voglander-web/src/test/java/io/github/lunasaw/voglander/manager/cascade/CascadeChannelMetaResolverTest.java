package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMeta;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMetaResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;

/**
 * {@link CascadeChannelMetaResolver} 派生规则测试。
 *
 * <p>锁住上级目录项的取值优先级：<b>人工覆盖值 &gt; 设备通道 extend &gt; 设备表</b>。
 * 这条链一旦次序错了，页面手填的值会被实时数据悄悄顶掉——现象是"改了不生效"，
 * 排查时几乎不会想到是优先级问题。
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeChannelMetaResolver 单元测试")
class CascadeChannelMetaResolverTest {

    private static final String DEVICE_ID  = "34020000001320000001";
    private static final String CHANNEL_ID = "34020000001320000099";
    private static final String KEY        = DeviceChannelManager.channelKey(DEVICE_ID, CHANNEL_ID);

    @Mock
    private DeviceChannelManager deviceChannelManager;
    @Mock
    private DeviceManager        deviceManager;

    private CascadeChannelMetaResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CascadeChannelMetaResolver(deviceChannelManager, deviceManager);
    }

    private CascadeChannelDTO channel() {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setPlatformId("34020000002000000001");
        dto.setLocalDeviceId(DEVICE_ID);
        dto.setLocalChannelId(CHANNEL_ID);
        dto.setCascadeChannelId("34020000001320000001");
        dto.setEnabled(1);
        return dto;
    }

    /** 拼一条 {@code tb_device_channel.extend}：channelInfo 是框架 DeviceItem 的 JSON 字符串。 */
    private String extendWithChannelInfo(JSONObject item) {
        return JSON.toJSONString(Map.of("channelInfo", item.toJSONString()));
    }

    private CascadeChannelMeta resolveSingle(String extendJson, String deviceIp) {
        when(deviceChannelManager.mapChannelExtend(any())).thenReturn(Map.of(KEY, extendJson));
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(DEVICE_ID);
        device.setIp(deviceIp);
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device);
        return resolver.resolve(List.of(channel())).get(KEY);
    }

    @Test
    @DisplayName("人工覆盖值优先于设备通道里的实时数据")
    void override_wins_over_device_data() {
        JSONObject item = new JSONObject();
        item.put("manufacturer", "hikvision");
        item.put("model", "DS-2CD");
        item.put("address", "杭州西湖");
        item.put("ipAddress", "192.168.1.2");
        item.put("ptzType", 2);

        CascadeChannelDTO dto = channel();
        dto.setManufacturer("Dahua");
        dto.setModel("DH-IPC");
        dto.setAddress("宁波北仑");
        dto.setIpAddress("10.1.1.1");
        dto.setPtzType(1);

        when(deviceChannelManager.mapChannelExtend(any())).thenReturn(Map.of(KEY, extendWithChannelInfo(item)));
        CascadeChannelMeta meta = resolver.resolve(List.of(dto)).get(KEY);

        assertEquals("Dahua", meta.manufacturer());
        assertEquals("DH-IPC", meta.model());
        assertEquals("宁波北仑", meta.address());
        assertEquals("10.1.1.1", meta.ipAddress());
        assertEquals(1, meta.ptzType());
    }

    @Test
    @DisplayName("GB28181 目录项里的厂商/型号/地址/IP/类型全部可派生，厂商标识归一")
    void derives_from_catalog_item() {
        JSONObject item = new JSONObject();
        item.put("manufacturer", "hikvision");
        item.put("model", "DS-2CD3T46");
        item.put("address", "杭州西湖");
        item.put("ipAddress", "192.168.1.64");
        item.put("ptzType", 2);

        CascadeChannelMeta meta = resolveSingle(extendWithChannelInfo(item), "10.0.0.9");

        assertEquals("Hik", meta.manufacturer(), "海康统一写成 Hik，与参考实现（CMS→CSS）一致");
        assertEquals("DS-2CD3T46", meta.model());
        assertEquals("杭州西湖", meta.address());
        assertEquals("192.168.1.64", meta.ipAddress(), "目录项 IP 优先于设备表 IP");
        assertEquals(2, meta.ptzType());
    }

    @Test
    @DisplayName("发现类通道从 vendor/sourceModel/sourceIp 派生，IP 缺失时回落设备表")
    void derives_from_discovery_extend_and_device_ip() {
        JSONObject extend = new JSONObject();
        extend.put("vendor", "dahua");
        extend.put("sourceModel", "DH-IPC-HFW");

        CascadeChannelMeta meta = resolveSingle(extend.toJSONString(), "10.0.0.9");

        assertEquals("Dahua", meta.manufacturer());
        assertEquals("DH-IPC-HFW", meta.model());
        assertEquals("10.0.0.9", meta.ipAddress(), "源设备 IP 缺失时用 tb_device.ip 兜底");
        assertEquals("China", meta.address(), "取不到地址时用固定兜底，不留空节点");
        assertEquals(3, meta.ptzType(), "取不到类型时按枪机");
    }

    @Test
    @DisplayName("目录里非标准的 PTZType（0 或 9）按枪机处理，不把脏值原样上送")
    void invalid_ptz_type_falls_back_to_bullet() {
        JSONObject item = new JSONObject();
        item.put("ptzType", 9);

        assertEquals(3, resolveSingle(extendWithChannelInfo(item), "10.0.0.9").ptzType());
    }

    @Test
    @DisplayName("非大华/非中维的厂商一律映射成 Hik，与参考实现的三值枚举一致")
    void unknown_vendor_maps_to_hik() {
        JSONObject extend = new JSONObject();
        extend.put("vendor", "uniview");

        assertEquals("Hik", resolveSingle(extend.toJSONString(), "10.0.0.9").manufacturer());
    }

    @Test
    @DisplayName("通道 extend 缺失时不抛异常，只回落兜底值")
    void missing_extend_uses_defaults() {
        when(deviceChannelManager.mapChannelExtend(any())).thenReturn(Map.of());
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(null);

        CascadeChannelMeta meta = resolver.resolve(List.of(channel())).get(KEY);

        assertEquals("Hik", meta.manufacturer(), "厂商恒非空，否则上级会当成未知厂商");
        assertNull(meta.model());
        assertEquals("China", meta.address());
        assertNull(meta.ipAddress());
        assertEquals(3, meta.ptzType());
    }
}
