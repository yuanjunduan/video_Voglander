package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

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

    /**
     * 目录是否带设备层：0=虚拟组织（通道直挂组织，默认），1=域/组织/设备/通道。
     */
    @Min(value = 0, message = "设备层开关只能为 0 或 1")
    @Max(value = 1, message = "设备层开关只能为 0 或 1")
    private Integer catalogWithDeviceLayer;

    /* ---------------- 平台编码规则（前 14 位里可配的五段；类型位由对象类型决定，不接受平台填写） ---------------- */

    /**
     * 省编码（2 位数字）；留空表示不用平台规则，编码回落组织规则与全局配置
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_2_REGEX, message = "省编码必须为 2 位数字")
    private String  codeProvince;

    /**
     * 市编码（2 位数字）
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_2_REGEX, message = "市编码必须为 2 位数字")
    private String  codeCity;

    /**
     * 县区编码（4 位数字）
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_4_REGEX, message = "县区编码必须为 4 位数字")
    private String  codeCounty;

    /**
     * 行业编码（2 位数字）
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_2_REGEX, message = "行业编码必须为 2 位数字")
    private String  codeIndustry;

    /**
     * 网络标识（1 位数字）
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_1_REGEX, message = "网络标识必须为 1 位数字")
    private String  codeNetwork;

    /**
     * 起始序号（6 位，只作发号下界，不会把已用号码降回去）
     */
    @Min(value = 0, message = "起始序号范围 0-999999")
    @Max(value = 999999, message = "起始序号范围 0-999999")
    private Integer codeStartSeq;

    /**
     * 平台编码规则开关：0=不启用（默认，编码沿用组织规则与全局配置） 1=按平台规则生成上报编码。
     *
     * <p>默认关闭是兼容红线：升级若替存量平台换码，上级会把所有老对象当成新设备。
     */
    @Min(value = 0, message = "平台编码规则开关只能为 0 或 1")
    @Max(value = 1, message = "平台编码规则开关只能为 0 或 1")
    private Integer codeRuleEnabled;

    /**
     * 发布策略：0=勾选组织时缺祖先即报错（默认） 1=自动补推缺失的祖先链。
     *
     * <p>默认关闭：自动补推会一次性放出整条祖先链，只能由用户显式选择。
     */
    @Min(value = 0, message = "父链补推开关只能为 0 或 1")
    @Max(value = 1, message = "父链补推开关只能为 0 或 1")
    private Integer publishAncestors;

    /* ---------------- 段位空白归一 ---------------- */

    /**
     * 段位在入口统一 trim：粘贴进来的 "  " 按「未填」处理，与 Manager 的 {@code trimToNull} 同一口径。
     *
     * <p>不在这里 trim 的话，{@code @Pattern} 会把 "  " 判成「必须为 2 位数字」，
     * 而 Manager 又把它当空——同一份输入两层给出相反的结论。
     * 只 trim 不 trimToNull：空串在更新语义里表示「清空该段」，必须与「没传」区分。
     */
    public void setCodeProvince(String codeProvince) {
        this.codeProvince = StringUtils.trim(codeProvince);
    }

    public void setCodeCity(String codeCity) {
        this.codeCity = StringUtils.trim(codeCity);
    }

    public void setCodeCounty(String codeCounty) {
        this.codeCounty = StringUtils.trim(codeCounty);
    }

    public void setCodeIndustry(String codeIndustry) {
        this.codeIndustry = StringUtils.trim(codeIndustry);
    }

    public void setCodeNetwork(String codeNetwork) {
        this.codeNetwork = StringUtils.trim(codeNetwork);
    }
}