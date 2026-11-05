package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.common.event.LocalCascadePlacementChangeEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlacementChangeListener;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishedAssetResolver;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;

/**
 * {@link CascadePlacementChangeListener} 单元测试。
 *
 * <p><b>只钉一件事</b>：{@code DEL} 报文里的 {@code ParentID} 必须是「变更前」那个父节点，
 * 且只在父节点真的换了的时候发。这两条错了，上级目录里要么留着同一通道的两份，
 * 要么把刚推上去的节点又删一次——两种都只在上级侧可见，本地日志一切正常。
 *
 * <p><b>为什么用纯 mock 而不是集成测试</b>：这里要验证的是「一份组织视图 + 一种归属覆盖」
 * 能否还原出变更前的父节点，与人造的组织表/发布表无关。落库与事件时序由
 * {@code CascadePlacementChangeEventTest} 单独覆盖。
 *
 * <p><b>为什么组织编码与上报编码刻意取不同值</b>：本地通道存的是组织编码（{@code org_code}），
 * 报文里要的是该平台的上报编码。两者在真实环境里常常不同（平台可改码），用例里若用同值，
 * 「拿错一列」这类缺陷就测不出来。
 *
 * <p><b>通道侧只走设备路径</b>：通道没有自己的组织归属，{@code scope=CHANNEL} 的事件在这里
 * 会被直接忽略。设备换组织时通道是「跟着设备走」还是「留在旧组织下」，得靠新旧父节点对比算出来，
 * 这是下面设备段用例的重点。
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadePlacementChangeListener 单元测试")
class CascadePlacementChangeListenerTest {

    private static final String PLATFORM_ID  = "34020000002000000001";
    private static final String DOMAIN_ID    = "34020000002000000009";
    private static final String DEVICE_ID    = "34020000001320000001";
    private static final String CHANNEL_ID   = "34020000001310000001";
    /** 上级眼里的通道编码：与本地通道标识不同，证明 DEL 用的是上报编码而不是本地键 */
    private static final String CHANNEL_CODE = "34020000001310000077";
    private static final String DEVICE_CODE  = "34020000001320000077";
    private static final String OBJECT_KEY   = DEVICE_ID + ":" + CHANNEL_ID;

    /** 本地组织编码（{@code tb_cascade_org.org_code}），通道/设备表里存的就是它 */
    private static final String LOCAL_ORG_A  = "ORG-LOCAL-A";
    private static final String LOCAL_ORG_B  = "ORG-LOCAL-B";
    /** 两台设备各自的上报编码 */
    private static final String PLAT_ORG_A   = "ORG-PLAT-A";
    private static final String PLAT_ORG_B   = "ORG-PLAT-B";

    private static final Long   ORG_A_ID     = 1L;
    private static final Long   ORG_B_ID     = 2L;

    @Mock
    private CascadePlatformManager        cascadePlatformManager;
    @Mock
    private CascadeOrgManager             cascadeOrgManager;
    @Mock
    private CascadeOrgService             cascadeOrgService;
    @Mock
    private CascadePublishManager         cascadePublishManager;
    @Mock
    private CascadePublishedAssetResolver publishedAssetResolver;
    @Mock
    private ApplicationEventPublisher     eventPublisher;

    private CascadePlacementChangeListener listener;

    @BeforeEach
    void setUp() {
        listener = new CascadePlacementChangeListener(cascadePlatformManager, cascadeOrgManager,
            cascadeOrgService, cascadePublishManager, publishedAssetResolver, eventPublisher);
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform(0));
        when(cascadeOrgService.list()).thenReturn(List.of(org(ORG_A_ID, LOCAL_ORG_A), org(ORG_B_ID, LOCAL_ORG_B)));
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID))
            .thenReturn(new CascadePlatformOrgView(
                List.of(platformOrg(LOCAL_ORG_A, PLAT_ORG_A), platformOrg(LOCAL_ORG_B, PLAT_ORG_B)), Set.of()));
    }

    // ------------------------------------------------------------------ 前置过滤

    @Test
    @DisplayName("非设备范围的归属变更：通道不再单独挪位置，直接忽略")
    void non_device_scope_is_ignored() {
        listener.onPlacementChange(event("CHANNEL", OBJECT_KEY, ORG_A_ID, ORG_B_ID));

        assertNoEvent();
    }

    @Test
    @DisplayName("归属没变：发 DEL 会把刚推上去的节点再删一次")
    void unchanged_placement_is_skipped() {
        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_A_ID));

        assertNoEvent();
    }

    @Test
    @DisplayName("旧组织已被物理删除：它的节点早随组织删除撤掉，不再重复删")
    void deleted_previous_org_is_skipped() {
        when(cascadeOrgService.list()).thenReturn(List.of(org(ORG_B_ID, LOCAL_ORG_B)));

        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_B_ID));

        assertNoEvent();
    }

    @Test
    @DisplayName("发布行停用：上级目录里本来就没有它，不发 DEL")
    void disabled_publish_row_is_skipped() {
        when(cascadePublishManager.listByObjectKeys(eq("DEVICE"), any()))
            .thenReturn(List.of(publishRow("DEVICE", DEVICE_ID, 0)));
        when(cascadePublishManager.listByObjectKeyPrefix(eq("CHANNEL"), any()))
            .thenReturn(List.of(publishRow("CHANNEL", OBJECT_KEY, 0)));
        when(publishedAssetResolver.projectChannels(any())).thenReturn(List.of(channel(null, LOCAL_ORG_B)));

        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_B_ID));

        assertNoEvent();
    }

    // ------------------------------------------------------------------ 设备换组织

    @Test
    @DisplayName("设备换到该平台不可见的组织：新位置没了，旧位置更得删")
    void device_moved_to_invisible_org_still_deletes_old_node() {
        /* 新组织不在该平台目录里、也不在 rerouted 集合中：快照里不会再出现这台设备和它的通道 */
        when(cascadeOrgManager.platformOrgView(PLATFORM_ID))
            .thenReturn(new CascadePlatformOrgView(List.of(platformOrg(LOCAL_ORG_A, PLAT_ORG_A)), Set.of()));
        stubDeviceRows();
        when(publishedAssetResolver.projectChannels(any())).thenReturn(List.of(channel(null, LOCAL_ORG_B)));

        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_B_ID));

        LocalCascadeCatalogChangeEvent del = captureSingle();
        assertEquals(1, del.getNodes().size(), "新父为 null 不能被当成「位置没变」而跳过");
        assertEquals(PLAT_ORG_A, del.getNodes().get(0).parentCode());
    }

    @Test
    @DisplayName("设备换组织且未开设备层：删的是跟随设备的通道，父为旧组织上报编码")
    void device_move_without_device_layer_deletes_following_channel() {
        stubDeviceRows();
        /* 通道自己无组织、跟随设备：设备换组织后通道从 A 挪到 B */
        when(publishedAssetResolver.projectChannels(any())).thenReturn(List.of(channel(null, LOCAL_ORG_B)));

        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_B_ID));

        LocalCascadeCatalogChangeEvent del = captureSingle();
        assertEquals(1, del.getNodes().size(), "设备层关着时上级没有设备节点，只有通道");
        assertEquals(CHANNEL_CODE, del.getNodes().get(0).cascadeId());
        assertEquals(PLAT_ORG_A, del.getNodes().get(0).parentCode());
    }

    @Test
    @DisplayName("设备换组织且开了设备层：只删设备节点，通道的父（设备编码）没变")
    void device_move_with_device_layer_deletes_only_device_node() {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform(1));
        stubDeviceRows();
        when(cascadePublishManager.get(PLATFORM_ID, "DEVICE", DEVICE_ID))
            .thenReturn(publishRow("DEVICE", DEVICE_ID, 1, DEVICE_CODE, "门口设备"));
        when(publishedAssetResolver.listDevices(PLATFORM_ID))
            .thenReturn(List.of(deviceView(LOCAL_ORG_B)));
        when(publishedAssetResolver.projectChannels(any())).thenReturn(List.of(channel(null, LOCAL_ORG_B)));

        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_B_ID));

        LocalCascadeCatalogChangeEvent del = captureSingle();
        assertEquals(1, del.getNodes().size(), "通道仍挂在同一个设备节点下，不该跟着删");
        assertEquals(DEVICE_CODE, del.getNodes().get(0).cascadeId());
        assertEquals(PLAT_ORG_A, del.getNodes().get(0).parentCode(), "设备节点从 A 搬到 B，删的是 A 下那份");
    }

    @Test
    @DisplayName("通道自带的 orgId 一律忽略：位置仍由设备决定")
    void channel_own_org_is_ignored_by_device_move() {
        stubDeviceRows();
        /* 历史脏值：通道行上还留着组织 A，但设备的归属已经改到 B；通道没有自己的位置 */
        when(publishedAssetResolver.projectChannels(any())).thenReturn(List.of(channel(LOCAL_ORG_A, LOCAL_ORG_B)));

        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_B_ID));

        LocalCascadeCatalogChangeEvent del = captureSingle();
        assertEquals(1, del.getNodes().size(), "通道必须跟着设备挪，不能因为自带组织就留在原处");
        assertEquals(CHANNEL_CODE, del.getNodes().get(0).cascadeId());
        assertEquals(PLAT_ORG_A, del.getNodes().get(0).parentCode(), "删的是旧组织下那份");
    }

    @Test
    @DisplayName("设备无启用发布行：它不在任何上级目录里，不发 DEL")
    void device_without_publish_row_is_skipped() {
        when(cascadePublishManager.listByObjectKeys(eq("DEVICE"), any())).thenReturn(List.of());
        when(cascadePublishManager.listByObjectKeyPrefix(eq("CHANNEL"), any())).thenReturn(List.of());

        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_B_ID));

        assertNoEvent();
    }

    @Test
    @DisplayName("前缀捞到别的设备的通道时按设备段复核，不跟着删")
    void channel_of_another_device_is_not_deleted() {
        CascadePublishDTO foreign = publishRow("CHANNEL", "OTHER-DEVICE:" + CHANNEL_ID, 1);
        when(cascadePublishManager.listByObjectKeys(eq("DEVICE"), any())).thenReturn(List.of());
        when(cascadePublishManager.listByObjectKeyPrefix(eq("CHANNEL"), any())).thenReturn(List.of(foreign));
        when(publishedAssetResolver.projectChannels(any())).thenReturn(List.of(channel(null, LOCAL_ORG_B)));

        listener.onPlacementChange(event("DEVICE", DEVICE_ID, ORG_A_ID, ORG_B_ID));

        assertNoEvent();
    }

    // ------------------------------------------------------------------ 夹具

    /** 设备自己那一行 + 名下通道那一行：{@code affectedRows} 两条都要捞到 */
    private void stubDeviceRows() {
        when(cascadePublishManager.listByObjectKeys(eq("DEVICE"), any()))
            .thenReturn(List.of(publishRow("DEVICE", DEVICE_ID, 1)));
        when(cascadePublishManager.listByObjectKeyPrefix(eq("CHANNEL"), any()))
            .thenReturn(List.of(publishRow("CHANNEL", OBJECT_KEY, 1)));
    }

    private LocalCascadeCatalogChangeEvent captureSingle() {
        ArgumentCaptor<LocalCascadeCatalogChangeEvent> captor =
            ArgumentCaptor.forClass(LocalCascadeCatalogChangeEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private void assertNoEvent() {
        verify(eventPublisher, never()).publishEvent(any(LocalCascadeCatalogChangeEvent.class));
    }

    private LocalCascadePlacementChangeEvent event(String scope, String objectKey, Long previous, Long current) {
        return new LocalCascadePlacementChangeEvent(scope, objectKey, previous, current);
    }

    private CascadePlatformDTO platform(int deviceLayer) {
        CascadePlatformDTO platform = new CascadePlatformDTO();
        platform.setPlatformId(PLATFORM_ID);
        platform.setLocalClientId(DOMAIN_ID);
        platform.setCatalogWithDeviceLayer(deviceLayer);
        return platform;
    }

    private CascadePlatformOrgDTO platformOrg(String localCode, String platformCode) {
        CascadePlatformOrgDTO org = new CascadePlatformOrgDTO();
        org.setLocalCode(localCode);
        org.setPlatformCode(platformCode);
        org.setName(localCode);
        return org;
    }

    private CascadeOrgDO org(Long id, String code) {
        CascadeOrgDO org = new CascadeOrgDO();
        org.setId(id);
        org.setOrgCode(code);
        return org;
    }

    private CascadePublishDTO publishRow(String objectType, String objectKey, int enabled) {
        return publishRow(objectType, objectKey, enabled, null, null);
    }

    private CascadePublishDTO publishRow(String objectType, String objectKey, int enabled,
        String publishCode, String publishName) {
        CascadePublishDTO row = new CascadePublishDTO();
        row.setPlatformId(PLATFORM_ID);
        row.setObjectType(objectType);
        row.setObjectKey(objectKey);
        row.setPublishCode(publishCode);
        row.setPublishName(publishName);
        row.setEnabled(enabled);
        return row;
    }

    private CascadeChannelDTO channel(String ownOrg, String deviceOrg) {
        CascadeChannelDTO channel = new CascadeChannelDTO();
        channel.setPlatformId(PLATFORM_ID);
        channel.setLocalDeviceId(DEVICE_ID);
        channel.setLocalChannelId(CHANNEL_ID);
        channel.setCascadeChannelId(CHANNEL_CODE);
        channel.setCascadeName("前门");
        channel.setCascadeOrgId(ownOrg);
        channel.setDeviceOrgCode(deviceOrg);
        channel.setEnabled(1);
        return channel;
    }

    private CascadePlatformDeviceDTO deviceView(String orgCode) {
        CascadePlatformDeviceDTO device = new CascadePlatformDeviceDTO();
        device.setLocalDeviceId(DEVICE_ID);
        device.setPlatformCode(DEVICE_CODE);
        device.setName("门口设备");
        device.setLocalOrgCode(orgCode);
        return device;
    }
}
