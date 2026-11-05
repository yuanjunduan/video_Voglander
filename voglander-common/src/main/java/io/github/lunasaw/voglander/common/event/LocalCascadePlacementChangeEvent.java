package io.github.lunasaw.voglander.common.event;

/**
 * 本地设备 / 通道的<b>归属组织</b>发生变更。
 *
 * <p><b>为什么要有这个事件</b>：上级目录是增量 upsert 语义——节点「这次没出现在快照里」
 * 不会被删除（见 {@link LocalCascadeCatalogChangeEvent} 的说明）。改了归属组织以后，
 * 快照会把节点推到新位置，旧位置却原封不动地留在上级目录里，于是同一个通道挂了两处，
 * 两处都能点播。要消掉旧位置，必须显式发一次 {@code Event=DEL}，而这一步只有知道
 * 「原来挂在哪」才做得对。
 *
 * <p><b>为什么事件里只带归属性，不带旧父节点</b>：父节点要把「本地组织编码 → 该平台的
 * 发布编码」映射一遍才知道，那是发布层与目录组装的知识。设备 / 通道管理器只认识
 * {@code org_id}，把解析留在监听器里，底层管理器就不必反向依赖级联那一套。
 *
 * <p><b>为什么必须由发送方限定「真的变了」才发</b>：{@code DeviceManager.update} 是心跳、
 * 入站响应回填等高频路径的公共出口，每个请求都去算一遍目录位置会把闲事做成开销。
 *
 * @author luna
 */
public class LocalCascadePlacementChangeEvent {

    /** 变更范围：{@code DEVICE} / {@code CHANNEL}，仅用于日志定位 */
    private final String scope;
    /** 变更对象的本地业务键：设备国标 ID，或通道的 {@code deviceId:channelId} */
    private final String objectKey;
    /** 变更前的组织主键；{@code null} 表示原本未归组织 */
    private final Long   previousOrgId;
    /** 变更后的组织主键；{@code null} 表示清空归属（退回未归组织） */
    private final Long   currentOrgId;

    public LocalCascadePlacementChangeEvent(String scope, String objectKey, Long previousOrgId,
        Long currentOrgId) {
        this.scope = scope;
        this.objectKey = objectKey;
        this.previousOrgId = previousOrgId;
        this.currentOrgId = currentOrgId;
    }

    public String getScope() {
        return scope;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Long getPreviousOrgId() {
        return previousOrgId;
    }

    public Long getCurrentOrgId() {
        return currentOrgId;
    }
}
