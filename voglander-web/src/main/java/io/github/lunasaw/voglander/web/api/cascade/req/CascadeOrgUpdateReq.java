package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import lombok.Data;

/**
 * 级联组织更新请求（Web 入参，id 必填）。
 *
 * <p>只允许改名称 / 排序 / 启停。<b>父节点与组织编码不可改</b>：上级侧没有"移动节点"语义，
 * 改父节点或编码只会表现为旧节点消失、新节点出现，已绑定的通道随之变成孤儿节点。
 * 需要调整层级时，删除后重建并把通道重新绑到新组织上。
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
}
