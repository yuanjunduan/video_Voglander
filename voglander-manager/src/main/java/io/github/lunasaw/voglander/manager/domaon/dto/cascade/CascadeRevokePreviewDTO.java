package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.util.ArrayList;
import java.util.List;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.RevokePolicy;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import lombok.Data;

/**
 * 级联组织「取消同步」的影响面：这次操作会把哪些节点从上级目录里摘掉。
 *
 * <p><b>为什么要单独一个预览</b>：取消同步不是改一个布尔字段——整棵子树连同其下通道都会
 * 从上级目录消失。用户在下决定之前必须看到「会移除 2 个组织、12 条通道」，
 * 而不是点完开关才发现一片区域不见了。
 *
 * <p>两个清单都已按协议要求的顺序排好：{@code orgs} 是「叶子 → 根」后序，
 * {@code channels} 排在最前（先删通道，再删它所在的组织）。
 *
 * @author luna
 */
@Data
public class CascadeRevokePreviewDTO {

    /** 所属上级平台编码 */
    private String                               platformId;
    /** 被取消同步的组织编码 */
    private String                               cascadeOrgId;
    /** 被取消同步的组织名称 */
    private String                               orgName;
    /** 将采用的通道处置策略：CASCADE / REROUTE */
    private String                               revokePolicy;

    /** 会被移除的组织节点（后序：叶子在前） */
    private List<LocalCascadeCatalogChangeEvent.Node> orgs     = new ArrayList<>();
    /**
     * 受影响的通道节点：{@code CASCADE} 时会被一并移除，{@code REROUTE} 时改挂域节点。
     *
     * <p>两种策略下都要列出来——用户需要知道「有多少条通道会被动到」，
     * 哪怕它们不会从上级目录消失。
     */
    private List<LocalCascadeCatalogChangeEvent.Node> channels = new ArrayList<>();

    /** 受影响组织数 */
    public int orgCount() {
        return orgs == null ? 0 : orgs.size();
    }

    /** 受影响通道数：CASCADE 会被删，REROUTE 会被改挂 */
    public int channelCount() {
        return channels == null ? 0 : channels.size();
    }

    /**
     * 实际会发出的删除报文条数。
     *
     * <p>{@code REROUTE} 只删组织，通道走改挂；若把通道也算进删除数，
     * 预览里显示的报文量会比实际多一倍，用户会以为操作量比真正的大。
     */
    public int deleteCount() {
        return RevokePolicy.REROUTE.equals(revokePolicy) ? orgCount() : orgCount() + channelCount();
    }
}
