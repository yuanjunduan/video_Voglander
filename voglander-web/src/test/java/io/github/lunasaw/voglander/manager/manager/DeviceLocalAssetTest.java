package io.github.lunasaw.voglander.manager.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.service.DeviceChannelService;
import io.github.lunasaw.voglander.manager.service.DeviceService;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 级联「本地资产层」在设备与通道上的读写约束。
 *
 * <p>守两条不能退的语义：
 * <ul>
 *   <li>{@code org_id} 的三态——{@code null} 不改、{@code 0} 清空、其余按 id 归组织。
 *       没有"清空"这个显式值，{@code updateById} 跳过 null 列会导致归属永远摘不掉。</li>
 *   <li>目录同步不覆盖用户配置——设备目录每秒都在重传，同步来的 DTO 不带
 *       {@code org_id}/{@code enabled}，批量 upsert 必须跳过这两列。</li>
 * </ul>
 *
 * <p>组织归属不做外键约束，用例直接用裸 id，避免为一次断言造一整棵组织树。
 *
 * @author luna
 */
@DisplayName("设备与通道的本地资产层约束")
class DeviceLocalAssetTest extends BaseTest {

    /** 用例自造的归属值：组织表无外键，任意正数都能表达"归了某个组织" */
    private static final Long TEST_ORG_ID = 998877L;

    @Autowired
    private DeviceManager        deviceManager;

    @Autowired
    private DeviceChannelManager deviceChannelManager;

    @Autowired
    private DeviceService        deviceService;

    @Autowired
    private DeviceChannelService deviceChannelService;

    private String lastDeviceId;

    @AfterEach
    void cleanup() {
        if (lastDeviceId != null) {
            deviceChannelService.remove(
                new LambdaQueryWrapper<DeviceChannelDO>().eq(DeviceChannelDO::getDeviceId, lastDeviceId));
            deviceService.remove(new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getDeviceId, lastDeviceId));
        }
    }

    // ------------------------------------------------------------------ 夹具

    private DeviceDO createDevice() {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO dto = new DeviceDTO();
        dto.setDeviceId(deviceId);
        dto.setIp("127.0.0.1");
        dto.setPort(5060);
        dto.setType(1);
        dto.setServerIp("127.0.0.1");
        dto.setName("dev-" + deviceId);
        Long id = deviceManager.add(dto);
        lastDeviceId = deviceId;
        return deviceService.getById(id);
    }

    private DeviceChannelDO createChannel(String deviceId) {
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setDeviceId(deviceId);
        dto.setChannelId(UniqueKeyFactory.channelId());
        dto.setName("ch-" + dto.getChannelId());
        Long id = deviceChannelManager.add(dto);
        return deviceChannelService.getById(id);
    }

    private DeviceDO reload(DeviceDO device) {
        return deviceService.getById(device.getId());
    }

    private DeviceChannelDO reload(DeviceChannelDO channel) {
        return deviceChannelService.getById(channel.getId());
    }

    // ------------------------------------------------------------------ 设备

    @Nested
    @DisplayName("设备：组织归属")
    class DeviceOrg {

        @Test
        @DisplayName("新增时可归组织，更新传 0 清空归属")
        void should_set_and_clear_org() {
            DeviceDO device = createDevice();

            DeviceDTO assign = new DeviceDTO();
            assign.setOrgId(TEST_ORG_ID);
            deviceManager.updateById(device.getId(), assign);
            assertEquals(TEST_ORG_ID, reload(device).getOrgId());

            DeviceDTO clear = new DeviceDTO();
            clear.setOrgId(0L);
            deviceManager.updateById(device.getId(), clear);
            assertNull(reload(device).getOrgId(), "0 是清空标记，落库必须是 NULL 而不是 0");
        }

        @Test
        @DisplayName("更新不传 orgId 时保留原归属（null 不改）")
        void should_keep_org_when_absent() {
            DeviceDO device = createDevice();
            DeviceDTO assign = new DeviceDTO();
            assign.setOrgId(TEST_ORG_ID);
            deviceManager.updateById(device.getId(), assign);

            DeviceDTO rename = new DeviceDTO();
            rename.setName("改名不动归属");
            deviceManager.updateById(device.getId(), rename);

            assertEquals(TEST_ORG_ID, reload(device).getOrgId());
            assertEquals("改名不动归属", reload(device).getName());
        }

        @Test
        @DisplayName("按组织统计与列举设备")
        void should_count_and_list_by_org() {
            DeviceDO device = createDevice();
            DeviceDTO assign = new DeviceDTO();
            assign.setOrgId(TEST_ORG_ID);
            deviceManager.updateById(device.getId(), assign);

            assertEquals(1, deviceManager.countByOrgId(TEST_ORG_ID));
            assertEquals(List.of(device.getDeviceId()), deviceManager.listDeviceIdsByOrgId(TEST_ORG_ID));
        }
    }

    @Nested
    @DisplayName("设备：本地启停")
    class DeviceEnabled {

        @Test
        @DisplayName("新增默认启用；停用不影响在线状态")
        void should_default_enabled_and_not_touch_status() {
            DeviceDO device = createDevice();
            assertEquals(1, reload(device).getEnabled(), "库默认值是 1");
            /* 新建设备是否已上线取决于上报流程，这里只要求停用不改变既有的在线状态 */
            Integer statusBefore = reload(device).getStatus();

            DeviceDTO disable = new DeviceDTO();
            disable.setEnabled(0);
            deviceManager.updateById(device.getId(), disable);

            assertEquals(0, reload(device).getEnabled(), "停用是本地语义");
            assertEquals(statusBefore, reload(device).getStatus(), "停用不能改动在线状态字段");
        }

        @Test
        @DisplayName("非法启停值直接拒绝，不让脏值进库")
        void should_reject_illegal_enabled() {
            DeviceDO device = createDevice();
            DeviceDTO illegal = new DeviceDTO();
            illegal.setEnabled(2);

            assertThrows(ServiceException.class, () -> deviceManager.updateById(device.getId(), illegal));
        }
    }

    // ------------------------------------------------------------------ 通道

    @Nested
    @DisplayName("通道：组织归属与本地启停")
    class ChannelAsset {

        @Test
        @DisplayName("通道可独立归组织，传 0 清空改为跟随设备")
        void should_set_and_clear_org() {
            DeviceDO device = createDevice();
            DeviceChannelDO channel = createChannel(device.getDeviceId());

            DeviceChannelDTO assign = new DeviceChannelDTO();
            assign.setOrgId(TEST_ORG_ID);
            deviceChannelManager.updateById(channel.getId(), assign);
            assertEquals(TEST_ORG_ID, reload(channel).getOrgId());

            DeviceChannelDTO clear = new DeviceChannelDTO();
            clear.setOrgId(0L);
            deviceChannelManager.updateById(channel.getId(), clear);
            assertNull(reload(channel).getOrgId());
        }

        @Test
        @DisplayName("通道停用只改 enabled，不碰在线状态")
        void should_disable_without_touching_status() {
            DeviceDO device = createDevice();
            DeviceChannelDO channel = createChannel(device.getDeviceId());
            assertEquals(1, reload(channel).getEnabled());

            DeviceChannelDTO disable = new DeviceChannelDTO();
            disable.setEnabled(0);
            deviceChannelManager.updateById(channel.getId(), disable);

            assertEquals(0, reload(channel).getEnabled());
            assertEquals(channel.getStatus(), reload(channel).getStatus());
        }

        @Test
        @DisplayName("目录同步不覆盖用户配置的组织与启停")
        void catalogSync_should_not_overwrite_user_config() {
            DeviceDO device = createDevice();
            DeviceChannelDO channel = createChannel(device.getDeviceId());

            DeviceChannelDTO config = new DeviceChannelDTO();
            config.setOrgId(TEST_ORG_ID);
            config.setEnabled(0);
            deviceChannelManager.updateById(channel.getId(), config);

            /* 目录重传：名字变了，但不带 orgId/enabled——这是设备上报的真实形态 */
            DeviceChannelDTO synced = new DeviceChannelDTO();
            synced.setDeviceId(device.getDeviceId());
            synced.setChannelId(channel.getChannelId());
            synced.setName("目录里的新名字");
            deviceChannelManager.batchUpsert(List.of(synced));

            DeviceChannelDO after = reload(channel);
            assertNotNull(after);
            assertEquals("目录里的新名字", after.getName(), "目录字段要跟上");
            assertEquals(TEST_ORG_ID, after.getOrgId(), "组织归属是人工配置，同步不能冲掉");
            assertEquals(0, after.getEnabled(), "本地停用是人工配置，同步不能冲掉");
        }

        @Test
        @DisplayName("按组织统计通道：显式归属与跟随设备归属分别计数")
        void should_count_direct_and_inherited() {
            DeviceDO device = createDevice();
            DeviceChannelDO direct = createChannel(device.getDeviceId());
            DeviceChannelDO inherited = createChannel(device.getDeviceId());

            DeviceChannelDTO orgOfChannel = new DeviceChannelDTO();
            orgOfChannel.setOrgId(TEST_ORG_ID);
            deviceChannelManager.updateById(direct.getId(), orgOfChannel);

            assertEquals(1, deviceChannelManager.countByOrgId(TEST_ORG_ID), "只数显式归属");
            assertEquals(1, deviceChannelManager.countUnownedByDeviceIds(List.of(device.getDeviceId())),
                "另一条未归属，跟随设备");
            assertEquals(2, deviceChannelManager.countByOrgId(TEST_ORG_ID)
                + deviceChannelManager.countUnownedByDeviceIds(List.of(device.getDeviceId())),
                "两种归属合起来才是该组织下的通道占用数");
            assertNull(reload(inherited).getOrgId());
        }
    }
}
