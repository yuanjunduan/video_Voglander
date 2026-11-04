package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 级联组织 VO（Web 出参，时间字段统一 Unix 毫秒）。
 *
 * <p><b>没有 {@code platformId}</b>：组织是本地资产，全库只有一棵树；同一个组织推给哪几个上级
 * 由 {@link #publishedPlatformIds} 表达。页面「某平台的组织树」= 切换到该平台的发布视图，
 * 而不是换一套组织数据。
 *
 * <p>树形接口返回的每个节点带 {@code children} 与 {@code channelCount}；
 * {@code channelCount} 只统计<b>直属</b>通道，便于前端提示"删除前需先迁走通道"。
 *
 * @author luna
 */
@Data
public class CascadeOrgVO implements Serializable {

    private static final long   serialVersionUID = 1L;

    private Long                id;

    /**
     * 创建时间（unix 时间戳，毫秒级）
     */
    private Long                createTime;

    /**
     * 更新时间（unix 时间戳，毫秒级）
     */
    private Long                updateTime;

    /**
     * 父组织主键，空表示根组织（直接挂在域节点下）
     */
    private Long                parentId;

    /**
     * 物化路径（只读）：根到自身的编码链，形如 {@code /A/B/}。
     *
     * <p>前端可用它直接算层级（斜杠数 - 1）与面包屑，不必自己递归父链。
     */
    private String              orgPath;

    /**
     * 本地组织编码（国标 20 位，后端生成，不可变更）
     */
    private String              orgCode;

    /**
     * 组织名称
     */
    private String              orgName;

    /**
     * 同级排序，越小越靠前
     */
    private Integer             sortOrder;

    /**
     * 本地是否启用：0 表示这个组织不用了，其下设备与通道从<b>所有</b>上级目录里消失。
     *
     * <p>与「不推给某个平台」是两件事：后者是发布行 {@code enabled=0}，组织在本地照常可用。
     */
    private Integer             enabled;

    /**
     * 本地是否真的可达：自身与整条祖先链都启用。
     *
     * <p>与 {@code enabled} 不是一回事：祖先停用后子孙的开关仍是 1，但它们已经随祖先从
     * 所有平台的目录里消失。页面只看 {@code enabled} 就会「显示启用、上级其实看不到」。
     */
    private Boolean             effective;

    /**
     * 被哪一级组织连带停用：自身停用就是自己的名称，否则是链上那级祖先；
     * 生效时为 null。前端用它把「为什么上级看不到」说清楚。
     */
    private String              blockedByOrgName;

    /**
     * 已发布到的上级平台编码，含<b>已停用</b>的发布行。
     *
     * <p>含停用行是刻意的：页面要能区分「配了但没开」与「根本没配」——
     * 前者只要打开开关就生效，后者需要先勾选平台。
     */
    private List<String>        publishedPlatformIds = new ArrayList<>();

    /* ---------------- 编码段规则（预留：{@code codeRuleEnabled=0} 时由后端默认生成） ---------------- */

    /** 省编码（2 位） */
    private String              codeProvince;

    /** 市编码（2 位） */
    private String              codeCity;

    /** 县区编码（4 位） */
    private String              codeCounty;

    /** 行业编码（2 位） */
    private String              codeIndustry;

    /** 类型编码（3 位，215 业务分组 / 216 虚拟组织） */
    private String              codeType;

    /** 网络标识（1 位） */
    private String              codeNetwork;

    /** 起始序号（6 位） */
    private Integer             codeStartSeq;

    /** 0=未启用（后端默认生成） 1=按七段规则生成 */
    private Integer             codeRuleEnabled;

    /**
     * 直属通道数（仅树形出参填充）
     */
    private Integer             channelCount;

    /**
     * 子组织（仅树形出参填充）
     */
    private List<CascadeOrgVO>  children;
}
