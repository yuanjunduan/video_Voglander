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
     * 直属通道数（仅树形出参填充）
     */
    private Integer           channelCount;

    /**
     * 子组织（仅树形出参填充）
     */
    private List<CascadeOrgVO> children;
}
