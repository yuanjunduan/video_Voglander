package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder;
import io.github.lunasaw.voglander.manager.code.CascadeCodeRuleService;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 通道编码派生（N3b）：组织段 + 类型 131 + 全库号池，且同一通道跨平台复用同一个号。
 *
 * <p>「复用」是这里最要紧的一条：通道推给两个上级时如果各发一次号，上级侧对账、
 * 故障回溯、跨平台核查全部对不上，而且这种错在单平台上完全看不出来。
 */
@DisplayName("通道编码规则")
class CascadeChannelCodeRuleTest extends BaseTest {

    private static final String PLATFORM_ID = "34020000002000000001";

    @Autowired
    private CascadeCodeRuleService cascadeCodeRuleService;

    @Autowired
    private CascadeOrgManager       cascadeOrgManager;

    @Autowired
    private CascadePublishManager   cascadePublishManager;

    @Autowired
    private DeviceManager           deviceManager;

    @Autowired
    private DeviceChannelManager    deviceChannelManager;

    @AfterEach
    void closeGlobalSwitch() {
        toggleGlobalSwitch(false);
    }

    /** 两个 Bean 各自持有一份总闸，必须同时翻转，否则「管理器不放行、服务放行」会得出两套结论 */
    private void toggleGlobalSwitch(boolean enabled) {
        ReflectionTestUtils.setField(cascadeOrgManager, "codeRuleGlobalEnabled", enabled);
        ReflectionTestUtils.setField(cascadeCodeRuleService, "codeRuleEnabled", enabled);
    }

    private Long newOrg(String county, int codeRuleEnabled, Integer startSeq) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setOrgName("org-通道编码-" + UniqueKeyFactory.app());
        dto.setCodeRuleEnabled(codeRuleEnabled);
        dto.setCodeProvince("37");
        dto.setCodeCity("01");
        dto.setCodeCounty(county);
        dto.setCodeIndustry("04");
        dto.setCodeType("216");
        dto.setCodeNetwork("0");
        dto.setCodeStartSeq(startSeq);
        return cascadeOrgManager.add(dto);
    }

    private record ChannelRef(Long pk, String deviceId, String channelId) {
    }

    private ChannelRef newChannel(Long orgId) {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(deviceId);
        device.setIp("127.0.0.1");
        device.setPort(5060);
        device.setType(1);
        device.setServerIp("127.0.0.1");
        device.setName("dev-通道编码");
        deviceManager.add(device);

        String channelId = UniqueKeyFactory.channelId();
        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(deviceId);
        channel.setChannelId(channelId);
        channel.setName("ch-通道编码");
        channel.setOrgId(orgId);
        return new ChannelRef(deviceChannelManager.add(channel), deviceId, channelId);
    }

    @Test
    @DisplayName("组织启用规则后，通道编码 = 组织段 + 类型131 + 全库号池")
    void should_compose_channel_code_from_org_segments() {
        toggleGlobalSwitch(true);
        ChannelRef channel = newChannel(newOrg("0200", 1, 200));

        String code = cascadeCodeRuleService.channelCode(channel.pk());

        assertNotNull(code);
        assertEquals(20, code.length());
        assertEquals("37010200041310", code.substring(0, 14), "省2+市2+县4+行业2+类型131+网络0");
        assertTrue(CascadeCodeBuilder.seqOf(code, "37010200041310") >= 200,
            "组织的号段起点对通道同样生效");
    }

    @Test
    @DisplayName("总闸关闭时通道编码保持本地 channel_id")
    void should_fall_back_when_global_switch_off() {
        ChannelRef channel = newChannel(newOrg("0201", 1, 1));

        assertEquals(channel.channelId(), cascadeCodeRuleService.channelCode(channel.pk()));
    }

    @Test
    @DisplayName("组织未启用规则时通道跟着组织用本地 channel_id")
    void should_fall_back_when_org_rule_off() {
        toggleGlobalSwitch(true);
        ChannelRef channel = newChannel(newOrg("0202", 0, null));

        assertEquals(channel.channelId(), cascadeCodeRuleService.channelCode(channel.pk()),
            "同一组织下不能一半国标一半本地");
    }

    @Test
    @DisplayName("已有发布编码的通道直接复用，不重新发号")
    void should_reuse_existing_published_code() {
        toggleGlobalSwitch(true);
        ChannelRef channel = newChannel(newOrg("0203", 1, 1));

        String published = "37010200041310000999";
        CascadePublishDTO row = new CascadePublishDTO();
        row.setPlatformId(PLATFORM_ID);
        row.setObjectType(PublishObjectType.CHANNEL.name());
        row.setObjectKey(PublishKeys.channel(channel.deviceId(), channel.channelId()));
        row.setPublishCode(published);
        cascadePublishManager.publish(row);

        assertEquals(published, cascadeCodeRuleService.channelCode(channel.pk()));
    }
}
