package io.github.lunasaw.voglander.web.api.cascade.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 级联平台入参校验测试（jakarta validation，不启动 Spring 容器）。
 *
 * <p>目的：确认"填错"类输入在进入 Manager 之前就被拦截，
 * 避免脏配置落库后表现为"注册一直离线"这类难排查问题。</p>
 *
 * @author luna
 */
@DisplayName("级联平台入参校验")
class CascadePlatformReqValidationTest {

    private static ValidatorFactory factory;
    private static Validator        validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    /* ---------------- 新增 ---------------- */

    @Test
    @DisplayName("合法新增请求无校验错误")
    void create_valid() {
        assertTrue(violations(validCreate()).isEmpty());
    }

    @Test
    @DisplayName("平台ID不是 20 位数字时拦截")
    void create_platform_id_invalid() {
        CascadePlatformCreateReq req = validCreate();
        req.setPlatformId("3402");
        assertEquals("上级平台ID必须为 20 位数字", messages(req));
    }

    @Test
    @DisplayName("平台IP非法时拦截")
    void create_platform_ip_invalid() {
        CascadePlatformCreateReq req = validCreate();
        req.setPlatformIp("10.0.0");
        assertEquals("上级平台IP格式不正确", messages(req));
    }

    @Test
    @DisplayName("平台端口越界时拦截")
    void create_platform_port_out_of_range() {
        CascadePlatformCreateReq req = validCreate();
        req.setPlatformPort(70000);
        assertEquals("上级平台端口范围 1-65535", messages(req));
    }

    @Test
    @DisplayName("缺少本端客户端ID时拦截")
    void create_local_client_id_blank() {
        CascadePlatformCreateReq req = validCreate();
        req.setLocalClientId(" ");
        assertTrue(messages(req).contains("本端客户端ID不能为空"));
    }

    @Test
    @DisplayName("传输协议拼错时拦截")
    void create_transport_invalid() {
        CascadePlatformCreateReq req = validCreate();
        req.setTransport("SCTP");
        assertEquals("传输协议仅支持 UDP 或 TCP", messages(req));
    }

    @Test
    @DisplayName("编码大小写不敏感（gb2312 合法）")
    void create_charset_case_insensitive() {
        CascadePlatformCreateReq req = validCreate();
        req.setCharset("gb2312");
        assertTrue(violations(req).isEmpty());
    }

    @Test
    @DisplayName("保活间隔超出 10-300 秒时拦截")
    void create_keepalive_out_of_range() {
        CascadePlatformCreateReq req = validCreate();
        req.setKeepaliveInterval(5);
        assertEquals("保活间隔范围 10-300 秒", messages(req));
    }

    @Test
    @DisplayName("注册有效期超出 60-7200 秒时拦截")
    void create_expires_out_of_range() {
        CascadePlatformCreateReq req = validCreate();
        req.setRegisterExpires(30);
        assertEquals("注册有效期范围 60-7200 秒", messages(req));
    }

    /* ---------------- 平台编码规则（2.3） ---------------- */

    @Test
    @DisplayName("平台编码规则：省编码 3 位时拦截")
    void create_code_province_width_invalid() {
        CascadePlatformCreateReq req = validCreate();
        req.setCodeProvince("340");
        assertEquals("省编码必须为 2 位数字", messages(req));
    }

    @Test
    @DisplayName("平台编码规则：县区编码 3 位时拦截")
    void create_code_county_width_invalid() {
        CascadePlatformCreateReq req = validCreate();
        req.setCodeCounty("020");
        assertEquals("县区编码必须为 4 位数字", messages(req));
    }

    @Test
    @DisplayName("平台编码规则：网络标识 2 位时拦截")
    void create_code_network_width_invalid() {
        CascadePlatformCreateReq req = validCreate();
        req.setCodeNetwork("00");
        assertEquals("网络标识必须为 1 位数字", messages(req));
    }

    @Test
    @DisplayName("平台编码规则：空串表示清空该段，不拦截")
    void create_blank_segment_allowed() {
        CascadePlatformCreateReq req = validCreate();
        req.setCodeProvince("");
        req.setCodeNetwork("");
        assertTrue(violations(req).isEmpty(), "清空段位是合法操作，不能当成“填错”拦在接口层");
    }

    @Test
    @DisplayName("平台编码规则：全空格段按未填处理，不被 @Pattern 判成位宽错误")
    void create_blank_segment_with_spaces_normalized() {
        CascadePlatformCreateReq req = validCreate();
        req.setCodeProvince("  ");
        req.setCodeNetwork(" ");
        assertTrue(violations(req).isEmpty(), "空格与空串必须同一口径，否则 @Pattern 与 Manager 会给出相反的结论");
        assertEquals("", req.getCodeProvince(), "空格归一为空串，由 Manager 统一 trimToNull 落 NULL");
        assertEquals("", req.getCodeNetwork());
    }

    @Test
    @DisplayName("平台编码规则：段位首尾空白自动去掉（\" 34 \" → 34）")
    void create_segment_trimmed() {
        CascadePlatformCreateReq req = validCreate();
        req.setCodeProvince(" 34 ");
        req.setCodeCounty(" 0000");
        assertTrue(violations(req).isEmpty(), "从表格复制粘贴常带不可见空白，不能因此拦下来");
        assertEquals("34", req.getCodeProvince());
        assertEquals("0000", req.getCodeCounty());
    }

    @Test
    @DisplayName("更新请求：空格段归一为空串而非 null（空串=清空，null=不改）")
    void update_blank_segment_stays_empty_not_null() {
        CascadePlatformUpdateReq req = new CascadePlatformUpdateReq();
        req.setId(5L);
        req.setCodeProvince("  ");
        assertEquals("", req.getCodeProvince(), "trim 不能把「清空该段」降级成「没传」");
        assertTrue(violations(req).isEmpty());
    }

    @Test
    @DisplayName("平台编码规则：起始序号超过 999999 时拦截")
    void create_code_start_seq_out_of_range() {
        CascadePlatformCreateReq req = validCreate();
        req.setCodeStartSeq(1_000_000);
        assertEquals("起始序号范围 0-999999", messages(req));
    }

    @Test
    @DisplayName("平台编码规则：开关只接受 0/1")
    void create_code_rule_enabled_out_of_range() {
        CascadePlatformCreateReq req = validCreate();
        req.setCodeRuleEnabled(2);
        assertEquals("平台编码规则开关只能为 0 或 1", messages(req));
    }

    @Test
    @DisplayName("类型位只读：三个平台入参都不提供 codeType 字段")
    void platform_req_should_not_expose_code_type() {
        for (Class<?> type : new Class<?>[] {CascadePlatformCreateReq.class, CascadePlatformUpdateReq.class,
            CascadePlatformCodeRuleReq.class}) {
            assertFalse(Arrays.stream(type.getDeclaredFields()).anyMatch(f -> "codeType".equals(f.getName())),
                type.getSimpleName() + " 不能有 codeType：类型位由对象类型决定（组织 215/216、设备 132、通道 131），"
                    + "否则会配出“通道码以 216 开头”这类看不出错的串");
        }
    }

    @Test
    @DisplayName("规则保存请求：五段齐全且开关打开时无校验错误")
    void code_rule_req_valid() {
        assertTrue(violations(validCodeRule()).isEmpty());
    }

    @Test
    @DisplayName("规则保存请求：行业编码非数字时拦截")
    void code_rule_req_industry_not_digits() {
        CascadePlatformCodeRuleReq req = validCodeRule();
        req.setCodeIndustry("0A");
        assertEquals("行业编码必须为 2 位数字", messages(req));
    }

    /* ---------------- 更新 ---------------- */

    @Test
    @DisplayName("更新只传部分字段时不做必填校验")
    void update_partial_allowed() {
        CascadePlatformUpdateReq req = new CascadePlatformUpdateReq();
        req.setPlatformIp("10.0.0.9");
        assertTrue(violations(req).isEmpty());
    }

    @Test
    @DisplayName("更新时端口 0 仍被拦截")
    void update_port_invalid() {
        CascadePlatformUpdateReq req = new CascadePlatformUpdateReq();
        req.setPlatformPort(0);
        assertEquals("上级平台端口范围 1-65535", messages(req));
    }

    @Test
    @DisplayName("更新时本端客户端ID传空串视为不修改")
    void update_local_client_id_blank_allowed() {
        CascadePlatformUpdateReq req = new CascadePlatformUpdateReq();
        req.setLocalClientId("");
        assertTrue(violations(req).isEmpty());
    }

    /* ---------------- 辅助 ---------------- */

    private static CascadePlatformCreateReq validCreate() {
        CascadePlatformCreateReq req = new CascadePlatformCreateReq();
        req.setPlatformId("34020000002000000001");
        req.setPlatformIp("10.0.0.1");
        req.setPlatformPort(5060);
        req.setLocalClientId("34020000002000000002");
        return req;
    }

    /** 合法规则：省 34 / 市 02 / 县 0000 / 行业 00 / 网络 0，启用 */
    private static CascadePlatformCodeRuleReq validCodeRule() {
        CascadePlatformCodeRuleReq req = new CascadePlatformCodeRuleReq();
        req.setCodeProvince("34");
        req.setCodeCity("02");
        req.setCodeCounty("0000");
        req.setCodeIndustry("00");
        req.setCodeNetwork("0");
        req.setCodeStartSeq(100);
        req.setCodeRuleEnabled(1);
        req.setPublishAncestors(0);
        return req;
    }

    private static Set<ConstraintViolation<Object>> violations(Object req) {
        return validator.validate(req);
    }

    private static String messages(Object req) {
        return violations(req).stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.joining(","));
    }
}