package io.github.lunasaw.voglander.common.event;

import java.util.List;

/**
 * 级联目录<b>结构</b>变更事件（组织增删改、通道换组织）。
 *
 * <p><b>与 {@link LocalChannelChangeEvent} 的分工</b>：
 * <ul>
 *   <li>通道上线/下线只影响一个节点的状态，用 {@code LocalChannelChangeEvent} 推增量条目，开销小；</li>
 *   <li>组织结构变化会改变父子关系（新增组织、停用组织、通道换挂组织），上级按
 *       {@code Item/ParentID} 建树、没有"移动节点"语义，增量条目无法表达这种变化，
 *       因此整棵树重推一次快照。</li>
 * </ul>
 * 结构变化是低频操作，重推整树的开销可以接受；用增量方式反而会留下孤儿节点。
 *
 * @author luna
 */
public class LocalCascadeCatalogChangeEvent {

    /** 所属上级平台编码 */
    private final String platformId;
    /** 变更范围：ORG / CHANNEL，仅用于日志定位 */
    private final String scope;
    /** 变更对象编码（组织编码或级联通道编码），仅用于日志定位 */
    private final String cascadeId;
    /** 变更类型：ADD / UPDATE / DEL */
    private final String event;
    /**
     * 批量节点清单（已按「叶子 → 根」后序排好），仅 DEL 场景使用。
     *
     * <p>组织「取消同步」会一次性把整棵子树从上级目录移除，节点数不定；
     * 元素为空时调用方按单个 {@link #cascadeId} 处理。
     */
    private final List<Node> nodes;

    public LocalCascadeCatalogChangeEvent(String platformId, String scope, String cascadeId, String event) {
        this(platformId, scope, cascadeId, event, null);
    }

    public LocalCascadeCatalogChangeEvent(String platformId, String scope, String cascadeId, String event,
        List<Node> nodes) {
        this.platformId = platformId;
        this.scope = scope;
        this.cascadeId = cascadeId;
        this.event = event;
        this.nodes = nodes;
    }

    /**
     * 需要通知上级的单个目录节点。
     *
     * @param cascadeId  上级眼里的节点编码（组织编码 / 级联通道编码）
     * @param name       节点名称，可空
     * @param parentCode 父节点编码，可空；带上上级才能确定删哪棵树
     */
    public record Node(String cascadeId, String name, String parentCode) {
    }

    public String getPlatformId() {
        return platformId;
    }

    public String getScope() {
        return scope;
    }

    public String getCascadeId() {
        return cascadeId;
    }

    public String getEvent() {
        return event;
    }

    public List<Node> getNodes() {
        return nodes;
    }
}
