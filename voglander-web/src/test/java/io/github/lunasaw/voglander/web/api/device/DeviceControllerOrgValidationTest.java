package io.github.lunasaw.voglander.web.api.device;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.web.api.device.assembler.DeviceWebAssembler;
import io.github.lunasaw.voglander.web.exception.GlobalExceptionHandler;

/**
 * DeviceController 组织归属校验单元测试。
 *
 * <p>
 * 锁定「手填新增设备必须归属一个组织」这一约束：<b>必填校验只能留在 Web 入口</b>，
 * 因为 SIP 自动注册复用 {@code DeviceManager#saveOrUpdate → add} 且没有表单，
 * 校验一旦下沉到 Manager，所有 GB28181 设备都会注册失败。
 * </p>
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
class DeviceControllerOrgValidationTest {

    @InjectMocks
    DeviceController    controller;
    @Mock
    DeviceManager       deviceManager;
    @Mock
    DeviceWebAssembler  deviceWebAssembler;
    @Mock
    CascadeOrgManager   cascadeOrgManager;

    MockMvc             mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void insert_withoutOrgId_rejectedBeforePersist() throws Exception {
        mvc.perform(post("/api/v1/device/insert")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"34020000001320000001\",\"type\":4}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.msg").value("新增设备必须选择所属组织"));

        // 校验必须发生在转换/落库之前，否则会先写出一条无组织设备
        verify(deviceWebAssembler, never()).toDeviceDTO(any(io.github.lunasaw.voglander.web.api.device.req.DeviceCreateReq.class));
        verify(deviceManager, never()).createDevice(any());
    }

    @Test
    void insert_withZeroOrgId_rejected() throws Exception {
        // 0 在更新接口是「清空归属」哨兵，新增时当作未选择处理
        mvc.perform(post("/api/v1/device/insert")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"34020000001320000001\",\"orgId\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.msg").value("新增设备必须选择所属组织"));

        verify(deviceManager, never()).createDevice(any());
    }

    @Test
    void insert_withUnknownOrgId_rejected() throws Exception {
        when(cascadeOrgManager.getById(99L)).thenReturn(null);

        mvc.perform(post("/api/v1/device/insert")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"34020000001320000001\",\"orgId\":99}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("所属组织不存在")));

        verify(deviceManager, never()).createDevice(any());
    }

    @Test
    void insert_withExistingOrgId_persists() throws Exception {
        when(cascadeOrgManager.getById(9L)).thenReturn(new CascadeOrgDTO());
        when(deviceWebAssembler.toDeviceDTO(any(io.github.lunasaw.voglander.web.api.device.req.DeviceCreateReq.class)))
            .thenReturn(new DeviceDTO());
        when(deviceManager.createDevice(any())).thenReturn(1L);

        mvc.perform(post("/api/v1/device/insert")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"34020000001320000001\",\"orgId\":9}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(deviceManager).createDevice(any());
    }

    @Test
    void update_withoutOrgId_skipsValidation() throws Exception {
        // SIP 自动注册的设备没有组织，编辑其它字段时不能因为 orgId 缺失被拦
        when(deviceWebAssembler.toDeviceDTO(any(io.github.lunasaw.voglander.web.api.device.req.DeviceUpdateReq.class)))
            .thenReturn(new DeviceDTO());
        when(deviceManager.updateDevice(any())).thenReturn(1L);

        mvc.perform(put("/api/v1/device/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":1,\"name\":\"改名\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(cascadeOrgManager, never()).getById(any());
    }

    @Test
    void update_withZeroOrgId_clearsWithoutOrgLookup() throws Exception {
        when(deviceWebAssembler.toDeviceDTO(any(io.github.lunasaw.voglander.web.api.device.req.DeviceUpdateReq.class)))
            .thenReturn(new DeviceDTO());
        when(deviceManager.updateDevice(any())).thenReturn(1L);

        mvc.perform(put("/api/v1/device/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":1,\"orgId\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(cascadeOrgManager, never()).getById(any());
    }

    @Test
    void update_withUnknownOrgId_rejected() throws Exception {
        when(cascadeOrgManager.getById(99L)).thenReturn(null);

        mvc.perform(put("/api/v1/device/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":1,\"orgId\":99}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("所属组织不存在")));

        verify(deviceManager, never()).updateDevice(any());
    }
}
