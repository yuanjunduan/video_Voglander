package io.github.lunasaw.voglander.manager.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;

import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.manager.domaon.dto.sys.SysConfigDTO;
import io.github.lunasaw.voglander.manager.manager.SysConfigManager;
import io.github.lunasaw.voglander.manager.service.SysConfigService;
import io.github.lunasaw.voglander.repository.entity.SysConfigDO;

/**
 * {@link SysConfigManager} 单元测试。
 *
 * <p>重点覆盖三层回落（覆盖值 → yml → 清单默认值）与写入口的整批拒绝语义：
 * 这两处出错的表现都是"页面显示的值和程序实际用的不一致"，现场极难排查。
 *
 * @author luna
 */
@DisplayName("SysConfigManager 单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SysConfigManagerTest {

    private static final String BATCH_SIZE     = SysConfigCatalog.CASCADE_CATALOG_BATCH_SIZE;
    private static final String SERIAL         = SysConfigCatalog.CASCADE_CATALOG_SERIAL;
    private static final String OK_TIMEOUT_MS  = SysConfigCatalog.CASCADE_CATALOG_OK_TIMEOUT_MS;
    private static final String SCAN_INTERVAL  = SysConfigCatalog.CASCADE_CATALOG_SCAN_INTERVAL_MS;

    @Mock
    private SysConfigService    sysConfigService;

    @Mock
    private Environment         environment;

    @InjectMocks
    private SysConfigManager    sysConfigManager;

    @BeforeEach
    void setUp() {
        when(sysConfigService.list()).thenReturn(List.of());
        sysConfigManager.refresh();
    }

    private SysConfigDO row(String key, String value) {
        SysConfigDO row = new SysConfigDO();
        row.setId(1L);
        row.setConfigKey(key);
        row.setConfigValue(value);
        return row;
    }

    private SysConfigDTO item(String key, String value) {
        SysConfigDTO dto = new SysConfigDTO();
        dto.setConfigKey(key);
        dto.setConfigValue(value);
        return dto;
    }

    private SysConfigDTO find(List<SysConfigDTO> items, String key) {
        return items.stream().filter(i -> key.equals(i.getConfigKey())).findFirst()
            .orElseThrow(() -> new AssertionError("缺少配置项: " + key));
    }

    @Test
    @DisplayName("无覆盖值时页面显示 yml 里显式配置的值，而不是清单默认值")
    void list_uses_yml_when_no_override() {
        when(environment.getProperty(BATCH_SIZE)).thenReturn("100");
        sysConfigManager.refresh();

        SysConfigDTO batch = find(sysConfigManager.listByGroup(SysConfigCatalog.GROUP_CASCADE_CATALOG), BATCH_SIZE);

        assertEquals("100", batch.getConfigValue());
        assertEquals("50", batch.getDefaultValue());
        assertFalse(batch.getOverridden());
        assertEquals(1, batch.getEditable());
    }

    @Test
    @DisplayName("有覆盖值时覆盖值优先，并标记 overridden")
    void list_uses_override_first() {
        SysConfigDO row = row(BATCH_SIZE, "120");
        row.setUpdateTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        when(sysConfigService.list()).thenReturn(List.of(row));
        sysConfigManager.refresh();

        SysConfigDTO batch = find(sysConfigManager.listByGroup(SysConfigCatalog.GROUP_CASCADE_CATALOG), BATCH_SIZE);

        assertEquals("120", batch.getConfigValue());
        assertTrue(batch.getOverridden());
        /* 页面"覆盖时间"列：有覆盖值时必须能给出落库时间 */
        assertNotNull(batch.getUpdateTime());
    }

    @Test
    @DisplayName("按分组过滤：只返回该组的配置项")
    void list_filters_by_group() {
        assertTrue(sysConfigManager.listByGroup(SysConfigCatalog.GROUP_CASCADE_CATALOG).stream()
            .allMatch(i -> SysConfigCatalog.GROUP_CASCADE_CATALOG.equals(i.getConfigGroup())));
        assertTrue(sysConfigManager.listByGroup("not-exists").isEmpty());
    }

    @Test
    @DisplayName("覆盖值非法时读取回落，不抛异常")
    void getters_fall_back_on_invalid_override() {
        when(sysConfigService.list()).thenReturn(List.of(row(OK_TIMEOUT_MS, "abc"), row(SERIAL, "maybe")));
        sysConfigManager.refresh();

        assertEquals(2000L, sysConfigManager.getLong(OK_TIMEOUT_MS, 2000L));
        assertTrue(sysConfigManager.getBoolean(SERIAL, true));
    }

    @Test
    @DisplayName("布尔覆盖值接受 0/1 与 true/false")
    void boolean_accepts_0_1() {
        when(sysConfigService.list()).thenReturn(List.of(row(SERIAL, "0")));
        sysConfigManager.refresh();
        assertFalse(sysConfigManager.getBoolean(SERIAL, true));

        when(sysConfigService.list()).thenReturn(List.of(row(SERIAL, "FALSE")));
        sysConfigManager.refresh();
        assertFalse(sysConfigManager.getBoolean(SERIAL, true));
    }

    @Test
    @DisplayName("需重启的配置项拒绝在线修改")
    void save_rejects_non_editable() {
        ServiceException e = assertThrows(ServiceException.class,
            () -> sysConfigManager.saveBatch(List.of(item(SCAN_INTERVAL, "2000"))));

        assertNotNull(e.getMessage());
        verify(sysConfigService, never()).save(any());
    }

    @Test
    @DisplayName("未知配置键拒绝写入")
    void save_rejects_unknown_key() {
        assertThrows(ServiceException.class,
            () -> sysConfigManager.saveBatch(List.of(item("gateway.cascade.catalog.nope", "1"))));
        verify(sysConfigService, never()).save(any());
    }

    @Test
    @DisplayName("数值越界拒绝写入")
    void save_rejects_out_of_range() {
        assertThrows(ServiceException.class,
            () -> sysConfigManager.saveBatch(List.of(item(BATCH_SIZE, "1000"))));
        verify(sysConfigService, never()).save(any());
    }

    @Test
    @DisplayName("整批拒绝：一项非法时另一项也不落库")
    void save_rejects_whole_batch() {
        assertThrows(ServiceException.class, () -> sysConfigManager.saveBatch(
            List.of(item(BATCH_SIZE, "80"), item(SCAN_INTERVAL, "3000"))));
        verify(sysConfigService, never()).save(any());
    }

    @Test
    @DisplayName("传空串表示删除覆盖值")
    void save_empty_removes_override() {
        SysConfigDO existing = row(BATCH_SIZE, "80");
        existing.setId(9L);
        when(sysConfigService.getOne(any(), anyBoolean())).thenReturn(existing);

        int changed = sysConfigManager.saveBatch(List.of(item(BATCH_SIZE, "")));

        assertEquals(1, changed);
        verify(sysConfigService).removeById(9L);
        verify(sysConfigService, never()).save(any());
    }

    @Test
    @DisplayName("无覆盖值时新增，并写入归一化后的值与元数据")
    void save_inserts_normalized_value() {
        when(sysConfigService.getOne(any(), anyBoolean())).thenReturn(null);

        int changed = sysConfigManager.saveBatch(List.of(item(BATCH_SIZE, " 80 ")));

        assertEquals(1, changed);
        ArgumentCaptor<SysConfigDO> captor = ArgumentCaptor.forClass(SysConfigDO.class);
        verify(sysConfigService).save(captor.capture());
        assertEquals(BATCH_SIZE, captor.getValue().getConfigKey());
        assertEquals("80", captor.getValue().getConfigValue());
        assertEquals(SysConfigCatalog.GROUP_CASCADE_CATALOG, captor.getValue().getConfigGroup());
        assertEquals(SysConfigCatalog.TYPE_INT, captor.getValue().getValueType());
    }

    @Test
    @DisplayName("值未变化时不产生写操作")
    void save_skips_unchanged_value() {
        when(sysConfigService.getOne(any(), anyBoolean())).thenReturn(row(BATCH_SIZE, "80"));

        assertEquals(0, sysConfigManager.saveBatch(List.of(item(BATCH_SIZE, "80"))));
        verify(sysConfigService, never()).updateById(any());
        verify(sysConfigService, never()).save(any());
    }
}
