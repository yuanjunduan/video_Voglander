package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import javax.sip.message.Request;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.github.lunasaw.gb28181.common.entity.notify.DeviceAlarmNotify;
import io.github.lunasaw.gb28181.common.entity.notify.DeviceOtherUpdateNotify;
import io.github.lunasaw.gbproxy.client.transmit.cmd.ClientCommandSender;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.sip.common.transmit.SipSender;
import io.github.lunasaw.sip.common.transmit.request.SipRequestBuilderFactory;
import io.github.lunasaw.sip.common.utils.SipRequestUtils;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.SubType;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogAssembler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMetaResolver;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeDeviceSupplier;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeNotifyAckTracker;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeNotifyPublisher;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeSubscribeDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadeSubscribeManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;

/**
 * {@link CascadeNotifyPublisher} 单元测试（C3/C4/C5）。
 *
 * <p>目录快照部分用<b>真实</b>的 {@link CascadeCatalogAssembler}（其依赖全部 mock），
 * 这样能同时验证"组装出的父子关系"与"最终报文内容"；其余用例验证订阅遍历与编码重映射。
 *
 * @author luna
 */
@DisplayName("CascadeNotifyPublisher 单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CascadeNotifyPublisherTest {

    private static final String DOMAIN_ID = "34020000002000000001";

    @Mock
    private CascadeSubscribeManager cascadeSubscribeManager;
    @Mock
    private CascadePlatformManager  cascadePlatformManager;
    @Mock
    private CascadeChannelManager   cascadeChannelManager;
    @Mock
    private CascadeOrgManager       cascadeOrgManager;
    @Mock
    private CascadeDeviceSupplier   cascadeDeviceSupplier;
    @Mock
    private CascadeNotifyAckTracker notifyAckTracker;
    @Mock
    private DeviceChannelManager    deviceChannelManager;
    @Mock
    private CascadeChannelMetaResolver channelMetaResolver;

    private CascadeNotifyPublisher publisher;

    @BeforeEach
    void setUp() {
        /* 目录元数据派生留空，通道项走 CascadeChannelMeta.DEFAULT，与真实兜底分支一致 */
        when(channelMetaResolver.resolve(any())).thenReturn(java.util.Map.of());
        CascadeCatalogAssembler catalogAssembler = new CascadeCatalogAssembler(cascadePlatformManager,
            cascadeOrgManager, cascadeChannelManager, deviceChannelManager, channelMetaResolver);
        publisher = new CascadeNotifyPublisher(cascadeSubscribeManager, cascadePlatformManager,
            cascadeChannelManager, cascadeDeviceSupplier, notifyAckTracker, catalogAssembler);
    }

    private CascadeSubscribeDTO sub(String platformId, String sn) {
        CascadeSubscribeDTO s = new CascadeSubscribeDTO();
        s.setPlatformId(platformId);
        s.setSn(sn);
        return s;
    }

    private CascadeChannelDTO channel(String platformId, String local, String cascade) {
        CascadeChannelDTO ch = new CascadeChannelDTO();
        ch.setPlatformId(platformId);
        ch.setLocalChannelId(local);
        ch.setCascadeChannelId(cascade);
        return ch;
    }

    private void stubEndpoint(String platformId) {
        CascadePlatformDTO p = new CascadePlatformDTO();
        p.setPlatformId(platformId);
        p.setLocalClientId(DOMAIN_ID);
        when(cascadePlatformManager.getByPlatformId(platformId)).thenReturn(p);
        FromDevice from = new FromDevice();
        from.setUserId(p.getLocalClientId());
        from.setIp("192.168.118.7");
        when(cascadeDeviceSupplier.buildFromDevice(p)).thenReturn(from);
        ToDevice to = new ToDevice();
        to.setUserId(platformId);
        when(cascadeDeviceSupplier.buildToDevice(p)).thenReturn(to);
    }

    /**
     * 接管目录 NOTIFY 的发送：记录每包 XML，并为 Event/Subscription-State 头提供 mock。
     *
     * <p>必须把 {@link SipSender} 与 {@link SipRequestUtils} 都 mock 掉——脱离 SIP 运行时
     * 它们会抛 {@code RuntimeException("ListeningPoint Not Exist")}，而这个异常会被推送的
     * catch 吞掉，表现为“一包都没发出去”。
     *
     * @return 按发送顺序累积的 XML 报文
     */
    private List<String> startSipCapture(MockedStatic<SipRequestBuilderFactory> factoryMock,
        MockedStatic<SipRequestUtils> utilsMock) {
        List<String> contents = new ArrayList<>();
        Request request = mock(Request.class);
        factoryMock.when(() -> SipRequestBuilderFactory.createNotifyRequest(any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                contents.add(invocation.getArgument(2));
                return request;
            });
        utilsMock.when(SipRequestUtils::getNewCallId).thenReturn("call-1");
        utilsMock.when(() -> SipRequestUtils.createEventHeader(any(), any()))
            .thenReturn(mock(javax.sip.header.EventHeader.class));
        utilsMock.when(() -> SipRequestUtils.createSubscriptionStateHeader(any()))
            .thenReturn(mock(javax.sip.header.SubscriptionStateHeader.class));
        return contents;
    }

    /* ---- pushCatalogSnapshot（首次订阅的目录基线） ---- */

    @Test
    @DisplayName("目录快照：首包只含域节点，第二包起下发组织与通道，SumNum 恒为总数")
    void catalog_snapshot_domain_then_rest() {
        String pf = "PF3";
        stubEndpoint(pf);
        when(cascadeOrgManager.listEffective(pf)).thenReturn(List.of());
        CascadeChannelDTO ch = channel(pf, "LOCAL01", "CASCADE01");
        ch.setEnabled(1);
        ch.setCascadeName("前门 <A&B>");
        when(cascadeChannelManager.listByPlatformId(pf)).thenReturn(List.of(ch));
        when(deviceChannelManager.mapChannelStatus(any())).thenReturn(java.util.Map.of());

        try (MockedStatic<SipRequestBuilderFactory> factoryMock = mockStatic(SipRequestBuilderFactory.class);
             MockedStatic<SipSender> sipMock = mockStatic(SipSender.class);
             MockedStatic<SipRequestUtils> utilsMock = mockStatic(SipRequestUtils.class)) {
            List<String> messages = startSipCapture(factoryMock, utilsMock);

            assertEquals(2, publisher.pushCatalogSnapshot(pf, "11436"));

            assertEquals(2, messages.size(), "域节点一包 + 通道一包");

            sipMock.verify(() -> SipSender.transmitRequest(any(), any()), times(2));
            utilsMock.verify(() -> SipRequestUtils.createEventHeader("Catalog", "11436"), times(2));

            String domainPacket = messages.get(0);
            assertTrue(domainPacket.contains("<SumNum>2</SumNum>"), domainPacket);
            assertTrue(domainPacket.contains("<DeviceList Num=\"1\">"), domainPacket);
            assertTrue(domainPacket.contains("<DeviceID>" + DOMAIN_ID + "</DeviceID>"), domainPacket);
            assertTrue(domainPacket.contains("<SN>11436</SN>"), domainPacket);
            assertTrue(!domainPacket.contains("CASCADE01"), "首包不应带通道");

            String restPacket = messages.get(1);
            assertTrue(restPacket.contains("<SumNum>2</SumNum>"), restPacket);
            assertTrue(restPacket.contains("<DeviceID>CASCADE01</DeviceID>"), restPacket);
            /* 通道名没转义会让整包变成非法 XML，上级直接丢弃 */
            assertTrue(restPacket.contains("前门 &lt;A&amp;B&gt;"), restPacket);
            assertTrue(restPacket.contains("<ParentID>" + DOMAIN_ID + "</ParentID>"),
                "无组织时通道仍需带 ParentID，否则上级不建节点: " + restPacket);
        }
    }

    @Test
    @DisplayName("目录快照：组织存在时通道父节点指向组织编码")
    void catalog_snapshot_channel_hangs_under_org() {
        String pf = "PF5";
        stubEndpoint(pf);
        CascadeOrgDTO org = new CascadeOrgDTO();
        org.setId(1L);
        org.setPlatformId(pf);
        org.setCascadeOrgId("34020000002150000001");
        org.setOrgName("一层");
        when(cascadeOrgManager.listEffective(pf)).thenReturn(List.of(org));
        when(cascadeOrgManager.indexById(any())).thenAnswer(inv -> {
            java.util.Map<Long, CascadeOrgDTO> m = new java.util.HashMap<>();
            m.put(1L, org);
            return m;
        });
        when(cascadeOrgManager.resolveParentCode(any(), any(), any())).thenReturn(DOMAIN_ID);

        CascadeChannelDTO ch = channel(pf, "LOCAL01", "CASCADE01");
        ch.setEnabled(1);
        ch.setCascadeOrgId("34020000002150000001");
        when(cascadeChannelManager.listByPlatformId(pf)).thenReturn(List.of(ch));

        try (MockedStatic<SipRequestBuilderFactory> factoryMock = mockStatic(SipRequestBuilderFactory.class);
             MockedStatic<SipSender> sipMock = mockStatic(SipSender.class);
             MockedStatic<SipRequestUtils> utilsMock = mockStatic(SipRequestUtils.class)) {
            List<String> messages = startSipCapture(factoryMock, utilsMock);

            assertEquals(2, publisher.pushCatalogSnapshot(pf, "9"));

            String restPacket = messages.get(1);
            assertTrue(restPacket.contains("<SumNum>3</SumNum>"), restPacket);
            assertTrue(restPacket.contains("<DeviceID>34020000002150000001</DeviceID>"), restPacket);
            assertTrue(restPacket.contains("<ParentID>34020000002150000001</ParentID>"), restPacket);
        }
    }

    @Test
    @DisplayName("目录快照：一个通道都没有也要发一包，至少带域节点")
    void catalog_snapshot_empty_still_pushes_domain() {
        String pf = "PF4";
        stubEndpoint(pf);
        when(cascadeOrgManager.listEffective(pf)).thenReturn(List.of());
        when(cascadeChannelManager.listByPlatformId(pf)).thenReturn(List.of());

        try (MockedStatic<SipRequestBuilderFactory> factoryMock = mockStatic(SipRequestBuilderFactory.class);
             MockedStatic<SipSender> sipMock = mockStatic(SipSender.class);
             MockedStatic<SipRequestUtils> utilsMock = mockStatic(SipRequestUtils.class)) {
            List<String> messages = startSipCapture(factoryMock, utilsMock);

            assertEquals(1, publisher.pushCatalogSnapshot(pf, "1"));

            String packet = messages.get(0);
            assertTrue(packet.contains("<SumNum>1</SumNum>"), packet);
            assertTrue(packet.contains("<DeviceList Num=\"1\">"), packet);
            assertTrue(packet.contains("<DeviceID>" + DOMAIN_ID + "</DeviceID>"), packet);
        }
    }

    @Test
    @DisplayName("目录快照：上级平台配置不存在时不发包")
    void catalog_snapshot_skip_unknown_platform() {
        when(cascadePlatformManager.getByPlatformId("NOPE")).thenReturn(null);
        try (MockedStatic<SipRequestBuilderFactory> factoryMock = mockStatic(SipRequestBuilderFactory.class);
             MockedStatic<SipSender> sipMock = mockStatic(SipSender.class)) {
            assertEquals(0, publisher.pushCatalogSnapshot("NOPE", "1"));
            factoryMock.verifyNoInteractions();
            sipMock.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("目录变更：推送已订阅上级，deviceId 重映射为 cascadeChannelId")
    void catalog_change_should_remap_and_push() {
        String pf = "PF1";
        when(cascadeSubscribeManager.listActiveByType(SubType.CATALOG)).thenReturn(List.of(sub(pf, "10")));
        when(cascadeChannelManager.getByPlatformAndChannel(pf, "LOCAL01"))
            .thenReturn(channel(pf, "LOCAL01", "CASCADE01"));
        stubEndpoint(pf);

        try (MockedStatic<ClientCommandSender> mocked = mockStatic(ClientCommandSender.class)) {
            publisher.pushCatalogChange("DEV", "LOCAL01", "OFF");

            mocked.verify(() -> ClientCommandSender.sendCatalogChangeNotify(
                any(FromDevice.class), any(ToDevice.class), eq("10"),
                org.mockito.ArgumentMatchers.argThat((List<DeviceOtherUpdateNotify.OtherItem> items) ->
                    items.size() == 1
                        && "CASCADE01".equals(items.get(0).getDeviceId())
                        && "OFF".equals(items.get(0).getEvent()))));
        }
    }

    @Test
    @DisplayName("无订阅时不推送")
    void no_subscribers_should_not_push() {
        when(cascadeSubscribeManager.listActiveByType(SubType.CATALOG)).thenReturn(List.of());
        try (MockedStatic<ClientCommandSender> mocked = mockStatic(ClientCommandSender.class)) {
            publisher.pushCatalogChange("DEV", "LOCAL01", "OFF");
            mocked.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("该上级未暴露此通道时跳过")
    void unmapped_channel_should_skip() {
        String pf = "PF1";
        when(cascadeSubscribeManager.listActiveByType(SubType.CATALOG)).thenReturn(List.of(sub(pf, "10")));
        when(cascadeChannelManager.getByPlatformAndChannel(pf, "LOCAL01")).thenReturn(null);
        try (MockedStatic<ClientCommandSender> mocked = mockStatic(ClientCommandSender.class)) {
            publisher.pushCatalogChange("DEV", "LOCAL01", "OFF");
            mocked.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("告警：推送已订阅上级，deviceId 重映射")
    void alarm_should_remap_and_push() {
        String pf = "PF2";
        when(cascadeSubscribeManager.listActiveByType(SubType.ALARM)).thenReturn(List.of(sub(pf, "20")));
        when(cascadeChannelManager.getByPlatformAndChannel(pf, "LOCAL01"))
            .thenReturn(channel(pf, "LOCAL01", "CASCADE01"));
        stubEndpoint(pf);

        try (MockedStatic<ClientCommandSender> mocked = mockStatic(ClientCommandSender.class)) {
            publisher.pushAlarm("DEV", "LOCAL01", "1", "1", "2026-06-14T10:00:00");

            mocked.verify(() -> ClientCommandSender.sendAlarmNotify(
                any(FromDevice.class), any(ToDevice.class),
                org.mockito.ArgumentMatchers.argThat((DeviceAlarmNotify n) ->
                    "CASCADE01".equals(n.getDeviceId()) && "20".equals(n.getSn()))));
        }
    }

    @Test
    @DisplayName("告警：指明通道但上级未暴露 → 跳过")
    void alarm_with_unmapped_channel_should_skip() {
        String pf = "PF2";
        when(cascadeSubscribeManager.listActiveByType(SubType.ALARM)).thenReturn(List.of(sub(pf, "20")));
        when(cascadeChannelManager.getByPlatformAndChannel(pf, "LOCAL01")).thenReturn(null);
        try (MockedStatic<ClientCommandSender> mocked = mockStatic(ClientCommandSender.class)) {
            publisher.pushAlarm("DEV", "LOCAL01", "1", "1", "t");
            mocked.verify(() -> ClientCommandSender.sendAlarmNotify(any(), any(), any()), never());
        }
    }
}
