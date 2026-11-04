package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 级联组织「停用」影响面预览 VO。
 *
 * <p>停用不是改一个布尔字段：整棵子树连同其下通道都会从上级目录消失。用户在下决定之前
 * 必须看到「会移除 2 个组织、12 条通道」，而不是点完开关才发现一片区域不见了。
 *
 * <p><b>为什么是逐平台一份投递条件</b>：同一组织可能推给多个上级，各平台在线状态、
 * 目录订阅各自独立，通道处置策略也各配一套。给一个汇总的 {@code deliverable} 会让用户
 * 误以为「全都发得出去」——实际可能只有一个平台在线，另一个的 DEL 直接丢掉。
 *
 * <p>{@code warnings} 是<b>投递条件</b>而不是操作结果：上级离线或没有生效的目录订阅时，
 * DEL 报文发出去没人接，本地却已经记为停用，两边状态会永久错位。这类风险必须在下决定前提示。
 *
 * @author luna
 */
@Data
public class CascadeRevokePreviewVO implements Serializable {

    private static final long   serialVersionUID = 1L;

    /**
     * 被停用的组织主键
     */
    private Long                orgId;

    /**
     * 被停用的组织本地编码
     */
    private String              orgCode;

    /**
     * 被停用的组织名称
     */
    private String              orgName;

    /**
     * 会被移除的组织节点（后序：叶子在前）
     */
    private List<NodeVO>        orgs             = new ArrayList<>();

    /**
     * 受影响的通道节点（各平台并集）：各平台策略为 CASCADE 时被移除，REROUTE 时改挂域节点。
     */
    private List<NodeVO>        channels         = new ArrayList<>();

    /**
     * 受影响组织数
     */
    private Integer             orgCount;

    /**
     * 受影响通道数
     */
    private Integer             channelCount;

    /**
     * 实际会发出的删除报文条数：逐平台按它自己的策略累加
     */
    private Integer             deleteCount;

    /**
     * 逐平台的投递条件；该组织未发布到任何平台时为空列表（也不会有任何报文）
     */
    private List<DeliveryVO>    deliveries       = new ArrayList<>();

    /**
     * 风险提示汇总（各平台的提示已带平台编码前缀，恒非空，无风险时为空列表）
     */
    private List<String>        warnings         = new ArrayList<>();

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

    /**
     * 某一个平台的投递条件与策略。
     */
    @Data
    public static class DeliveryVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 上级平台编码 */
        private String            platformId;

        /** 该平台的通道处置策略：CASCADE / REROUTE */
        private String            revokePolicy;

        /** 上级是否在线（注册状态 ONLINE） */
        private Boolean           platformOnline;

        /** 上级是否持有生效的目录订阅 */
        private Boolean           catalogSubscribed;

        /** 报文能否送达：在线且已订阅。为 false 时操作仍可执行，但删除报文会丢失 */
        private Boolean           deliverable;

        /** 该平台的风险提示 */
        private List<String>      warnings = new ArrayList<>();
    }
}
