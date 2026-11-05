package io.github.lunasaw.voglander.web.api.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.manager.service.DeviceChannelService;
import io.github.lunasaw.voglander.service.device.DeviceChannelDiscoveryService;
import io.github.lunasaw.voglander.web.api.channel.assembler.DeviceChannelWebAssembler;

/**
 * 设备通道出参的<b>组织归属回填</b>。
 *
 * <p><b>只钉一件事</b>：{@code orgId}/{@code orgName} 取的是<b>设备</b>的组织，不是通道行上的
 * {@code org_id}。通道行那一列已经停写，但历史上写进去的值还在库里——回填时若照抄，
 * 页面会显示一个目录里早已不生效的组织，而任何一边单看都发现不了。
 *
 * <p><b>为什么用纯 Mockito</b>：要验证的是「同一份数据从设备侧取还是从通道侧取」，
 * 与真实库表无关。落库与写入侧摘除 {@code orgId} 由 {@code DeviceLocalAssetTest} 覆盖。
 *
 * @author luna
 */
@DisplayName("DeviceChannelController 组织归属回填")
@ExtendWith(MockitoExtension.class)
class DeviceChannelControllerTest {

    private static final String DEVICE_ID      = "34020000001320000001";
    private static final String CHANNEL_ID     = "34020000001310000001";
    /** 通道行上残留的旧归属：停写前写进去的，任何回填都不该采用它 */
    private static final Long   STALE_CHANNEL_ORG = 900L;
    /** 设备当前归属的组织 */
    private static final Long   DEVICE_ORG     = 5L;

    @InjectMocks
    private DeviceChannelController controller;

    @Mock
    private DeviceChannelService        deviceChannelService;
    @Mock
    private DeviceChannelManager        deviceChannelManager;
    @Mock
    private CascadeOrgManager           cascadeOrgManager;
    @Mock
    private DeviceManager               deviceManager;
    @Mock
    private DeviceChannelDiscoveryService deviceChannelDiscoveryService;

    @Spy
    private DeviceChannelWebAssembler   deviceChannelWebAssembler = new DeviceChannelWebAssembler();

    private MockMvc                     mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("分页：组织取设备归属，通道行残留的 org_id 被覆盖")
    void page_org_follows_device_not_channel_row() throws Exception {
        stubPage(channel(STALE_CHANNEL_ORG));
        when(deviceManager.mapOrgIdByDeviceIds(argThat(ids -> ids.contains(DEVICE_ID))))
            .thenReturn(Map.of(DEVICE_ID, DEVICE_ORG));
        when(cascadeOrgManager.nameIndexById()).thenReturn(Map.of(DEVICE_ORG, "二层"));

        mvc.perform(post("/api/v1/deviceChannel/getPage")
            .param("page", "1").param("size", "10")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].orgId").value(DEVICE_ORG))
            .andExpect(jsonPath("$.data.items[0].orgName").value("二层"));
    }

    @Test
    @DisplayName("分页：设备未归组织时 orgId/orgName 都为空，不拿通道残留值顶上")
    void page_device_without_org_returns_null() throws Exception {
        stubPage(channel(STALE_CHANNEL_ORG));
        /* 设备没归属：map 里查不到这个 deviceId */
        when(deviceManager.mapOrgIdByDeviceIds(any())).thenReturn(Map.of());
        when(cascadeOrgManager.nameIndexById()).thenReturn(Map.of(DEVICE_ORG, "二层"));

        mvc.perform(post("/api/v1/deviceChannel/getPage")
            .param("page", "1").param("size", "10")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].orgId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].orgName").doesNotExist());
    }

    @Test
    @DisplayName("分页：整页只查一次设备组织，不逐行回表")
    void page_looks_up_device_org_once() throws Exception {
        DeviceChannelDTO first = channel(1L);
        first.setChannelId(CHANNEL_ID + "-a");
        DeviceChannelDTO second = channel(1L);
        second.setChannelId(CHANNEL_ID + "-b");
        Page<DeviceChannelDTO> page = new Page<>(1, 10);
        page.setRecords(List.of(first, second));
        page.setTotal(2);
        when(deviceChannelManager.getPage(any(DeviceChannelDTO.class), anyInt(), anyInt())).thenReturn(page);
        when(deviceManager.mapOrgIdByDeviceIds(any())).thenReturn(Map.of(DEVICE_ID, DEVICE_ORG));
        when(cascadeOrgManager.nameIndexById()).thenReturn(Map.of(DEVICE_ORG, "二层"));

        mvc.perform(post("/api/v1/deviceChannel/getPage")
            .param("page", "1").param("size", "10")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());

        verify(deviceManager, times(1)).mapOrgIdByDeviceIds(any());
    }

    @Test
    @DisplayName("单条查询：与分页同一口径，仍按设备组织回填")
    void get_single_uses_device_org() throws Exception {
        when(deviceChannelManager.get(any(DeviceChannelDTO.class))).thenReturn(channel(STALE_CHANNEL_ORG));
        when(deviceManager.mapOrgIdByDeviceIds(argThat(ids -> ids.contains(DEVICE_ID))))
            .thenReturn(Map.of(DEVICE_ID, DEVICE_ORG));
        when(cascadeOrgManager.nameIndexById()).thenReturn(Map.of(DEVICE_ORG, "二层"));

        mvc.perform(post("/api/v1/deviceChannel/get")
            .contentType(MediaType.APPLICATION_JSON).content("{\"channelId\":\"" + CHANNEL_ID + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orgId").value(DEVICE_ORG))
            .andExpect(jsonPath("$.data.orgName").value("二层"));
    }

    // ------------------------------------------------------------------ 夹具

    /** 一条通道：行上带着停写前的旧归属，模拟真实的存量脏值 */
    private DeviceChannelDTO channel(Long rowOrgId) {
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setId(1L);
        dto.setDeviceId(DEVICE_ID);
        dto.setChannelId(CHANNEL_ID);
        dto.setName("前门");
        dto.setStatus(1);
        dto.setEnabled(1);
        dto.setOrgId(rowOrgId);
        return dto;
    }

    private void stubPage(DeviceChannelDTO dto) {
        Page<DeviceChannelDTO> page = new Page<>(1, 10);
        page.setRecords(List.of(dto));
        page.setTotal(1);
        when(deviceChannelManager.getPage(any(DeviceChannelDTO.class), anyInt(), anyInt())).thenReturn(page);
    }
}
