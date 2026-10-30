package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogAssembler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogNode;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMeta;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMetaResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
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
    private CascadeChannelManager  cascadeChannelManager;
    @Mock
    private DeviceChannelManager   deviceChannelManager;
    @Mock
    private CascadeChannelMetaResolver channelMetaResolver;

    private CascadeCatalogAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new CascadeCatalogAssembler(cascadePlatformManager, cascadeOrgManager,
            cascadeChannelManager, deviceChannelManager, channelMetaResolver);

        CascadePlatformDTO platform = new CascadePlatformDTO();
        platform.setPlatformId(PLATFORM_ID);
        platform.setLocalClientId(DOMAIN_ID);
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform);
        when(deviceChannelManager.mapChannelStatus(any())).thenReturn(new HashMap<>());
        /* 默认不派生：通道项走 CascadeChannelMeta.DEFAULT（地址 China、枪机 3） */
        when(channelMetaResolver.resolve(any())).thenReturn(new HashMap<>());
    }

    private CascadeOrgDTO org(Long id, String parentId, String code, String name) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setId(id);
        dto.setPlatformId(PLATFORM_ID);
        dto.setParentOrgId(parentId == null ? null : Long.valueOf(parentId));
        dto.setCascadeOrgId(code);
        dto.setOrgName(name);
        dto.setEnabled(1);
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

    /** 两层组织 + 一个挂在子组织下的通道，父编码走真实的 resolveParentCode 语义。 */
    private List<CascadeCatalogNode> assembleTree() {
        CascadeOrgDTO top = org(1L, null, ORG_TOP, "一层");
        CascadeOrgDTO sub = org(2L, "1", ORG_SUB, "二层");
        when(cascadeOrgManager.listEffective(PLATFORM_ID)).thenReturn(List.of(top, sub));

        Map<Long, CascadeOrgDTO> byId = new HashMap<>();
        byId.put(1L, top);
        byId.put(2L, sub);
        when(cascadeOrgManager.indexById(any())).thenReturn(byId);

        when(cascadeOrgManager.resolveParentCode(eq(top), any(), eq(DOMAIN_ID))).thenReturn(DOMAIN_ID);
        when(cascadeOrgManager.resolveParentCode(eq(sub), any(), eq(DOMAIN_ID))).thenReturn(ORG_TOP);

        when(cascadeChannelManager.listByPlatformId(PLATFORM_ID)).thenReturn(List.of(channel(ORG_SUB)));
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
    @DisplayName("通道所属组织未生效时改挂域节点，CivilCode 也随之等于域编码")
    void channel_fallback_to_domain_keeps_civil_code_in_sync() {
        when(cascadeOrgManager.listEffective(PLATFORM_ID)).thenReturn(List.of());
        when(cascadeOrgManager.indexById(any())).thenReturn(Map.of());
        when(cascadeChannelManager.listByPlatformId(PLATFORM_ID)).thenReturn(List.of(channel(ORG_SUB)));

        List<CascadeCatalogNode> nodes = assembler.assemble(PLATFORM_ID, PLATFORM_ID);

        assertEquals(2, nodes.size(), "域节点 + 一个通道");
        CascadeCatalogNode ch = nodes.get(1);
        assertEquals(DOMAIN_ID, ch.parentId(), "组织不存在时通道挂域节点");
        assertEquals(DOMAIN_ID, ch.civilCode(), "CivilCode 必须跟着 ParentID 走：" + ch);
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

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
