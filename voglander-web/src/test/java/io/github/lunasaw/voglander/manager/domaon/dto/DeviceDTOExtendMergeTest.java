package io.github.lunasaw.voglander.manager.domaon.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备扩展字段合并语义：{@code mergeExtend} 是显式白名单，新增字段必须同步登记，
 * 否则更新设备请求未携带该字段时会被 null 覆盖清空（historically rtspUrl 就踩过）。
 *
 * @author luna
 */
@DisplayName("DeviceDTO.mergeExtend — 辅码流地址合并")
class DeviceDTOExtendMergeTest {

    @Test
    @DisplayName("patch 带 rtspSubUrl → 覆盖库中现值")
    void patchWithSubUrl_overrides() {
        DeviceDTO.ExtendInfo base = new DeviceDTO.ExtendInfo();
        base.setRtspUrl("rtsp://cam/Streaming/Channels/101");
        base.setRtspSubUrl("rtsp://cam/Streaming/Channels/102");

        DeviceDTO.ExtendInfo patch = new DeviceDTO.ExtendInfo();
        patch.setRtspSubUrl("rtsp://cam/Streaming/Channels/202");

        DeviceDTO.ExtendInfo merged = DeviceDTO.mergeExtend(base, patch);

        assertEquals("rtsp://cam/Streaming/Channels/202", merged.getRtspSubUrl());
        // 未提交的字段保持库中现值
        assertEquals("rtsp://cam/Streaming/Channels/101", merged.getRtspUrl());
    }

    @Test
    @DisplayName("patch 不带 rtspSubUrl → 保留库中现值，不被清空")
    void patchWithoutSubUrl_keepsExisting() {
        DeviceDTO.ExtendInfo base = new DeviceDTO.ExtendInfo();
        base.setRtspSubUrl("rtsp://cam/Streaming/Channels/102");

        DeviceDTO.ExtendInfo patch = new DeviceDTO.ExtendInfo();
        patch.setRtspUrl("rtsp://cam/Streaming/Channels/101");

        DeviceDTO.ExtendInfo merged = DeviceDTO.mergeExtend(base, patch);

        assertEquals("rtsp://cam/Streaming/Channels/102", merged.getRtspSubUrl());
    }

    @Test
    @DisplayName("库中无 extend → patch 只有辅流地址也能落库")
    void emptyBase_acceptsSubUrl() {
        DeviceDTO.ExtendInfo patch = new DeviceDTO.ExtendInfo();
        patch.setRtspSubUrl("rtsp://cam/sub");

        DeviceDTO.ExtendInfo merged = DeviceDTO.mergeExtend(null, patch);

        assertEquals("rtsp://cam/sub", merged.getRtspSubUrl());
        assertNull(merged.getRtspUrl());
    }
}
