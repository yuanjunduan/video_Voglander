package io.github.lunasaw.voglander.web.api.cascade;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeClientScheduler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.config.properties.VoglanderSipClientProperties;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;

/**
 * CascadePlatformController 纯 Mockito + MockMvc 单元测试。
 *
 * <p>覆盖 CRUD/分页端点连通性，以及"调度生命周期"约束：新增后启动、更新后重启、删除前先停。</p>
 *
 * @author luna
 */
@DisplayName("CascadePlatformController 单元测试")
@ExtendWith(MockitoExtension.class)
class CascadePlatformControllerTest {

    /** 合法的 20 位平台 ID */
    private static final String PLATFORM_ID    = "34020000002000000001";
    /** 合法的 20 位本端客户端 ID */
    private static final String LOCAL_CLIENT_ID = "34020000002000000002";

    @InjectMocks
    CascadePlatformController controller;
    @Mock
    CascadePlatformManager       cascadePlatformManager;
    @Mock
    CascadeClientScheduler       cascadeClientScheduler;
    @Mock
    VoglanderSipClientProperties sipClientProperties;
    @Spy
    CascadeWebAssembler          cascadeWebAssembler = new CascadeWebAssembler();

    MockMvc                      mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /* ---------------- 新增 ---------------- */

    @Test
    @DisplayName("新增：成功后按最新记录启动注册调度")
    void add_starts_scheduler() throws Exception {
        CascadePlatformDTO saved = platform(88L, 1);
        when(cascadePlatformManager.add(any(CascadePlatformDTO.class))).thenReturn(88L);
        when(cascadePlatformManager.getById(88L)).thenReturn(saved);

        mvc.perform(post("/api/v1/cascade/platform")
            .contentType(MediaType.APPLICATION_JSON).content(createBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(88));

        verify(cascadePlatformManager).add(any(CascadePlatformDTO.class));
        verify(cascadeClientScheduler).startPlatform(saved);
    }

    @Test
    @DisplayName("新增：平台ID已存在时返回友好提示且不落库")
    void add_duplicate_platform_id() throws Exception {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform(9L, 1));

        mvc.perform(post("/api/v1/cascade/platform")
            .contentType(MediaType.APPLICATION_JSON).content(createBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msg").value("平台ID已存在：" + PLATFORM_ID));

        verify(cascadePlatformManager, never()).add(any(CascadePlatformDTO.class));
        verify(cascadeClientScheduler, never()).startPlatform(any());
    }

    @Test
    @DisplayName("新增：平台默认停用（enabled 未置 1）时不启动调度")
    void add_disabled_no_scheduler() throws Exception {
        when(cascadePlatformManager.add(any(CascadePlatformDTO.class))).thenReturn(90L);
        when(cascadePlatformManager.getById(90L)).thenReturn(platform(90L, 0));

        mvc.perform(post("/api/v1/cascade/platform")
            .contentType(MediaType.APPLICATION_JSON).content(createBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(90));

        verify(cascadeClientScheduler, never()).startPlatform(any());
    }

    @Test
    @DisplayName("新增：未填本端地址/平台域时由后端补齐（sip.client + 平台ID前10位）")
    void add_fills_defaults() throws Exception {
        when(cascadePlatformManager.add(any(CascadePlatformDTO.class))).thenReturn(91L);
        when(sipClientProperties.getDomain()).thenReturn("192.168.118.7");
        when(sipClientProperties.getPort()).thenReturn(5061);

        mvc.perform(post("/api/v1/cascade/platform")
            .contentType(MediaType.APPLICATION_JSON).content(createBody()))
            .andExpect(status().isOk());

        verify(cascadePlatformManager).add(argThat(d -> "192.168.118.7".equals(d.getLocalIp())
            && Integer.valueOf(5061).equals(d.getLocalPort())
            && "3402000000".equals(d.getPlatformDomain())));
    }

    /* ---------------- 更新 ---------------- */

    @Test
    @DisplayName("更新：密码留空时保留库中原密码，并重启调度")
    void update_keeps_password_when_blank() throws Exception {
        CascadePlatformDTO existing = platform(5L, 1);
        existing.setPassword("p@ssw0rd");
        when(cascadePlatformManager.getById(5L)).thenReturn(existing);
        when(cascadePlatformManager.update(any(CascadePlatformDTO.class))).thenReturn(true);

        mvc.perform(put("/api/v1/cascade/platform/5")
            .contentType(MediaType.APPLICATION_JSON).content("{\"platformIp\":\"10.0.0.2\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(cascadePlatformManager).update(argThat(d -> Long.valueOf(5L).equals(d.getId())
            && "p@ssw0rd".equals(d.getPassword())));
        InOrder inOrder = inOrder(cascadeClientScheduler);
        inOrder.verify(cascadeClientScheduler).stopPlatform(5L);
        inOrder.verify(cascadeClientScheduler).startPlatform(existing);
    }

    @Test
    @DisplayName("更新：显式传密码时使用新密码")
    void update_uses_new_password() throws Exception {
        CascadePlatformDTO existing = platform(5L, 1);
        when(cascadePlatformManager.getById(5L)).thenReturn(existing);
        when(cascadePlatformManager.update(any(CascadePlatformDTO.class))).thenReturn(true);

        mvc.perform(put("/api/v1/cascade/platform/5")
            .contentType(MediaType.APPLICATION_JSON).content("{\"platformIp\":\"10.0.0.2\",\"password\":\"new-secret\"}"))
            .andExpect(status().isOk());

        verify(cascadePlatformManager).update(argThat(d -> "new-secret".equals(d.getPassword())));
    }

    @Test
    @DisplayName("更新：平台不存在时返回错误且不落库")
    void update_not_found() throws Exception {
        when(cascadePlatformManager.getById(5L)).thenReturn(null);

        mvc.perform(put("/api/v1/cascade/platform/5")
            .contentType(MediaType.APPLICATION_JSON).content("{\"platformIp\":\"10.0.0.2\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msg").value("上级平台不存在：5"));

        verify(cascadePlatformManager, never()).update(any(CascadePlatformDTO.class));
        verify(cascadeClientScheduler, never()).startPlatform(any());
    }

    /* ---------------- 删除 ---------------- */

    @Test
    @DisplayName("删除：必须先停调度再删记录（顺序不可颠倒）")
    void delete_stops_scheduler_before_delete() throws Exception {
        when(cascadePlatformManager.delete(5L)).thenReturn(true);

        mvc.perform(delete("/api/v1/cascade/platform/5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        InOrder inOrder = inOrder(cascadeClientScheduler, cascadePlatformManager);
        inOrder.verify(cascadeClientScheduler).stopPlatform(5L);
        inOrder.verify(cascadePlatformManager).delete(5L);
    }

    /* ---------------- 查询 ---------------- */

    @Test
    @DisplayName("详情：返回 VO 且不回传密码")
    void getById_does_not_leak_password() throws Exception {
        CascadePlatformDTO dto = platform(5L, 1);
        dto.setPassword("secret");
        when(cascadePlatformManager.getById(5L)).thenReturn(dto);

        mvc.perform(get("/api/v1/cascade/platform/5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(5))
            .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    @DisplayName("分页：POST /getPage 委托 manager.getPage，返回 total + items")
    void page_ok() throws Exception {
        when(cascadePlatformManager.getPage(any(CascadePlatformDTO.class), eq(1), eq(20)))
            .thenReturn(new Page<>(1, 20));
        mvc.perform(post("/api/v1/cascade/platform/getPage")
            .param("page", "1").param("size", "20")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.items").isArray());
        verify(cascadePlatformManager).getPage(any(CascadePlatformDTO.class), eq(1), eq(20));
    }

    /* ---------------- 启停 ---------------- */

    @Test
    @DisplayName("启用：成功后启动平台注册调度")
    void enable_starts_scheduler() throws Exception {
        CascadePlatformDTO dto = platform(5L, 1);
        when(cascadePlatformManager.enablePlatform(5L)).thenReturn(true);
        when(cascadePlatformManager.getById(5L)).thenReturn(dto);

        mvc.perform(post("/api/v1/cascade/platform/5/enable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(cascadePlatformManager).enablePlatform(5L);
        verify(cascadeClientScheduler).startPlatform(dto);
    }

    @Test
    @DisplayName("启用：平台不存在时返回错误，不启用不调度")
    void enable_not_found() throws Exception {
        when(cascadePlatformManager.getById(5L)).thenReturn(null);

        mvc.perform(post("/api/v1/cascade/platform/5/enable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msg").value("上级平台不存在：5"));

        verify(cascadePlatformManager, never()).enablePlatform(anyLong());
        verify(cascadeClientScheduler, never()).startPlatform(any());
    }

    @Test
    @DisplayName("停用：先停调度再停用 manager")
    void disable_stops_scheduler_then_manager() throws Exception {
        when(cascadePlatformManager.disablePlatform(5L)).thenReturn(true);
        mvc.perform(post("/api/v1/cascade/platform/5/disable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        InOrder inOrder = inOrder(cascadeClientScheduler, cascadePlatformManager);
        inOrder.verify(cascadeClientScheduler).stopPlatform(5L);
        inOrder.verify(cascadePlatformManager).disablePlatform(5L);
    }

    @Test
    @DisplayName("刷新：委托调度器 refreshRegistrations")
    void refresh_ok() throws Exception {
        mvc.perform(post("/api/v1/cascade/platform/refresh"))
            .andExpect(status().isOk());
        verify(cascadeClientScheduler).refreshRegistrations();
    }

    /* ---------------- 辅助 ---------------- */

    private static CascadePlatformDTO platform(Long id, Integer enabled) {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setId(id);
        dto.setPlatformId(PLATFORM_ID);
        dto.setPlatformIp("10.0.0.1");
        dto.setPlatformPort(5060);
        dto.setLocalClientId(LOCAL_CLIENT_ID);
        dto.setLocalIp("192.168.118.7");
        dto.setLocalPort(5061);
        dto.setEnabled(enabled);
        return dto;
    }

    private static String createBody() {
        return "{\"platformId\":\"" + PLATFORM_ID + "\",\"platformIp\":\"10.0.0.1\","
            + "\"platformPort\":5060,\"localClientId\":\"" + LOCAL_CLIENT_ID + "\"}";
    }
}