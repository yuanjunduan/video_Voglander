package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import lombok.Data;

/**
 * 级联组织新增请求（Web 入参）。
 *
 * <p>组织编码由后端按国标规则生成，入参不接受 {@code orgCode}：
 * 手填容易出现类型位错误或与平台前缀不一致，上级会把这种节点判为非法。
 *
 * <p><b>{@code platformIds} 让「建组织」与「推给谁」一次完成</b>：没有发布范围的组织在
 * 上级目录里不可见，先建后配等于让用户面对一片空白再去找另一个入口。
 * 传空表示暂不发布。
 *
 * @author luna
 */
@Data
public class CascadeOrgCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 父组织主键；为空表示根组织，直接挂在域节点下
     */
    private Long              parentId;

    /**
     * 组织名称（必填）
     */
    private String            orgName;

    /**
     * 同级排序，越小越靠前
     */
    private Integer           sortOrder;

    /**
     * 本地启停 1启用 / 0停用（停用会连带整棵子树一起从所有上级目录中剪掉）
     */
    private Integer           enabled;

    /**
     * 本次要发布到的上级平台编码集合；空表示暂不发布，之后可在发布配置里补
     */
    private List<String>      platformIds;

    /* ---------------- 编码段规则（预留，不填则按后端默认规则生成） ---------------- */

    /** 省编码（2 位） */
    private String            codeProvince;

    /** 市编码（2 位） */
    private String            codeCity;

    /** 县区编码（4 位） */
    private String            codeCounty;

    /** 行业编码（2 位） */
    private String            codeIndustry;

    /** 类型编码（3 位，215 业务分组 / 216 虚拟组织） */
    private String            codeType;

    /** 网络标识（1 位） */
    private String            codeNetwork;

    /** 起始序号（6 位） */
    private Integer           codeStartSeq;

    /** 0=未启用（后端默认生成） 1=按七段规则生成 */
    private Integer           codeRuleEnabled;

    /* ---------------- 段位空白归一 ---------------- */

    /**
     * 段位在入口统一 trim：粘贴进来的 "  " 按「未填」处理，与落库归一的 {@code trimToNull} 同一口径。
     *
     * <p>不在这里 trim 的话，同一份表单值在不同入口会有两种落地：带空白的原样入库，
     * 回显时看着是空、库里却是一串空格，编码生成侧还得各自再清一次。
     *
     * <p>只 trim 不 trimToNull：空串在更新语义里表示「清空该段」，必须与「没传」（null=不改）区分。
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

    public void setCodeType(String codeType) {
        this.codeType = StringUtils.trim(codeType);
    }

    public void setCodeNetwork(String codeNetwork) {
        this.codeNetwork = StringUtils.trim(codeNetwork);
    }
}
