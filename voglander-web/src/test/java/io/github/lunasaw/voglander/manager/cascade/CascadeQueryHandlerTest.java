package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.github.lunasaw.gb28181.common.entity.query.DeviceConfigDownload;
import io.github.lunasaw.gb28181.common.entity.query.DeviceQuery;
import io.github.lunasaw.gb28181.common.entity.query.DeviceRecordQuery;
import io.github.lunasaw.gb28181.common.entity.query.PresetQuery;
import io.github.lunasaw.gb28181.common.entity.response.DeviceInfo;
import io.github.lunasaw.gb28181.common.entity.response.DeviceItem;
import io.github.lunasaw.gb28181.common.entity.response.DeviceResponse;
import io.github.lunasaw.gb28181.common.entity.response.DeviceStatus;
import io.github.lunasaw.gb28181.common.entity.response.PresetQueryResponse;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogAssembler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogNode;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogPager;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeQueryHandler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeUpperMessenger;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.server.command.device.VoglanderServerDeviceCommand;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.server.command.record.VoglanderServerRecordCommand;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadeRecordRequestManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;

/**
 * {@link CascadeQueryHandler} 单元测试。
 *
 * <p>本类只验证<b>决策</b>：谁负责下发（自发送 vs 回退框架）、下发报文里有哪些字段、
 * 预置位缓存命中/未命中两条路径。分片细节在 {@code CascadeCatalogPagerTest}，
 * 报文外观在 {@code CascadeManscdpXmlTest}。
 *
 * <p>应答必须以「平台身份」自发送：框架用 {@code sip.client.clientId} 当 From，上级不认。
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeQueryHandler 单元测试")
class CascadeQueryHandlerTest {

    private static final String PLATFORM_ID = "37000020002000000100";
    private static final String LOCAL_IP    = "192.168.118.7";
    private static final String DOMAIN_ID   = "34020000002000000001";
    private static final String CHANNEL_ID  = "34020000001320000007";

    @Mock  CascadeChannelManager        cascadeChannelManager;
    @Mock  CascadePlatformManager       cascadePlatformManager;
    @Mock  CascadeRecordRequestManager  cascadeRecordRequestManager;
    @Mock  VoglanderServerRecordCommand serverRecordCommand;
    @Mock  VoglanderServerDeviceCommand serverDeviceCommand;
    @Mock  CascadeCatalogAssembler      catalogAssembler;
    @Mock  CascadeCatalogPager          catalogPager;
    @Mock  CascadeUpperMessenger        upperMessenger;
    @Mock  DeviceManager                deviceManager;
    @InjectMocks CascadeQueryHandler handler;

    private DeviceQuery query(String sn, String deviceId) {
        DeviceQuery q = new DeviceQuery();
        q.setSn(sn);
        q.setDeviceId(deviceId);
        return q;
    }

    /** 平台配置齐备（localIp 已回填）。 */
    private CascadePlatformDTO stubPlatform() {
        CascadePlatformDTO platform = new CascadePlatformDTO();
        platform.setPlatformId(PLATFORM_ID);
        platform.setLocalClientId(DOMAIN_ID);
        platform.setLocalIp(LOCAL_IP);
        platform.setLocalPort(5061);
        platform.setRegisterExpires(3600);
        platform.setKeepaliveInterval(60);
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform);
        when(upperMessenger.send(eq(platform), anyString(), nullable(String.class), isNull())).thenReturn("call-1");
        return platform;
    }

    /** 抓取自发送的 XML 正文。 */
    private String captureXml(CascadePlatformDTO platform) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(upperMessenger).send(eq(platform), captor.capture(), any(), isNull());
        return captor.getValue();
    }

    /* ---- onCatalogQuery ---- */

    @Test
    @DisplayName("目录查询：平台可发送时委托分片器，且不把应答交回框架")
    void catalogQuery_delegatesToPager() {
        List<CascadeCatalogNode> nodes = Arrays.asList(
            new CascadeCatalogNode(DOMAIN_ID, DOMAIN_ID, null, null, null, null, null, null, null, null, "ON",
                CascadeCatalogNode.Kind.DOMAIN));
        when(catalogAssembler.assemble(PLATFORM_ID, PLATFORM_ID)).thenReturn(nodes);
        when(catalogPager.sendCatalog(any(), any(), any(), any(), anyList())).thenReturn(1);
        CascadePlatformDTO platform = stubPlatform();

        DeviceResponse resp = handler.onCatalogQuery(PLATFORM_ID, query("1", PLATFORM_ID));

        assertNull(resp, "分片器已下发，必须返回 null，否则框架会用设备编码当 From 再发一遍");
        verify(catalogPager).sendCatalog(eq(platform), any(), eq(PLATFORM_ID), eq("1"), eq(nodes));
    }

    @Test
    @DisplayName("目录查询：平台 localIp 未回填时交回框架兜底")
    void catalogQuery_fallsBackWhenLocalIpMissing() {
        List<CascadeCatalogNode> nodes = List.of(new CascadeCatalogNode(DOMAIN_ID, DOMAIN_ID, null, null, null, null,
            null, null, null, null, "ON", CascadeCatalogNode.Kind.DOMAIN));
        when(catalogAssembler.assemble(PLATFORM_ID, PLATFORM_ID)).thenReturn(nodes);
        DeviceItem item = new DeviceItem();
        item.setDeviceId(DOMAIN_ID);
        when(catalogAssembler.toDeviceItems(nodes)).thenReturn(List.of(item));

        CascadePlatformDTO platform = new CascadePlatformDTO();
        platform.setPlatformId(PLATFORM_ID);
        platform.setLocalClientId(DOMAIN_ID);
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform);

        DeviceResponse resp = handler.onCatalogQuery(PLATFORM_ID, query("6", PLATFORM_ID));

        assertNotNull(resp, "没有可用平台地址时必须把 resp 交回框架，否则一包都发不出去");
        assertEquals(1, resp.getDeviceItemList().size());
        verifyNoInteractions(catalogPager);
    }

    /* ---- onDeviceInfoQuery ---- */

    @Test
    @DisplayName("设备信息：自发送且补齐 DeviceName/Channel")
    void deviceInfo_selfSentWithChannel() {
        CascadePlatformDTO platform = stubPlatform();
        when(cascadeChannelManager.listByPlatformId(PLATFORM_ID)).thenReturn(List.of(enabledChannel(), disabledChannel()));

        assertNull(handler.onDeviceInfoQuery(PLATFORM_ID, query("3", PLATFORM_ID)));

        String xml = captureXml(platform);
        assertTrue(xml.contains("<CmdType>DeviceInfo</CmdType>"), xml);
        assertTrue(xml.contains("<DeviceID>" + PLATFORM_ID + "</DeviceID>"), xml);
        assertTrue(xml.contains("<DeviceName>" + DOMAIN_ID + "</DeviceName>"), "名称缺省回落平台编码: " + xml);
        assertTrue(xml.contains("<Result>OK</Result>"), xml);
        assertTrue(xml.contains("<Manufacturer>Voglander</Manufacturer>"), xml);
        assertTrue(xml.contains("<Channel>1</Channel>"), "Channel 只算已启用通道: " + xml);
    }

    @Test
    @DisplayName("设备信息：平台配置缺失时回退框架实体")
    void deviceInfo_fallbackWhenPlatformMissing() {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(null);

        DeviceInfo info = handler.onDeviceInfoQuery(PLATFORM_ID, query("3", PLATFORM_ID));

        assertNotNull(info);
        assertEquals("Voglander", info.getManufacturer());
        verifyNoInteractions(upperMessenger);
    }

    /* ---- onDeviceStatusQuery ---- */

    @Test
    @DisplayName("设备状态：自发送且带 DeviceTime")
    void deviceStatus_selfSentWithDeviceTime() {
        CascadePlatformDTO platform = stubPlatform();

        assertNull(handler.onDeviceStatusQuery(PLATFORM_ID, query("4", PLATFORM_ID)));

        String xml = captureXml(platform);
        assertTrue(xml.contains("<CmdType>DeviceStatus</CmdType>"), xml);
        assertTrue(xml.contains("<Online>ONLINE</Online>"), xml);
        assertTrue(xml.contains("<DeviceTime>"), "国标要求 DeviceTime: " + xml);
        assertTrue(xml.contains("<Encode>ON</Encode>"), xml);
    }

    @Test
    @DisplayName("设备状态：平台配置缺失时回退框架实体")
    void deviceStatus_fallbackWhenPlatformMissing() {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(null);

        DeviceStatus status = handler.onDeviceStatusQuery(PLATFORM_ID, query("4", PLATFORM_ID));

        assertNotNull(status);
        assertEquals("ONLINE", status.getOnline());
    }

    /* ---- onConfigDownloadQuery ---- */

    @Test
    @DisplayName("配置下载：BasicParam 用平台注册/心跳参数")
    void configDownload_containsBasicParam() {
        CascadePlatformDTO platform = stubPlatform();
        DeviceConfigDownload q = new DeviceConfigDownload();
        q.setSn("5");
        q.setDeviceId(PLATFORM_ID);

        assertNull(handler.onConfigDownloadQuery(PLATFORM_ID, q));

        String xml = captureXml(platform);
        assertTrue(xml.contains("<CmdType>ConfigDownload</CmdType>"), xml);
        assertTrue(xml.contains("<BasicParam>"), xml);
        assertTrue(xml.contains("<Expiration>3600</Expiration>"), xml);
        assertTrue(xml.contains("<HeartBeatInterval>60</HeartBeatInterval>"), xml);
        assertTrue(xml.contains("<HeartBeatCount>3</HeartBeatCount>"), xml);
    }

    /* ---- onPresetQuery ---- */

    @Test
    @DisplayName("预置位：命中设备缓存则回真实预置位")
    void presetQuery_readsCache() {
        CascadePlatformDTO platform = stubPlatform();
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(PLATFORM_ID, CHANNEL_ID))
            .thenReturn(channel());
        DeviceDTO device = new DeviceDTO();
        DeviceDTO.ExtendInfo extend = new DeviceDTO.ExtendInfo();
        extend.setPresets("{\"sn\":\"9\",\"deviceId\":\"dev-1\",\"presetList\":{\"num\":1,"
            + "\"items\":[{\"presetId\":\"3\",\"presetName\":\"大门\"}]}}");
        device.setExtendInfo(extend);
        when(deviceManager.getDtoByDeviceId("dev-1")).thenReturn(device);

        assertNull(handler.onPresetQuery(PLATFORM_ID, presetQuery("9")));

        String xml = captureXml(platform);
        assertTrue(xml.contains("<PresetList Num=\"1\">"), xml);
        assertTrue(xml.contains("<PresetID>3</PresetID>"), xml);
        assertTrue(xml.contains("<PresetName>大门</PresetName>"), xml);
        verifyNoInteractions(serverDeviceCommand);
    }

    @Test
    @DisplayName("预置位：缓存缺失时触发刷新并回空列表")
    void presetQuery_cacheMiss_triggersRefresh() {
        CascadePlatformDTO platform = stubPlatform();
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(PLATFORM_ID, CHANNEL_ID))
            .thenReturn(channel());
        when(deviceManager.getDtoByDeviceId("dev-1")).thenReturn(null);

        assertNull(handler.onPresetQuery(PLATFORM_ID, presetQuery("9")));

        assertTrue(captureXml(platform).contains("<PresetList Num=\"0\">"));
        verify(serverDeviceCommand).queryDevicePreset("dev-1");
    }

    @Test
    @DisplayName("预置位：找不到通道映射时回空列表且不查设备")
    void presetQuery_unknownChannel() {
        CascadePlatformDTO platform = stubPlatform();
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(PLATFORM_ID, CHANNEL_ID)).thenReturn(null);

        assertNull(handler.onPresetQuery(PLATFORM_ID, presetQuery("9")));

        assertTrue(captureXml(platform).contains("<PresetList Num=\"0\">"));
        verifyNoInteractions(deviceManager, serverDeviceCommand);
    }

    @Test
    @DisplayName("预置位：平台缺失时回退框架实体并带上缓存条目")
    void presetQuery_fallbackEntity() {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(null);
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(PLATFORM_ID, CHANNEL_ID))
            .thenReturn(channel());
        DeviceDTO device = new DeviceDTO();
        DeviceDTO.ExtendInfo extend = new DeviceDTO.ExtendInfo();
        extend.setPresets("{\"presetList\":{\"num\":1,\"items\":[{\"presetId\":\"1\",\"presetName\":\"p1\"}]}}");
        device.setExtendInfo(extend);
        when(deviceManager.getDtoByDeviceId("dev-1")).thenReturn(device);

        PresetQueryResponse resp = handler.onPresetQuery(PLATFORM_ID, presetQuery("9"));

        assertNotNull(resp);
        assertEquals(CHANNEL_ID, resp.getDeviceId(), "回退实体的 DeviceID 也要是上级编码");
        assertEquals(1, resp.getPresetList().getItems().size());
    }

    /* ---- onRecordInfoQuery ---- */

    @Test
    @DisplayName("录像查询：找不到级联通道映射时返回 null，由框架回 404")
    void recordQuery_unmapped_channel_returns_null() {
        DeviceRecordQuery q = new DeviceRecordQuery();
        q.setSn("7");
        q.setDeviceId(CHANNEL_ID);
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(PLATFORM_ID, CHANNEL_ID)).thenReturn(null);

        assertNull(handler.onRecordInfoQuery(PLATFORM_ID, q));
        verifyNoInteractions(cascadeRecordRequestManager, serverRecordCommand);
    }

    @Test
    @DisplayName("录像查询：命中映射则登记请求并转查本地设备")
    void recordQuery_forwards_to_local_device() {
        CascadeChannelDTO channel = channel();
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(PLATFORM_ID, CHANNEL_ID)).thenReturn(channel);

        DeviceRecordQuery q = new DeviceRecordQuery();
        q.setSn("7");
        q.setDeviceId(CHANNEL_ID);

        assertNull(handler.onRecordInfoQuery(PLATFORM_ID, q), "异步响应，框架不应立即回包");
        verify(cascadeRecordRequestManager).create(eq(PLATFORM_ID), eq("7"), eq(channel), any(), any());
        verify(serverRecordCommand).queryDeviceRecord(eq("dev-1"), nullable(String.class), nullable(String.class));
    }

    /* ---- helpers ---- */

    private CascadeChannelDTO channel() {
        CascadeChannelDTO ch = new CascadeChannelDTO();
        ch.setPlatformId(PLATFORM_ID);
        ch.setLocalDeviceId("dev-1");
        ch.setLocalChannelId("7");
        ch.setCascadeChannelId(CHANNEL_ID);
        ch.setEnabled(1);
        return ch;
    }

    private CascadeChannelDTO enabledChannel() {
        return channel();
    }

    private CascadeChannelDTO disabledChannel() {
        CascadeChannelDTO ch = channel();
        ch.setCascadeChannelId("34020000001320000008");
        ch.setEnabled(0);
        return ch;
    }

    private PresetQuery presetQuery(String sn) {
        PresetQuery q = new PresetQuery();
        q.setSn(sn);
        q.setDeviceId(CHANNEL_ID);
        return q;
    }
}
