package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.CodeSource;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.manager.code.CascadeCodeResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 平台编码规则（2.3）的回归用例：<b>接线、必填校验、优先链</b>三者必须同时成立。
 *
 * <p><b>为什么放一个类</b>：只接线不校验，能配出「平台规则启用但只填了省」的半截配置；
 * 只校验不接线，界面上填的段反而落不了库；优先链写反，前 14 位会一半来自平台、一半来自组织，
 * 而报文里看不出是哪里错。这三条任一条失效，另外两条的断言都会跟着失去意义。
 *
 * <p><b>两把开关默认关闭</b>：{@code code_rule_enabled} 与 {@code publish_ancestors} 建表即 0。
 * 本类第一条用例就是钉死这一点——升级替存量平台换码，等于让上级把老对象全部当成新设备。
 *
 * @author luna
 */
@DisplayName("级联：平台编码规则（接线、校验与优先链）")
class CascadePlatformCodeRuleTest extends BaseTest {

    @Autowired
    private CascadePlatformManager cascadePlatformManager;

    @Autowired
    private CascadeOrgManager      cascadeOrgManager;

    @Autowired
    private CascadePublishManager  cascadePublishManager;

    @Autowired
    private CascadeCodeResolver    cascadeCodeResolver;

    @AfterEach
    void closeGlobalSwitch() {
        ReflectionTestUtils.setField(cascadeCodeResolver, "codeRuleGlobalEnabled", false);
        ReflectionTestUtils.setField(cascadeOrgManager, "codeRuleGlobalEnabled", false);
    }

    private void toggleGlobalSwitch(boolean enabled) {
        ReflectionTestUtils.setField(cascadeCodeResolver, "codeRuleGlobalEnabled", enabled);
        ReflectionTestUtils.setField(cascadeOrgManager, "codeRuleGlobalEnabled", enabled);
    }

    // ---------------------------------------------------------------- 默认关闭

    @Test
    @DisplayName("两个开关默认 0：不传规则的平台仍按本地码上报，且不写发布行")
    void default_switches_should_be_off_and_keep_local_code() {
        toggleGlobalSwitch(true);
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, null, null, null, null, null, null, null);

        CascadePlatformDTO saved = cascadePlatformManager.getById(id);
        assertEquals(0, saved.getCodeRuleEnabled(), "升级不能替存量平台打开规则：上级会把老对象当成新设备");
        assertEquals(0, saved.getPublishAncestors(), "自动补推祖先会一次放出整条链，只能由用户显式选择");
        assertNull(saved.getCodeProvince(), "规则未配置时段位就是空列，不能填默认值冒充用户配置");

        String orgCode = addOrg(List.of(platformId));
        CascadePublishDTO row = row(platformId, orgCode);
        assertNull(row.getPublishCode(), "规则没开不许悄悄固化");
        assertEquals(orgCode, cascadeCodeResolver.resolve(platformId, PublishObjectType.ORG.name(), orgCode),
            "关着开关必须逐字节回本地码");
    }

    // ---------------------------------------------------------------- 优先链

    @Test
    @DisplayName("优先链：前 14 位来自平台段，类型位仍由对象类型决定")
    void platform_segments_should_drive_head() {
        toggleGlobalSwitch(true);
        String platformId = UniqueKeyFactory.deviceId();
        /* 省34/市02/县0000/行业00/网络0 + 类型215 + 序号 000100 */
        addPlatform(platformId, 1, "34", "02", "0000", "00", "0", 100);

        String orgCode = addOrg(List.of(platformId));

        CascadePublishDTO row = row(platformId, orgCode);
        assertNotNull(row.getPublishCode(), "平台规则启用且总闸打开时，建行即固化");
        assertEquals(CodeSource.AUTO, row.getCodeSource());
        assertEquals("34020000002150" + "000100", row.getPublishCode(),
            "前 14 位应为 平台五段 + 类型位(215) + 网络位(0)，序号从平台起始序号起");
    }

    @Test
    @DisplayName("优先链：平台段覆盖组织段，类型位仍取组织配置的 216")
    void platform_segments_should_win_over_org_rule() {
        toggleGlobalSwitch(true);
        String platformId = UniqueKeyFactory.deviceId();
        addPlatform(platformId, 1, "44", "03", "0000", "00", "0", null);

        /* 组织自己的规则是 37/01/0200/04/216/0：平台段必须整体压过它，只有类型位不受平台影响 */
        CascadeOrgDTO org = ruleOrg();
        org.setPlatformIds(List.of(platformId));
        String orgCode = cascadeOrgManager.getById(cascadeOrgManager.add(org)).getOrgCode();

        String code = row(platformId, orgCode).getPublishCode();
        assertNotNull(code);
        assertEquals(20, code.length(), "国标编码恒为 20 位: " + code);
        assertTrue(code.startsWith("4403000000"), "前 10 位应全部来自平台段: " + code);
        assertEquals("216", code.substring(10, 13), "类型位只读：仍取对象类型，不被平台段覆盖");
        assertEquals("0", code.substring(13, 14), "网络位取平台段的 0");
    }

    @Test
    @DisplayName("同一组织推两平台、规则不同 → 两套不同前缀的 20 位码")
    void two_platforms_should_get_their_own_prefix() {
        toggleGlobalSwitch(true);
        String provinceA = UniqueKeyFactory.deviceId();
        String provinceB = UniqueKeyFactory.deviceId();
        addPlatform(provinceA, 1, "34", "02", "0000", "00", "0", null);
        addPlatform(provinceB, 1, "44", "03", "0000", "00", "0", null);

        String orgCode = addOrg(List.of(provinceA, provinceB));

        String codeA = row(provinceA, orgCode).getPublishCode();
        String codeB = row(provinceB, orgCode).getPublishCode();
        assertNotNull(codeA);
        assertNotNull(codeB);
        assertNotEquals(codeA, codeB, "两个上级是独立编码空间，同一个对象可以有两套码");
        assertTrue(codeA.startsWith("3402000000"), codeA);
        assertTrue(codeB.startsWith("4403000000"), codeB);
    }

    // ---------------------------------------------------------------- 校验

    @Test
    @DisplayName("启用规则但缺网络位 → 拒绝保存，并指明缺的是哪一段")
    void missing_segment_should_be_rejected_when_enabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> addPlatform(UniqueKeyFactory.deviceId(), 1, "34", "02", "0000", "00", null, null));

        assertTrue(ex.getMessage().contains("前 14 位必填"), ex.getMessage());
        assertTrue(ex.getMessage().contains("网络标识"), ex.getMessage());
    }

    @Test
    @DisplayName("段位宽不对 → 即使开关关闭也拒绝保存")
    void segment_width_should_be_validated() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> addPlatform(UniqueKeyFactory.deviceId(), 0, "340", "02", "0000", "00", "0", null));

        assertTrue(ex.getMessage().contains("省编码必须为 2 位数字"), ex.getMessage());
        assertTrue(ex.getMessage().contains("340"), "报错要带上当前值，前端才能指出是哪个格子: " + ex.getMessage());
    }

    @Test
    @DisplayName("通用更新只改一段：按「库中现值 + 本次提交」合并校验，不误报缺段")
    void partial_update_should_merge_with_stored_rule() {
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 1, "34", "02", "0000", "00", "0", 100);

        CascadePlatformDTO patch = new CascadePlatformDTO();
        patch.setId(id);
        patch.setCodeCounty("0001");

        assertDoesNotThrow(() -> cascadePlatformManager.update(patch),
            "其余段没传不等于被清空，不能按“前 14 位必填”拦下来");
        assertEquals("0001", cascadePlatformManager.getById(id).getCodeCounty());
        assertEquals("34", cascadePlatformManager.getById(id).getCodeProvince(), "未提交的段保持原值");
    }

    @Test
    @DisplayName("通用更新只改连接参数：不触碰规则列，不做规则校验")
    void connection_only_patch_should_skip_rule_validation() {
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 0, null, null, null, null, null, null);

        CascadePlatformDTO patch = new CascadePlatformDTO();
        patch.setId(id);
        patch.setPlatformIp("10.9.9.9");

        assertDoesNotThrow(() -> cascadePlatformManager.update(patch));
        assertNull(cascadePlatformManager.getById(id).getCodeProvince(), "不能顺手给规则填默认值");
    }

    @Test
    @DisplayName("通用更新在启用态把某段清成空串 → 拒绝，且库里不留半截规则")
    void patch_clearing_segment_should_be_rejected_when_enabled() {
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 1, "34", "02", "0000", "00", "0", 100);

        CascadePlatformDTO patch = new CascadePlatformDTO();
        patch.setId(id);
        patch.setCodeCounty("");

        /* 空串在这条路径上是「显式清空」，而不是「没带这个字段」：启用态下清空就是缺段，
           不能当成未提交放过去，否则库里会留下一份看着已启用、实际缺一段的规则 */
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> cascadePlatformManager.update(patch));
        assertTrue(ex.getMessage().contains("前 14 位必填"), ex.getMessage());
        assertTrue(ex.getMessage().contains("县区编码"), ex.getMessage());

        CascadePlatformDTO saved = cascadePlatformManager.getById(id);
        assertEquals("0000", saved.getCodeCounty(), "被拒的提交不能留下半截规则");
        assertEquals(1, saved.getCodeRuleEnabled());
    }

    @Test
    @DisplayName("通用更新在停用态清空某段：库里写 NULL，不留空串")
    void patch_clearing_segment_should_store_null_when_disabled() {
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 0, "34", "02", "0000", "00", "0", null);

        CascadePlatformDTO patch = new CascadePlatformDTO();
        patch.setId(id);
        patch.setCodeCounty("");

        assertDoesNotThrow(() -> cascadePlatformManager.update(patch));
        /* getById 不做归一，能区分 NULL 与 '': 落 '' 会让「未配置」出现两种表示，
           将来按 IS NULL 写的统计/迁移会漏掉这些行 */
        assertNull(cascadePlatformManager.getById(id).getCodeCounty(), "清空必须落 NULL");
        assertEquals("34", cascadePlatformManager.getById(id).getCodeProvince(), "未提交的段保持原值");
    }

    @Test
    @DisplayName("创建平台：空串或纯空白的段落库为 NULL，有值段去空白后保留")
    void create_should_store_null_for_blank_segments() {
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 0, "", "  ", null, " 00 ", "0", null);

        CascadePlatformDTO saved = cascadePlatformManager.getById(id);
        assertNull(saved.getCodeProvince(), "空串不能原样落库，否则「未配置」有两种表示");
        assertNull(saved.getCodeCity(), "纯空白同理");
        assertNull(saved.getCodeCounty());
        assertEquals("00", saved.getCodeIndustry(), "有值段只去空白，不能被归一掉");
        assertEquals("0", saved.getCodeNetwork());
    }

    // ---------------------------------------------------------------- 保存与关闭

    @Test
    @DisplayName("规则保存：整份覆盖可清空段与开关，且不改写已上报的码")
    void save_rule_should_not_rewrite_existing_code() {
        toggleGlobalSwitch(true);
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 1, "34", "02", "0000", "00", "0", 100);
        String orgCode = addOrg(List.of(platformId));
        String frozen = row(platformId, orgCode).getPublishCode();
        assertNotNull(frozen);

        CascadePlatformDTO rule = new CascadePlatformDTO();
        rule.setCodeRuleEnabled(0);
        rule.setPublishAncestors(1);
        assertTrue(cascadePlatformManager.updateCodeRule(id, rule));

        CascadePlatformDTO saved = cascadePlatformManager.getById(id);
        assertEquals(0, saved.getCodeRuleEnabled(), "开关必须能关回 0");
        assertEquals(1, saved.getPublishAncestors(), "父链策略要能单独打开");
        assertNull(saved.getCodeProvince(), "整份覆盖语义：没传的段就是未配置");
        assertNull(saved.getCodeStartSeq());

        assertEquals(frozen, row(platformId, orgCode).getPublishCode(), "保存规则不许改写已上报的码");
        assertEquals(frozen, cascadeCodeResolver.resolve(platformId, PublishObjectType.ORG.name(), orgCode),
            "已固化的码原样返回：改码对上级等于删旧建新");
    }

    @Test
    @DisplayName("规则保存：平台不存在时拒绝，且不写库")
    void save_rule_should_reject_missing_platform() {
        CascadePlatformDTO rule = new CascadePlatformDTO();
        rule.setCodeRuleEnabled(0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> cascadePlatformManager.updateCodeRule(-1L, rule));
        assertTrue(ex.getMessage().contains("上级平台不存在"), ex.getMessage());
    }

    @Test
    @DisplayName("规则保存传 enabled=1 + 空串段 → 空串按「清空」处理，命中必填校验")
    void save_rule_with_blank_segment_should_be_rejected_when_enabled() {
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 0, null, null, null, null, null, null);

        CascadePlatformDTO rule = new CascadePlatformDTO();
        rule.setCodeRuleEnabled(1);
        rule.setCodeProvince("34");
        /* 全空格在 Manager 里经 trimToNull 等同未填：Req 放行空串，必填在这里兜 */
        rule.setCodeCity("  ");
        rule.setCodeCounty("0000");
        rule.setCodeIndustry("00");
        rule.setCodeNetwork("0");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> cascadePlatformManager.updateCodeRule(id, rule));
        assertTrue(ex.getMessage().contains("前 14 位必填"), ex.getMessage());
        assertTrue(ex.getMessage().contains("市编码"), ex.getMessage());

        CascadePlatformDTO saved = cascadePlatformManager.getById(id);
        assertEquals(0, saved.getCodeRuleEnabled(), "校验在写库之前，被拒的保存不能改动开关");
        assertNull(saved.getCodeProvince(), "也不能先写进半截段");
    }

    @Test
    @DisplayName("同一份规则连续保存两次：第二次仍报成功（不看 affectedRows）")
    void repeated_save_should_still_report_success() {
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 0, null, null, null, null, null, null);

        CascadePlatformDTO rule = new CascadePlatformDTO();
        rule.setCodeRuleEnabled(1);
        rule.setCodeProvince("34");
        rule.setCodeCity("02");
        rule.setCodeCounty("0000");
        rule.setCodeIndustry("00");
        rule.setCodeNetwork("0");
        rule.setCodeStartSeq(100);

        assertTrue(cascadePlatformManager.updateCodeRule(id, rule));
        /* 第二次的八列与库里完全一致：MySQL 默认对「命中但无值变化」的 UPDATE 返回 0，
           拿 affectedRows 当业务结论就会把这次合法保存报成失败。
           SQLite 的 changes() 计匹配行，这条断言在本地跑不出差异，是契约测试而非方言回归。 */
        assertTrue(cascadePlatformManager.updateCodeRule(id, rule),
            "重复保存是合法操作，不能因为没改到字节就返回失败");
        assertEquals("0000", cascadePlatformManager.getById(id).getCodeCounty());
    }

    @Test
    @DisplayName("规则保存未提交父链开关 → 保留库中现值，不静默清零")
    void save_rule_should_keep_publish_ancestors_when_not_submitted() {
        String platformId = UniqueKeyFactory.deviceId();
        Long id = addPlatform(platformId, 0, null, null, null, null, null, null);

        CascadePlatformDTO open = new CascadePlatformDTO();
        open.setPublishAncestors(1);
        assertTrue(cascadePlatformManager.updateCodeRule(id, open));
        assertEquals(1, cascadePlatformManager.getById(id).getPublishAncestors());

        /* 只提交编码五段：publishAncestors 缺席不等于「关掉补推」 */
        CascadePlatformDTO rule = new CascadePlatformDTO();
        rule.setCodeRuleEnabled(1);
        rule.setCodeProvince("34");
        rule.setCodeCity("02");
        rule.setCodeCounty("0000");
        rule.setCodeIndustry("00");
        rule.setCodeNetwork("0");
        assertTrue(cascadePlatformManager.updateCodeRule(id, rule));
        assertEquals(1, cascadePlatformManager.getById(id).getPublishAncestors(),
            "未提交就归 0 会把「自动补推」改成「缺祖先即报错」，失败点在组织发布入口");

        /* 显式传 0 仍要能关掉：保留现值不等于不能改 */
        CascadePlatformDTO close = new CascadePlatformDTO();
        close.setPublishAncestors(0);
        assertTrue(cascadePlatformManager.updateCodeRule(id, close));
        assertEquals(0, cascadePlatformManager.getById(id).getPublishAncestors());
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 建一个真实平台记录：解析器按 {@code platformId} 查库读规则，用假 ID 走不到平台规则这一支。
     */
    private Long addPlatform(String platformId, Integer ruleEnabled, String province, String city, String county,
        String industry, String network, Integer startSeq) {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setPlatformId(platformId);
        dto.setPlatformIp("192.168.1.200");
        dto.setPlatformPort(5060);
        dto.setPlatformDomain(platformId.substring(0, 10));
        dto.setLocalClientId(UniqueKeyFactory.deviceId());
        dto.setCodeRuleEnabled(ruleEnabled);
        dto.setCodeProvince(province);
        dto.setCodeCity(city);
        dto.setCodeCounty(county);
        dto.setCodeIndustry(industry);
        dto.setCodeNetwork(network);
        dto.setCodeStartSeq(startSeq);
        return cascadePlatformManager.add(dto);
    }

    /** 带组织规则的 DTO：用于验证「平台段整体压过组织段」 */
    private CascadeOrgDTO ruleOrg() {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setOrgName("org-平台规则-" + UniqueKeyFactory.app());
        dto.setCodeRuleEnabled(1);
        dto.setCodeProvince("37");
        dto.setCodeCity("01");
        dto.setCodeCounty("0200");
        dto.setCodeIndustry("04");
        dto.setCodeType("216");
        dto.setCodeNetwork("0");
        return dto;
    }

    private String addOrg(List<String> platformIds) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setOrgName("org-平台规则-" + UniqueKeyFactory.app());
        dto.setPlatformIds(platformIds);
        return cascadeOrgManager.getById(cascadeOrgManager.add(dto)).getOrgCode();
    }

    private CascadePublishDTO row(String platformId, String objectKey) {
        return cascadePublishManager.get(platformId, PublishObjectType.ORG.name(), objectKey);
    }
}
