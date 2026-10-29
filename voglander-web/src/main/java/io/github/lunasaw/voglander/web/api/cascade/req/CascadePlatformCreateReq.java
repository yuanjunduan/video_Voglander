package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 级联上级平台新增请求（Web 入参）。
 *
 * <p>
 * 校验只拦截"填错"（ID 超长、端口越界、IP 非法、传输协议拼错），不做能力判断；
 * 上级平台是否认可本端 ID/密码只能靠注册结果反馈。
 * </p>
 *
 * @author luna
 * @date 2026/06/16
 */
@Data
public class CascadePlatformCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 上级平台国标 ID（20 位，必填）
     */
    @NotBlank(message = "上级平台ID不能为空")
    @Pattern(regexp = CascadeConstant.GB_ID_REGEX, message = "上级平台ID必须为 20 位数字")
    private String  platformId;

    /**
     * 上级平台 IP（必填）
     */
    @NotBlank(message = "上级平台IP不能为空")
    @Pattern(regexp = CascadeConstant.IPV4_REGEX, message = "上级平台IP格式不正确")
    private String  platformIp;

    /**
     * 上级平台端口（必填，默认 5060）
     */
    @NotNull(message = "上级平台端口不能为空")
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
     * 认证密码
     */
    @Size(max = 128, message = "认证密码长度不能超过 128")
    private String  password;

    /**
     * 本端客户端国标 ID（20 位，必填）
     */
    @NotBlank(message = "本端客户端ID不能为空")
    @Pattern(regexp = CascadeConstant.GB_ID_REGEX, message = "本端客户端ID必须为 20 位数字")
    private String  localClientId;

    /**
     * 本端 IP；留空时后端取 SIP 客户端对外通告地址
     */
    @Pattern(regexp = CascadeConstant.IPV4_REGEX, message = "本端IP格式不正确")
    private String  localIp;

    /**
     * 本端端口；留空时后端取 SIP 客户端监听端口，必须与 SIP 客户端保持一致
     */
    @Min(value = 1, message = "本端端口范围 1-65535")
    @Max(value = 65535, message = "本端端口范围 1-65535")
    private Integer localPort;

    /**
     * 保活心跳间隔（秒，默认 60）
     */
    @Min(value = 10, message = "保活间隔范围 10-300 秒")
    @Max(value = 300, message = "保活间隔范围 10-300 秒")
    private Integer keepaliveInterval;

    /**
     * 注册有效期（秒，默认 3600）
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
     * 传输协议 UDP / TCP（默认 UDP）
     */
    @Pattern(regexp = "(?i)UDP|TCP", message = "传输协议仅支持 UDP 或 TCP")
    private String  transport;
}