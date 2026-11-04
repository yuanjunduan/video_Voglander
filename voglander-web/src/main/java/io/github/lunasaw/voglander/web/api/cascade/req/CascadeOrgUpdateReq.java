package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

import lombok.Data;

/**
 * 级联组织更新请求（Web 入参，id 必填）。
 *
 * <p>只允许改名称 / 排序 / 本地启停 / 编码段规则。<b>父节点与组织编码不可改</b>：
 * 上级侧没有"移动节点"语义，改父节点或编码只会表现为旧节点消失、新节点出现，
 * 已绑定的通道随之变成孤儿节点。需要调整层级时走 {@code POST /cascade/org/{id}/move}。
 *
 * <p><b>「推给哪些平台」不在这里</b>：那是发布配置，走 {@code PUT /cascade/org/{id}/publish}。
 * 混进本接口会让一次改名顺手改掉发布范围，用户很难预期。
 *
 * @author luna
 */
@Data
public class CascadeOrgUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID（必填）
     */
    private Long              id;

    /**
     * 组织名称
     */
    private String            orgName;

    /**
     * 同级排序，越小越靠前
     */
    private Integer           sortOrder;

    /**
     * 本地启停 1启用 / 0停用；1→0 会连带整棵子树从各上级目录移除
     */
    private Integer           enabled;

    /**
     * 父组织主键，<b>仅供非法调用识别</b>：本接口一律拒绝非空值并提示改用移动接口。
     *
     * <p>保留这个字段而不是删掉，是因为旧调用方（或复用了新增表单的页面）
     * 会把 parentId 一起 POST 上来；直接忽略会让人以为层级已经改成功了。
     */
    private Long              parentId;

    /* ---------------- 编码段规则（预留：null 不改，空串清空） ---------------- */

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
     * <p>只 trim 不 trimToNull：本接口是 patch 语义，null=不改、空串=清空，
     * 把空串归一成 null 会让「清空某段」静默失效。
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
