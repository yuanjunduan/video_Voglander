package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.code.CascadeCodeReapplyResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeRuleService;
import io.github.lunasaw.voglander.manager.code.LocalCascadeCodeRuleService;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 编码规则服务（R6：接口 + 未启用的默认实现）的行为约束。
 *
 * <p>这里断言的不是「将来怎么生成编码」，而是<b>开关关着时什么都不会变</b>：
 * 三个 code 方法必须原样回本地编码，{@code reapply} 必须一行都不写。
 * 这条基线一旦被破坏，升级后上级目录里的编码会集体跳变，而报文是单向的、没有回滚通道。
 */
@DisplayName("CascadeCodeRuleService 集成测试")
class CascadeCodeRuleServiceTest extends BaseTest {

    @Autowired
    private CascadeCodeRuleService cascadeCodeRuleService;

    @Autowired
    private CascadeOrgManager      cascadeOrgManager;

    @Autowired
    private DeviceManager          deviceManager;

    @Autowired
    private DeviceChannelManager   deviceChannelManager;

    private Long newOrg() {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setOrgName("org-编码规则-" + UniqueKeyFactory.app());
        return cascadeOrgManager.add(dto);
    }

    private String newDevice() {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(deviceId);
        device.setIp("127.0.0.1");
        device.setPort(5060);
        device.setType(1);
        device.setServerIp("127.0.0.1");
        device.setName("dev-编码规则");
        deviceManager.add(device);
        return deviceId;
    }

    private Long newChannel(String deviceId) {
        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(deviceId);
        channel.setChannelId(UniqueKeyFactory.channelId());
        channel.setName("ch-编码规则");
        return deviceChannelManager.add(channel);
    }

    @Nested
    @DisplayName("规则开关")
    class Switches {

        @Test
        @DisplayName("默认实现必须处于未启用状态")
        void should_be_disabled_by_default() {
            assertFalse(cascadeCodeRuleService.enabled());
        }
    }

    @Nested
    @DisplayName("取编码 - 未启用时回本地编码")
    class CodeLookup {

        @Test
        @DisplayName("orgCode 返回本地 org_code")
        void org_code_should_fall_back_to_local() {
            Long orgId = newOrg();

            assertEquals(cascadeOrgManager.getById(orgId).getOrgCode(), cascadeCodeRuleService.orgCode(orgId));
        }

        @Test
        @DisplayName("deviceCode 返回本地 device_id")
        void device_code_should_fall_back_to_local() {
            String deviceId = newDevice();
            DeviceDO local = deviceManager.getByDeviceId(deviceId);

            assertEquals(deviceId, cascadeCodeRuleService.deviceCode(local.getId()));
        }

        @Test
        @DisplayName("channelCode 返回本地 channel_id")
        void channel_code_should_fall_back_to_local() {
            String deviceId = newDevice();
            Long channelPk = newChannel(deviceId);

            assertEquals(deviceChannelManager.getDeviceChannelDTOById(channelPk).getChannelId(),
                cascadeCodeRuleService.channelCode(channelPk));
        }

        @Test
        @DisplayName("对象不存在或入参为空时返回 null，不抛异常")
        void should_return_null_for_missing_object() {
            assertNull(cascadeCodeRuleService.orgCode(-1L));
            assertNull(cascadeCodeRuleService.deviceCode(-1L));
            assertNull(cascadeCodeRuleService.channelCode(-1L));
            assertNull(cascadeCodeRuleService.orgCode(null));
            assertNull(cascadeCodeRuleService.deviceCode(null));
            assertNull(cascadeCodeRuleService.channelCode(null));
        }
    }

    @Nested
    @DisplayName("reapply - 覆盖动作空转")
    class Reapply {

        @Test
        @DisplayName("未启用时不落库，返回空结果与说明")
        void should_not_write_when_disabled() {
            Long orgId = newOrg();
            String before = cascadeOrgManager.getById(orgId).getOrgCode();

            CascadeCodeReapplyResult result = cascadeCodeRuleService.reapply(orgId, true);

            assertNotNull(result);
            assertFalse(result.isRuleEnabled());
            assertTrue(result.isDryRun(), "dryRun 必须原样透传，调用方据此决定是否展示预览");
            assertFalse(result.isApplied());
            assertTrue(result.getChanges().isEmpty());
            assertTrue(result.getConflicts().isEmpty());
            assertEquals(LocalCascadeCodeRuleService.DISABLED_NOTE, result.getNote());
            assertEquals(before, cascadeOrgManager.getById(orgId).getOrgCode(), "编码规则未启用时不允许改写组织编码");
        }

        @Test
        @DisplayName("传错组织 id 直接报错，不返回假成功")
        void should_reject_missing_org() {
            assertThrows(IllegalArgumentException.class, () -> cascadeCodeRuleService.reapply(-1L, true));
            assertThrows(IllegalArgumentException.class, () -> cascadeCodeRuleService.reapply(null, true));
        }
    }
}
