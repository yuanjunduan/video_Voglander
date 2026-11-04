package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 组织编码规则（N3a）的行为约束：开关打开才换口径，关着必须与改造前逐字节一致。
 *
 * <p>这条基线比「新规则算得对不对」更要紧：升级后如果关着开关的部署也开始按七段发号，
 * 上级目录里的节点编码会集体跳变，而报文是单向的、没有回滚通道。
 *
 * <p>总闸 {@code gateway.cascade.code-rule.enabled} 用反射打开而不是改配置，
 * 因为它是单例 Bean 的字段，改配置就得多起一套 Spring 上下文。
 */
@DisplayName("组织编码规则")
class CascadeOrgCodeRuleTest extends BaseTest {

    /** 默认配置（code-prefix=3402000000、org-type=215、网络位 0）下的前 14 位 */
    private static final String LEGACY_HEAD = "34020000002150";

    @Autowired
    private CascadeOrgManager cascadeOrgManager;

    @AfterEach
    void closeGlobalSwitch() {
        toggleGlobalSwitch(false);
    }

    private void toggleGlobalSwitch(boolean enabled) {
        ReflectionTestUtils.setField(cascadeOrgManager, "codeRuleGlobalEnabled", enabled);
    }

    private CascadeOrgDTO newOrg(CascadeOrgDTO dto) {
        dto.setOrgName("org-编码规则-" + UniqueKeyFactory.app());
        return cascadeOrgManager.getById(cascadeOrgManager.add(dto));
    }

    /** 规则启用、段位任意的组织配置 */
    private CascadeOrgDTO ruleDto(String province, String city, String county, String industry, String type,
        String network) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setCodeRuleEnabled(1);
        dto.setCodeProvince(province);
        dto.setCodeCity(city);
        dto.setCodeCounty(county);
        dto.setCodeIndustry(industry);
        dto.setCodeType(type);
        dto.setCodeNetwork(network);
        return dto;
    }

    @Test
    @DisplayName("总闸关闭时仍按旧口径发号，且序号连续")
    void should_keep_legacy_code_when_disabled() {
        CascadeOrgDTO first = newOrg(new CascadeOrgDTO());
        CascadeOrgDTO second = newOrg(new CascadeOrgDTO());

        assertEquals(LEGACY_HEAD, first.getOrgCode().substring(0, 14), "关着开关不允许改口径");
        assertEquals(CascadeCodeBuilder.seqOf(first.getOrgCode(), LEGACY_HEAD) + 1,
            CascadeCodeBuilder.seqOf(second.getOrgCode(), LEGACY_HEAD), "序号必须接着全库最大值往下发");
    }

    @Test
    @DisplayName("组织开关未开时即使总闸打开也走旧口径")
    void should_keep_legacy_code_when_org_switch_off() {
        toggleGlobalSwitch(true);

        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setCodeProvince("37");
        dto.setCodeCity("01");
        dto.setCodeCounty("0200");

        assertEquals(LEGACY_HEAD, newOrg(dto).getOrgCode().substring(0, 14));
    }

    @Test
    @DisplayName("总闸与组织开关都打开时按七段拼装，起始序号作为下界")
    void should_compose_code_from_segments() {
        toggleGlobalSwitch(true);

        CascadeOrgDTO dto = ruleDto("37", "01", "0200", "04", "216", "1");
        dto.setCodeStartSeq(100);
        String code = newOrg(dto).getOrgCode();

        assertEquals("37010200042161", code.substring(0, 14), "省2+市2+县4+行业2+类型3+网络1");
        assertEquals(20, code.length());
        assertTrue(CascadeCodeBuilder.seqOf(code, "37010200042161") >= 100,
            "code_start_seq 是下界，不能被已用号码顶下去");
    }

    @Test
    @DisplayName("子组织只配县级时，其余段继承父组织")
    void should_fall_back_to_parent_segments() {
        toggleGlobalSwitch(true);
        CascadeOrgDTO parent = newOrg(ruleDto("37", "01", "0199", "04", "216", "0"));

        CascadeOrgDTO childDto = new CascadeOrgDTO();
        childDto.setParentId(parent.getId());
        childDto.setCodeRuleEnabled(1);
        childDto.setCodeCounty("0200");
        CascadeOrgDTO child = newOrg(childDto);

        /* 父的 0199 被子的 0200 覆盖，省/市/行业/类型/网络全部继承 */
        assertEquals("37010200042160", child.getOrgCode().substring(0, 14));
        assertTrue(CascadeCodeBuilder.seqOf(child.getOrgCode(), "37010200042160")
            > CascadeCodeBuilder.seqOf(parent.getOrgCode(), "37010200042160"),
            "父子同前缀时序号必须继续往后发，不能撞码");
    }

    @Test
    @DisplayName("网络位不参与发号：同前缀不同网络位共享号池")
    void network_should_not_split_sequence_pool() {
        toggleGlobalSwitch(true);

        String zero = newOrg(ruleDto("37", "01", "0301", "04", "216", "0")).getOrgCode();
        String one = newOrg(ruleDto("37", "01", "0301", "04", "216", "1")).getOrgCode();

        assertEquals(zero.substring(0, 13), one.substring(0, 13), "只有网络位不同");
        assertNotEquals(zero.substring(14), one.substring(14),
            "网络位不进号池：同前缀第二条必须换号，否则去掉网络位看就是重号");
    }

    @Test
    @DisplayName("创建组织：空串或纯空白的段落库为 NULL，有值段去空白后保留")
    void create_should_store_null_for_blank_segments() {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setCodeProvince("");
        dto.setCodeCity("  ");
        dto.setCodeIndustry(" 04 ");
        dto.setCodeType("216");
        dto.setCodeNetwork(" 1 ");
        CascadeOrgDTO saved = newOrg(dto);

        assertNull(saved.getCodeProvince(), "空串不能原样落库，否则「未配置」有两种表示");
        assertNull(saved.getCodeCity(), "纯空白同理");
        assertNull(saved.getCodeCounty(), "没传的段天然是 NULL");
        assertEquals("04", saved.getCodeIndustry(), "有值段只去空白，不能被归一掉");
        assertEquals("216", saved.getCodeType());
        assertEquals("1", saved.getCodeNetwork());
    }

    @Test
    @DisplayName("编辑组织清空某段：库里写 NULL，未提交的段保持原值")
    void patch_clearing_segment_should_store_null() {
        CascadeOrgDTO created = newOrg(ruleDto("37", "01", "0200", "04", "216", "1"));

        CascadeOrgDTO patch = new CascadeOrgDTO();
        patch.setId(created.getId());
        patch.setCodeCounty("");
        assertDoesNotThrow(() -> cascadeOrgManager.update(patch));

        CascadeOrgDTO after = cascadeOrgManager.getById(created.getId());
        assertNull(after.getCodeCounty(), "清空必须落 NULL，不能留空串");
        assertEquals("37", after.getCodeProvince(), "未提交的段保持原值");
        assertEquals("216", after.getCodeType(), "类型位与其它段同样不能被顺手清掉");
    }
}
