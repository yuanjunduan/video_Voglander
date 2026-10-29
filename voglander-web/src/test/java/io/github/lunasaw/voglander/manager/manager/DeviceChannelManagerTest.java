package io.github.lunasaw.voglander.manager.manager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.mapper.DeviceChannelMapper;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * DeviceChannelManager 集成测试
 *
 * @author luna
 */
@DisplayName("DeviceChannelManager 集成测试")
class DeviceChannelManagerTest extends BaseTest {

    @Autowired
    private DeviceChannelManager deviceChannelManager;

    @Autowired
    private DeviceManager        deviceManager;

    @Autowired
    private DeviceChannelMapper  deviceChannelMapper;

    private String lastDeviceId;

    @AfterEach
    void cleanup() {
        if (lastDeviceId != null) {
            deviceChannelMapper.delete(
                new LambdaQueryWrapper<DeviceChannelDO>().eq(DeviceChannelDO::getDeviceId, lastDeviceId));
        }
    }

    private String createTestDevice() {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO dto = new DeviceDTO();
        dto.setDeviceId(deviceId);
        dto.setIp("127.0.0.1");
        dto.setPort(5060);
        dto.setType(1);
        dto.setServerIp("127.0.0.1");
        dto.setName("dev-" + deviceId);
        deviceManager.add(dto);
        return deviceId;
    }

    private DeviceChannelDTO buildDto(String deviceId, String channelId) {
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setDeviceId(deviceId);
        dto.setChannelId(channelId);
        dto.setName("ch-" + channelId);
        return dto;
    }

    @Nested
    @DisplayName("batchUpsert UNIQUE 冲突兜底")
    class BatchUpsert {

        @Test
        @DisplayName("batchUpsert 重复 channelId+deviceId 应幂等，不抛异常")
        void should_upsert_on_duplicate() {
            String deviceId = createTestDevice();
            lastDeviceId = deviceId;

            String ch1 = UniqueKeyFactory.channelId();
            String ch2 = UniqueKeyFactory.channelId();

            List<DeviceChannelDTO> batch = List.of(
                buildDto(deviceId, ch1),
                buildDto(deviceId, ch2),
                buildDto(deviceId, ch1) // 重复
            );

            assertDoesNotThrow(() -> deviceChannelManager.batchUpsert(batch));

            long count = deviceChannelMapper.selectCount(
                new LambdaQueryWrapper<DeviceChannelDO>().eq(DeviceChannelDO::getDeviceId, deviceId));
            assertEquals(2, count, "重复项应幂等处理，DB 应只有 2 条");
        }

        @Test
        @DisplayName("batchUpsert 二次调用应更新已存在记录")
        void should_update_existing_on_second_call() {
            String deviceId = createTestDevice();
            lastDeviceId = deviceId;
            String channelId = UniqueKeyFactory.channelId();

            deviceChannelManager.batchUpsert(List.of(buildDto(deviceId, channelId)));
            deviceChannelManager.batchUpsert(List.of(buildDto(deviceId, channelId)));

            long count = deviceChannelMapper.selectCount(
                new LambdaQueryWrapper<DeviceChannelDO>()
                    .eq(DeviceChannelDO::getDeviceId, deviceId)
                    .eq(DeviceChannelDO::getChannelId, channelId));
            assertEquals(1, count, "DB 应只有一条记录");
        }
    }

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("updateStatus 应只更新 status 字段")
        void should_update_only_status() {
            String deviceId = createTestDevice();
            lastDeviceId = deviceId;
            String channelId = UniqueKeyFactory.channelId();
            deviceChannelManager.batchUpsert(List.of(buildDto(deviceId, channelId)));

            deviceChannelManager.updateStatus(deviceId, channelId, 1);

            DeviceChannelDO result = deviceChannelMapper.selectOne(
                new LambdaQueryWrapper<DeviceChannelDO>()
                    .eq(DeviceChannelDO::getDeviceId, deviceId)
                    .eq(DeviceChannelDO::getChannelId, channelId));
            assertNotNull(result);
            assertEquals(1, result.getStatus());
        }
    }

    @Nested
    @DisplayName("updateChannelVideoProbe 编码回填")
    class UpdateVideoProbe {

        private DeviceChannelDTO.ExtendInfo readExtend(String deviceId, String channelId) {
            DeviceChannelDO row = deviceChannelMapper.selectOne(
                new LambdaQueryWrapper<DeviceChannelDO>()
                    .eq(DeviceChannelDO::getDeviceId, deviceId)
                    .eq(DeviceChannelDO::getChannelId, channelId));
            assertNotNull(row, "通道记录应存在");
            return row.getExtend() == null ? null
                : JSON.parseObject(row.getExtend(), DeviceChannelDTO.ExtendInfo.class);
        }

        @Test
        @DisplayName("首次回填应写入编码与分辨率")
        void should_write_codec_and_resolution() {
            String deviceId = createTestDevice();
            lastDeviceId = deviceId;
            String channelId = UniqueKeyFactory.channelId();
            deviceChannelManager.batchUpsert(List.of(buildDto(deviceId, channelId)));

            assertTrue(deviceChannelManager.updateChannelVideoProbe(deviceId, channelId, "H264", 1920, 1080));

            DeviceChannelDTO.ExtendInfo extendInfo = readExtend(deviceId, channelId);
            assertEquals("H264", extendInfo.getVideoCodec());
            assertEquals(1920, extendInfo.getVideoWidth());
            assertEquals(1080, extendInfo.getVideoHeight());
        }

        @Test
        @DisplayName("分辨率未知(0) 不应覆盖已探测到的合法值")
        void should_keep_resolution_when_incoming_invalid() {
            String deviceId = createTestDevice();
            lastDeviceId = deviceId;
            String channelId = UniqueKeyFactory.channelId();
            deviceChannelManager.batchUpsert(List.of(buildDto(deviceId, channelId)));
            deviceChannelManager.updateChannelVideoProbe(deviceId, channelId, "H265", 2560, 1440);

            // 刚建流拿不到分辨率时回 0，此时应视为无变化
            assertFalse(deviceChannelManager.updateChannelVideoProbe(deviceId, channelId, "H265", 0, 0));

            DeviceChannelDTO.ExtendInfo extendInfo = readExtend(deviceId, channelId);
            assertEquals(2560, extendInfo.getVideoWidth());
            assertEquals(1440, extendInfo.getVideoHeight());
        }

        @Test
        @DisplayName("分辨率未知且无旧值时不落 0x0")
        void should_not_persist_zero_resolution() {
            String deviceId = createTestDevice();
            lastDeviceId = deviceId;
            String channelId = UniqueKeyFactory.channelId();
            deviceChannelManager.batchUpsert(List.of(buildDto(deviceId, channelId)));

            assertTrue(deviceChannelManager.updateChannelVideoProbe(deviceId, channelId, "H265", 0, 0));

            DeviceChannelDTO.ExtendInfo extendInfo = readExtend(deviceId, channelId);
            assertEquals("H265", extendInfo.getVideoCodec());
            assertNull(extendInfo.getVideoWidth(), "未知分辨率不应落成 0");
            assertNull(extendInfo.getVideoHeight(), "未知分辨率不应落成 0");
        }
    }

    @Nested
    @DisplayName("updateDeviceChannel 不得冲掉探测结果")
    class UpdateDeviceChannel {

        private DeviceChannelDO findRow(String deviceId, String channelId) {
            return deviceChannelMapper.selectOne(
                new LambdaQueryWrapper<DeviceChannelDO>()
                    .eq(DeviceChannelDO::getDeviceId, deviceId)
                    .eq(DeviceChannelDO::getChannelId, channelId));
        }

        private DeviceChannelDTO.ExtendInfo readExtend(DeviceChannelDO row) {
            assertNotNull(row, "通道记录应存在");
            return row.getExtend() == null ? null
                : JSON.parseObject(row.getExtend(), DeviceChannelDTO.ExtendInfo.class);
        }

        /** 造一条带完整探测结果的通道（发现器写入的地址 + 点播回填的编码/分辨率） */
        private String prepareProbedChannel() {
            String deviceId = createTestDevice();
            lastDeviceId = deviceId;
            String channelId = UniqueKeyFactory.channelId();
            DeviceChannelDTO dto = buildDto(deviceId, channelId);
            DeviceChannelDTO.ExtendInfo extendInfo = new DeviceChannelDTO.ExtendInfo();
            extendInfo.setRtspUrl("rtsp://admin:pwd@192.168.60.165:554/Streaming/Channels/701");
            extendInfo.setVendor("hikvision");
            extendInfo.setProxyProtocol("HIKVISION");
            dto.setExtendInfo(extendInfo);
            deviceChannelManager.batchUpsert(List.of(dto));
            deviceChannelManager.updateChannelVideoProbe(deviceId, channelId, "H265", 2560, 1440);
            return deviceId;
        }

        @Test
        @DisplayName("只改 name 时 RTSP 地址、编码、分辨率应保持")
        void should_keep_extend_when_renaming() {
            String deviceId = prepareProbedChannel();
            DeviceChannelDO row = findRow(deviceId, row0ChannelId(deviceId));

            DeviceChannelDTO update = new DeviceChannelDTO();
            update.setId(row.getId());
            update.setDeviceId(deviceId);
            update.setChannelId(row.getChannelId());
            update.setName("改个名字");
            deviceChannelManager.updateDeviceChannel(update);

            DeviceChannelDO after = findRow(deviceId, row.getChannelId());
            assertEquals("改个名字", after.getName());
            DeviceChannelDTO.ExtendInfo extendInfo = readExtend(after);
            assertNotNull(extendInfo, "extend 不应被写成字符串 \"null\"");
            assertEquals("rtsp://admin:pwd@192.168.60.165:554/Streaming/Channels/701", extendInfo.getRtspUrl());
            assertEquals("hikvision", extendInfo.getVendor());
            assertEquals("H265", extendInfo.getVideoCodec());
            assertEquals(2560, extendInfo.getVideoWidth());
            assertEquals(1440, extendInfo.getVideoHeight());
        }

        @Test
        @DisplayName("显式提交部分 extendInfo 时只覆盖该字段")
        void should_merge_partial_extend_info() {
            String deviceId = prepareProbedChannel();
            DeviceChannelDO row = findRow(deviceId, row0ChannelId(deviceId));

            DeviceChannelDTO.ExtendInfo patch = new DeviceChannelDTO.ExtendInfo();
            patch.setChannelInfo("<Channel>merged</Channel>");
            DeviceChannelDTO update = new DeviceChannelDTO();
            update.setId(row.getId());
            update.setDeviceId(deviceId);
            update.setChannelId(row.getChannelId());
            update.setExtendInfo(patch);
            deviceChannelManager.updateDeviceChannel(update);

            DeviceChannelDTO.ExtendInfo extendInfo = readExtend(findRow(deviceId, row.getChannelId()));
            assertEquals("<Channel>merged</Channel>", extendInfo.getChannelInfo());
            assertEquals("H265", extendInfo.getVideoCodec(), "未提交的编码应保留");
            assertEquals(2560, extendInfo.getVideoWidth());
        }

        @Test
        @DisplayName("无扩展信息新增时 extend 落 null，而不是字符串 \"null\"")
        void should_not_store_null_literal_on_create() {
            String deviceId = createTestDevice();
            lastDeviceId = deviceId;
            String channelId = UniqueKeyFactory.channelId();

            deviceChannelManager.add(buildDto(deviceId, channelId));

            DeviceChannelDO row = findRow(deviceId, channelId);
            assertNotNull(row);
            assertNull(row.getExtend(), "extend 应为 NULL，不能是字符串 \"null\"");
        }

        /** prepardProbedChannel 只造一条通道：取该设备下唯一那条的 channelId */
        private String row0ChannelId(String deviceId) {
            return deviceChannelMapper.selectList(
                new LambdaQueryWrapper<DeviceChannelDO>().eq(DeviceChannelDO::getDeviceId, deviceId))
                .get(0).getChannelId();
        }
    }
}
