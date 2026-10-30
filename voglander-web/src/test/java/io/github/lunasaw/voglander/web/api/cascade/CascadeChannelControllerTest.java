package io.github.lunasaw.voglander.web.api.cascade;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMetaResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CatalogVisibility;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;

/**
 * CascadeChannelController 纯 Mockito + MockMvc 单元测试。
 *
 * @author luna
 */
@DisplayName("CascadeChannelController 单元测试")
@ExtendWith(MockitoExtension.class)
class CascadeChannelControllerTest {

    @InjectMocks
    CascadeChannelController controller;
    @Mock
    CascadeChannelManager   cascadeChannelManager;
    @Mock
    CascadeChannelMetaResolver channelMetaResolver;
    @Mock
    CascadeOrgManager     cascadeOrgManager;
    @Mock
    CatalogVisibility     catalogVisibility;
    @Spy
    CascadeWebAssembler     cascadeWebAssembler = new CascadeWebAssembler();

    MockMvc                 mvc;

    @BeforeEach
    void setup() {
        /* 旧用例不断言派生字段，留空即走兜底值，避免把派生规则重复写进控制器测试 */
        lenient().when(channelMetaResolver.resolve(any())).thenReturn(java.util.Map.of());
        /* 可见性快照需要整平台数据才能算，控制器只负责填：这里给个全可见的条，避免用例依赖组织数据 */
        lenient().when(cascadeOrgManager.catalogVisibility(any())).thenReturn(catalogVisibility);
        lenient().when(catalogVisibility.isChannelVisible(any())).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("新增：委托 manager.add 并返回 id")
    void add_ok() throws Exception {
        when(cascadeChannelManager.add(any(CascadeChannelDTO.class))).thenReturn(66L);
        mvc.perform(post("/api/v1/cascade/channel")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"platformId\":\"p1\",\"cascadeChannelId\":\"34020000001310000001\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(66));
        verify(cascadeChannelManager).add(any(CascadeChannelDTO.class));
    }

    @Test
    @DisplayName("更新：回填路径 id 后委托 manager.update")
    void update_ok() throws Exception {
        when(cascadeChannelManager.update(any(CascadeChannelDTO.class))).thenReturn(true);
        mvc.perform(put("/api/v1/cascade/channel/7")
            .contentType(MediaType.APPLICATION_JSON).content("{\"cascadeName\":\"x\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
        verify(cascadeChannelManager).update(argThat(d -> Long.valueOf(7L).equals(d.getId())));
    }

    @Test
    @DisplayName("删除：委托 manager.delete")
    void delete_ok() throws Exception {
        when(cascadeChannelManager.delete(7L)).thenReturn(true);
        mvc.perform(delete("/api/v1/cascade/channel/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
        verify(cascadeChannelManager).delete(7L);
    }

    @Test
    @DisplayName("详情：委托 manager.getById 返回 VO")
    void getById_ok() throws Exception {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setId(7L);
        when(cascadeChannelManager.getById(7L)).thenReturn(dto);
        mvc.perform(get("/api/v1/cascade/channel/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(7));
        verify(cascadeChannelManager).getById(7L);
    }

    @Test
    @DisplayName("详情：所属组织停报时 effective=false 且回填停报来源")
    void getById_marks_invisible_when_org_revoked() throws Exception {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setId(7L);
        dto.setPlatformId("p1");
        dto.setEnabled(1);
        dto.setCascadeOrgId("34020000002150000002");
        when(cascadeChannelManager.getById(7L)).thenReturn(dto);
        when(catalogVisibility.isChannelVisible("34020000002150000002")).thenReturn(false);
        when(catalogVisibility.blockedOrgName("34020000002150000002")).thenReturn("一层");

        mvc.perform(get("/api/v1/cascade/channel/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(1))
            .andExpect(jsonPath("$.data.effective").value(false))
            .andExpect(jsonPath("$.data.blockedByOrgName").value("一层"));
    }

    @Test
    @DisplayName("分页：POST /getPage 委托 manager.getPage 并透传 platformId 过滤")
    void page_ok() throws Exception {
        when(cascadeChannelManager.getPage(any(CascadeChannelDTO.class), eq(1), eq(20), eq(false)))
            .thenReturn(new Page<>(1, 20));
        mvc.perform(post("/api/v1/cascade/channel/getPage")
            .param("page", "1").param("size", "20")
            .contentType(MediaType.APPLICATION_JSON).content("{\"platformId\":\"p1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.items").isArray());
        verify(cascadeChannelManager).getPage(argThat(d -> "p1".equals(d.getPlatformId())), eq(1), eq(20),
            eq(false));
    }

    @Test
    @DisplayName("分页：includeSubOrg=true 时透传给 manager，按整棵子树过滤")
    void page_include_sub_org() throws Exception {
        when(cascadeChannelManager.getPage(any(CascadeChannelDTO.class), eq(1), eq(20), eq(true)))
            .thenReturn(new Page<>(1, 20));
        mvc.perform(post("/api/v1/cascade/channel/getPage")
            .param("page", "1").param("size", "20")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"platformId\":\"p1\",\"cascadeOrgId\":\"34020000002150000001\",\"includeSubOrg\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
        verify(cascadeChannelManager).getPage(
            argThat(d -> "34020000002150000001".equals(d.getCascadeOrgId())), eq(1), eq(20), eq(true));
    }

    @Test
    @DisplayName("取消同步：委托 manager.revoke（推 DEL 并保留记录）")
    void revoke_ok() throws Exception {
        when(cascadeChannelManager.revoke(7L)).thenReturn(true);
        mvc.perform(post("/api/v1/cascade/channel/7/revoke"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
        verify(cascadeChannelManager).revoke(7L);
        /* 取消同步不能走删除：删了就再也恢复不了 */
        verify(cascadeChannelManager, never()).delete(any());
    }

    @Test
    @DisplayName("恢复同步：委托 manager.publish（推 ADD）")
    void publish_ok() throws Exception {
        when(cascadeChannelManager.publish(7L)).thenReturn(true);
        mvc.perform(post("/api/v1/cascade/channel/7/publish"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
        verify(cascadeChannelManager).publish(7L);
    }

    @Test
    @DisplayName("批量绑定：委托 manager.batchBind 并返回新增条数")
    void batchBind_ok() throws Exception {
        when(cascadeChannelManager.batchBind(anyList())).thenReturn(2);
        mvc.perform(post("/api/v1/cascade/channel/batchBind")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"platformId\":\"p1\",\"channels\":["
                + "{\"localChannelId\":\"34020000001310000001\"},"
                + "{\"localChannelId\":\"34020000001310000002\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(2));
        verify(cascadeChannelManager).batchBind(argThat(list -> list.size() == 2
            && "p1".equals(list.get(0).getPlatformId())));
    }
}
