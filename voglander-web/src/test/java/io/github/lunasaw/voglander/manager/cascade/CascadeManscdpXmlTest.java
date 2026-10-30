package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeManscdpXml;

/**
 * {@link CascadeManscdpXml} 报文渲染单测（附录 A）。
 *
 * <p>国标报文是「字段名 + 可选性」问题，写错一个标签上级就整包丢弃，所以这里逐字段断言。
 *
 * @author luna
 */
@DisplayName("CascadeManscdpXml 报文渲染")
class CascadeManscdpXmlTest {

    @Test
    @DisplayName("语音广播应答：CmdType/SN/DeviceID/Result 四要素齐全")
    void broadcastResponse() {
        String xml = CascadeManscdpXml.broadcastResponse("34020000001320000007", "992", "OK");

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"), xml);
        assertTrue(xml.contains("<Response>"), xml);
        assertTrue(xml.contains("<CmdType>Broadcast</CmdType>"), xml);
        assertTrue(xml.contains("<SN>992</SN>"), xml);
        assertTrue(xml.contains("<DeviceID>34020000001320000007</DeviceID>"), xml);
        assertTrue(xml.contains("<Result>OK</Result>"), xml);
        assertTrue(xml.endsWith("</Response>\n"), xml);
    }

    @Test
    @DisplayName("设备信息：Channel 即使为 0 也必须出现")
    void deviceInfo_keepsZeroChannel() {
        String xml = CascadeManscdpXml.deviceInfoResponse(new CascadeManscdpXml.DeviceInfoView(
            "34020000002000000001", "1", "平台", "OK", "Voglander", "CascadePlatform", "1.10.1", 0));

        assertTrue(xml.contains("<CmdType>DeviceInfo</CmdType>"), xml);
        assertTrue(xml.contains("<DeviceName>平台</DeviceName>"), xml);
        assertTrue(xml.contains("<Result>OK</Result>"), xml);
        assertTrue(xml.contains("<Channel>0</Channel>"), xml);
    }

    @Test
    @DisplayName("设备信息：特殊字符转义，避免整包非法")
    void deviceInfo_escapesName() {
        String xml = CascadeManscdpXml.deviceInfoResponse(new CascadeManscdpXml.DeviceInfoView(
            "1", "1", "A&B<C>", "OK", "V", "M", "F", 1));

        assertTrue(xml.contains("<DeviceName>A&amp;B&lt;C&gt;</DeviceName>"), xml);
        assertFalse(xml.contains("A&B<C>"), "未转义会让上级解析失败: " + xml);
    }

    @Test
    @DisplayName("设备状态：DeviceTime 用国标 yyyy-MM-ddTHH:mm:ss")
    void deviceStatus_formatsDeviceTime() {
        String xml = CascadeManscdpXml.deviceStatusResponse(new CascadeManscdpXml.DeviceStatusView(
            "1", "2", "OK", true, "ON", "OFF", LocalDateTime.of(2026, 10, 30, 9, 8, 7)));

        assertTrue(xml.contains("<Online>ONLINE</Online>"), xml);
        assertTrue(xml.contains("<DeviceTime>2026-10-30T09:08:07</DeviceTime>"), xml);
        assertTrue(xml.contains("<Encode>ON</Encode>"), xml);
        assertTrue(xml.contains("<Record>OFF</Record>"), xml);
    }

    @Test
    @DisplayName("设备状态：离线时 Online 为 OFFLINE")
    void deviceStatus_offline() {
        String xml = CascadeManscdpXml.deviceStatusResponse(new CascadeManscdpXml.DeviceStatusView(
            "1", "2", "OK", false, null, null, null));

        assertTrue(xml.contains("<Online>OFFLINE</Online>"), xml);
        assertTrue(xml.contains("<Record>OFF</Record>"), "缺省 Record 为 OFF: " + xml);
    }

    @Test
    @DisplayName("配置下载：BasicParam 四个字段")
    void configDownload_basicParam() {
        String xml = CascadeManscdpXml.configDownloadResponse("1", "3",
            new CascadeManscdpXml.BasicParamView("平台", 3600, 60, 3));

        assertTrue(xml.contains("<CmdType>ConfigDownload</CmdType>"), xml);
        assertTrue(xml.contains("<BasicParam>"), xml);
        assertTrue(xml.contains("<Name>平台</Name>"), xml);
        assertTrue(xml.contains("<Expiration>3600</Expiration>"), xml);
        assertTrue(xml.contains("<HeartBeatInterval>60</HeartBeatInterval>"), xml);
        assertTrue(xml.contains("<HeartBeatCount>3</HeartBeatCount>"), xml);
    }

    @Test
    @DisplayName("配置下载：BasicParam 全空时整段省略")
    void configDownload_omitsEmptyBasicParam() {
        String xml = CascadeManscdpXml.configDownloadResponse("1", "3", null);

        assertTrue(xml.contains("<Result>OK</Result>"), xml);
        assertFalse(xml.contains("<BasicParam>"), xml);
    }

    @Test
    @DisplayName("预置位：空列表也要带 Num=\"0\"")
    void presetQuery_emptyKeepsNum() {
        String xml = CascadeManscdpXml.presetQueryResponse("1", "4", List.of());

        assertTrue(xml.contains("<PresetList Num=\"0\">"), xml);
        assertFalse(xml.contains("<Item>"), xml);
    }

    @Test
    @DisplayName("预置位：条目按 PresetID/PresetName 渲染")
    void presetQuery_items() {
        String xml = CascadeManscdpXml.presetQueryResponse("1", "4",
            List.of(new CascadeManscdpXml.PresetItemView("1", "大门"), new CascadeManscdpXml.PresetItemView("2", "后门")));

        assertTrue(xml.contains("<PresetList Num=\"2\">"), xml);
        assertTrue(xml.contains("<PresetID>1</PresetID>"), xml);
        assertTrue(xml.contains("<PresetName>后门</PresetName>"), xml);
    }
}
