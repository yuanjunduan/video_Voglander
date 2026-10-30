package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 级联组织 VO（Web 出参，时间字段统一 Unix 毫秒）。
 *
 * <p>树形接口返回的每个节点带 {@code children} 与 {@code channelCount}；
 * {@code channelCount} 只统计<b>直属</b>通道，便于前端提示"删除前需先迁走通道"。
 *
 * @author luna
 */
@Data
public class CascadeOrgVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long              id;

    /**
     * 创建时间（unix 时间戳，毫秒级）
     */
    private Long              createTime;

    /**
     * 更新时间（unix 时间戳，毫秒级）
     */
    private Long              updateTime;

    /**
     * 所属上级平台国标 ID
     */
    private String            platformId;

    /**
     * 父组织主键，空表示根组织
     */
    private Long              parentOrgId;

    /**
     * 物化路径（只读）：根到自身的编码链，形如 {@code /A/B/}。
     *
     * <p>前端可用它直接算层级（斜杠数 - 1）与面包屑，不必自己递归父链。
     */
    private String            orgPath;

    /**
     * 上报给上级的组织编码（国标 20 位，后端生成，不可变更）
     */
    private String            cascadeOrgId;

    /**
     * 上报给上级的组织名称
     */
    private String            orgName;

    /**
     * 同级排序，越小越靠前
     */
    private Integer           sortOrder;

    /**
     * 是否上报 1上报 / 0不上报
     */
    private Integer           enabled;

    /**
     * 上级目录里是否真的能看到这个节点：自身与整条祖先链都在上报。
     *
     * <p>与 {@code enabled} 不是一回事：祖先停报后子孙的开关仍是 1，
     * 但它们早已随祖先被 DEL 从上级目录删掉。页面只看 {@code enabled} 就会
     * 「显示上报中、上级其实看不到」。
     */
    private Boolean           effective;

    /**
     * 被哪一级组织连带停报：自身停报就是自己的名称，否则是链上那级祖先；
     * 生效时为 null。前端用它把「上级不可见」的原因说清楚。
     */
    private String            blockedByOrgName;

    /**
     * 取消同步时其下通道的处置策略：CASCADE / REROUTE。
     *
     * <p>取消同步弹窗需要回显上次的选择，避免用户每次都重选。
     */
    private String            revokePolicy;

    /**
     * 直属通道数（仅树形出参填充）
     */
    private Integer           channelCount;

    /**
     * 子组织（仅树形出参填充）
     */
    private List<CascadeOrgVO> children;
}
