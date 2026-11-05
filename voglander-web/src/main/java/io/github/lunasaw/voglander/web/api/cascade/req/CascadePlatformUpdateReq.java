package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

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
     * 平台名称（选填）：只用于本地辨识，GB28181 报文里用的是 platformId。
     *
     * <p>三态：{@code null}=不改，空串（或纯空白）=清空为未命名，其余按 trim 后的值保存。
     */
    @Size(max = 128, message = "平台名称长度不能超过 128")
    private String  platformName;

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
     * 本端客户端主键；null 表示不改，换了客户端等于换注册身份。
     */
    private Long    clientRefId;

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

    /**
     * 目录是否带设备层：0=虚拟组织（通道直挂组织），1=域/组织/设备/通道。
     *
     * <p>为 null 表示不改：切换层级结构会让上级目录的父子关系整体变化，
     * 只能由用户显式提交，不能因为“字段没传”就默默回成默认值。
     */
    @Min(value = 0, message = "设备层开关只能为 0 或 1")
    @Max(value = 1, message = "设备层开关只能为 0 或 1")
    private Integer catalogWithDeviceLayer;

    /* ---------------- 平台编码规则（前 14 位里可配的五段；类型位由对象类型决定，不接受平台填写） ---------------- */

    /**
     * 省编码（2 位数字）。
     *
     * <p>三态：{@code null}=不改，空串=清空该段，其余按值保存。
     * 「启用规则时前 14 位必填」需要看合并后的最终值，由 Manager 校验，不在这里强制。
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
     * 起始序号（6 位）；null 表示不改
     */
    @Min(value = 0, message = "起始序号范围 0-999999")
    @Max(value = 999999, message = "起始序号范围 0-999999")
    private Integer codeStartSeq;

    /**
     * 平台编码规则开关：0=不启用 1=启用；null 表示不改。
     *
     * <p>开关字段不能用「空串清空」三态：建表时列是 NOT NULL DEFAULT 0，只能传 0/1。
     */
    @Min(value = 0, message = "平台编码规则开关只能为 0 或 1")
    @Max(value = 1, message = "平台编码规则开关只能为 0 或 1")
    private Integer codeRuleEnabled;

    /**
     * 发布策略：0=不补推祖先 1=自动补推；null 表示不改
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
     * 只 trim 不 trimToNull：空串表示「清空该段」，必须与 null（不改）区分。
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