package io.github.lunasaw.voglander.web.api.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeClientScheduler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeClientDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeClientManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeClientVO;

/**
 * CascadeClientController 纯 Mockito + MockMvc 单元测试。
 *
 * <p>钉住两条看不见的约束：删除被引用时必须可读地拒绝；改通告 IP 后必须重建引用平台的注册调度
 * （调度任务里存的是拼好 From 头的 DTO 快照，不重建就还在用旧地址，而页面已显示新地址）。
 *
 * @author luna
 */
@DisplayName("CascadeClientController 单元测试")
@ExtendWith(MockitoExtension.class)
class CascadeClientControllerTest {

    /** 本端客户端主键 */
    private static final Long   CLIENT_ID       = 7L;
    /** 合法的 20 位本端平台编码 */
    private static final String CLIENT_CODE     = "34020000002000000002";
    /** 上级平台主键 */
    private static final Long   PLATFORM_ID     = 5L;
    /** 上级平台编码 */
    private static final String PLATFORM_CODE   = "34020000002000000001";

    @InjectMocks
    CascadeClientController    controller;
    @Mock
    CascadeClientManager       cascadeClientManager;
    @Mock
    CascadePlatformManager     cascadePlatformManager;
    @Mock
    CascadeClientScheduler     cascadeClientScheduler;
    @Spy
    CascadeWebAssembler        cascadeWebAssembler = new CascadeWebAssembler();

    MockMvc                    mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /* ---------------- 列表 / 详情 ---------------- */

    @Test
    @DisplayName("列表：一次算出引用数并逐行带上 platformRefCount")
    void list_carries_platform_ref_count() throws Exception {
        when(cascadeClientManager.countPlatformRefsGrouped()).thenReturn(Map.of(CLIENT_ID, 3L));
        when(cascadeClientManager.listAll(false)).thenReturn(List.of(client(CLIENT_ID, CLIENT_CODE)));

        mvc.perform(get("/api/v1/cascade/client/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].clientId").value(CLIENT_CODE))
            .andExpect(jsonPath("$.data[0].platformRefCount").value(3));

        /* 分页/列表不能退化成逐行 count：这里只查一次分组统计 */
        verify(cascadeClientManager, never()).countPlatformRefs(any());
    }

    @Test
    @DisplayName("列表：onlyEnabled=true 透传到 Manager（新增平台下拉只列启用项）")
    void list_only_enabled() throws Exception {
        when(cascadeClientManager.countPlatformRefsGrouped()).thenReturn(Map.of());
        when(cascadeClientManager.listAll(true)).thenReturn(List.of());

        mvc.perform(get("/api/v1/cascade/client/list").param("onlyEnabled", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());

        verify(cascadeClientManager).listAll(true);
    }

    @Test
    @DisplayName("详情：不存在时回可读错误")
    void getById_not_found() throws Exception {
        when(cascadeClientManager.getById(CLIENT_ID)).thenReturn(null);

        mvc.perform(get("/api/v1/cascade/client/" + CLIENT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msg").value("本端客户端不存在：" + CLIENT_ID));
    }

    /* ---------------- 新增 ---------------- */

    @Test
    @DisplayName("新增：编码透传给 Manager，成功返回主键")
    void add_ok() throws Exception {
        when(cascadeClientManager.add(any(CascadeClientDTO.class))).thenReturn(CLIENT_ID);

        mvc.perform(post("/api/v1/cascade/client")
            .contentType(MediaType.APPLICATION_JSON).content(createBody("192.168.118.7")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(CLIENT_ID));

        verify(cascadeClientManager).add(argThat(c -> CLIENT_CODE.equals(c.getClientId())
            && "192.168.118.7".equals(c.getClientIp())));
    }

    @Test
    @DisplayName("新增：编码重复等用户填错回可读原因，不当系统异常抛")
    void add_duplicate_code_returns_reason() throws Exception {
        when(cascadeClientManager.add(any(CascadeClientDTO.class)))
            .thenThrow(new IllegalArgumentException("本端平台编码已存在：" + CLIENT_CODE));

        mvc.perform(post("/api/v1/cascade/client")
            .contentType(MediaType.APPLICATION_JSON).content(createBody("")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msg").value("本端平台编码已存在：" + CLIENT_CODE));
    }

    /* ---------------- 更新 ---------------- */

    @Test
    @DisplayName("更新：改通告 IP 后先停后起，重建引用平台的注册调度")
    void update_ip_changed_restarts_referenced_platforms() throws Exception {
        when(cascadeClientManager.getById(CLIENT_ID)).thenReturn(client(CLIENT_ID, CLIENT_CODE));
        when(cascadeClientManager.update(any(CascadeClientDTO.class))).thenReturn(true);
        CascadePlatformDTO enabled = platform(PLATFORM_ID, 1);
        when(cascadePlatformManager.listByClientRefId(CLIENT_ID)).thenReturn(List.of(enabled));

        mvc.perform(put("/api/v1/cascade/client/" + CLIENT_ID)
            .contentType(MediaType.APPLICATION_JSON).content("{\"clientIp\":\"10.0.0.9\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        InOrder inOrder = inOrder(cascadeClientScheduler);
        inOrder.verify(cascadeClientScheduler).stopPlatform(PLATFORM_ID);
        inOrder.verify(cascadeClientScheduler).startPlatform(enabled);
    }

    @Test
    @DisplayName("更新：同名改备注不动 IP 时不重启注册（SIP 报文完全没有变化）")
    void update_without_ip_change_keeps_scheduler() throws Exception {
        when(cascadeClientManager.getById(CLIENT_ID)).thenReturn(client(CLIENT_ID, CLIENT_CODE));
        when(cascadeClientManager.update(any(CascadeClientDTO.class))).thenReturn(true);

        mvc.perform(put("/api/v1/cascade/client/" + CLIENT_ID)
            .contentType(MediaType.APPLICATION_JSON).content("{\"clientName\":\"市公安局本端\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(cascadePlatformManager, never()).listByClientRefId(any());
        verify(cascadeClientScheduler, never()).startPlatform(any());
        verify(cascadeClientScheduler, never()).stopPlatform(anyLong());
    }

    @Test
    @DisplayName("更新：IP 与库中一致时也不重启（前端整表回传不会被当成变更）")
    void update_same_ip_keeps_scheduler() throws Exception {
        when(cascadeClientManager.getById(CLIENT_ID)).thenReturn(client(CLIENT_ID, CLIENT_CODE));
        when(cascadeClientManager.update(any(CascadeClientDTO.class))).thenReturn(true);

        mvc.perform(put("/api/v1/cascade/client/" + CLIENT_ID)
            .contentType(MediaType.APPLICATION_JSON).content("{\"clientIp\":\" 192.168.118.7 \"}"))
            .andExpect(status().isOk());

        verify(cascadePlatformManager, never()).listByClientRefId(any());
        verify(cascadeClientScheduler, never()).startPlatform(any());
    }

    @Test
    @DisplayName("更新：被禁用的平台不重建调度")
    void update_ip_changed_skips_disabled_platform() throws Exception {
        when(cascadeClientManager.getById(CLIENT_ID)).thenReturn(client(CLIENT_ID, CLIENT_CODE));
        when(cascadeClientManager.update(any(CascadeClientDTO.class))).thenReturn(true);
        when(cascadePlatformManager.listByClientRefId(CLIENT_ID))
            .thenReturn(List.of(platform(PLATFORM_ID, 0)));

        mvc.perform(put("/api/v1/cascade/client/" + CLIENT_ID)
            .contentType(MediaType.APPLICATION_JSON).content("{\"clientIp\":\"10.0.0.9\"}"))
            .andExpect(status().isOk());

        verify(cascadeClientScheduler, never()).startPlatform(any());
    }

    @Test
    @DisplayName("更新：单条平台重建失败只告警，不影响接口成功返回")
    void update_restart_failure_does_not_fail_request() throws Exception {
        when(cascadeClientManager.getById(CLIENT_ID)).thenReturn(client(CLIENT_ID, CLIENT_CODE));
        when(cascadeClientManager.update(any(CascadeClientDTO.class))).thenReturn(true);
        CascadePlatformDTO broken = platform(PLATFORM_ID, 1);
        when(cascadePlatformManager.listByClientRefId(CLIENT_ID)).thenReturn(List.of(broken));
        doThrow(new IllegalStateException("SIP 客户端未就绪")).when(cascadeClientScheduler).startPlatform(any());

        mvc.perform(put("/api/v1/cascade/client/" + CLIENT_ID)
            .contentType(MediaType.APPLICATION_JSON).content("{\"clientIp\":\"10.0.0.9\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("更新：不存在时回可读错误且不落库")
    void update_not_found() throws Exception {
        when(cascadeClientManager.getById(CLIENT_ID)).thenReturn(null);

        mvc.perform(put("/api/v1/cascade/client/" + CLIENT_ID)
            .contentType(MediaType.APPLICATION_JSON).content("{\"clientName\":\"x\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msg").value("本端客户端不存在：" + CLIENT_ID));

        verify(cascadeClientManager, never()).update(any());
    }

    /* ---------------- 删除 / 默认 ---------------- */

    @Test
    @DisplayName("删除：仍被平台引用时回可读原因，提示先换成其他客户端")
    void delete_referenced_returns_reason() throws Exception {
        when(cascadeClientManager.delete(CLIENT_ID))
            .thenThrow(new IllegalStateException("该本端客户端仍被 2 个上级平台引用，请先改用其他客户端"));

        mvc.perform(delete("/api/v1/cascade/client/" + CLIENT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msg").value("该本端客户端仍被 2 个上级平台引用，请先改用其他客户端"));
    }

    @Test
    @DisplayName("删除：无引用时正常删除")
    void delete_ok() throws Exception {
        when(cascadeClientManager.delete(CLIENT_ID)).thenReturn(true);

        mvc.perform(delete("/api/v1/cascade/client/" + CLIENT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("设为默认：委托 Manager（清其他行的默认标记由 Manager 保证互斥）")
    void set_default_ok() throws Exception {
        when(cascadeClientManager.setDefault(CLIENT_ID)).thenReturn(true);

        mvc.perform(post("/api/v1/cascade/client/" + CLIENT_ID + "/default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(cascadeClientManager).setDefault(CLIENT_ID);
    }

    /* ---------------- VO ---------------- */

    @Test
    @DisplayName("VO：时间为空时输出 null，不报 NPE")
    void vo_handles_null_time() {
        CascadeClientDTO dto = client(CLIENT_ID, CLIENT_CODE);
        dto.setCreateTime(null);
        dto.setUpdateTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5));

        CascadeClientVO vo = CascadeClientVO.convertVO(dto, 0L);
        assertNull(vo.getCreateTime());
        /* 断言按本机时区换算，写死时间戳会在换时区的 CI 上假失败 */
        assertEquals(dto.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            vo.getUpdateTime(), "时间统一按系统时区转 Unix 毫秒");
    }

    /* ---------------- 辅助 ---------------- */

    private static CascadeClientDTO client(Long id, String code) {
        CascadeClientDTO dto = new CascadeClientDTO();
        dto.setId(id);
        dto.setClientId(code);
        dto.setClientName("本端");
        dto.setClientIp("192.168.118.7");
        dto.setIsDefault(1);
        dto.setEnabled(1);
        return dto;
    }

    private static CascadePlatformDTO platform(Long id, Integer enabled) {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setId(id);
        dto.setPlatformId(PLATFORM_CODE);
        dto.setPlatformIp("10.0.0.1");
        dto.setPlatformPort(5060);
        dto.setClientRefId(CLIENT_ID);
        dto.setEnabled(enabled);
        return dto;
    }

    private static String createBody(String clientIp) {
        return "{\"clientId\":\"" + CLIENT_CODE + "\",\"clientName\":\"本端\",\"clientIp\":\"" + clientIp + "\"}";
    }
}
