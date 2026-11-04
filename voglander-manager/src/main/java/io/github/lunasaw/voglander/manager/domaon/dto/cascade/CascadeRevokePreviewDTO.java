package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.RevokePolicy;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import lombok.Data;

/**
 * 级联组织「停用」的影响面：这次操作会把哪些节点、从哪些上级的目录里摘掉。
 *
 * <p><b>为什么要单独一个预览</b>：停用不是改一个布尔字段——整棵子树连同其下通道都会
 * 从上级目录消失。用户在下决定之前必须看到「会移除 2 个组织、12 条通道」，
 * 而不是点完开关才发现一片区域不见了。
 *
 * <p><b>为什么要按平台给策略</b>：同一组织可能推给多个上级，各平台的通道处置策略独立
 * （{@code tb_cascade_publish.revoke_policy}）：CASCADE 连带删通道、REROUTE 只删组织。
 * 因此报文条数必须逐平台累加，不能用一个全局策略去估算。
 *
 * <p>两个清单都已按协议要求的顺序排好：{@code orgs} 是「叶子 → 根」后序，
 * {@code channels} 排在最前（先删通道，再删它所在的组织）。
 *
 * @author luna
 */
@Data
public class CascadeRevokePreviewDTO {

    /** 被停用的组织主键 */
    private Long                                      orgId;
    /** 被停用的组织编码 */
    private String                                    orgCode;
    /** 被停用的组织名称 */
    private String                                    orgName;

    /** 该组织已发布到的上级平台（含停用的发布行）；为空表示停用不会有任何报文 */
    private List<String>                              publishedPlatformIds = new ArrayList<>();
    /** 各平台的通道处置策略：platformId → CASCADE / REROUTE */
    private Map<String, String>                       revokePolicyByPlatform = new LinkedHashMap<>();

    /** 会被移除的组织节点（后序：叶子在前） */
    private List<LocalCascadeCatalogChangeEvent.Node> orgs     = new ArrayList<>();
    /**
     * 受影响的通道节点（各平台并集、按编码去重）。
     *
     * <p>两种策略下都要列出来——用户需要知道「有多少条通道会被动到」，
     * 哪怕 REROUTE 下它们不会从上级目录消失。
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
     * 实际会发出的删除报文条数：逐个平台按它自己的策略累加。
     *
     * <p>把所有平台都按 CASCADE 估会虚高，全按 REROUTE 估会漏报——
     * 用户按这个数字判断操作量，口径必须与真实推送一致。
     */
    public int deleteCount() {
        int total = 0;
        for (String policy : revokePolicyByPlatform.values()) {
            total += RevokePolicy.REROUTE.equals(RevokePolicy.normalize(policy))
                ? orgCount() : orgCount() + channelCount();
        }
        return total;
    }
}
