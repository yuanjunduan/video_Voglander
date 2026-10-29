package io.github.lunasaw.voglander.intergration.wrapper.channel.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoverResult;
import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoveredChannel;
import io.github.lunasaw.voglander.intergration.wrapper.channel.support.ChannelEndpoint;
import io.github.lunasaw.voglander.intergration.wrapper.channel.support.HttpDigestClient;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;

/**
 * {@link HikvisionChannelDiscoverer} 单元测试：厂商确认、台账读取、地址拼装、状态缺失的容错。
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("海康通道发现")
class HikvisionChannelDiscovererTest {

    private static final String HOST          = "192.168.60.165";
    private static final String RTSP_URL      = "rtsp://admin:Admin123@" + HOST
        + ":554/Streaming/Channels/301";
    private static final String URL_DEVICE    = "http://" + HOST + ":80/ISAPI/System/deviceInfo";
    private static final String URL_CHANNELS  = "http://" + HOST
        + ":80/ISAPI/ContentMgmt/InputProxy/channels";
    private static final String URL_STATUS    = "http://" + HOST
        + ":80/ISAPI/ContentMgmt/InputProxy/channels/status";
    private static final int    TIMEOUT_MS    = 3000;

    @Mock
    private HttpDigestClient       httpDigestClient;

    @InjectMocks
    private HikvisionChannelDiscoverer discoverer;

    private DeviceDTO              device;

    @BeforeEach
    void setUp() {
        device = new DeviceDTO();
        device.setDeviceId("9000003");
        DeviceDTO.ExtendInfo extendInfo = new DeviceDTO.ExtendInfo();
        extendInfo.setRtspUrl(RTSP_URL);
        device.setExtendInfo(extendInfo);
    }

    @Test
    @DisplayName("厂商应答不是海康：直接失败，且不再读台账")
    void discover_shouldFailWhenVendorMismatch() {
        when(httpDigestClient.get(eq(URL_DEVICE), anyString(), anyString(), anyInt()))
            .thenReturn(ok("<DeviceInfo><manufacturer>dahua</manufacturer></DeviceInfo>"));

        DiscoverResult result = discoverer.discover(device, TIMEOUT_MS);

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureReason().contains("不是海康"), result.getFailureReason());
        assertTrue(result.getChannels().isEmpty());
    }

    @Test
    @DisplayName("设备信息读不到（如 401）：失败原因带状态码，便于区分网络/凭据问题")
    void discover_shouldFailWhenDeviceInfoUnauthorized() {
        when(httpDigestClient.get(eq(URL_DEVICE), anyString(), anyString(), anyInt()))
            .thenReturn(response(401, null, "账号或密码错误（HTTP 401）"));

        DiscoverResult result = discoverer.discover(device, TIMEOUT_MS);

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureReason().contains("读取设备信息失败"), result.getFailureReason());
    }

    @Test
    @DisplayName("地址里没有凭据：提前失败，避免拿匿名请求去撞 401")
    void discover_shouldFailWhenNoCredential() {
        DeviceDTO.ExtendInfo extendInfo = new DeviceDTO.ExtendInfo();
        extendInfo.setRtspUrl("rtsp://" + HOST + ":554/Streaming/Channels/301");
        device.setExtendInfo(extendInfo);

        DiscoverResult result = discoverer.discover(device, TIMEOUT_MS);

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureReason().contains("没有账号密码"), result.getFailureReason());
    }

    @Test
    @DisplayName("正常台账：通道号直接拼 01/02 码流后缀，状态与型号一并带回")
    void discover_shouldBuildChannelUrls() {
        when(httpDigestClient.get(eq(URL_DEVICE), anyString(), anyString(), anyInt())).thenReturn(ok("""
            <DeviceInfo><manufacturer>hikvision</manufacturer><model>DS-7608N-K2</model>
            <firmwareVersion>V4.30.000</firmwareVersion></DeviceInfo>
            """));
        when(httpDigestClient.get(eq(URL_CHANNELS), anyString(), anyString(), anyInt())).thenReturn(ok("""
            <InputProxyChannelList>
            <InputProxyChannel><id>3</id><name>海康IPC-直连-60.2</name>
            <ipAddress>192.168.60.2</ipAddress><managePortNo>8000</managePortNo>
            <proxyProtocol>HIKVISION</proxyProtocol></InputProxyChannel>
            <InputProxyChannel><id>7</id><name>测试</name>
            <ipAddress>192.168.60.214</ipAddress><managePortNo>80</managePortNo>
            <proxyProtocol>ONVIF</proxyProtocol></InputProxyChannel>
            </InputProxyChannelList>
            """));
        when(httpDigestClient.get(eq(URL_STATUS), anyString(), anyString(), anyInt())).thenReturn(ok("""
            <InputProxyChannelStatusList>
            <InputProxyChannelStatus><id>3</id><online>true</online></InputProxyChannelStatus>
            <InputProxyChannelStatus><id>7</id><online>false</online></InputProxyChannelStatus>
            </InputProxyChannelStatusList>
            """));

        DiscoverResult result = discoverer.discover(device, TIMEOUT_MS);

        assertTrue(result.isSuccess(), result.getFailureReason());
        assertEquals("hikvision", result.getVendor());
        assertEquals("DS-7608N-K2", result.getDeviceModel());
        assertEquals("V4.30.000", result.getFirmwareVersion());

        List<DiscoveredChannel> channels = result.getChannels();
        assertEquals(2, channels.size());

        DiscoveredChannel channel3 = channels.get(0);
        assertEquals("3", channel3.getChannelId());
        assertEquals("海康IPC-直连-60.2", channel3.getName());
        assertEquals(1, channel3.getStatus());
        assertEquals("rtsp://admin:Admin123@" + HOST + ":554/Streaming/Channels/301", channel3.getRtspUrl());
        assertEquals("rtsp://admin:Admin123@" + HOST + ":554/Streaming/Channels/302", channel3.getRtspSubUrl());
        assertEquals("192.168.60.2", channel3.getSourceIp());
        assertEquals(8000, channel3.getSourcePort());
        assertEquals("HIKVISION", channel3.getProxyProtocol());

        DiscoveredChannel channel7 = channels.get(1);
        assertEquals("7", channel7.getChannelId());
        assertEquals(0, channel7.getStatus());
        assertEquals("rtsp://admin:Admin123@" + HOST + ":554/Streaming/Channels/701", channel7.getRtspUrl());
    }

    @Test
    @DisplayName("状态接口失败不阻断：通道照常返回，status 留空表示未知")
    void discover_shouldTolerateStatusFailure() {
        when(httpDigestClient.get(eq(URL_DEVICE), anyString(), anyString(), anyInt()))
            .thenReturn(ok("<DeviceInfo><manufacturer>hikvision</manufacturer></DeviceInfo>"));
        when(httpDigestClient.get(eq(URL_CHANNELS), anyString(), anyString(), anyInt())).thenReturn(ok("""
            <InputProxyChannelList>
            <InputProxyChannel><id>1</id><name>IPCamera 01</name></InputProxyChannel>
            </InputProxyChannelList>
            """));
        when(httpDigestClient.get(eq(URL_STATUS), anyString(), anyString(), anyInt()))
            .thenReturn(response(500, null, "HTTP 500"));

        DiscoverResult result = discoverer.discover(device, TIMEOUT_MS);

        assertTrue(result.isSuccess(), result.getFailureReason());
        assertEquals(1, result.getChannels().size());
        assertNull(result.getChannels().get(0).getStatus());
    }

    @Test
    @DisplayName("台账接口返回非 XML：失败原因可读，不抛异常到编排层")
    void discover_shouldFailWhenLedgerNotXml() {
        when(httpDigestClient.get(eq(URL_DEVICE), anyString(), anyString(), anyInt()))
            .thenReturn(ok("<DeviceInfo><manufacturer>hikvision</manufacturer></DeviceInfo>"));
        when(httpDigestClient.get(eq(URL_CHANNELS), anyString(), anyString(), anyInt()))
            .thenReturn(ok("<!DOCTYPE html><html><body>login</body></html>"));

        DiscoverResult result = discoverer.discover(device, TIMEOUT_MS);

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureReason().contains("合法 XML"), result.getFailureReason());
    }

    @Test
    @DisplayName("设备既无地址也无 IP：supports 为 false，编辑期就能拦住")
    void supports_shouldRequireHost() {
        assertFalse(discoverer.supports(new DeviceDTO()));
        assertTrue(discoverer.supports(device));
        assertEquals(ChannelEndpoint.DEFAULT_HTTP_PORT, ChannelEndpoint.of(device).getHttpPort());
    }

    private HttpDigestClient.Response ok(String body) {
        return response(200, body, null);
    }

    private HttpDigestClient.Response response(int status, String body, String message) {
        HttpDigestClient.Response response = new HttpDigestClient.Response();
        response.setStatusCode(status);
        response.setBody(body);
        response.setMessage(message);
        return response;
    }
}
