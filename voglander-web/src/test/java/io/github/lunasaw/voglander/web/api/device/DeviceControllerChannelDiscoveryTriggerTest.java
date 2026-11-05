package io.github.lunasaw.voglander.web.api.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;

import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.service.device.DeviceChannelDiscoveryService;
import io.github.lunasaw.voglander.service.device.dto.ChannelSyncResultDTO;
import io.github.lunasaw.voglander.web.api.device.assembler.DeviceWebAssembler;
import io.github.lunasaw.voglander.web.api.device.req.DeviceCreateReq;

/**
 * 新增设备后的通道发现触发语义。
 *
 * <p>
 * 只覆盖三条边界：拉流类设备要触发、国标设备不许触发（对国标发管理面请求没有意义）、
 * 发现本身失败不能把新增结果带崩。
 * </p>
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceControllerChannelDiscoveryTriggerTest {

    private static final String DEVICE_ID = "192.168.60.165";

    private static final long   ORG_ID    = 9L;

    @InjectMocks
    DeviceController                     controller;
    @Mock
    DeviceManager                        deviceManager;
    @Mock
    DeviceWebAssembler                   deviceWebAssembler;
    @Mock
    CascadeOrgManager                    cascadeOrgManager;
    @Mock
    DeviceChannelDiscoveryService        deviceChannelDiscoveryService;
    @Mock
    TaskExecutor                         channelDiscoveryExecutor;

    @BeforeEach
    void setup() {
        // 测试里让执行器同步跑，避免真异步导致 verify 时机不确定
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(channelDiscoveryExecutor).execute(any());
    }

    @Test
    void insert_rtspDevice_triggersChannelDiscovery() {
        stubCreate(4);
        ChannelSyncResultDTO syncResult = new ChannelSyncResultDTO();
        syncResult.setPersisted(true);
        syncResult.setDiscovered(6);
        when(deviceChannelDiscoveryService.sync(DEVICE_ID)).thenReturn(syncResult);

        AjaxResult resp = controller.insert(newRequest(4));

        assertEquals(0, resp.getCode());
        verify(deviceChannelDiscoveryService).sync(DEVICE_ID);
    }

    @Test
    void insert_onvifDevice_triggersChannelDiscovery() {
        stubCreate(3);
        ChannelSyncResultDTO syncResult = new ChannelSyncResultDTO();
        syncResult.setPersisted(true);
        when(deviceChannelDiscoveryService.sync(DEVICE_ID)).thenReturn(syncResult);

        controller.insert(newRequest(3));

        verify(deviceChannelDiscoveryService).sync(DEVICE_ID);
    }

    @Test
    void insert_gb28181Device_skipsChannelDiscovery() {
        // 国标设备的通道由 Catalog 应答维护，主动发现只面向拉流类设备
        stubCreate(1);

        controller.insert(newRequest(1));

        verify(deviceChannelDiscoveryService, never()).sync(anyString());
        verify(channelDiscoveryExecutor, never()).execute(any());
    }

    @Test
    void insert_discoveryFailure_doesNotBreakCreate() {
        stubCreate(4);
        when(deviceChannelDiscoveryService.sync(DEVICE_ID)).thenThrow(new ServiceException("设备不存在"));

        AjaxResult resp = controller.insert(newRequest(4));

        // 发现失败只记日志：设备本身已经建好，不能因此把新增判成失败
        assertEquals(0, resp.getCode());
    }

    private void stubCreate(int type) {
        when(cascadeOrgManager.getById(ORG_ID)).thenReturn(new CascadeOrgDTO());
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(DEVICE_ID);
        device.setType(type);
        when(deviceWebAssembler.toDeviceDTO(any(DeviceCreateReq.class))).thenReturn(device);
        when(deviceManager.createDevice(any(DeviceDTO.class))).thenReturn(1L);
    }

    private DeviceCreateReq newRequest(int type) {
        DeviceCreateReq req = new DeviceCreateReq();
        req.setDeviceId(DEVICE_ID);
        req.setType(type);
        req.setOrgId(ORG_ID);
        return req;
    }
}
