package io.github.lunasaw.voglander.intergration.wrapper.channel.support;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 海康 ISAPI 应答解析（只读 XML → 纯数据）。
 *
 * <p>
 * 只管"把报文读懂"，不拼地址、不落库：{@link #parseChannels} 给出的是设备自己声明的通道清单，
 * RTSP 地址由 {@code HikvisionChannelDiscoverer} 按厂商规则生成。
 * </p>
 *
 * <p>
 * 安全：设备应答也算外部输入，解析前关闭 DTD 与外部实体引用（XXE），并关闭命名空间感知——
 * ISAPI 的根元素带 {@code xmlns="http://www.hikvision.com/ver20/XMLSchema"}，但标签本身无前缀，
 * 按 tagName 匹配更省事。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
public final class IsapiXmlParser {

    /** 在线标记值（ISAPI 用 true/false） */
    private static final String ONLINE_TRUE = "true";

    private static final int    STATUS_ONLINE = 1;

    private static final int    STATUS_OFFLINE = 0;

    private IsapiXmlParser() {
    }

    /**
     * 解析设备信息应答里的某个字段（如 manufacturer / model / firmwareVersion）。
     *
     * @return 字段值；不存在返回 null
     */
    public static String deviceInfoField(String xml, String tag) {
        Document doc = parse(xml);
        return text(doc, tag);
    }

    /**
     * 解析 {@code /ISAPI/ContentMgmt/InputProxy/channels}：NVR 的通道台账。
     *
     * @return 通道清单；报文里没有通道时返回空列表
     */
    public static List<IsapiChannel> parseChannels(String xml) {
        Document doc = parse(xml);
        List<Element> nodes = elements(doc, "InputProxyChannel");
        List<IsapiChannel> channels = new ArrayList<>(nodes.size());
        for (Element node : nodes) {
            String id = text(node, "id");
            if (id == null || id.isBlank()) {
                // 没有通道号就无法拼地址，直接跳过；宁可少一条也不造出不可用的通道
                continue;
            }
            channels.add(new IsapiChannel(
                id.trim(),
                text(node, "name"),
                text(node, "proxyProtocol"),
                text(node, "ipAddress"),
                intOrNull(text(node, "managePortNo")),
                intOrNull(text(node, "srcInputPort")),
                text(node, "model"),
                text(node, "firmwareVersion")));
        }
        return channels;
    }

    /**
     * 解析 {@code /ISAPI/ContentMgmt/InputProxy/channels/status}：通道在线态。
     *
     * @return channelId → 1/0；报文里没有状态的通道不会出现在结果里
     */
    public static Map<String, Integer> parseChannelStatus(String xml) {
        Document doc = parse(xml);
        List<Element> nodes = elements(doc, "InputProxyChannelStatus");
        Map<String, Integer> statusMap = new HashMap<>(nodes.size());
        for (Element node : nodes) {
            String id = text(node, "id");
            String online = text(node, "online");
            if (id == null || id.isBlank() || online == null) {
                continue;
            }
            statusMap.put(id.trim(), ONLINE_TRUE.equalsIgnoreCase(online.trim()) ? STATUS_ONLINE : STATUS_OFFLINE);
        }
        return statusMap;
    }

    private static Document parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("设备应答为空");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE：设备应答属外部输入，禁用 DTD 与外部实体
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("设备应答不是合法 XML：" + e.getMessage(), e);
        }
    }

    private static List<Element> elements(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        List<Element> result = new ArrayList<>(list.getLength());
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static String text(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : blankToNull(list.item(0).getTextContent());
    }

    /**
     * 取子元素文本：空标签（如离线通道的 {@code <model></model>}）统一返回 null，
     * 避免把空串写进库当成"型号是一个空字符串"。
     */
    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : blankToNull(list.item(0).getTextContent());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer intOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * ISAPI 台账里的一条通道（原样字段，未做任何平台侧加工）。
     */
    public record IsapiChannel(
        String channelId,
        String name,
        String proxyProtocol,
        String sourceIp,
        Integer sourcePort,
        Integer sourceChannel,
        String sourceModel,
        String sourceFirmware) {
    }
}
