package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogAssembler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogNode;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMeta;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMetaResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishedAssetResolver;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;

/**
 * {@link CascadeCatalogAssembler} 字段规则测试。
 *
 * <p>重点锁住一条对接海康的硬规则：<b>非根节点的 {@code CivilCode} 必须等于 {@code ParentID}</b>
 * （参考实现 CSS 把设备项的 {@code CivilCode} 直接写成 {@code ParentSn}）。此前的实现固定写
 * 省市前缀 {@code 340200}，与父节点编码不一致，海康会把它当成别的区域下的无效条目，表现为
 * 「SIP 回 200、上级目录里却没有这些节点」。
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeCatalogAssembler 单元测试")
class CascadeCatalogAssemblerTest {

    private static final String PLATFORM_ID = "34020000002000000001";
    private static final String DOMAIN_ID   = "34020000002000000001";
    private static final String ORG_TOP     = "34020000002150000001";
    private static final String ORG_SUB     = "34020000002150000002";
    private static final String CHANNEL_ID  = "34020000001320000001";

    @Mock
    private CascadePlatformManager cascadePlatformManager;
    @Mock
    private CascadeOrgManager      cascadeOrgManager;
    @Mock
    private CascadePublishedAssetResolver publishedAssetResolver;
    @Mock
    private DeviceChannelManager   deviceChannelManager;
    @Mock
    private CascadeChannelMetaResolver channelMetaResolver;

    private CascadeCatalogAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new CascadeCatalogAssembler(cascadePlatformManager, cascadeOrgManager,
            publishedAssetResolver, deviceChannelManager, channelMetaResolver);

        CascadePlatformDTO platform = platform(0);
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform);
        when(deviceChannelManager.mapChannelStatus(any())).thenReturn(new HashMap<>());
        /* 默认不派生：通道项走 CascadeChannelMeta.DEFAULT（地址 China、枪机 3） */
        when(channelMetaResolver.resolve(any())).thenReturn(new HashMap<>());
    }

    /** 平台配置：{@code deviceLayer} 0=通道直挂组织，1=多插一层设备节点 */
    private CascadePlatformDTO platform(int deviceLayer) {
        CascadePlatformDTO platform = new CascadePlatformDTO();
        platform.setPlatformId(PLATFORM_ID);
        platform.setLocalClientId(DOMAIN_ID);
        platform.setCatalogWithDeviceLayer(deviceLayer);
        return platform;
    }

    /**
     * 目录节点：{@code platformCode} 是本平台的上报编码（可能被发布行覆盖），
     * {@code parentPlatformCode} 是已解析好的父节点上报编码（null 时组装器回落域编码）。
     */
    private CascadePlatformOrgDTO platformOrg(Long id, String localCode, String platformCode,
        String parentPlatformCode, String name, int sortOrder) {
        CascadePlatformOrgDTO dto = new CascadePlatformOrgDTO();
        dto.setId(id);
        dto.setLocalCode(localCode);
        dto.setPlatformCode(platformCode);
        dto.setParentPlatformCode(parentPlatformCode);
        dto.setName(name);
        dto.setSortOrder(sortOrder);
        return dto;
    }

    private CascadeChannelDTO channel(String orgCode) {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setPlatformId(PLATFORM_ID);
        dto.setLocalDeviceId("DEV1");
        dto.setLocalChannelId("LOCAL1");
        dto.setCascadeChannelId(CHANNEL_ID);
        dto.setCascadeName("前门");
        dto.setCascadeOrgId(orgCode);
        dto.setEnabled(1);
        return dto;
    }

    /** 一条已发布设备：设备层开启时它会成为目录里的一层。 */
    private CascadePlatformDeviceDTO device(String orgCode) {
        CascadePlatformDeviceDTO device = new CascadePlatformDeviceDTO();
        device.setLocalDeviceId("DEV1");
        device.setPlatformCode("DEV1");
        device.setName("门口设备");
        device.setLocalOrgCode(orgCode);
        device.setSortOrder(0);
        device.setStatus(1);
        return device;
    }

    /** 两层组织 + 一个挂在子组织下的通道；组织视图由 CascadeOrgManager 预处理好父子与编码。 */
    private List<CascadeCatalogNode> assembleTree() {
        CascadePlatformOrgDTO top = platformOrg(1L, ORG_TOP, ORG_TOP, null, "一层", 1);
        CascadePlatformOrgDTO sub = platformOrg(2L, ORG_SUB, ORG_SUB, ORG_TOP, "二层", 2);
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID))
            .thenReturn(new CascadePlatformOrgView(List.of(top, sub), Set.of()));

        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel(ORG_SUB)));
        return assembler.assemble(PLATFORM_ID, PLATFORM_ID);
    }

    @Test
    @DisplayName("组织与通道的 CivilCode 必须等于自己的 ParentID")
    void civil_code_follows_parent() {
        List<CascadeCatalogNode> nodes = assembleTree();

        assertEquals(4, nodes.size(), "域 + 两层组织 + 一个通道");

        CascadeCatalogNode domain = nodes.get(0);
        assertNull(domain.parentId(), "域节点是根，不带 ParentID");
        assertEquals("340200", domain.civilCode(), "域节点保留省市前缀");

        CascadeCatalogNode top = nodes.get(1);
        assertEquals(DOMAIN_ID, top.parentId());
        assertEquals(top.parentId(), top.civilCode(), "根组织挂在域节点上，CivilCode 必须是域编码：" + top);

        CascadeCatalogNode sub = nodes.get(2);
        assertEquals(ORG_TOP, sub.parentId());
        assertEquals(sub.parentId(), sub.civilCode(), "子组织 CivilCode 必须是父组织编码：" + sub);

        CascadeCatalogNode ch = nodes.get(3);
        assertEquals(ORG_SUB, ch.parentId());
        assertEquals(ch.parentId(), ch.civilCode(), "通道 CivilCode 必须是所属组织编码：" + ch);
    }

    @Test
    @DisplayName("渲染报文里 CivilCode 与 ParentID 同值，且不再出现 340200 常量")
    void rendered_xml_carries_parent_as_civil_code() {
        List<CascadeCatalogNode> nodes = assembleTree();

        String xml = assembler.renderNotify(DOMAIN_ID, "11449", nodes.size(), nodes);

        assertTrue(xml.contains("<ParentID>" + ORG_SUB + "</ParentID>"), xml);
        assertTrue(xml.contains("<CivilCode>" + ORG_SUB + "</CivilCode>"), xml);
        assertTrue(xml.contains("<CivilCode>" + ORG_TOP + "</CivilCode>"), xml);
        /* 固定前缀只允许出现在域节点上；组织/通道用父编码，否则海康会把节点归到错误区域 */
        assertEquals(1, countOccurrences(xml, "<CivilCode>340200</CivilCode>"),
            "只有域节点可以带固定前缀: " + xml);
    }

    @Test
    @DisplayName("通道未归属任何组织时直接挂域节点，CivilCode 也随之等于域编码")
    void channel_without_org_hangs_under_domain() {
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID)).thenReturn(CascadePlatformOrgView.empty());
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel(null)));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(2, nodes.size(), "域节点 + 一个通道");
        CascadeCatalogNode ch = nodes.get(1);
        assertEquals(DOMAIN_ID, ch.parentId(), "没有归属组织时通道挂域节点");
        assertEquals(DOMAIN_ID, ch.civilCode(), "CivilCode 必须跟着 ParentID 走：" + ch);
    }

    @Test
    @DisplayName("组织不在该平台目录里且未配 REROUTE 时，其下通道不上报")
    void channel_of_unpublished_org_is_dropped() {
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID)).thenReturn(CascadePlatformOrgView.empty());
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel(ORG_SUB)));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(1, nodes.size(), "默认 CASCADE：组织没进去，通道也不该单独出现: " + nodes);
    }

    @Test
    @DisplayName("阻断组织配了 CASCADE 时，其下通道不进快照")
    void revoked_org_with_cascade_policy_hides_channels() {
        /* 组织没进目录即已被阻断（本地停用或未发布），策略已由管理器折算成 reroutedLocalCodes 的有无 */
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID)).thenReturn(CascadePlatformOrgView.empty());
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel(ORG_SUB)));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        /* 只剩域节点：若跟着只发了 DEL 却不剪快照，下一次推快照（心跳/查询/重订阅）会把通道加回来 */
        assertEquals(1, nodes.size(), "CASCADE 策略下通道必须一起消失: " + nodes);
    }

    @Test
    @DisplayName("阻断组织配了 REROUTE 时，通道改挂域节点继续上报")
    void revoked_org_with_reroute_policy_keeps_channels() {
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID))
            .thenReturn(new CascadePlatformOrgView(List.of(), Set.of(ORG_SUB)));
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel(ORG_SUB)));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(2, nodes.size(), "域节点 + 上浮的通道: " + nodes);
        assertEquals(DOMAIN_ID, nodes.get(1).parentId(), "REROUTE 下通道改挂域节点");
    }

    @Test
    @DisplayName("通道项带上解析后的厂商/型号/IP/摄像机类型，域与组织节点不带")
    void channel_item_carries_resolved_meta() {
        when(channelMetaResolver.resolve(any())).thenReturn(Map.of(
            DeviceChannelManager.channelKey("DEV1", "LOCAL1"),
            new CascadeChannelMeta("Hik", "DS-2CD3T46", "杭州西湖", "192.168.1.64", 1)));

        List<CascadeCatalogNode> nodes = assembleTree();
        String xml = assembler.renderNotify(DOMAIN_ID, "11449", nodes.size(), nodes);

        assertTrue(xml.contains("<Manufacturer>Hik</Manufacturer>"), xml);
        assertTrue(xml.contains("<Model>DS-2CD3T46</Model>"), xml);
        assertTrue(xml.contains("<Address>杭州西湖</Address>"), xml);
        assertTrue(xml.contains("<IPAddress>192.168.1.64</IPAddress>"), xml);
        assertTrue(xml.contains("<PTZType>1</PTZType>"), xml);
        /* 域与组织不是视频设备，整个报文只允许一个 Info 段 */
        assertEquals(1, countOccurrences(xml, "<Info>"), "只有通道项可以带 Info: " + xml);
        assertNull(nodes.get(1).ptzType(), "组织节点不带摄像机类型");
        assertNull(nodes.get(1).ipAddress(), "组织节点不带设备 IP");
    }

    @Test
    @DisplayName("解析结果缺失时通道项回落枪机 3，PTZType 不会缺席")
    void channel_item_defaults_to_bullet_when_meta_missing() {
        List<CascadeCatalogNode> nodes = assembleTree();
        /* 未 mock 的 key 走 DEFAULT：地址 China、类型 3 */
        CascadeCatalogNode ch = nodes.get(3);
        assertEquals("China", ch.address());
        assertEquals(3, ch.ptzType());
    }

    @Test
    @DisplayName("报文外观对齐 CSS：LF 换行、单空格缩进、GB2312 声明")
    void rendered_xml_matches_css_shape() {
        List<CascadeCatalogNode> nodes = assembleTree();
        String xml = assembler.renderNotify(DOMAIN_ID, "11449", nodes.size(), nodes);

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>\n"), xml.substring(0, Math.min(80, xml.length())));
        assertTrue(!xml.contains("\r"), "不允许残留 CR：上级按行拆分时会带上多余字符");
        assertTrue(xml.contains("\n <CmdType>Catalog</CmdType>\n"), "除首行外每行一个前导空格");
        assertTrue(xml.contains("\n <Item>\n"), xml);
        assertTrue(xml.trim().endsWith("</Notify>"), xml);
    }

    @Test
    @DisplayName("Parental 按节点类型：域/组织为 1，通道为 0")
    void parental_follows_node_kind() {
        List<CascadeCatalogNode> nodes = assembleTree();

        assertEquals(1, nodes.get(0).parental(), "域节点是目录节点");
        assertEquals(1, nodes.get(1).parental(), "组织节点是目录节点");
        assertEquals(0, nodes.get(3).parental(), "通道不是目录节点，否则上级会把它当可点播设备");

        String xml = assembler.renderNotify(DOMAIN_ID, "11449", nodes.size(), nodes);
        assertEquals(3, countOccurrences(xml, "<Parental>1</Parental>"), xml);
        assertEquals(1, countOccurrences(xml, "<Parental>0</Parental>"), xml);
    }

    @Test
    @DisplayName("开启设备层时插入设备节点，未归属的通道默认挂到设备下")
    void device_layer_inserts_device_nodes() {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform(1));
        CascadePlatformOrgDTO top = platformOrg(1L, ORG_TOP, ORG_TOP, null, "一层", 1);
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID))
            .thenReturn(new CascadePlatformOrgView(List.of(top), Set.of()));
        when(publishedAssetResolver.listDevices(PLATFORM_ID)).thenReturn(List.of(device(ORG_TOP)));
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel(null)));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(4, nodes.size(), "域 + 组织 + 设备 + 通道: " + nodes);
        CascadeCatalogNode dev = nodes.get(2);
        assertEquals(CascadeCatalogNode.Kind.DEVICE, dev.kind());
        assertEquals(ORG_TOP, dev.parentId(), "设备挂在它归属的组织下");
        assertEquals("ON", dev.status(), "在线状态取自本地设备");
        assertEquals(1, dev.parental(), "设备是目录节点");
        assertEquals("DEV1", nodes.get(3).parentId(), "通道自己没有归属时跟随设备");
    }

    @Test
    @DisplayName("设备层关闭时不插设备节点，通道仍挂组织")
    void device_layer_disabled_keeps_channels_under_org() {
        CascadePlatformOrgDTO top = platformOrg(1L, ORG_TOP, ORG_TOP, null, "一层", 1);
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID))
            .thenReturn(new CascadePlatformOrgView(List.of(top), Set.of()));
        when(publishedAssetResolver.listDevices(PLATFORM_ID)).thenReturn(List.of(device(ORG_TOP)));
        /* 发布层解析器会用设备归属回填 deviceOrgCode，这里直接给出解析结果 */
        CascadeChannelDTO channel = channel(null);
        channel.setDeviceOrgCode(ORG_TOP);
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(3, nodes.size(), "域 + 组织 + 通道，设备发布行被忽略: " + nodes);
        assertTrue(nodes.stream().noneMatch(n -> n.kind() == CascadeCatalogNode.Kind.DEVICE));
        assertEquals(ORG_TOP, nodes.get(2).parentId(), "无设备层时通道回落设备归属组织");
    }

    @Test
    @DisplayName("设备归属组织不可见时设备节点不出现，其下通道一起消失")
    void device_with_invisible_org_is_dropped() {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform(1));
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID)).thenReturn(CascadePlatformOrgView.empty());
        when(publishedAssetResolver.listDevices(PLATFORM_ID)).thenReturn(List.of(device(ORG_SUB)));
        CascadeChannelDTO channel = channel(null);
        channel.setDeviceOrgCode(ORG_SUB);
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(1, nodes.size(), "组织 CASCADE：设备与通道都不能凭空出现: " + nodes);
    }

    @Test
    @DisplayName("发布行 parent_key 命中时优先于本地结构推导")
    void parent_key_override_wins() {
        CascadePlatformOrgDTO top = platformOrg(1L, ORG_TOP, ORG_TOP, null, "一层", 1);
        CascadePlatformOrgDTO sub = platformOrg(2L, ORG_SUB, ORG_SUB, ORG_TOP, "二层", 2);
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID))
            .thenReturn(new CascadePlatformOrgView(List.of(top, sub), Set.of()));

        CascadeChannelDTO channel = channel(ORG_SUB);
        channel.setParentKey(ORG_TOP);
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(ORG_TOP, nodes.get(nodes.size() - 1).parentId(),
            "同一通道在不同上级可以挂不同父节点，parent_key 优先");
    }

    @Test
    @DisplayName("parent_key 指向不在该平台目录里的节点时回落本地结构，不造孤儿")
    void parent_key_pointing_outside_catalog_falls_back() {
        CascadePlatformOrgDTO top = platformOrg(1L, ORG_TOP, ORG_TOP, null, "一层", 1);
        CascadePlatformOrgDTO sub = platformOrg(2L, ORG_SUB, ORG_SUB, ORG_TOP, "二层", 2);
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID))
            .thenReturn(new CascadePlatformOrgView(List.of(top, sub), Set.of()));

        CascadeChannelDTO channel = channel(ORG_SUB);
        channel.setParentKey("34020000002150009999");
        when(publishedAssetResolver.listChannels(PLATFORM_ID)).thenReturn(List.of(channel));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(ORG_SUB, nodes.get(nodes.size() - 1).parentId(),
            "目标节点不存在时只能回落本地归属，否则上级拿到一条挂不住的节点");
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
