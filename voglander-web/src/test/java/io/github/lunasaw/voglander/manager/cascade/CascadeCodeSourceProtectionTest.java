package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.CodeSource;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBatchFillResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeReapplyResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeResolver;
import io.github.lunasaw.voglander.manager.code.CascadeCodeRuleService;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.manager.service.CascadePublishService;
import io.github.lunasaw.voglander.repository.entity.CascadePublishDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 2.5 人工值保护：人工填写的 {@code publish_code} 要标 MANUAL，且不被任何自动链路改写。
 *
 * <p><b>三条自动链路一次钉住</b>：组织重算（{@code reapply} 只换本地编码，共享码不动）、
 * 发布配置覆盖（写完之后紧跟着的那次 {@code resolveAll}）、批量补齐（{@code batchFill} 只补空列）。
 * 三条共用同一个 {@code code_source} 判定，任何一条退回「有码也重算」，其余断言就会跟着失效。
 *
 * <p><b>历史未标记行按非 AUTO 即人工处理</b>：升级前配过覆盖的行来源是一片 NULL，
 * 它们必须和手工码一样被保护——否则第一次批量补齐就会把已在上级生效的码集体换掉。
 *
 * <p><b>「原样再保存」不翻来源</b>：前端把生效码回显后原样提交是最常见的操作，
 * 那一次什么都没改；把 AUTO 翻成 MANUAL 会让这个码从此不再跟随规则，用户并没有这个意图。
 */
@DisplayName("级联：上报编码人工值保护")
class CascadeCodeSourceProtectionTest extends BaseTest {

    /** 每个用例独占一套平台编码，避免与库里既有发布行互串 */
    private final String platformId = UniqueKeyFactory.deviceId();

    @Autowired
    private CascadeOrgManager      cascadeOrgManager;

    @Autowired
    private CascadePublishManager  cascadePublishManager;

    @Autowired
    private CascadePublishService  cascadePublishService;

    @Autowired
    private CascadeCodeResolver    cascadeCodeResolver;

    @Autowired
    private CascadeCodeRuleService cascadeCodeRuleService;

    @Autowired
    private CascadeChannelManager  cascadeChannelManager;

    @Autowired
    private DeviceManager          deviceManager;

    @Autowired
    private DeviceChannelManager   deviceChannelManager;

    @AfterEach
    void closeGlobalSwitch() {
        toggleGlobalSwitch(false);
    }

    /** 三个 Bean 各持有一份总闸，必须同时翻转，否则「管理器不放行、解析器放行」会得出两套结论 */
    private void toggleGlobalSwitch(boolean enabled) {
        ReflectionTestUtils.setField(cascadeOrgManager, "codeRuleGlobalEnabled", enabled);
        ReflectionTestUtils.setField(cascadeCodeResolver, "codeRuleGlobalEnabled", enabled);
        ReflectionTestUtils.setField(cascadeCodeRuleService, "codeRuleEnabled", enabled);
    }

    // ---------------------------------------------------------------- 写入侧标 MANUAL

    @Test
    @DisplayName("人工填码即标 MANUAL，改码沿用人工标记且生成时间留空")
    void manual_code_should_be_marked_on_publish() {
        String orgCode = addOrg(new CascadeOrgDTO(), List.of(platformId)).getOrgCode();
        String first = "34020000002160000501";
        publish(PublishObjectType.ORG.name(), orgCode, first);

        CascadePublishDTO created = rowOf(orgCode);
        assertEquals(first, created.getPublishCode());
        assertEquals(CodeSource.MANUAL, created.getCodeSource(), "人工填的码必须标 MANUAL");
        assertNull(created.getCodeGeneratedTime(), "人工码没有「生成」这回事，时间必须留空");

        String second = "34020000002160000502";
        publish(PublishObjectType.ORG.name(), orgCode, second);

        CascadePublishDTO changed = rowOf(orgCode);
        assertEquals(second, changed.getPublishCode());
        assertEquals(CodeSource.MANUAL, changed.getCodeSource(), "改码仍然是人工值");
        assertNull(changed.getCodeGeneratedTime());
    }

    @Test
    @DisplayName("回显原值再存不翻来源：AUTO 仍是 AUTO；改成新码才转人工并清空生成时间")
    void resubmitting_frozen_code_should_keep_auto() {
        toggleGlobalSwitch(true);
        String orgCode = addOrg(ruleDto(), List.of(platformId)).getOrgCode();

        CascadePublishDTO frozen = rowOf(orgCode);
        assertEquals(CodeSource.AUTO, frozen.getCodeSource(), "建行即固化，来源应为 AUTO");
        String autoCode = frozen.getPublishCode();
        assertNotNull(autoCode, "规则开启时建行即已固化");
        assertNotNull(frozen.getCodeGeneratedTime());

        publish(PublishObjectType.ORG.name(), orgCode, autoCode);

        CascadePublishDTO same = rowOf(orgCode);
        assertEquals(CodeSource.AUTO, same.getCodeSource(), "原样再保存一次不能把规则生成的码变成人工值");
        assertNotNull(same.getCodeGeneratedTime());

        String manual = "34020000002160000503";
        publish(PublishObjectType.ORG.name(), orgCode, manual);

        CascadePublishDTO overwritten = rowOf(orgCode);
        assertEquals(CodeSource.MANUAL, overwritten.getCodeSource(), "值变了就是人工改写");
        assertNull(overwritten.getCodeGeneratedTime(), "旧的 AUTO 生成时间要清空，否则看起来像刚生成过");
    }

    // ---------------------------------------------------------------- 批量补齐

    @Test
    @DisplayName("批量补齐只补空列：人工码受保护并单独计数")
    void batch_fill_should_skip_manual_codes() {
        String manualOrg = addOrg(new CascadeOrgDTO(), List.of(platformId)).getOrgCode();
        String blankOrg = addOrg(new CascadeOrgDTO(), List.of(platformId)).getOrgCode();
        /* 受保护行也要落在候选集里（规则开着但它已有码），否则「没被重算」只是因为规则没开 */
        enableRule(manualOrg);
        enableRule(blankOrg);
        String manual = "34020000002160000504";
        publish(PublishObjectType.ORG.name(), manualOrg, manual);
        toggleGlobalSwitch(true);

        CascadeCodeBatchFillResult result = cascadeCodeResolver.batchFill(platformId,
            PublishObjectType.ORG.name(), false);

        assertEquals(1, result.getChanges().size(), "只有空列那一行该被补");
        assertEquals(blankOrg, result.getChanges().get(0).getObjectKey());
        assertEquals(1, result.getManualProtected(), "人工码要计入受保护行数，否则现场以为漏了一行");
        assertTrue(result.getNote().contains("人工码"), result.getNote());
        assertEquals(manual, rowOf(manualOrg).getPublishCode(), "人工码不许被规则重算");
        assertEquals(CodeSource.MANUAL, rowOf(manualOrg).getCodeSource());
        assertEquals(CodeSource.AUTO, rowOf(blankOrg).getCodeSource(), "补出来的码是规则生成值");
    }

    @Test
    @DisplayName("历史行（有码、来源未标记）按人工处理：批量补齐一行都不动它")
    void legacy_row_without_source_should_be_treated_as_manual() {
        String legacyOrg = addOrg(new CascadeOrgDTO(), List.of(platformId)).getOrgCode();
        String blankOrg = addOrg(new CascadeOrgDTO(), List.of(platformId)).getOrgCode();
        /* 同上：旧行规则开着、来源为空，才真正验证「非 AUTO 即人工」 */
        enableRule(legacyOrg);
        enableRule(blankOrg);
        String legacyCode = "34020000002160000506";
        publish(PublishObjectType.ORG.name(), legacyOrg, legacyCode);
        clearCodeSource(legacyOrg);
        toggleGlobalSwitch(true);

        CascadeCodeBatchFillResult result = cascadeCodeResolver.batchFill(platformId,
            PublishObjectType.ORG.name(), false);

        assertEquals(1, result.getManualProtected(), "来源为空的旧行必须按人工对待，否则升级就会集体换码");
        assertEquals(1, result.getChanges().size());
        assertEquals(legacyCode, rowOf(legacyOrg).getPublishCode());
        assertNull(rowOf(legacyOrg).getCodeSource(), "补码不能顺手给历史行补来源标记");
    }

    @Test
    @DisplayName("固化只写空列：行上已有码时返回 OCCUPIED，规则码不覆盖")
    void freeze_should_not_overwrite_existing_code() {
        String orgCode = addOrg(new CascadeOrgDTO(), List.of(platformId)).getOrgCode();
        String manual = "34020000002160000508";
        publish(PublishObjectType.ORG.name(), orgCode, manual);

        CascadePublishManager.FreezeOutcome outcome = cascadePublishManager.freezeCode(
            rowOf(orgCode).getId(), "34020000002160000509", CodeSource.AUTO, LocalDateTime.now());

        assertEquals(CascadePublishManager.FreezeOutcome.OCCUPIED, outcome,
            "读与写之间该行已有码：必须报「已有码」而不是写入成功");
        assertEquals(manual, rowOf(orgCode).getPublishCode(), "已有码的行不能被规则码覆盖");
        assertEquals(CodeSource.MANUAL, rowOf(orgCode).getCodeSource(), "来源也不能被翻成 AUTO");
    }

    // ---------------------------------------------------------------- 组织重算

    @Test
    @DisplayName("组织重算只换本地编码，人工上报码与来源原样保留")
    void org_reapply_should_keep_manual_publish_code() {
        CascadeOrgDTO org = addOrg(ruleDto(), List.of(platformId));
        String oldCode = org.getOrgCode();
        String manual = "34020000002160000507";
        publish(PublishObjectType.ORG.name(), oldCode, manual);
        toggleGlobalSwitch(true);

        CascadeCodeReapplyResult result = cascadeCodeRuleService.reapply(org.getId(), false);

        assertTrue(result.isApplied(), "前置条件：组织编码确实被重算: " + result.getNote());
        String newCode = cascadeOrgManager.getById(org.getId()).getOrgCode();
        assertNotEquals(oldCode, newCode);
        CascadePublishDTO row = cascadePublishManager.get(platformId, PublishObjectType.ORG.name(), newCode);
        assertNotNull(row, "发布行应随业务键搬到新编码");
        assertEquals(manual, row.getPublishCode(), "重算组织编码不是改共享码的理由");
        assertEquals(CodeSource.MANUAL, row.getCodeSource());
    }

    // ---------------------------------------------------------------- 通道新增 / 编辑

    @Test
    @DisplayName("通道新增：显式填的通道编码标 MANUAL，不被回填链路改写")
    void channel_add_with_explicit_code_should_be_manual() {
        ChannelRef ref = newChannel();
        String explicit = "34020000001310000901";
        Long id = cascadeChannelManager.add(channelDto(ref, explicit));

        CascadePublishDTO row = channelRow(ref);
        assertEquals(id, row.getId());
        assertEquals(explicit, row.getPublishCode());
        assertEquals(CodeSource.MANUAL, row.getCodeSource(), "新增时人工填的码必须标 MANUAL");
    }

    @Test
    @DisplayName("通道编辑：本地回落值不算人工，改成新码才转 MANUAL")
    void channel_update_from_local_fallback_should_become_manual() {
        ChannelRef ref = newChannel();
        cascadeChannelManager.add(channelDto(ref, null));

        CascadePublishDTO created = channelRow(ref);
        assertEquals(ref.channelId(), created.getPublishCode(), "规则未启用时补的是本地通道码");
        assertNull(created.getCodeSource(), "回落值既不是规则生成也不是人工填写，不标来源");

        CascadeChannelDTO patch = new CascadeChannelDTO();
        patch.setId(created.getId());
        patch.setCascadeChannelId("34020000001310000904");
        cascadeChannelManager.update(patch);

        CascadePublishDTO updated = channelRow(ref);
        assertEquals("34020000001310000904", updated.getPublishCode());
        assertEquals(CodeSource.MANUAL, updated.getCodeSource(), "改码就是人工值");
    }

    // ------------------------------------------------------------------ 夹具

    /** 规则启用、段位固定的组织；总闸关着创建时编码走旧口径，正是升级前存量的样子 */
    private CascadeOrgDTO ruleDto() {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setCodeRuleEnabled(1);
        dto.setCodeProvince("37");
        dto.setCodeCity("01");
        dto.setCodeCounty("0200");
        dto.setCodeIndustry("04");
        dto.setCodeType("216");
        dto.setCodeNetwork("0");
        dto.setCodeStartSeq(1);
        return dto;
    }

    private CascadeOrgDTO addOrg(CascadeOrgDTO dto, List<String> platformIds) {
        dto.setOrgName("org-人工值保护-" + UniqueKeyFactory.app());
        dto.setPlatformIds(platformIds);
        return cascadeOrgManager.getById(cascadeOrgManager.add(dto));
    }

    /** 只给这一个组织开规则：批量补齐里其余行应当被跳过 */
    private void enableRule(String orgCode) {
        CascadeOrgDTO patch = new CascadeOrgDTO();
        patch.setId(cascadeOrgManager.getByCode(orgCode).getId());
        patch.setCodeRuleEnabled(1);
        patch.setCodeProvince("37");
        patch.setCodeCity("01");
        patch.setCodeCounty("0200");
        patch.setCodeIndustry("04");
        patch.setCodeType("216");
        patch.setCodeNetwork("0");
        cascadeOrgManager.update(patch);
    }

    private void publish(String objectType, String objectKey, String publishCode) {
        CascadePublishDTO dto = new CascadePublishDTO();
        dto.setPlatformId(platformId);
        dto.setObjectType(objectType);
        dto.setObjectKey(objectKey);
        dto.setPublishCode(publishCode);
        cascadePublishManager.publish(dto);
    }

    private CascadePublishDTO rowOf(String orgCode) {
        return cascadePublishManager.get(platformId, PublishObjectType.ORG.name(), orgCode);
    }

    /** 模拟 1.10.2 升级上来的行：列上有码，来源是一片 NULL */
    private void clearCodeSource(String orgCode) {
        cascadePublishService.lambdaUpdate()
            .eq(CascadePublishDO::getId, rowOf(orgCode).getId())
            .set(CascadePublishDO::getCodeSource, null)
            .update();
    }

    private record ChannelRef(String deviceId, String channelId) {
    }

    private ChannelRef newChannel() {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(deviceId);
        device.setIp("127.0.0.1");
        device.setPort(5060);
        device.setType(1);
        device.setServerIp("127.0.0.1");
        device.setName("dev-人工值保护");
        deviceManager.add(device);

        String channelId = UniqueKeyFactory.channelId();
        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(deviceId);
        channel.setChannelId(channelId);
        channel.setName("ch-人工值保护");
        deviceChannelManager.add(channel);
        return new ChannelRef(deviceId, channelId);
    }

    private CascadeChannelDTO channelDto(ChannelRef ref, String explicitCode) {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setPlatformId(platformId);
        dto.setLocalDeviceId(ref.deviceId());
        dto.setLocalChannelId(ref.channelId());
        dto.setCascadeChannelId(explicitCode);
        return dto;
    }

    private CascadePublishDTO channelRow(ChannelRef ref) {
        return cascadePublishManager.get(platformId, PublishObjectType.CHANNEL.name(),
            PublishKeys.channel(ref.deviceId(), ref.channelId()));
    }
}
