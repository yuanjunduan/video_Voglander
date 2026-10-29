package io.github.lunasaw.voglander.intergration.wrapper.channel.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;

/**
 * {@link ChannelEndpoint} 单元测试：从拉流地址解析管理面端点与凭据。
 *
 * @author luna
 */
@DisplayName("发现端点解析")
class ChannelEndpointTest {

    @Test
    @DisplayName("标准拉流地址：host/rtsp 端口/凭据全部解析出来，管理面固定 80")
    void of_shouldParseRtspUrl() {
        DeviceDTO device = device("rtsp://admin:Admin123@192.168.60.165:554/Streaming/Channels/301");

        ChannelEndpoint endpoint = ChannelEndpoint.of(device);

        assertEquals("192.168.60.165", endpoint.getHost());
        assertEquals(554, endpoint.getRtspPort());
        assertEquals(ChannelEndpoint.DEFAULT_HTTP_PORT, endpoint.getHttpPort());
        assertEquals("admin", endpoint.getUser());
        assertEquals("Admin123", endpoint.getPassword());
        assertTrue(endpoint.hasCredential());
    }

    @Test
    @DisplayName("凭据含 URL 编码字符：解析时还原成明文，供 Digest 计算使用")
    void of_shouldDecodeUserInfo() {
        DeviceDTO device = device("rtsp://admin:p%40ss%3Aword@10.0.0.2/Streaming/Channels/101");

        ChannelEndpoint endpoint = ChannelEndpoint.of(device);

        assertEquals("admin", endpoint.getUser());
        assertEquals("p@ss:word", endpoint.getPassword());
    }

    @Test
    @DisplayName("无 userinfo 的地址：host 可用但没有凭据，发现会如实报 401")
    void of_shouldReportMissingCredential() {
        DeviceDTO device = device("rtsp://192.168.60.98:554/cam/realmonitor?channel=1&subtype=0");

        ChannelEndpoint endpoint = ChannelEndpoint.of(device);

        assertEquals("192.168.60.98", endpoint.getHost());
        assertFalse(endpoint.hasCredential());
    }

    @Test
    @DisplayName("地址缺端口：回落 RTSP 默认 554")
    void of_shouldFallbackToDefaultRtspPort() {
        DeviceDTO device = device("rtsp://admin:pass@192.168.60.113/Streaming/Channels/601");

        assertEquals(ChannelEndpoint.DEFAULT_RTSP_PORT, ChannelEndpoint.of(device).getRtspPort());
    }

    @Test
    @DisplayName("地址为空时回落 ip + port，且没有凭据")
    void of_shouldFallbackToDeviceIp() {
        DeviceDTO device = new DeviceDTO();
        device.setIp("192.168.60.165");
        device.setPort(554);
        DeviceDTO.ExtendInfo extendInfo = new DeviceDTO.ExtendInfo();
        extendInfo.setRtspUrl("");
        device.setExtendInfo(extendInfo);

        ChannelEndpoint endpoint = ChannelEndpoint.of(device);

        assertEquals("192.168.60.165", endpoint.getHost());
        assertFalse(endpoint.hasCredential());
    }

    @Test
    @DisplayName("既没地址也没 ip：返回 null（调用方据此给出明确失败原因）")
    void of_shouldReturnNullWhenNoHost() {
        assertNull(ChannelEndpoint.of(null));
        assertNull(ChannelEndpoint.of(new DeviceDTO()));
    }

    @Test
    @DisplayName("地址拼接：管理面走 http:80，通道地址回带同一组凭据")
    void shouldBuildUrls() {
        ChannelEndpoint endpoint = ChannelEndpoint.of(
            device("rtsp://admin:Admin123@192.168.60.165:554/Streaming/Channels/301"));

        assertEquals("http://192.168.60.165:80/ISAPI/ContentMgmt/InputProxy/channels",
            endpoint.httpUrl("/ISAPI/ContentMgmt/InputProxy/channels"));
        assertEquals("rtsp://admin:Admin123@192.168.60.165:554/Streaming/Channels/402",
            endpoint.rtspUrl("/Streaming/Channels/402"));
    }

    private DeviceDTO device(String rtspUrl) {
        DeviceDTO device = new DeviceDTO();
        DeviceDTO.ExtendInfo extendInfo = new DeviceDTO.ExtendInfo();
        extendInfo.setRtspUrl(rtspUrl);
        device.setExtendInfo(extendInfo);
        return device;
    }
}
