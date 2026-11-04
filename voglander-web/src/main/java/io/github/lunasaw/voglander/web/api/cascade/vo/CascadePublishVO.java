package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.io.Serializable;

import lombok.Data;

/**
 * 级联发布配置 VO：某个本地对象在某个上级平台上的可见性与编码覆盖。
 *
 * <p>「空 = 继承本地值」是这条记录的读法：{@code publishCode} / {@code publishName} /
 * {@code sortOrder} 为 null 时一律回落本地资产的值，只有真正需要按平台区分的字段才有值。
 * 前端据此把「未覆盖」与「覆盖成空」区分开。
 *
 * @author luna
 */
@Data
public class CascadePublishVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发布记录主键 */
    private Long              id;

    /** 上级平台编码 */
    private String            platformId;

    /** 发布对象类型：ORG / DEVICE / CHANNEL */
    private String            objectType;

    /**
     * 本地对象业务键：ORG={@code org_code}、DEVICE={@code device_id}、CHANNEL={@code device_id:channel_id}。
     *
     * <p>不用自增主键：主键是存储细节，设备与通道被删重建后发布关系就成了孤儿。
     */
    private String            objectKey;

    /**
     * 在该平台下的父节点覆盖（父对象的 {@code publishCode}）；null=按本地结构推导。
     *
     * <p>同一个对象推给多个上级而要挂在不同父节点下时，差异全在这一列。
     */
    private String            parentKey;

    /** 上报编码；null=沿用本地编码 */
    private String            publishCode;

    /** 上报名称；null=沿用本地名称 */
    private String            publishName;

    /** 是否推给该平台 1是 0否 */
    private Integer           enabled;

    /** 该平台目录内的排序；null=沿用本地排序 */
    private Integer           sortOrder;

    /**
     * 该组织被停用时其下通道的处置策略：CASCADE（一并移除）/ REROUTE（上浮挂域节点）。
     *
     * <p>策略属于「发布关系」而不是组织本身：同一组织推给两个上级，可以一边连带删通道、
     * 一边让通道上浮。非 ORG 类型的发布行为 null。
     */
    private String            revokePolicy;
}
