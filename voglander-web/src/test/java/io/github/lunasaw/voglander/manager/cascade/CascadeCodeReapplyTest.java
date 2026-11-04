package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.manager.code.CascadeCodeReapplyResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeRuleService;
import io.github.lunasaw.voglander.manager.code.LocalCascadeCodeRuleService;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 编码覆盖动作（N3c）：重算组织编码，同步物化路径 / 发布业务键 / 通道父节点覆盖。
 *
 * <p>这条链路一次写入会动三处结构，任何一处漏掉都会留下「编码换了、结构没换」的目录。
 * 所以测试不只断言 {@code org_code}，而是把 {@code org_path}、ORG 行的 {@code object_key}、
 * CHANNEL 行的 {@code parent_key} 一起钉住。
 */
@DisplayName("组织编码覆盖动作")
class CascadeCodeReapplyTest extends BaseTest {

    private static final String PLATFORM_ID = "34020000002000000001";
    /** 默认配置（3402000000 + 215 + 网络位 0） */
    private static final String LEGACY_HEAD = "34020000002150";
    /** 段配置 37/01/0200/04/216/0 拼出的 head */
    private static final String RULE_HEAD   = "37010200042160";

    @Autowired
    private CascadeCodeRuleService cascadeCodeRuleService;

    @Autowired
    private CascadeOrgManager      cascadeOrgManager;

    @Autowired
    private CascadePublishManager  cascadePublishManager;

    @AfterEach
    void closeGlobalSwitch() {
        toggleGlobalSwitch(false);
    }

    private void toggleGlobalSwitch(boolean enabled) {
        ReflectionTestUtils.setField(cascadeOrgManager, "codeRuleGlobalEnabled", enabled);
        ReflectionTestUtils.setField(cascadeCodeRuleService, "codeRuleEnabled", enabled);
    }

    private CascadeOrgDTO ruleDto() {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setOrgName("org-编码重算-" + UniqueKeyFactory.app());
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

    /**
     * 规则关闭时创建：编码走旧口径，正是升级前存量的样子。
     *
     * <p>这是重算动作唯一有意义的输入——已经在规则下创建的组织本来就符合规则，
     * 用它们测等于测「什么都没发生」。
     */
    private CascadeOrgDTO newLegacyOrg() {
        CascadeOrgDTO dto = ruleDto();
        dto.setPlatformIds(List.of(PLATFORM_ID));
        return cascadeOrgManager.getById(cascadeOrgManager.add(dto));
    }

    private CascadePublishDTO newChannelRow(String objectKey) {
        CascadePublishDTO row = new CascadePublishDTO();
        row.setPlatformId(PLATFORM_ID);
        row.setObjectType(PublishObjectType.CHANNEL.name());
        row.setObjectKey(objectKey);
        return row;
    }

    @Test
    @DisplayName("dryRun 只预览：返回变更明细但不写库")
    void dry_run_should_not_write() {
        CascadeOrgDTO org = newLegacyOrg();
        assertEquals(LEGACY_HEAD, org.getOrgCode().substring(0, 14), "存量组织应是旧口径编码");
        toggleGlobalSwitch(true);

        CascadeCodeReapplyResult result = cascadeCodeRuleService.reapply(org.getId(), true);

        assertTrue(result.isRuleEnabled());
        assertFalse(result.isApplied());
        assertEquals(1, result.getChanges().size());
        assertEquals(org.getOrgCode(), result.getChanges().get(0).getBefore());
        assertEquals(RULE_HEAD, result.getChanges().get(0).getAfter().substring(0, 14));
        assertEquals(org.getOrgCode(), cascadeOrgManager.getById(org.getId()).getOrgCode(),
            "预览不允许写库");
    }

    @Test
    @DisplayName("落库后编码与物化路径同步，发布业务键整体跟到新编码")
    void reapply_should_rewrite_code_path_and_publish_key() {
        CascadeOrgDTO org = newLegacyOrg();
        String oldCode = org.getOrgCode();
        assertEquals(1, cascadePublishManager.listByObject(PublishObjectType.ORG.name(), oldCode).size(),
            "前置条件：组织已发布到一个平台");
        toggleGlobalSwitch(true);

        CascadePublishDTO channel = newChannelRow("dev-x:ch-x");
        channel.setParentKey(oldCode);
        cascadePublishManager.publish(channel);

        CascadeCodeReapplyResult result = cascadeCodeRuleService.reapply(org.getId(), false);

        assertTrue(result.isApplied());
        CascadeOrgDTO after = cascadeOrgManager.getById(org.getId());
        assertNotEquals(oldCode, after.getOrgCode());
        assertEquals(RULE_HEAD, after.getOrgCode().substring(0, 14));
        assertEquals("/" + after.getOrgCode() + "/", after.getOrgPath(), "物化路径必须用新编码重建");
        assertTrue(cascadePublishManager.listByObject(PublishObjectType.ORG.name(), oldCode).isEmpty(),
            "旧业务键不允许残留发布行");
        assertEquals(1,
            cascadePublishManager.listByObject(PublishObjectType.ORG.name(), after.getOrgCode()).size(),
            "发布配置必须整体搬到新业务键，不能重建——重建会丢编码覆盖与启停");
        assertEquals(after.getOrgCode(),
            cascadePublishManager.get(PLATFORM_ID, PublishObjectType.CHANNEL.name(), "dev-x:ch-x")
                .getParentKey(),
            "通道父节点覆盖必须同步，否则上级目录里通道会挂到不存在的父节点");
    }

    @Test
    @DisplayName("已符合规则的编码不再变动，重复点重编码是幂等的")
    void reapply_should_skip_compliant_org() {
        toggleGlobalSwitch(true);
        CascadeOrgDTO org = cascadeOrgManager.getById(cascadeOrgManager.add(ruleDto()));
        assertEquals(RULE_HEAD, org.getOrgCode().substring(0, 14));

        CascadeCodeReapplyResult result = cascadeCodeRuleService.reapply(org.getId(), false);

        assertFalse(result.isApplied());
        assertTrue(result.getChanges().isEmpty());
        assertEquals(org.getOrgCode(), cascadeOrgManager.getById(org.getId()).getOrgCode());
    }

    @Test
    @DisplayName("总闸开着但组织未开规则：不能报成「已经合规」")
    void org_switch_off_should_not_be_reported_as_compliant() {
        CascadeOrgDTO dto = ruleDto();
        dto.setCodeRuleEnabled(0);
        CascadeOrgDTO org = cascadeOrgManager.getById(cascadeOrgManager.add(dto));
        toggleGlobalSwitch(true);

        CascadeCodeReapplyResult result = cascadeCodeRuleService.reapply(org.getId(), false);

        assertFalse(result.isRuleEnabled(), "组织未放行时规则整体视为未启用");
        assertFalse(result.isApplied());
        assertTrue(result.getChanges().isEmpty());
        assertEquals(LocalCascadeCodeRuleService.ORG_DISABLED_NOTE, result.getNote(),
            "用户要打开的是组织开关，不是去查一个没问题的总闸");
    }

    @Test
    @DisplayName("平台内编码冲突时整批拒绝，一行都不写")
    void conflict_should_reject_whole_batch() {
        CascadeOrgDTO org = newLegacyOrg();
        String oldCode = org.getOrgCode();
        toggleGlobalSwitch(true);
        String target = cascadeCodeRuleService.reapply(org.getId(), true).getChanges().get(0).getAfter();

        /* 另一类对象占着目标编码：uk_publish_code 不含 object_type，落库时同样撞键 */
        CascadePublishDTO channel = newChannelRow("dev-y:ch-y");
        channel.setPublishCode(target);
        cascadePublishManager.publish(channel);

        CascadeCodeReapplyResult result = cascadeCodeRuleService.reapply(org.getId(), false);

        assertFalse(result.isApplied());
        assertFalse(result.getConflicts().isEmpty());
        assertEquals(oldCode, cascadeOrgManager.getById(org.getId()).getOrgCode(),
            "有冲突必须整批回滚，不能留下半新半旧的结构");
    }
}
