package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 级联上级平台更新请求（Web 入参，id 必填）。
 *
 * <p>
 * platformId 为唯一索引，编辑时不可改（前端只展示）；后端按 id 更新其余字段。
 * </p>
 *
 * <p>
 * 更新场景只做"填了才校验"的格式/范围校验，不强制必填：前端若只提交变更字段，
 * 未提交的字段由 MyBatis-Plus 的 null 忽略机制保持原值；密码留空表示不修改。
 * </p>
 *
 * @author luna
 * @date 2026/06/16
 */
@Data
public class CascadePlatformUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID（必填）
     */
    private Long    id;

    /**
     * 上级平台国标 ID（展示，不可改）
     */
    @Pattern(regexp = "^(\\d{20})?$", message = "上级平台ID必须为 20 位数字")
    private String  platformId;

    /**
     * 上级平台 IP
     */
    @Pattern(regexp = CascadeConstant.IPV4_REGEX, message = "上级平台IP格式不正确")
    private String  platformIp;

    /**
     * 上级平台端口
     */
    @Min(value = 1, message = "上级平台端口范围 1-65535")
    @Max(value = 65535, message = "上级平台端口范围 1-65535")
    private Integer platformPort;

    /**
     * 上级平台域（SIP domain）；留空时后端取平台ID前 10 位
     */
    @Size(max = 64, message = "上级平台域长度不能超过 64")
    private String  platformDomain;

    /**
     * 认证用户名（仅存库展示，SIP 摘要鉴权使用 localClientId）
     */
    @Size(max = 64, message = "认证用户名长度不能超过 64")
    private String  username;

    /**
     * 认证密码；留空表示保持原密码不变
     */
    @Size(max = 128, message = "认证密码长度不能超过 128")
    private String  password;

    /**
     * 本端客户端国标 ID
     */
    @Pattern(regexp = "^(\\d{20})?$", message = "本端客户端ID必须为 20 位数字")
    private String  localClientId;

    /**
     * 本端 IP
     */
    @Pattern(regexp = CascadeConstant.IPV4_REGEX, message = "本端IP格式不正确")
    private String  localIp;

    /**
     * 本端端口；必须与 SIP 客户端监听端口一致
     */
    @Min(value = 1, message = "本端端口范围 1-65535")
    @Max(value = 65535, message = "本端端口范围 1-65535")
    private Integer localPort;

    /**
     * 保活心跳间隔（秒）
     */
    @Min(value = 10, message = "保活间隔范围 10-300 秒")
    @Max(value = 300, message = "保活间隔范围 10-300 秒")
    private Integer keepaliveInterval;

    /**
     * 注册有效期（秒）
     */
    @Min(value = 60, message = "注册有效期范围 60-7200 秒")
    @Max(value = 7200, message = "注册有效期范围 60-7200 秒")
    private Integer registerExpires;

    /**
     * 编码（GB2312/UTF-8）
     */
    @Pattern(regexp = "(?i)GB2312|UTF-8", message = "编码仅支持 GB2312 或 UTF-8")
    private String  charset;

    /**
     * 传输协议 UDP / TCP
     */
    @Pattern(regexp = "(?i)UDP|TCP", message = "传输协议仅支持 UDP 或 TCP")
    private String  transport;
}