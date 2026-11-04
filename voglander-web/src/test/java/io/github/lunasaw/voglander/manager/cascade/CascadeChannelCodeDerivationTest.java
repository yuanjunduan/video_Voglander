package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.lunasaw.voglander.manager.code.CascadeCodeResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;

/**
 * 通道上报编码的生成时序：<b>先建发布行 → 再 resolve → 后回填</b>。
 *
 * <p>这里刻意用 mock 而不用集成测试：集成测试里「规则没开时码 = 本地通道码」与改动前的结果
 * 逐字节相同，看不出走的是哪条链路。把 {@link CascadeCodeResolver} 的返回值换掉、并断言
 * {@code publish_code} 先留空再回填，才能钉住时序——顺序反了（先生成再建行）码就没有落库位。
 *
 * <p>{@code fillCodeIfBlank} 本身是「列上空才写」的 SQL 条件，这里只验证调用参数：
 * 规则启用时 resolver 已写过 AUTO 码，那次 UPDATE 在库上是 0 行，不构成覆盖。
 */
@DisplayName("通道上报编码生成时序")
class CascadeChannelCodeDerivationTest {

    private static final String PLATFORM_ID      = "34020000002000000001";
    private static final String LOCAL_DEVICE_ID  = "dev-1";
    private static final String LOCAL_CHANNEL_ID = "ch-1";
    private static final Long   PUBLISH_ID       = 1L;
    private static final String OBJECT_KEY       = LOCAL_DEVICE_ID + ":" + LOCAL_CHANNEL_ID;

    private CascadeChannelManager cascadeChannelManager;
    private CascadePublishManager cascadePublishManager;
    private CascadeCodeResolver   cascadeCodeResolver;

    @BeforeEach
    void setUp() {
        cascadeChannelManager = new CascadeChannelManager();
        cascadePublishManager = mock(CascadePublishManager.class);
        cascadeCodeResolver = mock(CascadeCodeResolver.class);

        ReflectionTestUtils.setField(cascadeChannelManager, "cascadePublishManager", cascadePublishManager);
        ReflectionTestUtils.setField(cascadeChannelManager, "cascadeCodeResolver", cascadeCodeResolver);
        /* 归属校验会回查本地通道：这里查不到（null）就放行，不干扰编码时序本身 */
        ReflectionTestUtils.setField(cascadeChannelManager, "deviceChannelManager",
            mock(DeviceChannelManager.class));

        when(cascadePublishManager.publish(any())).thenReturn(PUBLISH_ID);
        when(cascadeCodeResolver.resolve(anyString(), anyString(), anyString())).thenReturn(LOCAL_CHANNEL_ID);
    }

    private CascadeChannelDTO newDto() {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setPlatformId(PLATFORM_ID);
        dto.setLocalDeviceId(LOCAL_DEVICE_ID);
        dto.setLocalChannelId(LOCAL_CHANNEL_ID);
        return dto;
    }

    @Test
    @DisplayName("编码留空时先建空行，再按 resolve 结果回填")
    void should_publish_blank_then_fill_resolved_code() {
        String generated = "34020000001310000099";
        when(cascadeCodeResolver.resolve(PLATFORM_ID, "CHANNEL", OBJECT_KEY)).thenReturn(generated);

        cascadeChannelManager.add(newDto());

        assertNull(publishedRow().getPublishCode(), "生成必须发生在建行之后：建行时 publish_code 应留空");
        verify(cascadeCodeResolver).resolve(PLATFORM_ID, "CHANNEL", OBJECT_KEY);
        verify(cascadePublishManager).fillCodeIfBlank(PUBLISH_ID, generated, null, null);
    }

    @Test
    @DisplayName("规则未启用时 resolve 回本地码，也要落到 publish_code（上级反查只认这一列）")
    void should_fill_local_code_when_rule_disabled() {
        when(cascadeCodeResolver.resolve(PLATFORM_ID, "CHANNEL", OBJECT_KEY)).thenReturn(LOCAL_CHANNEL_ID);

        cascadeChannelManager.add(newDto());

        verify(cascadePublishManager).fillCodeIfBlank(PUBLISH_ID, LOCAL_CHANNEL_ID, null, null);
    }

    @Test
    @DisplayName("显式传入编码时建行即落库，resolve 不会把它改掉")
    void should_keep_explicit_code() {
        String explicit = "34020000001310000001";
        CascadeChannelDTO dto = newDto();
        dto.setCascadeChannelId(explicit);
        when(cascadeCodeResolver.resolve(PLATFORM_ID, "CHANNEL", OBJECT_KEY)).thenReturn(explicit);

        cascadeChannelManager.add(dto);

        assertEquals(explicit, publishedRow().getPublishCode());
        /* 回填仍在同一条链路上调用，但库上 publish_code 已非空，UPDATE 影响 0 行 */
        verify(cascadePublishManager).fillCodeIfBlank(PUBLISH_ID, explicit, null, null);
    }

    @Test
    @DisplayName("发布行主键必用于回填：不能按业务键二次查询后再写")
    void should_fill_by_publish_id() {
        cascadeChannelManager.add(newDto());

        verify(cascadePublishManager).fillCodeIfBlank(eq(PUBLISH_ID), anyString(), isNull(), isNull());
    }

    private CascadePublishDTO publishedRow() {
        ArgumentCaptor<CascadePublishDTO> captor = ArgumentCaptor.forClass(CascadePublishDTO.class);
        verify(cascadePublishManager).publish(captor.capture());
        return captor.getValue();
    }
}
