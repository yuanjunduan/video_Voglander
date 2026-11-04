package io.github.lunasaw.voglander.web.api.cascade;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
import io.github.lunasaw.voglander.manager.code.CascadeCodeReapplyResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeRuleService;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeBlockedOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgCatalog;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRevokePreviewDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.support.CascadeDeliveryGate;

/**
 * CascadeOrgController「本地停用」相关端点单元测试。
 *
 * <p>重点不在转发本身，而在两个容易漏掉的行为：预览必须<b>回填逐平台投递风险</b>
 * （否则用户不知道上级离线时 DEL 会丢），批量必须<b>逐条隔离失败</b>
 * （一条失败不能让其余几条的结果无从得知）。
 *
 * <p><b>为什么风险是逐平台一份</b>：组织是本地资产，「推给谁」由 {@code tb_cascade_publish}
 * 决定，同一组织可以同时推两个上级；各平台在线状态与通道处置策略互不相干，
 * 给一个汇总的 {@code deliverable} 会让用户误以为「全都发得出去」。
 *
 * @author luna
 */
@DisplayName("CascadeOrgController 停用端点")
@ExtendWith(MockitoExtension.class)
class CascadeOrgControllerTest {

    @InjectMocks
    CascadeOrgController  controller;
    @Mock
    CascadeOrgManager     cascadeOrgManager;
    @Mock
    CascadeDeliveryGate   cascadeDeliveryGate;
    @Mock
    CascadeCodeRuleService cascadeCodeRuleService;
    @Spy
    CascadeWebAssembler   cascadeWebAssembler = new CascadeWebAssembler();

    MockMvc               mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private CascadeRevokePreviewDTO previewDto() {
        CascadeRevokePreviewDTO dto = new CascadeRevokePreviewDTO();
        dto.setOrgId(7L);
        dto.setOrgCode("34020000002150000001");
        dto.setOrgName("一层");
        /* 策略按平台各配一套：这里只推了 PF1，且配的是 CASCADE（组织与通道一起删） */
        dto.setPublishedPlatformIds(List.of("PF1"));
        dto.setRevokePolicyByPlatform(Map.of("PF1", "CASCADE"));
        dto.getOrgs().add(new LocalCascadeCatalogChangeEvent.Node("34020000002150000001", "一层", null));
        dto.getChannels().add(new LocalCascadeCatalogChangeEvent.Node("CH-1", "前门", "34020000002150000001"));
        return dto;
    }

    @Test
    @DisplayName("停用预览：返回节点清单与计数，并回填逐平台投递风险")
    void preview_should_fill_delivery_warnings() throws Exception {
        when(cascadeOrgManager.previewDisable(7L)).thenReturn(previewDto());
        when(cascadeDeliveryGate.check("PF1")).thenReturn(new CascadeDeliveryGate.Report(
            false, true, List.of("上级平台当前不在线，删除报文会丢失")));

        mvc.perform(get("/api/v1/cascade/org/7/disable/preview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orgCount").value(1))
            .andExpect(jsonPath("$.data.channelCount").value(1))
            /* CASCADE 平台按「组织 + 通道」累加：1 + 1 */
            .andExpect(jsonPath("$.data.deleteCount").value(2))
            .andExpect(jsonPath("$.data.deliveries[0].platformId").value("PF1"))
            .andExpect(jsonPath("$.data.deliveries[0].revokePolicy").value("CASCADE"))
            .andExpect(jsonPath("$.data.deliveries[0].platformOnline").value(false))
            .andExpect(jsonPath("$.data.deliveries[0].deliverable").value(false))
            /* 顶层提示带平台前缀：多平台各有各的问题时能分清该修哪个 */
            .andExpect(jsonPath("$.data.warnings[0]").value("[PF1] 上级平台当前不在线，删除报文会丢失"))
            .andExpect(jsonPath("$.data.orgs[0].cascadeId").value("34020000002150000001"))
            .andExpect(jsonPath("$.data.channels[0].parentId").value("34020000002150000001"));
    }

    @Test
    @DisplayName("停用预览：组织未发布到任何平台时明确提示不会有报文")
    void preview_should_warn_when_unpublished() throws Exception {
        CascadeRevokePreviewDTO dto = new CascadeRevokePreviewDTO();
        dto.setOrgId(8L);
        dto.setOrgCode("34020000002150000009");
        when(cascadeOrgManager.previewDisable(8L)).thenReturn(dto);

        mvc.perform(get("/api/v1/cascade/org/8/disable/preview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deliveries").isEmpty())
            .andExpect(jsonPath("$.data.warnings[0]").value("该组织尚未发布到任何上级平台，停用不会发出任何报文"));
    }

    @Test
    @DisplayName("本地停用：委托 manager.disable，不做物理删除")
    void disable_should_delegate() throws Exception {
        when(cascadeOrgManager.disable(7L)).thenReturn(true);

        mvc.perform(post("/api/v1/cascade/org/7/disable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
        verify(cascadeOrgManager).disable(7L);
        /* 停用可恢复，删除不可恢复：两个入口绝不能混用 */
        verify(cascadeOrgManager, never()).delete(any());
    }

    private static CascadeCodeReapplyResult reapplyResult(boolean dryRun, boolean applied) {
        CascadeCodeReapplyResult result = new CascadeCodeReapplyResult();
        result.setRuleEnabled(true);
        result.setDryRun(dryRun);
        result.setApplied(applied);
        result.setNote(applied ? "已重算 1 个组织编码，通道编码保持稳定" : "预览：1 个组织将重编码，未写入任何数据");
        CascadeCodeReapplyResult.Change change = new CascadeCodeReapplyResult.Change();
        change.setObjectType("ORG");
        change.setObjectKey("34020000002150000001");
        change.setBefore("34020000002150000001");
        change.setAfter("37010200042160000001");
        result.getChanges().add(change);
        return result;
    }

    @Test
    @DisplayName("编码重算：不传 dryRun 时默认只预览，绝不落库")
    void reapplyCode_should_default_to_dry_run() throws Exception {
        when(cascadeCodeRuleService.reapply(7L, true)).thenReturn(reapplyResult(true, false));

        mvc.perform(post("/api/v1/cascade/org/7/code/reapply"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true))
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.changes[0].before").value("34020000002150000001"))
            .andExpect(jsonPath("$.data.changes[0].after").value("37010200042160000001"));
        /* 默认值本身就是安全边界：漏传参数不等于「确认执行」 */
        verify(cascadeCodeRuleService).reapply(7L, true);
    }

    @Test
    @DisplayName("编码重算：dryRun=false 才委托落库，冲突一次列全")
    void reapplyCode_should_apply_when_asked() throws Exception {
        CascadeCodeReapplyResult result = reapplyResult(false, true);
        when(cascadeCodeRuleService.reapply(7L, false)).thenReturn(result);

        mvc.perform(post("/api/v1/cascade/org/7/code/reapply").param("dryRun", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied").value(true))
            .andExpect(jsonPath("$.data.dryRun").value(false))
            .andExpect(jsonPath("$.data.conflicts").isEmpty())
            .andExpect(jsonPath("$.data.note").value("已重算 1 个组织编码，通道编码保持稳定"));
    }

    @Test
    @DisplayName("批量停用：逐条隔离，一条抛异常不影响其余结果")
    void disableBatch_should_isolate_failures() throws Exception {
        CascadeOrgDTO ok = new CascadeOrgDTO();
        ok.setOrgName("可停用");
        when(cascadeOrgManager.getById(1L)).thenReturn(ok);
        when(cascadeOrgManager.disable(1L)).thenReturn(true);
        when(cascadeOrgManager.getById(2L)).thenThrow(new IllegalArgumentException("组织不存在: id=2"));

        mvc.perform(post("/api/v1/cascade/org/disable/batch")
            .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[1,2]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.succeeded").value(1))
            .andExpect(jsonPath("$.data.failed").value(1))
            .andExpect(jsonPath("$.data.items[0].ok").value(true))
            .andExpect(jsonPath("$.data.items[0].orgName").value("可停用"))
            .andExpect(jsonPath("$.data.items[1].ok").value(false))
            .andExpect(jsonPath("$.data.items[1].message").value("组织不存在: id=2"));
    }

    @Test
    @DisplayName("新增组织：JSON 里的首尾空白在反序列化时经 setter 归一")
    void add_should_trim_segments_on_deserialize() throws Exception {
        when(cascadeOrgManager.add(any(CascadeOrgDTO.class))).thenReturn(11L);

        mvc.perform(post("/api/v1/cascade/org")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orgName\":\"一层\",\"codeProvince\":\" 34 \",\"codeCity\":\"02\","
                + "\"codeCounty\":\" 0000 \",\"codeIndustry\":\"00\",\"codeType\":\"215\","
                + "\"codeNetwork\":\" 0 \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(11));

        /* 只测 setter 的话，换成别的 HttpMessageConverter 或改字段可见性都不会失败 */
        verify(cascadeOrgManager).add(argThat(d -> "34".equals(d.getCodeProvince())
            && "0000".equals(d.getCodeCounty()) && "0".equals(d.getCodeNetwork())));
    }

    @Test
    @DisplayName("编辑组织：空白经 setter 归一，但空串仍保留为「清空」")
    void update_should_trim_segments_but_keep_blank_as_empty() throws Exception {
        when(cascadeOrgManager.update(any(CascadeOrgDTO.class))).thenReturn(true);

        mvc.perform(put("/api/v1/cascade/org/7")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"codeProvince\":\" 37 \",\"codeCity\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        /* "" 不能变成 null：null 在 update 里是「不改」，清空某段会静默失效 */
        verify(cascadeOrgManager).update(argThat(d -> "37".equals(d.getCodeProvince())
            && "".equals(d.getCodeCity())));
    }

    @Test
    @DisplayName("组织详情：VO 六段把空白归一成 null，前端只判一种「未配置」")
    void getById_should_normalize_blank_segments_in_vo() throws Exception {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setId(9L);
        dto.setOrgCode("34020000002150000009");
        dto.setCodeProvince("");
        dto.setCodeCity("  ");
        dto.setCodeType("216");
        when(cascadeOrgManager.getById(9L)).thenReturn(dto);

        mvc.perform(get("/api/v1/cascade/org/9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.codeProvince").value(nullValue()))
            .andExpect(jsonPath("$.data.codeCity").value(nullValue()))
            .andExpect(jsonPath("$.data.codeCounty").value(nullValue()))
            .andExpect(jsonPath("$.data.codeType").value("216"));
    }

    @Test
    @DisplayName("平台组织视角：目录与阻断同源下发，三个统计值一次算好")
    void listPublished_should_merge_catalog_and_blocked() throws Exception {
        CascadePlatformOrgDTO delivered = new CascadePlatformOrgDTO();
        delivered.setId(1L);
        delivered.setLocalCode("34020000002150000001");
        delivered.setPlatformCode("37000200002000000001");
        delivered.setName("一层");
        delivered.setSortOrder(0);
        CascadePlatformOrgDTO child = new CascadePlatformOrgDTO();
        child.setId(2L);
        child.setLocalCode("34020000002150000002");
        child.setPlatformCode("34020000002150000002");
        child.setName("二层");
        child.setSortOrder(1);
        CascadeBlockedOrgDTO blocked = new CascadeBlockedOrgDTO();
        blocked.setId(3L);
        blocked.setLocalCode("34020000002150000003");
        blocked.setName("三层");
        blocked.setBlockedByCode("34020000002150000002");
        blocked.setBlockedByName("二层");
        blocked.setReason(CascadeBlockedOrgDTO.REASON_ANCESTOR_NOT_PUBLISHED);
        blocked.setRerouted(true);

        when(cascadeOrgManager.platformOrgCatalog("PF1")).thenReturn(new CascadePlatformOrgCatalog(
            List.of(delivered, child), Set.of("34020000002150000002"), List.of(blocked)));

        mvc.perform(get("/api/v1/cascade/org/published").param("platformId", "PF1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.platformId").value("PF1"))
            .andExpect(jsonPath("$.data.deliveredCount").value(2))
            .andExpect(jsonPath("$.data.blockedCount").value(1))
            /* 上浮集合覆盖全量被阻断组织，与阻断清单不是同一集合，计数直接取集合大小 */
            .andExpect(jsonPath("$.data.reroutedCount").value(1))
            .andExpect(jsonPath("$.data.items[0].platformCode").value("37000200002000000001"))
            .andExpect(jsonPath("$.data.blocked[0].blockedByCode").value("34020000002150000002"))
            .andExpect(jsonPath("$.data.blocked[0].reason").value("ANCESTOR_NOT_PUBLISHED"))
            .andExpect(jsonPath("$.data.blocked[0].rerouted").value(true));
    }
}
