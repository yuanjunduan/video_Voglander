package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.github.lunasaw.sip.common.transmit.event.Event;
import io.github.lunasaw.sip.common.utils.SipRequestUtils;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogAssembler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogNode;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeCatalogPager;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeUpperMessenger;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.SysConfigManager;
import io.github.lunasaw.voglander.manager.sys.SysConfigCatalog;

/**
 * {@link CascadeCatalogPager} 单元测试（附录 N）。
 *
 * <p>只验证推进策略：串行时「一页在途」、收到 200 OK 或超时后推进、非串行时一次发完、
 * 发送失败即放弃整批。报文内容由 {@code CascadeCatalogAssembler} 自己保证。
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeCatalogPager 单元测试")
class CascadeCatalogPagerTest {

    private static final String DOMAIN = "34020000002000000001";

    @Mock CascadeUpperMessenger   upperMessenger;
    @Mock CascadeCatalogAssembler catalogAssembler;
    @Mock SysConfigManager        sysConfigManager;
    @InjectMocks CascadeCatalogPager pager;

    private final List<String> sentCallIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        setField("serial", true);
        setField("catalogBatchSize", 50);
        setField("okTimeoutMs", 2000L);
        setField("batchTtlMs", 120000L);
        /* 没有库内覆盖值时直接回落 yml/字段值，与线上"未配覆盖值"的路径一致 */
        when(sysConfigManager.getInt(eq(SysConfigCatalog.CASCADE_CATALOG_BATCH_SIZE), anyInt()))
            .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigManager.getBoolean(eq(SysConfigCatalog.CASCADE_CATALOG_SERIAL), anyBoolean()))
            .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigManager.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(catalogAssembler.renderCatalogResponse(any(), any(), anyInt(), anyList()))
            .thenAnswer(inv -> "page");
        when(upperMessenger.send(any(CascadePlatformDTO.class), anyString(), nullable(String.class),
            nullable(Event.class))).thenAnswer(inv -> {
                sentCallIds.add(inv.getArgument(2));
                return inv.getArgument(2) == null ? "generated" : inv.getArgument(2);
            });
    }

    private CascadePlatformDTO platform() {
        CascadePlatformDTO p = new CascadePlatformDTO();
        p.setPlatformId("37000020002000000100");
        p.setLocalClientId(DOMAIN);
        p.setLocalIp("192.168.118.7");
        return p;
    }

    private CascadeCatalogNode node(String id, CascadeCatalogNode.Kind kind) {
        return new CascadeCatalogNode(id, id, null, null, null, null, null, null, null, null, "ON", kind);
    }

    /** 域节点 + 3 个通道 → 2 页（域单独一页、其余一页）。 */
    private List<CascadeCatalogNode> nodes() {
        return List.of(
            node(DOMAIN, CascadeCatalogNode.Kind.DOMAIN),
            node("34020000001320000001", CascadeCatalogNode.Kind.CHANNEL),
            node("34020000001320000002", CascadeCatalogNode.Kind.CHANNEL),
            node("34020000001320000003", CascadeCatalogNode.Kind.CHANNEL));
    }

    @Test
    @DisplayName("串行：首页沿用入站 Call-ID，收到 200 OK 后才发下一页")
    void serial_firstPageThenAck() {
        try (MockedStatic<SipRequestUtils> utils = mockStatic(SipRequestUtils.class)) {
            utils.when(SipRequestUtils::getNewCallId).thenReturn("gen-1", "gen-2");

            int sent = pager.sendCatalog(platform(), "in-call", DOMAIN, "1", nodes());

            assertEquals(1, sent, "串行首轮只发首页");
            assertEquals(List.of("in-call"), sentCallIds);

            /* 上级回 200 OK（Call-ID 仍是首页的入站值）→ 推进第二页 */
            pager.onPageAck("in-call");
            assertEquals(List.of("in-call", "gen-1"), sentCallIds);

            /* 第二页也回 200 OK → 队列空，不再发 */
            pager.onPageAck("gen-1");
            assertEquals(2, sentCallIds.size());
            pager.onPageAck("gen-1");
            assertEquals(2, sentCallIds.size(), "重复 200 OK 必须幂等");
        }
    }

    @Test
    @DisplayName("串行：上级不回 200 OK 时看门狗到点强制推进")
    void serial_watchdogAdvances() throws Exception {
        setField("okTimeoutMs", 0L);
        try (MockedStatic<SipRequestUtils> utils = mockStatic(SipRequestUtils.class)) {
            utils.when(SipRequestUtils::getNewCallId).thenReturn("gen-1", "gen-2");

            pager.sendCatalog(platform(), "in-call", DOMAIN, "1", nodes());
            pager.scanTimeoutPages();

            assertEquals(List.of("in-call", "gen-1"), sentCallIds);
        }
    }

    @Test
    @DisplayName("串行：未知 Call-ID 的 200 OK 不推进")
    void serial_unknownAckIgnored() {
        try (MockedStatic<SipRequestUtils> utils = mockStatic(SipRequestUtils.class)) {
            utils.when(SipRequestUtils::getNewCallId).thenReturn("gen-1");

            pager.sendCatalog(platform(), "in-call", DOMAIN, "1", nodes());
            pager.onPageAck("unknown");

            assertEquals(1, sentCallIds.size());
        }
    }

    @Test
    @DisplayName("非串行：一次性发完全部分片")
    void nonSerial_sendsAllPages() throws Exception {
        setField("serial", false);
        try (MockedStatic<SipRequestUtils> utils = mockStatic(SipRequestUtils.class)) {
            utils.when(SipRequestUtils::getNewCallId).thenReturn("gen-1", "gen-2");

            int sent = pager.sendCatalog(platform(), "in-call", DOMAIN, "1", nodes());

            assertEquals(2, sent);
            assertEquals(List.of("in-call", "gen-1"), sentCallIds);
        }
    }

    @Test
    @DisplayName("首页发送失败：放弃整批，不再尝试后续分片")
    void firstPageFailure_dropsBatch() {
        when(upperMessenger.send(any(CascadePlatformDTO.class), anyString(), nullable(String.class),
            nullable(Event.class))).thenReturn(null);

        assertEquals(0, pager.sendCatalog(platform(), "in-call", DOMAIN, "1", nodes()));
        pager.scanTimeoutPages();

        /* 批次已出表：看门狗找不到可推进的页，不会继续灌包 */
        verify(upperMessenger, times(1)).send(any(CascadePlatformDTO.class), anyString(), nullable(String.class),
            nullable(Event.class));
    }

    @Test
    @DisplayName("只有域节点：单页直接发完，进入非串行快路径")
    void singlePage_usesFastPath() {
        try (MockedStatic<SipRequestUtils> utils = mockStatic(SipRequestUtils.class)) {
            utils.when(SipRequestUtils::getNewCallId).thenReturn("gen-1");

            int sent = pager.sendCatalog(platform(), "in-call", DOMAIN, "1",
                List.of(node(DOMAIN, CascadeCatalogNode.Kind.DOMAIN)));

            assertEquals(1, sent);
            assertEquals(List.of("in-call"), sentCallIds);
        }
    }

    /** 回调本身不参与断言（测试里直接调 onPageAck），但非空才说明事件已绑定。 */
    @Test
    @DisplayName("每页都绑定了 200 OK 回调")
    void everyPageBindsOkEvent() {
        try (MockedStatic<SipRequestUtils> utils = mockStatic(SipRequestUtils.class)) {
            utils.when(SipRequestUtils::getNewCallId).thenReturn("gen-1");

            pager.sendCatalog(platform(), "in-call", DOMAIN, "1", nodes());

            verify(upperMessenger).send(any(CascadePlatformDTO.class), anyString(), eq("in-call"), notNull(Event.class));
        }
    }

    private void setField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = CascadeCatalogPager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(pager, value);
    }
}
