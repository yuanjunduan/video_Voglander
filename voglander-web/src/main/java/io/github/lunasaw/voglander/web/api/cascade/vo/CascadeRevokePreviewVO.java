package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 级联「取消同步」影响面预览 VO。
 *
 * <p>取消同步不是改一个布尔字段：整棵子树连同其下通道都会从上级目录消失。用户在下决定之前
 * 必须看到「会移除 2 个组织、12 条通道」，而不是点完开关才发现一片区域不见了。
 *
 * <p>{@code warnings} 是<b>投递条件</b>而不是操作结果：上级离线或没有生效的目录订阅时，
 * DEL 报文发出去没人接，本地却已经记为停报，两边状态会永久错位。这类风险必须在下决定前提示，
 * 而不是等运维发现海康目录里还留着节点再来排查。
 *
 * @author luna
 */
@Data
public class CascadeRevokePreviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属上级平台国标 ID
     */
    private String       platformId;

    /**
     * 被取消同步的组织编码
     */
    private String       cascadeOrgId;

    /**
     * 被取消同步的组织名称
     */
    private String       orgName;

    /**
     * 本次将采用的通道处置策略：CASCADE / REROUTE
     */
    private String       revokePolicy;

    /**
     * 会被移除的组织节点（后序：叶子在前）
     */
    private List<NodeVO> orgs     = new ArrayList<>();

    /**
     * 受影响的通道节点：CASCADE 会被一并移除，REROUTE 会改挂域节点。
     */
    private List<NodeVO> channels = new ArrayList<>();

    /**
     * 受影响组织数
     */
    private Integer      orgCount;

    /**
     * 受影响通道数：CASCADE 会被删，REROUTE 会被改挂
     */
    private Integer      channelCount;

    /**
     * 实际会发出的删除报文条数（REROUTE 只删组织）
     */
    private Integer      deleteCount;

    /**
     * 上级是否在线（注册状态 ONLINE）
     */
    private Boolean      platformOnline;

    /**
     * 上级是否持有生效的目录订阅
     */
    private Boolean      catalogSubscribed;

    /**
     * 报文能否送达：在线且已订阅。为 false 时操作仍可执行，但删除报文会丢失
     */
    private Boolean      deliverable;

    /**
     * 风险提示（恒非空，无风险时为空列表）
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 单个目录节点。
     *
     * <p>{@code parentId} 是上级眼里的父节点编码：删除报文要带它，上级才能定位到具体分支。
     */
    @Data
    public static class NodeVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 国标 20 位编码 */
        private String            cascadeId;

        /** 上报给上级的节点名称 */
        private String            name;

        /** 上级眼里的父节点编码 */
        private String            parentId;
    }
}