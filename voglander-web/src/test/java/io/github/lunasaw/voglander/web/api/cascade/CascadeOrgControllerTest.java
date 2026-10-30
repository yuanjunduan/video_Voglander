package io.github.lunasaw.voglander.web.api.cascade;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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

import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRevokePreviewDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.support.CascadeDeliveryGate;

/**
 * CascadeOrgController「取消同步」相关端点单元测试。
 *
 * <p>重点不在转发本身，而在两个容易漏掉的行为：预览必须<b>回填投递风险</b>
 * （否则用户不知道上级离线时 DEL 会丢），批量必须<b>逐条隔离失败</b>
 * （一条失败不能让其余几条的结果无从得知）。
 *
 * @author luna
 */
@DisplayName("CascadeOrgController 取消同步端点")
@ExtendWith(MockitoExtension.class)
class CascadeOrgControllerTest {

    @InjectMocks
    CascadeOrgController  controller;
    @Mock
    CascadeOrgManager     cascadeOrgManager;
    @Mock
    CascadeDeliveryGate   cascadeDeliveryGate;
    @Spy
    CascadeWebAssembler   cascadeWebAssembler = new CascadeWebAssembler();

    MockMvc               mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private CascadeRevokePreviewDTO previewDto() {
        CascadeRevokePreviewDTO dto = new CascadeRevokePreviewDTO();
        dto.setPlatformId("PF1");
        dto.setCascadeOrgId("34020000002150000001");
        dto.setOrgName("一层");
        dto.setRevokePolicy("CASCADE");
        dto.getOrgs().add(new LocalCascadeCatalogChangeEvent.Node("34020000002150000001", "一层", null));
        dto.getChannels().add(new LocalCascadeCatalogChangeEvent.Node("CH-1", "前门", "34020000002150000001"));
        return dto;
    }

    @Test
    @DisplayName("预览：返回节点清单与计数，并回填投递风险提示")
    void preview_should_fill_delivery_warnings() throws Exception {
        when(cascadeOrgManager.previewRevoke(eq(7L), eq("CASCADE"))).thenReturn(previewDto());
        when(cascadeDeliveryGate.check("PF1")).thenReturn(new CascadeDeliveryGate.Report(
            false, true, List.of("上级平台当前不在线，删除报文会丢失")));

        mvc.perform(get("/api/v1/cascade/org/7/revoke/preview").param("policy", "CASCADE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orgCount").value(1))
            .andExpect(jsonPath("$.data.channelCount").value(1))
            .andExpect(jsonPath("$.data.deleteCount").value(2))
            .andExpect(jsonPath("$.data.platformOnline").value(false))
            .andExpect(jsonPath("$.data.deliverable").value(false))
            .andExpect(jsonPath("$.data.warnings[0]").value("上级平台当前不在线，删除报文会丢失"))
            .andExpect(jsonPath("$.data.orgs[0].cascadeId").value("34020000002150000001"))
            .andExpect(jsonPath("$.data.channels[0].parentId").value("34020000002150000001"));
    }

    @Test
    @DisplayName("取消同步：透传 policy，不传时交给 manager 回落已存策略")
    void revoke_should_pass_policy() throws Exception {
        when(cascadeOrgManager.revoke(7L, "REROUTE")).thenReturn(true);

        mvc.perform(post("/api/v1/cascade/org/7/revoke")
            .contentType(MediaType.APPLICATION_JSON).content("{\"policy\":\"REROUTE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
        verify(cascadeOrgManager).revoke(7L, "REROUTE");
        verify(cascadeOrgManager, never()).delete(any());
    }

    @Test
    @DisplayName("批量取消同步：逐条隔离，一条抛异常不影响其余结果")
    void revokeBatch_should_isolate_failures() throws Exception {
        CascadeOrgDTO ok = new CascadeOrgDTO();
        ok.setOrgName("可取消");
        when(cascadeOrgManager.getById(1L)).thenReturn(ok);
        when(cascadeOrgManager.revoke(1L, null)).thenReturn(true);
        when(cascadeOrgManager.getById(2L)).thenThrow(new IllegalArgumentException("组织不存在: id=2"));

        mvc.perform(post("/api/v1/cascade/org/revoke/batch")
            .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[1,2]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.succeeded").value(1))
            .andExpect(jsonPath("$.data.failed").value(1))
            .andExpect(jsonPath("$.data.items[0].ok").value(true))
            .andExpect(jsonPath("$.data.items[0].orgName").value("可取消"))
            .andExpect(jsonPath("$.data.items[1].ok").value(false))
            .andExpect(jsonPath("$.data.items[1].message").value("组织不存在: id=2"));
    }
}