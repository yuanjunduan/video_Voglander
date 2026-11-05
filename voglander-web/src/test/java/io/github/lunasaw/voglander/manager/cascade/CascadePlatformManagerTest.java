package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * CascadePlatformManager Stage 1 集成测试（TDD 先红后绿）
 */
@DisplayName("CascadePlatformManager 集成测试")
class CascadePlatformManagerTest extends BaseTest {

    @Autowired
    private CascadePlatformManager cascadePlatformManager;

    /** 构造最小合法 DTO */
    private CascadePlatformDTO buildDto(String platformId) {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setPlatformId(platformId);
        dto.setPlatformIp("192.168.1.100");
        dto.setPlatformPort(5060);
        dto.setPlatformDomain("3402000000");
        dto.setLocalClientId(UniqueKeyFactory.deviceId());
        return dto;
    }

    @Nested
    @DisplayName("add - 新增上级平台")
    class Add {

        @Test
        @DisplayName("最小合法 DTO 应返回自增 ID")
        void should_return_id_on_add() {
            Long id = cascadePlatformManager.add(buildDto(UniqueKeyFactory.deviceId()));
            assertNotNull(id);
            assertTrue(id > 0);
        }

        @Test
        @DisplayName("新增后 enabled=1, registerStatus=0（默认离线）")
        void should_have_default_status() {
            String platformId = UniqueKeyFactory.deviceId();
            Long id = cascadePlatformManager.add(buildDto(platformId));

            CascadePlatformDTO saved = cascadePlatformManager.getByPlatformId(platformId);
            assertNotNull(saved);
            assertEquals(1, saved.getEnabled());
            assertEquals(0, saved.getRegisterStatus());
        }

        @Test
        @DisplayName("platformId 重复应抛出异常")
        void should_throw_on_duplicate_platform_id() {
            String platformId = UniqueKeyFactory.deviceId();
            cascadePlatformManager.add(buildDto(platformId));
            assertThrows(Exception.class, () -> cascadePlatformManager.add(buildDto(platformId)));
        }
    }

    @Nested
    @DisplayName("updateRegisterStatus - 更新注册状态")
    class UpdateRegisterStatus {

        @Test
        @DisplayName("状态变更为 1（在线）应持久化")
        void should_persist_register_status() {
            String platformId = UniqueKeyFactory.deviceId();
            Long id = cascadePlatformManager.add(buildDto(platformId));

            cascadePlatformManager.updateRegisterStatus(id, 1);

            CascadePlatformDTO updated = cascadePlatformManager.getByPlatformId(platformId);
            assertEquals(1, updated.getRegisterStatus());
        }
    }

    @Nested
    @DisplayName("deleteOne - 删除上级平台")
    class Delete {

        @Test
        @DisplayName("按 platformId 删除后应查不到记录")
        void should_delete_by_platform_id() {
            String platformId = UniqueKeyFactory.deviceId();
            cascadePlatformManager.add(buildDto(platformId));

            CascadePlatformDTO dto = new CascadePlatformDTO();
            dto.setPlatformId(platformId);
            assertTrue(cascadePlatformManager.deleteOne(dto));

            assertNull(cascadePlatformManager.getByPlatformId(platformId));
        }
    }

    @Nested
    @DisplayName("platformName - 平台名称")
    class PlatformName {

        @Test
        @DisplayName("新增时名称落库并 trim 首尾空白")
        void should_persist_trimmed_name_on_add() {
            String platformId = UniqueKeyFactory.deviceId();
            CascadePlatformDTO dto = buildDto(platformId);
            dto.setPlatformName("  市公安局上级平台  ");
            cascadePlatformManager.add(dto);

            assertEquals("市公安局上级平台", cascadePlatformManager.getByPlatformId(platformId).getPlatformName());
        }

        @Test
        @DisplayName("新增时不传名称应落空串（未命名），而不是 null")
        void should_persist_empty_name_when_absent() {
            String platformId = UniqueKeyFactory.deviceId();
            cascadePlatformManager.add(buildDto(platformId));

            /* 列是 NOT NULL DEFAULT ''：读回必须是空串，前端才能统一用 name || platformId 回落 */
            assertEquals("", cascadePlatformManager.getByPlatformId(platformId).getPlatformName());
        }

        @Test
        @DisplayName("更新只提交名称时改名，其余字段保持原值")
        void should_rename_without_touching_other_fields() {
            String platformId = UniqueKeyFactory.deviceId();
            CascadePlatformDTO created = buildDto(platformId);
            created.setPlatformName("旧名称");
            Long id = cascadePlatformManager.add(created);

            CascadePlatformDTO patch = new CascadePlatformDTO();
            patch.setId(id);
            patch.setPlatformName("新名称");
            assertTrue(cascadePlatformManager.update(patch));

            CascadePlatformDTO saved = cascadePlatformManager.getById(id);
            assertEquals("新名称", saved.getPlatformName());
            assertEquals("192.168.1.100", saved.getPlatformIp());
            assertEquals("3402000000", saved.getPlatformDomain());
        }

        @Test
        @DisplayName("更新传空串应清空名称（回未命名）")
        void should_clear_name_with_empty_string() {
            String platformId = UniqueKeyFactory.deviceId();
            CascadePlatformDTO created = buildDto(platformId);
            created.setPlatformName("待清空");
            Long id = cascadePlatformManager.add(created);

            CascadePlatformDTO patch = new CascadePlatformDTO();
            patch.setId(id);
            patch.setPlatformName("");
            cascadePlatformManager.update(patch);

            assertEquals("", cascadePlatformManager.getById(id).getPlatformName());
        }

        @Test
        @DisplayName("更新不传名称应保持原值（patch 语义）")
        void should_keep_name_when_not_submitted() {
            String platformId = UniqueKeyFactory.deviceId();
            CascadePlatformDTO created = buildDto(platformId);
            created.setPlatformName("不应被清掉");
            Long id = cascadePlatformManager.add(created);

            CascadePlatformDTO patch = new CascadePlatformDTO();
            patch.setId(id);
            patch.setPlatformIp("192.168.1.101");
            cascadePlatformManager.update(patch);

            CascadePlatformDTO saved = cascadePlatformManager.getById(id);
            assertEquals("不应被清掉", saved.getPlatformName());
            assertEquals("192.168.1.101", saved.getPlatformIp());
        }

        @Test
        @DisplayName("分页按名称模糊匹配")
        void should_filter_by_name_like() {
            String keyword = "模糊" + System.nanoTime();
            String platformId = UniqueKeyFactory.deviceId();
            CascadePlatformDTO dto = buildDto(platformId);
            dto.setPlatformName(keyword + "平台");
            cascadePlatformManager.add(dto);

            CascadePlatformDTO query = new CascadePlatformDTO();
            query.setPlatformName(keyword);
            Page<CascadePlatformDTO> page = cascadePlatformManager.getPage(query, 1, 10);

            assertEquals(1, page.getRecords().size());
            assertEquals(platformId, page.getRecords().get(0).getPlatformId());
        }
    }

    @Nested
    @DisplayName("getPage - 分页查询")
    class Pagination {

        @Test
        @DisplayName("分页应返回已新增的记录")
        void should_include_added_record() {
            cascadePlatformManager.add(buildDto(UniqueKeyFactory.deviceId()));
            Page<CascadePlatformDTO> page = cascadePlatformManager.getPage(null, 1, 10);
            assertTrue(page.getTotal() >= 1);
        }
    }
}
