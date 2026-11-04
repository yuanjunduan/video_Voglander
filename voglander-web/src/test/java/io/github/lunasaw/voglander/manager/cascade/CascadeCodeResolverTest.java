package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.lunasaw.voglander.manager.code.CascadeCodeResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager.CodeReapplyPlan;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager.OrgCodeChange;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 上报编码解析（2.2）的回归用例：<b>生成 + 固化</b>与<b>纯读口径</b>的分工必须钉住。
 *
 * <p><b>为什么这五条要放一个类</b>：它们共用同一条前提——「没有码时按规则给一个并钉住，
 * 有码时原样返回，没落库位时回本地码」。任何一条退回「每次都现算」，其余断言都会跟着失效：
 * 号池一变，同一路通道下次就换号，而报文没有改码语义。
 *
 * <p><b>两道总闸都用反射打开</b>：{@code gateway.cascade.code-rule.enabled} 是单例 Bean 的字段，
 * 改配置就得多起一套 Spring 上下文；而解析器与组织管理器各读一份，必须同时打开，
 * 否则「组织规则生效」这一路根本走不到。
 *
 * <p><b>2.4 的入口改造钉在这里</b>：组织发布建行时就已经固化（{@link #should_generate_and_freeze_on_first_resolve}），
 * 批量补齐预览也必须占号，否则同批多行会拿到同一个码。
 */
@DisplayName("级联：上报编码解析与固化")
class CascadeCodeResolverTest extends BaseTest {

    /** 组织规则 37/01/0200/04/216/0 拼出的前 14 位 */
    private static final String RULE_HEAD = "37010200042160";

    @Autowired
    private CascadeCodeResolver   cascadeCodeResolver;

    @Autowired
    private CascadeOrgManager     cascadeOrgManager;

    @Autowired
    private CascadePublishManager cascadePublishManager;

    /** 每个用例独占一套平台编码，避免与库里既有发布行互串 */
    private final String          platformA = UniqueKeyFactory.deviceId();
    private final String          platformB = UniqueKeyFactory.deviceId();

    @AfterEach
    void closeGlobalSwitch() {
        toggleGlobalSwitch(false);
    }

    private void toggleGlobalSwitch(boolean enabled) {
        ReflectionTestUtils.setField(cascadeCodeResolver, "codeRuleGlobalEnabled", enabled);
        ReflectionTestUtils.setField(cascadeOrgManager, "codeRuleGlobalEnabled", enabled);
    }

    // ---------------------------------------------------------------- 生成并固化

    @Test
    @DisplayName("组织发布建行即按规则固化，再解析返回同一个码")
    void should_generate_and_freeze_on_first_resolve() {
        toggleGlobalSwitch(true);
        String orgCode = addOrg(ruleDto(), List.of(platformA));

        /* 2.4 起 add 里就调了 resolveAll：不许等到第一次读取才发号——那会让首条目录报文与后续报文
           在两个时机各算一次，只要号池中间多进一行，同一节点就会带着两个 DeviceID 上报 */
        CascadePublishDTO frozen = cascadePublishManager.get(platformA, PublishObjectType.ORG.name(), orgCode);
        assertEquals(CodeSource.AUTO, frozen.getCodeSource(), "建行即固化，来源标记必须已落库");
        String code = frozen.getPublishCode();
        assertNotNull(code, "建行即固化：publish_code 不应等到读取时才有值");
        assertEquals(20, code.length(), "国标编码恒为 20 位: " + code);
        assertTrue(code.startsWith(RULE_HEAD), "前 14 位应来自组织规则: " + code);
        assertFalse(code.equals(orgCode), "号池里已有它自己的本地码，生成必须换号");
        assertNotNull(frozen.getCodeGeneratedTime(), "生成时间要落库，便于排查「这个码什么时候算出来的」");

        assertEquals(code, cascadeCodeResolver.resolve(platformA, PublishObjectType.ORG.name(), orgCode),
            "已固化的码不自动变更：改码对上级等于删旧建新，历史录像与订阅全部错位");
    }

    @Test
    @DisplayName("已有值（人工填写或历史数据）原样返回，不改来源标记")
    void should_keep_existing_publish_code() {
        /* 总闸关着建行：不生成，publish_code 留空；随后人工填一个码 */
        String orgCode = addOrg(ruleDto(), List.of(platformA));
        String manual = "34020000002160000777";
        publish(platformA, PublishObjectType.ORG.name(), orgCode, manual, 1);
        toggleGlobalSwitch(true);

        assertEquals(manual, cascadeCodeResolver.resolve(platformA, PublishObjectType.ORG.name(), orgCode),
            "读取路径不能覆盖已上报的码");

        CascadePublishDTO row = cascadePublishManager.get(platformA, PublishObjectType.ORG.name(), orgCode);
        assertEquals(manual, row.getPublishCode());
        assertEquals(CodeSource.MANUAL, row.getCodeSource(),
            "人工填写的码要标 MANUAL；只读路径不得把它改标成 AUTO");
    }

    @Test
    @DisplayName("resolveAll 只给本次勾选的平台发号，不动其它平台")
    void resolve_all_should_only_touch_selected_platforms() {
        toggleGlobalSwitch(true);
        String orgCode = addOrg(ruleDto(), List.of(platformA));

        assertNotNull(cascadePublishManager.get(platformA, PublishObjectType.ORG.name(), orgCode)
            .getPublishCode(), "勾选的平台要在建行时就固化");
        assertNull(cascadePublishManager.get(platformB, PublishObjectType.ORG.name(), orgCode),
            "没勾选的平台不该被顺手建出发布行");
    }

    @Test
    @DisplayName("批量补齐：预览逐行占号（同批不重复），落库后 publish_code 全部非空")
    void batch_fill_should_reserve_codes_during_preview() {
        String orgA = addOrg(new CascadeOrgDTO(), List.of(platformA));
        String orgB = addOrg(new CascadeOrgDTO(), List.of(platformA));
        enableRule(orgA);
        enableRule(orgB);
        toggleGlobalSwitch(true);

        CascadeCodeBatchFillResult preview = cascadeCodeResolver.batchFill(platformA,
            PublishObjectType.ORG.name(), true);

        assertTrue(preview.isRuleEnabled());
        assertFalse(preview.isApplied(), "预览不许落库");
        assertEquals(2, preview.getChanges().size(), "两行都没有码，都应出现在补码清单里");
        assertNotEquals(preview.getChanges().get(0).getAfter(), preview.getChanges().get(1).getAfter(),
            "预览不占号会给两行返回同一个码，用户照着确认反而制造冲突");
        assertNull(cascadePublishManager.get(platformA, PublishObjectType.ORG.name(), orgA).getPublishCode(),
            "预览不写库");

        CascadeCodeBatchFillResult applied = cascadeCodeResolver.batchFill(platformA,
            PublishObjectType.ORG.name(), false);

        assertTrue(applied.isApplied());
        assertEquals(2, applied.getChanges().size());
        assertTrue(applied.getConflicts().isEmpty(), "号段充足时不应有失败行: " + applied.getConflicts());
        assertEquals(CodeSource.AUTO,
            cascadePublishManager.get(platformA, PublishObjectType.ORG.name(), orgA).getCodeSource(),
            "补出来的码同样是规则生成值，要留 AUTO 标记");
    }

    @Test
    @DisplayName("批量补齐：总闸关闭时一行也不补，并回可读结论")
    void batch_fill_should_skip_when_global_switch_off() {
        String orgCode = addOrg(new CascadeOrgDTO(), List.of(platformA));
        enableRule(orgCode);

        CascadeCodeBatchFillResult result = cascadeCodeResolver.batchFill(platformA,
            PublishObjectType.ORG.name(), false);

        assertFalse(result.isRuleEnabled());
        assertFalse(result.isApplied());
        assertTrue(result.getChanges().isEmpty(), "总闸关着不允许悄悄固化");
        assertNotNull(result.getNote(), "给用户看到一句话结论，而不是空响应");
    }

    // ---------------------------------------------------------------- 零行为变化

    @Test
    @DisplayName("总闸关闭：规则配了也回本地码，且不写库")
    void should_return_local_code_when_global_switch_off() {
        String orgCode = addOrg(ruleDto(), List.of(platformA));

        assertEquals(orgCode, cascadeCodeResolver.resolve(platformA, PublishObjectType.ORG.name(), orgCode),
            "关着开关不允许改口径，升级后必须逐字节一致");

        CascadePublishDTO row = cascadePublishManager.get(platformA, PublishObjectType.ORG.name(), orgCode);
        assertNull(row.getPublishCode(), "开关关着不允许悄悄固化");
        assertNull(row.getCodeSource());
    }

    @Test
    @DisplayName("发布行不存在：回本地码，不生成（没有落库位就会次次换号）")
    void should_return_local_code_when_publish_row_missing() {
        toggleGlobalSwitch(true);
        String orgCode = addOrg(ruleDto(), List.of(platformA));

        assertEquals(orgCode, cascadeCodeResolver.resolve(platformB, PublishObjectType.ORG.name(), orgCode),
            "该对象没发布到 platformB，算出来的码无处固化");
        assertNull(cascadePublishManager.get(platformB, PublishObjectType.ORG.name(), orgCode),
            "生成编码不应该顺手建发布行");
    }

    // ---------------------------------------------------------------- 平台号池

    @Test
    @DisplayName("号池含「未配码按本地码上报」与停用行，跨平台互不影响")
    void effective_code_pool_should_cover_local_codes_and_disabled_rows() {
        String orgCode = addOrg(new CascadeOrgDTO(), List.of(platformA));
        String deviceCode = RULE_HEAD + "000123";
        String deviceKey = UniqueKeyFactory.deviceId();
        String channelKey = UniqueKeyFactory.channelId();
        String fullChannelKey = PublishKeys.channel(deviceKey, channelKey);
        publish(platformA, PublishObjectType.DEVICE.name(), deviceKey, deviceCode, 0);
        publish(platformA, PublishObjectType.CHANNEL.name(), fullChannelKey, null, 1);

        List<String> pool = cascadeCodeResolver.effectiveCodePool(platformA);

        assertTrue(pool.contains(orgCode), "未配码的对象占的就是本地码，只数 publish_code 列会漏");
        assertTrue(pool.contains(deviceCode), "停用行的节点可能还在上级，新号占上去就变成两个同 DeviceID");
        assertTrue(pool.contains(channelKey), "通道未配码时报的是通道段，不是 deviceId:channelId");
        assertTrue(cascadeCodeResolver.effectiveCodePool(platformB).isEmpty(),
            "不同平台是独立编码空间，不该互相占号");
    }

    // ---------------------------------------------------------------- 平台内查重

    @Test
    @DisplayName("组织重算查重：对方 publish_code 为空也按生效码判冲突")
    void publish_conflicts_should_match_effective_code_of_unconfigured_rows() {
        toggleGlobalSwitch(true);
        String orgCode = addOrg(new CascadeOrgDTO(), List.of(platformA));
        enableRule(orgCode);

        CodeReapplyPlan plan = cascadeOrgManager.planCodeReapply(orgId(orgCode));
        assertFalse(plan.hasConflict(), "此时平台内还没有同号节点: " + plan.conflicts());
        String after = plan.changes().stream().filter(OrgCodeChange::changed).map(OrgCodeChange::after)
            .findFirst().orElseThrow(() -> new IllegalStateException("规则与旧编码不同，应有变更"));

        /* 关键：这一行的 publish_code 是 NULL，上报出去的却是 object_key 本身 */
        publish(platformA, PublishObjectType.DEVICE.name(), after, null, 1);

        CodeReapplyPlan conflicted = cascadeOrgManager.planCodeReapply(orgId(orgCode));
        assertTrue(conflicted.hasConflict(),
            "只查 publish_code 列会放过这条真冲突，落到库里就是唯一键异常: " + conflicted.conflicts());
        assertTrue(conflicted.conflicts().get(0).contains(after), conflicted.conflicts().get(0));
        assertTrue(conflicted.conflicts().get(0).contains("DEVICE"), "冲突提示要指出被谁占用");
    }

    // ------------------------------------------------------------------ 夹具

    /** 规则启用、段位固定的组织；编码由 {@code add} 按同一规则算出 */
    private CascadeOrgDTO ruleDto() {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setCodeRuleEnabled(1);
        dto.setCodeProvince("37");
        dto.setCodeCity("01");
        dto.setCodeCounty("0200");
        dto.setCodeIndustry("04");
        dto.setCodeType("216");
        dto.setCodeNetwork("0");
        return dto;
    }

    /** @return 落库后的组织编码 */
    private String addOrg(CascadeOrgDTO dto, List<String> platformIds) {
        dto.setOrgName("org-编码解析-" + UniqueKeyFactory.app());
        dto.setPlatformIds(platformIds);
        return cascadeOrgManager.getById(cascadeOrgManager.add(dto)).getOrgCode();
    }

    /** 先按旧口径建组织，再打开规则并改段位：库里留着一个不符合规则的编码，重算才会真的产生变更 */
    private void enableRule(String orgCode) {
        CascadeOrgDTO patch = new CascadeOrgDTO();
        patch.setId(orgId(orgCode));
        patch.setCodeRuleEnabled(1);
        patch.setCodeProvince("37");
        patch.setCodeCity("01");
        patch.setCodeCounty("0200");
        patch.setCodeIndustry("04");
        patch.setCodeType("216");
        patch.setCodeNetwork("0");
        cascadeOrgManager.update(patch);
    }

    private Long orgId(String orgCode) {
        return cascadeOrgManager.getByCode(orgCode).getId();
    }

    private void publish(String platformId, String objectType, String objectKey, String publishCode,
        int enabled) {
        CascadePublishDTO dto = new CascadePublishDTO();
        dto.setPlatformId(platformId);
        dto.setObjectType(objectType);
        dto.setObjectKey(objectKey);
        dto.setPublishCode(publishCode);
        dto.setEnabled(enabled);
        cascadePublishManager.publish(dto);
    }
}
