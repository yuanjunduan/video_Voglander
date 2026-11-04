package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 平台编码规则保存请求（{@code PUT /cascade/platform/{id}/code-rule}，id 在路径上）。
 *
 * <p><b>本接口是「整份覆盖」，不是 patch</b>：弹窗里看到的九个格子就是全部规则，
 * 未传的段按未配置落库（空列）。这与通用更新接口的「null 不改」不同——
 * 通用接口的 null 表示「这次表单没带这个字段」，而这里保存的是完整的一份配置。
 *
 * <p><b>唯一例外是 {@code publishAncestors}</b>：它描述发布策略而非编码规则，不提交时保留库中现值。
 * 归 0 会把「自动补推祖先」改成「缺祖先即报错」，而失败点在另一个入口，用户难以关联。
 *
 * <p><b>没有 codeType 字段</b>：类型位（组织 215/216、设备 132、通道 131）由对象类型决定，
 * 不允许平台填写。加这个字段就等于允许配出「通道以 216 开头」的码，而这类串一眼看不出错。
 *
 * <p>段位长度在这里做格式校验；「启用规则时五段必填」是跨字段约束，
 * 由 {@code CascadePlatformManager#assertCodeRuleValid} 统一校验，
 * 与新增/编辑平台走同一份实现，避免两套规则慢慢分叉。
 *
 * @author luna
 * @date 2026/11/04
 */
@Data
public class CascadePlatformCodeRuleReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 省编码（2 位数字）；启用规则时必填
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_2_REGEX, message = "省编码必须为 2 位数字")
    private String  codeProvince;

    /**
     * 市编码（2 位数字）；启用规则时必填
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_2_REGEX, message = "市编码必须为 2 位数字")
    private String  codeCity;

    /**
     * 县区编码（4 位数字）；启用规则时必填
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_4_REGEX, message = "县区编码必须为 4 位数字")
    private String  codeCounty;

    /**
     * 行业编码（2 位数字）；启用规则时必填
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_2_REGEX, message = "行业编码必须为 2 位数字")
    private String  codeIndustry;

    /**
     * 网络标识（1 位数字）；启用规则时必填
     */
    @Pattern(regexp = CascadeConstant.CODE_SEG_1_REGEX, message = "网络标识必须为 1 位数字")
    private String  codeNetwork;

    /**
     * 起始序号（0-999999）；只作发号下界，不会把已用号码降回去
     */
    @Min(value = 0, message = "起始序号范围 0-999999")
    @Max(value = 999999, message = "起始序号范围 0-999999")
    private Integer codeStartSeq;

    /**
     * 平台编码规则开关：0=不启用（默认，沿用组织规则与全局配置） 1=按平台规则生成上报编码
     */
    @Min(value = 0, message = "平台编码规则开关只能为 0 或 1")
    @Max(value = 1, message = "平台编码规则开关只能为 0 或 1")
    private Integer codeRuleEnabled;

    /**
     * 发布策略：0=勾选组织时缺祖先即报错（默认） 1=自动补推缺失的祖先链
     *
     * <p>未提交（null）时保留库中现值，不按 0 覆盖。
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
     * 只 trim 不 trimToNull：空串与 null 在本接口都表示「未配置」，由 Manager 统一落位 NULL。
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
