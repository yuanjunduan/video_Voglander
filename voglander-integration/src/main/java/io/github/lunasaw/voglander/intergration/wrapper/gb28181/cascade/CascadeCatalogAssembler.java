package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.github.lunasaw.gb28181.common.entity.response.DeviceItem;
import io.github.lunasaw.voglander.common.constant.device.DeviceConstant;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlacementIndex;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishedAssetResolver;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联目录组装器：把本地数据拼成上级要的目录树。
 *
 * <p><b>存在的意义</b>：上级有两条路径拿目录——主动 {@code Query Catalog}（我们要回 Response）
 * 和订阅后的 {@code Catalog NOTIFY}（我们主动推）。两条路径必须产出<b>完全一致</b>的节点集合，
 * 否则「查询看到的」和「推送看到的」会长期漂移。此前两处各自拼报文，字段一多就同步不上，
 * 因此统一收口到这里。
 *
 * <p>节点顺序固定为：域节点 → 组织节点（按 sortOrder）→ [设备节点] → 通道节点，域节点恒在首位。
 *
 * <p><b>只认发布集</b>：上级看到的节点来自两份派生视图——组织来自 {@code platformOrgView}
 * （本地启用链完整 ∩ 该平台已发布），设备与通道来自 {@code CascadePublishedAssetResolver}
 * （发布行 ∩ 本地启用）。本地组织树只有一棵，多上级只是同一棵树被组装成不同视图，
 * 而不是维护多棵树。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeCatalogAssembler {

    /** 域节点的行政区划码，取本平台编码的省市前缀；组织/通道节点不取此值（见 {@link #assemble}） */
    private static final String CIVIL_CODE    = "340200";
    /** 平台节点名称 */
    private static final String PLATFORM_NAME = "Voglander";
    /** 报文换行：对齐 CSS 参考实现的 {@code \n}，上级日志按行拆分时不会带上残留的 {@code \r} */
    private static final String LF            = "\n";
    /** 报文首行之后每行的固定前导空格（对齐 CSS {@code xml.MarshalIndent(v, " ", "")}） */
    private static final String INDENT        = " ";

    private final CascadePlatformManager    cascadePlatformManager;
    private final CascadeOrgManager         cascadeOrgManager;
    private final CascadePublishedAssetResolver publishedAssetResolver;
    private final DeviceChannelManager      deviceChannelManager;
    private final CascadeChannelMetaResolver channelMetaResolver;

    /**
     * 组装该上级能看到的完整目录树。
     *
     * @param platformId        上级平台编码（查 tb_cascade_platform）
     * @param fallbackDomainId  取不到平台配置时的域节点编码兜底（通常用上级查询的目标编码）
     * @return 域节点恒在首位；无有效组织/通道时只返回域节点
     */
    public List<CascadeCatalogNode> assemble(String platformId, String fallbackDomainId) {
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        String domainId = resolveDomainId(platform, fallbackDomainId);
        List<CascadeCatalogNode> nodes = new ArrayList<>();
        nodes.add(domainNode(domainId));

        /* 组织只取「该平台目录里真能立起来」的那些：祖先链没发布齐全的节点即使自己在发布集里，
           上级也会因为 ParentID 指向不存在的节点而丢弃它，不如这里就剔除 */
        CascadePlatformOrgView orgView = cascadeOrgManager.platformOrgView(platformId);
        CascadePlacementIndex parentIndex = CascadePlacementIndex.of(domainId, orgView);
        for (CascadePlatformOrgDTO org : orgView.catalogOrgs()) {
            if (StringUtils.isBlank(org.getPlatformCode())) {
                continue;
            }
            String parentCode = StringUtils.defaultIfBlank(org.getParentPlatformCode(), domainId);
            parentIndex.addOrg(org);
            nodes.add(new CascadeCatalogNode(org.getPlatformCode(),
                StringUtils.defaultIfBlank(org.getName(), org.getPlatformCode()),
                parentCode,
                domainId, PLATFORM_NAME, "CascadeOrg", civilCodeOf(parentCode), domainId,
                null, null, "ON",
                CascadeCatalogNode.Kind.ORG));
        }

        /* 设备层是对端画像：同一个上级要「域/组织/设备/通道」，另一个可能只要「域/组织/通道」。
           它只是往树里插一层，不引入新的可见性维度——设备所属组织立不起来，设备也就不出现 */
        addDeviceNodes(platform, platformId, domainId, parentIndex, nodes);

        List<CascadeChannelDTO> channels = publishedAssetResolver.listChannels(platformId);
        List<CascadeChannelDTO> visibleChannels = new ArrayList<>(channels.size());
        List<String> parentIds = new ArrayList<>(channels.size());
        for (CascadeChannelDTO channel : channels) {
            String parentId = parentIndex.resolveChannelParent(channel);
            if (parentId == null) {
                continue;
            }
            visibleChannels.add(channel);
            parentIds.add(parentId);
        }

        Map<String, Integer> statusMap = loadChannelStatus(visibleChannels);
        /* 通道项的厂商/型号/地址/IP/PTZ 类型：覆盖值优先，其余按设备通道实时数据派生 */
        Map<String, CascadeChannelMeta> metaMap = channelMetaResolver.resolve(visibleChannels);
        for (int i = 0; i < visibleChannels.size(); i++) {
            CascadeChannelDTO channel = visibleChannels.get(i);
            String parentId = parentIds.get(i);
            CascadeChannelMeta meta = metaMap.getOrDefault(
                DeviceChannelManager.channelKey(channel.getLocalDeviceId(), channel.getLocalChannelId()),
                CascadeChannelMeta.DEFAULT);
            nodes.add(new CascadeCatalogNode(channel.getCascadeChannelId(),
                StringUtils.defaultIfBlank(channel.getCascadeName(), channel.getLocalChannelId()),
                parentId,
                domainId, meta.manufacturer(), meta.model(), civilCodeOf(parentId), meta.address(),
                meta.ipAddress(), meta.ptzType(),
                channelStatus(channel, statusMap),
                CascadeCatalogNode.Kind.CHANNEL));
        }

        int orgCount = parentIndex.orgCount();
        log.debug("目录树组装完成: platformId={}, domainId={}, 组织={}, 设备={}, 通道={}",
            platformId, domainId, orgCount, parentIndex.deviceCount(), visibleChannels.size());
        return nodes;
    }

    /**
     * 把设备节点追加到目录树。
     *
     * <p>设备节点只在平台开了设备层时出现，而且必须挂在它所在的组织下：组织在该平台不可见时，
     * 设备无论是否已发布都不出现——上浮到域节点会凭空造出一个本地不存在的层级结构。
     *
     * <p>设备没有可见通道时也保留节点：目录要与发布配置一致，否则用户勾了设备却看不到节点，
     * 会以为发布没生效；通道上下线只影响 {@code Status} 字段。
     */
    private void addDeviceNodes(CascadePlatformDTO platform, String platformId, String domainId,
        CascadePlacementIndex parentIndex, List<CascadeCatalogNode> nodes) {
        if (platform == null || !platform.useDeviceLayer()) {
            return;
        }
        for (CascadePlatformDeviceDTO device : publishedAssetResolver.listDevices(platformId)) {
            String parentId = parentIndex.resolveDeviceParent(device);
            if (parentId == null) {
                continue;
            }
            parentIndex.addDevice(device);
            nodes.add(new CascadeCatalogNode(device.getPlatformCode(),
                StringUtils.defaultIfBlank(device.getName(), device.getPlatformCode()),
                parentId,
                domainId, PLATFORM_NAME, "CascadeDevice", civilCodeOf(parentId),
                null, null, null,
                deviceStatus(device),
                CascadeCatalogNode.Kind.DEVICE));
        }
    }

    /** 设备在线状态：只影响报文 {@code Status}，不影响节点是否出现。 */
    private String deviceStatus(CascadePlatformDeviceDTO device) {
        Integer status = device.getStatus();
        return status != null && status == DeviceConstant.Status.ONLINE ? "ON" : "OFF";
    }

    /** 本端平台编码：优先取平台配置，缺配置时用调用方给的兜底值。 */
    public String resolveDomainId(CascadePlatformDTO platform, String fallbackDomainId) {
        if (platform != null && StringUtils.isNotBlank(platform.getLocalClientId())) {
            return platform.getLocalClientId();
        }
        return fallbackDomainId;
    }

    /** 转成框架的 {@code DeviceItem}，供 Catalogue 应答使用（框架负责序列化）。 */
    public List<DeviceItem> toDeviceItems(List<CascadeCatalogNode> nodes) {
        List<DeviceItem> items = new ArrayList<>(nodes.size());
        for (CascadeCatalogNode node : nodes) {
            DeviceItem item = new DeviceItem();
            item.setDeviceId(node.deviceId());
            item.setName(node.name());
            item.setManufacturer(node.manufacturer());
            item.setModel(node.model());
            item.setOwner(node.owner());
            item.setCivilCode(node.civilCode());
            item.setAddress(node.address());
            /* GB/T 28181：0=非目录节点（通道），1=目录节点（域/组织/设备）。
               一律写 0 会让上级把组织节点当作可点播设备 */
            item.setParental(node.parental());
            /* 根节点不设 ParentID：框架序列化跳过 null，报文里就不会出现空标签 */
            item.setParentId(node.parentId());
            item.setSafetyWay(0);
            item.setRegisterWay(1);
            item.setIpAddress(node.ipAddress());
            item.setPtzType(node.ptzType());
            item.setStatus(node.status());
            items.add(item);
        }
        return items;
    }

    /**
     * 渲染一条 Catalog NOTIFY 的 MANSCDP 报文。
     *
     * <p>头部声明写 GB2312，实际字节由发送层 {@code CascadeManscdpXml.encode} 按
     * {@code tb_cascade_platform.charset} 转换——两边必须一致，否则上级按声明解码会把中文读成乱码。
     *
     * @param domainId 域节点编码，写在报文头的 {@code DeviceID}
     * @param sn       上级订阅/查询报文里的 SN，必须原样带回
     * @param sumNum   本次目录的<b>总节点数</b>（不是本包条数）
     */
    public String renderNotify(String domainId, String sn, int sumNum, List<CascadeCatalogNode> nodes) {
        return renderCatalog("Notify", domainId, sn, sumNum, nodes);
    }

    /**
     * 渲染一条目录查询<b>应答</b>的 MANSCDP 报文（{@code <Response>}）。
     *
     * <p>与 {@link #renderNotify} 共用同一套 Item 渲染，差别只在根元素：应答的 {@code <DeviceID>}
     * 必须原样回上级查询报文里的值，且每包 {@code SumNum} 恒为<b>总节点数</b>——框架的
     * {@code sendCatalogCommandBatched} 会把 SumNum 改写成本包条数，上级据此以为「已收满」，
     * 于是只收到第一包。
     *
     * @param deviceId 上级查询报文里的 DeviceID（原样带回）
     * @param sn       上级查询报文里的 SN（原样带回）
     * @param sumNum   本次目录的<b>总节点数</b>（不是本包条数）
     */
    public String renderCatalogResponse(String deviceId, String sn, int sumNum, List<CascadeCatalogNode> nodes) {
        return renderCatalog("Response", deviceId, sn, sumNum, nodes);
    }

    /**
     * 目录报文骨架：{@code <Notify>} 与 {@code <Response>} 仅根元素不同。
     *
     * <p><b>报文外观对齐参考实现 CSS</b>（{@code manscdp/marshal.go}）：换行用 {@code \n}、
     * 除首行外每行一个前导空格、声明 {@code encoding="GB2312"}。CSS 输出的是 UTF-8 字节、只在头部
     * 写死 GB2312，海康 iSecure Center 会按声明解码，导致中文名全成乱码——本实现在发送前按平台字符集
     * 重编字节（{@link CascadeManscdpXml#encode}），保持声明与实际字节一致。
     */
    private String renderCatalog(String root, String deviceId, String sn, int sumNum, List<CascadeCatalogNode> nodes) {
        StringBuilder xml = new StringBuilder(400 + nodes.size() * 240);
        /* 首行不缩进：与 xml.MarshalIndent 的输出一致 */
        xml.append("<?xml version=\"1.0\" encoding=\"GB2312\"?>").append(LF)
            .append('<').append(root).append('>').append(LF);
        appendElement(xml, "CmdType", "Catalog");
        appendElement(xml, "SN", sn);
        appendElement(xml, "DeviceID", deviceId);
        appendElement(xml, "SumNum", String.valueOf(sumNum));
        xml.append(INDENT).append("<DeviceList Num=\"").append(nodes.size()).append("\">").append(LF);
        for (CascadeCatalogNode node : nodes) {
            xml.append(INDENT).append("<Item>").append(LF);
            appendElement(xml, "DeviceID", node.deviceId());
            appendElement(xml, "Name", node.name());
            appendElement(xml, "Manufacturer", node.manufacturer());
            appendElement(xml, "Model", node.model());
            appendElement(xml, "Owner", node.owner());
            appendElement(xml, "CivilCode", node.civilCode());
            appendElement(xml, "Address", node.address());
            appendElement(xml, "Parental", String.valueOf(node.parental()));
            appendElement(xml, "ParentID", node.parentId());
            appendElement(xml, "SafetyWay", "0");
            appendElement(xml, "RegisterWay", "1");
            appendElement(xml, "Secrecy", "0");
            appendElement(xml, "IPAddress", node.ipAddress());
            appendElement(xml, "Status", node.status());
            appendPtzInfo(xml, node);
            xml.append(INDENT).append("</Item>").append(LF);
        }
        xml.append(INDENT).append("</DeviceList>").append(LF)
            .append("</").append(root).append('>').append(LF);
        return xml.toString();
    }

    /**
     * 通道项的视频类型：上级按 {@code Info/PTZType} 区分球机/半球/枪机，
     * 缺失时部分实现会把监控点当成非视频通道，直接影响预览与点播入口。
     * 域/组织节点不是视频设备，不发该段。
     */
    private void appendPtzInfo(StringBuilder xml, CascadeCatalogNode node) {
        if (node.kind() != CascadeCatalogNode.Kind.CHANNEL || node.ptzType() == null) {
            return;
        }
        xml.append(INDENT).append(INDENT).append("<Info>").append(LF)
            .append(INDENT).append(INDENT).append(INDENT)
            .append("<PTZType>").append(node.ptzType()).append("</PTZType>").append(LF)
            .append(INDENT).append(INDENT).append("</Info>").append(LF);
    }

    /**
     * 非根节点的行政区划码：直接取<b>父节点编码</b>，与 {@code ParentID} 保持同值。
     *
     * <p>对齐参考实现 CSS（{@code convertDevCatalogItemToGb} 把 {@code CivilCode} 写成
     * {@code ParentSn}）的实测做法：海康 iSecure Center 的目录同步按 {@code CivilCode}
     * 归属区域，与本级域编码不一致时会判为无效条目或挂到错误区域，表现为「SIP 回 200、
     * 目录里却看不到节点」。因此这里不再使用固定省市前缀。
     *
     * <p>父节点编码缺失时回落固定省市前缀，保证必选字段不空。
     */
    private String civilCodeOf(String parentId) {
        return StringUtils.isNotBlank(parentId) ? parentId : CIVIL_CODE;
    }

    /** 域节点：整棵目录的根，即本级平台自身。 */
    private CascadeCatalogNode domainNode(String domainId) {
        return new CascadeCatalogNode(domainId, PLATFORM_NAME, null, domainId, PLATFORM_NAME,
            "CascadePlatform", CIVIL_CODE, domainId, null, null, "ON", CascadeCatalogNode.Kind.DOMAIN);
    }

    /** 一次查出本次涉及设备下所有通道的状态，避免逐通道回表。 */
    private Map<String, Integer> loadChannelStatus(List<CascadeChannelDTO> channels) {
        Set<String> deviceIds = channels.stream()
            .map(CascadeChannelDTO::getLocalDeviceId)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        if (deviceIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Integer> statusMap = deviceChannelManager.mapChannelStatus(deviceIds);
        return statusMap == null ? new LinkedHashMap<>() : statusMap;
    }

    /** 真实通道离线时上级必须看到 OFF，否则点播会打到已掉线的设备上、等到超时才失败。 */
    private String channelStatus(CascadeChannelDTO channel, Map<String, Integer> statusMap) {
        Integer status = statusMap.get(
            DeviceChannelManager.channelKey(channel.getLocalDeviceId(), channel.getLocalChannelId()));
        return status != null && status == DeviceConstant.Status.ONLINE ? "ON" : "OFF";
    }

    /** 写一个可选元素：值为 null 或空白时整段省略（空标签会被上级当成非法节点）。 */
    private void appendElement(StringBuilder xml, String name, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        xml.append(INDENT).append('<').append(name).append('>')
            .append(escapeXml(value))
            .append("</").append(name).append('>').append(LF);
    }

    /** 名称可能含 {@code & < > " '}，不转义会让整条报文变成非法 XML 被上级丢弃。 */
    private String escapeXml(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
