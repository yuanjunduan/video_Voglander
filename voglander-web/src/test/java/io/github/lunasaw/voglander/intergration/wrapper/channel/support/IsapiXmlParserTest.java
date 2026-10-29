package io.github.lunasaw.voglander.intergration.wrapper.channel.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link IsapiXmlParser} 单元测试：报文解析、空值归一、XXE 防护。
 *
 * <p>
 * 用例报文取自真实设备（海康 NVR，192.168.60.165）的 ISAPI 应答，保留命名空间与空标签写法，
 * 避免"用自己臆想的 XML 测自己写的解析器"。
 * </p>
 *
 * @author luna
 */
@DisplayName("ISAPI 应答解析")
class IsapiXmlParserTest {

    private static final String CHANNELS_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <InputProxyChannelList xmlns="http://www.hikvision.com/ver20/XMLSchema" version="2.0">
        <InputProxyChannel>
        <id>1</id>
        <name>IPCamera 01</name>
        <sourceInputPortDescriptor>
        <ipAddress>192.168.60.204</ipAddress>
        <managePortNo>80</managePortNo>
        <srcInputPort>1</srcInputPort>
        <proxyProtocol>ONVIF</proxyProtocol>
        </sourceInputPortDescriptor>
        </InputProxyChannel>
        <InputProxyChannel>
        <id>3</id>
        <name>海康IPC-直连-60.2</name>
        <sourceInputPortDescriptor>
        <ipAddress>192.168.60.2</ipAddress>
        <managePortNo>8000</managePortNo>
        <srcInputPort>1</srcInputPort>
        <proxyProtocol>HIKVISION</proxyProtocol>
        </sourceInputPortDescriptor>
        </InputProxyChannel>
        <InputProxyChannel>
        <id></id>
        <name>无编号通道</name>
        </InputProxyChannel>
        </InputProxyChannelList>
        """;

    private static final String STATUS_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <InputProxyChannelStatusList version="2.0">
        <InputProxyChannelStatus>
        <id>1</id>
        <online>false</online>
        </InputProxyChannelStatus>
        <InputProxyChannelStatus>
        <id>3</id>
        <online>true</online>
        </InputProxyChannelStatus>
        </InputProxyChannelStatusList>
        """;

    @Test
    @DisplayName("台账：按通道号解析名称/源IP/端口/接入协议，无编号的通道跳过")
    void parseChannels_shouldReadLedgerAndSkipIdless() {
        List<IsapiXmlParser.IsapiChannel> channels = IsapiXmlParser.parseChannels(CHANNELS_XML);

        assertEquals(2, channels.size(), "没有 <id> 的通道无法拼地址，应被跳过");

        IsapiXmlParser.IsapiChannel first = channels.get(0);
        assertEquals("1", first.channelId());
        assertEquals("IPCamera 01", first.name());
        assertEquals("192.168.60.204", first.sourceIp());
        assertEquals(80, first.sourcePort());
        assertEquals(1, first.sourceChannel());
        assertEquals("ONVIF", first.proxyProtocol());

        IsapiXmlParser.IsapiChannel second = channels.get(1);
        assertEquals("3", second.channelId());
        assertEquals("海康IPC-直连-60.2", second.name());
        assertEquals(8000, second.sourcePort());
        assertEquals("HIKVISION", second.proxyProtocol());
    }

    @Test
    @DisplayName("状态：online=true 记 1，其余记 0")
    void parseChannelStatus_shouldMapOnlineFlag() {
        Map<String, Integer> status = IsapiXmlParser.parseChannelStatus(STATUS_XML);

        assertEquals(2, status.size());
        assertEquals(0, status.get("1"));
        assertEquals(1, status.get("3"));
    }

    @Test
    @DisplayName("设备信息：取指定字段，缺失返回 null")
    void deviceInfoField_shouldReadSingleField() {
        String xml = """
            <DeviceInfo version="2.0">
            <deviceName>NVR</deviceName>
            <deviceID>DS-7608N-K2</deviceID>
            <model>DS-7608N-K2</model>
            <manufacturer>hikvision</manufacturer>
            <firmwareVersion>V4.30.000</firmwareVersion>
            </DeviceInfo>
            """;

        assertEquals("hikvision", IsapiXmlParser.deviceInfoField(xml, "manufacturer"));
        assertEquals("DS-7608N-K2", IsapiXmlParser.deviceInfoField(xml, "model"));
        assertNull(IsapiXmlParser.deviceInfoField(xml, "notExist"));
    }

    @Test
    @DisplayName("空标签归一为 null：离线通道的 <model></model> 不应变成空字符串")
    void parseChannels_shouldTreatBlankFieldAsNull() {
        String xml = """
            <InputProxyChannelList>
            <InputProxyChannel>
            <id>2</id>
            <name>楼顶云台</name>
            <model></model>
            </InputProxyChannel>
            </InputProxyChannelList>
            """;

        List<IsapiXmlParser.IsapiChannel> channels = IsapiXmlParser.parseChannels(xml);

        assertEquals(1, channels.size());
        assertNull(channels.get(0).sourceModel());
    }

    @Test
    @DisplayName("XXE：带 DOCTYPE 的应答直接拒绝解析")
    void parseChannels_shouldRejectDoctype() {
        String evil = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <InputProxyChannelList><InputProxyChannel><id>&xxe;</id></InputProxyChannel></InputProxyChannelList>
            """;

        assertThrows(IllegalArgumentException.class, () -> IsapiXmlParser.parseChannels(evil));
    }

    @Test
    @DisplayName("非 XML 应答当外部输入处理：抛出可读异常，不返回半成品")
    void parseChannels_shouldRejectNonXml() {
        assertThrows(IllegalArgumentException.class, () -> IsapiXmlParser.parseChannels("not xml at all"));
        assertThrows(IllegalArgumentException.class, () -> IsapiXmlParser.parseChannels("  "));
    }

    @Test
    @DisplayName("空台账：合法 XML 但无通道时返回空列表而非异常")
    void parseChannels_shouldReturnEmptyList() {
        List<IsapiXmlParser.IsapiChannel> channels =
            IsapiXmlParser.parseChannels("<InputProxyChannelList></InputProxyChannelList>");

        assertNotNull(channels);
        assertTrue(channels.isEmpty());
        assertFalse(channels.size() > 0);
    }
}
