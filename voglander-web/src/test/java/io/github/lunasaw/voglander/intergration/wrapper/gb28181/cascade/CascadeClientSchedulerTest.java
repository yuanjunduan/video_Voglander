package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lunasaw.gbproxy.client.transmit.cmd.ClientCommandSender;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;

/**
 * D3 次生问题红线：空库 + {@code @PostConstruct} 竞态下，{@link CascadeClientScheduler#refreshRegistrations()}
 * 查 {@code tb_cascade_platform} 抛 {@code no such table} 不应导致启动失败。
 * <p>
 * 与 {@code SqliteSchemaInitializer} 无依赖顺序保证，调度器查询必须对「表不存在」容错（跳过 + 告警），
 * 不把次生 DB 异常上抛成启动阻断。
 *
 * <p>另含 C1 拆分（注册续期 + 独立保活）与失败可见性/退避重试相关行为验证。
 *
 * @author luna
 */
@DisplayName("CascadeClientScheduler — 启动容错 + 注册保活拆分(C1) + 失败可见性")
@ExtendWith(MockitoExtension.class)
class CascadeClientSchedulerTest {

    private static final String PLATFORM_ID = "34020000002000000001";

    @Mock
    private CascadePlatformManager cascadePlatformManager;

    @Mock
    private CascadeDeviceSupplier   cascadeDeviceSupplier;

    @Mock
    private CascadePeerAliveTracker peerAliveTracker;

    @Mock
    private CascadeUnregisterService cascadeUnregisterService;

    private CascadeClientScheduler  scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.destroy();
        }
    }

    /**
     * 心跳存活记录与注销服务在本测试里只作为协作对象，行为由各自的单元测试覆盖，
     * 这里用 mock 隔离，避免把调度逻辑的断言耦合到 tracker 的内存状态。
     */
    private CascadeClientScheduler newScheduler() {
        return new CascadeClientScheduler(cascadePlatformManager, cascadeDeviceSupplier,
            peerAliveTracker, cascadeUnregisterService);
    }

    @Test
    @DisplayName("getPage 抛 no such table → refreshRegistrations 不上抛，启动不阻断")
    void refreshRegistrations_whenTableMissing_shouldNotPropagate() {
        when(cascadePlatformManager.getPage(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                    "select", "SELECT ...", new java.sql.SQLException("[SQLITE_ERROR] SQL error or missing database (no such table: tb_cascade_platform)")));

        scheduler = newScheduler();

        assertDoesNotThrow(scheduler::refreshRegistrations,
            "空库下查 tb_cascade_platform 缺表异常应被容错吞掉并告警，不阻断启动");
    }

    @Test
    @DisplayName("stopPlatform 应置 OFFLINE（取消注册 + 保活两套任务）")
    void stopPlatform_should_set_offline() {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setId(1L);
        dto.setPlatformId("PF1");
        dto.setRegisterExpires(3600);
        dto.setKeepaliveInterval(60);
        lenient().when(cascadePlatformManager.getById(1L)).thenReturn(dto);

        scheduler = newScheduler();

        assertDoesNotThrow(() -> scheduler.startPlatform(dto));
        scheduler.stopPlatform(1L);

        verify(cascadePlatformManager, atLeastOnce()).updateRegisterStatus(1L, CascadeConstant.RegisterStatus.OFFLINE);
    }

    @Test
    @DisplayName("REGISTER 已发送但状态仍 REGISTERING → 超时标记 FAILED")
    void registerTimeout_should_mark_failed_when_still_registering() throws Exception {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setId(2L);
        dto.setPlatformId("PF2");
        dto.setLocalClientId("LOCAL2");
        dto.setRegisterStatus(CascadeConstant.RegisterStatus.REGISTERING);
        dto.setRegisterExpires(3600);
        dto.setKeepaliveInterval(60);

        FromDevice from = new FromDevice();
        ToDevice to = new ToDevice();
        when(cascadePlatformManager.getById(2L)).thenReturn(dto);
        when(cascadeDeviceSupplier.buildFromDevice(dto)).thenReturn(from);
        when(cascadeDeviceSupplier.buildToDevice(dto)).thenReturn(to);

        scheduler = newScheduler();
        scheduler.setRegisterTimeoutSeconds(0);

        try (MockedStatic<ClientCommandSender> mocked = mockStatic(ClientCommandSender.class)) {
            mocked.when(() -> ClientCommandSender.sendRegisterCommand(from, to, 3600)).thenReturn("call-2");
            scheduler.startPlatform(dto);
            Thread.sleep(300);
        }

        verify(cascadePlatformManager, atLeastOnce())
            .updateRegisterStatus(2L, CascadeConstant.RegisterStatus.FAILED);
    }

    /* ---------------- 失败可见性与退避重试 ---------------- */

    @Test
    @DisplayName("退避间隔按 30/60/120/300 秒递增并封顶")
    void retry_delay_backoff() {
        assertEquals(30L, CascadeClientScheduler.retryDelaySeconds(1));
        assertEquals(60L, CascadeClientScheduler.retryDelaySeconds(2));
        assertEquals(120L, CascadeClientScheduler.retryDelaySeconds(3));
        assertEquals(300L, CascadeClientScheduler.retryDelaySeconds(4));
        assertEquals(300L, CascadeClientScheduler.retryDelaySeconds(9));
        assertEquals(30L, CascadeClientScheduler.retryDelaySeconds(0));
    }

    @Test
    @DisplayName("注册失败：失败原因合并进 extend，保留原有键")
    void failure_writes_last_error_into_extend() {
        scheduler = newScheduler();
        CascadePlatformDTO latest = platform(1L, "{\"foo\":\"bar\"}");
        when(cascadePlatformManager.getById(1L)).thenReturn(latest);

        scheduler.markRegisterFailure(latest, "REGISTER 失败 statusCode=401");

        ArgumentCaptor<CascadePlatformDTO> captor = ArgumentCaptor.forClass(CascadePlatformDTO.class);
        verify(cascadePlatformManager).update(captor.capture());
        String extend = captor.getValue().getExtend();
        assertTrue(extend.contains("\"foo\":\"bar\""), "原有 extend 键必须保留：" + extend);
        assertTrue(extend.contains("\"lastError\":\"REGISTER 失败 statusCode=401\""), extend);
        assertTrue(extend.contains("\"lastErrorAt\""), extend);
    }

    @Test
    @DisplayName("注册成功：清空 extend.lastError")
    void success_clears_last_error() {
        scheduler = newScheduler();
        CascadePlatformDTO latest = platform(1L, "{\"lastError\":\"old\"}");
        when(cascadePlatformManager.getById(1L)).thenReturn(latest);

        scheduler.markRegisterSuccess(latest);

        ArgumentCaptor<CascadePlatformDTO> captor = ArgumentCaptor.forClass(CascadePlatformDTO.class);
        verify(cascadePlatformManager).update(captor.capture());
        String extend = captor.getValue().getExtend();
        assertTrue(extend.contains("\"lastError\":\"\""), extend);
        assertFalse(extend.contains("old"), extend);
    }

    @Test
    @DisplayName("extend 为非法 JSON 时重置后仍能写入失败原因")
    void failure_tolerates_invalid_extend_json() {
        scheduler = newScheduler();
        CascadePlatformDTO latest = platform(1L, "not-a-json");
        when(cascadePlatformManager.getById(1L)).thenReturn(latest);

        scheduler.markRegisterFailure(latest, "boom");

        ArgumentCaptor<CascadePlatformDTO> captor = ArgumentCaptor.forClass(CascadePlatformDTO.class);
        verify(cascadePlatformManager).update(captor.capture());
        assertTrue(captor.getValue().getExtend().contains("\"lastError\":\"boom\""));
    }

    @Test
    @DisplayName("平台已被删除时写入失败原因不影响流程")
    void failure_when_platform_missing() {
        scheduler = newScheduler();
        CascadePlatformDTO snapshot = platform(1L, null);
        when(cascadePlatformManager.getById(1L)).thenReturn(null);

        assertDoesNotThrow(() -> scheduler.markRegisterFailure(snapshot, "boom"));
        verify(cascadePlatformManager, never()).update(any(CascadePlatformDTO.class));
    }

    private static CascadePlatformDTO platform(Long id, String extend) {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setId(id);
        dto.setPlatformId(PLATFORM_ID);
        dto.setPlatformIp("10.0.0.1");
        dto.setPlatformPort(5060);
        dto.setLocalClientId("34020000002000000002");
        dto.setLocalIp("192.168.118.7");
        dto.setLocalPort(5061);
        dto.setEnabled(1);
        dto.setExtend(extend);
        return dto;
    }
}