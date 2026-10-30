package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import lombok.Data;

/**
 * 级联组织更新请求（Web 入参，id 必填）。
 *
 * <p>只允许改名称 / 排序 / 启停。<b>父节点与组织编码不可改</b>：上级侧没有"移动节点"语义，
 * 改父节点或编码只会表现为旧节点消失、新节点出现，已绑定的通道随之变成孤儿节点。
 * 需要调整层级时走 {@code POST /cascade/org/{id}/move}（保留编码，但上级会重建子树）。
 *
 * @author luna
 */
@Data
public class CascadeOrgUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID（必填）
     */
    private Long    id;

    /**
     * 上报给上级的组织名称
     */
    private String  orgName;

    /**
     * 同级排序，越小越靠前
     */
    private Integer sortOrder;

    /**
     * 是否上报 1上报 / 0不上报
     */
    private Integer enabled;

    /**
     * 父组织主键，<b>仅供非法调用识别</b>：本接口一律拒绝非空值并提示改用移动接口。
     *
     * <p>保留这个字段而不是删掉，是因为旧调用方（或复用了新增表单的页面）
     * 会把 parentOrgId 一起 POST 上来；直接忽略会让人以为层级已经改成功了。
     */
    private Long    parentOrgId;

    /**
     * 取消同步时其下通道的处置策略：CASCADE-通道一并取消（默认）/ REROUTE-通道上浮挂域节点。
     *
     * <p>只在“取消同步”动作上有意义；传空表示不改。
     */
    private String  revokePolicy;
}
