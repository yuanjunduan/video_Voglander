package io.github.lunasaw.voglander.web.api.cascade.req;

import static org.junit.jupiter.api.Assertions.*;

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

    private static Set<ConstraintViolation<Object>> violations(Object req) {
        return validator.validate(req);
    }

    private static String messages(Object req) {
        return violations(req).stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.joining(","));
    }
}