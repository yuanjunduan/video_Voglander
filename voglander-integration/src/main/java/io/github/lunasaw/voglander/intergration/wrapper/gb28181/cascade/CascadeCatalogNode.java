package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

/**
 * 目录树上的一个节点（域 / 组织 / 通道）。
 *
 * <p>上级按 {@code parentId} 建树，因此每个字段都是必需项：缺 {@code parentId} 的条目
 * 会被当成孤儿节点丢弃（SIP 层照样回 200 OK，界面里就是不出现）。
 *
 * <p>{@code ipAddress} 与 {@code ptzType} 只对通道节点有意义（上级靠后者区分球机/半球/枪机，
 * 为空容易被当成非视频通道）；域与组织节点传 null。
 *
 * @param kind 节点种类，仅用于日志与问题定位，不参与报文
 */
public record CascadeCatalogNode(String deviceId, String name, String parentId, String owner,
    String manufacturer, String model, String civilCode, String address,
    String ipAddress, Integer ptzType, String status, Kind kind) {

    /** 节点种类。 */
    public enum Kind {
        /** 域节点：本级平台自身，整棵树的根 */
        DOMAIN,
        /** 组织节点：设备或通道的父节点 */
        ORG,
        /** 设备节点：只在平台开了设备层时出现，容纳该设备的通道 */
        DEVICE,
        /** 通道节点：目录树的叶子 */
        CHANNEL
    }

    /** 是否为根节点（域节点没有父节点）。 */
    public boolean isRoot() {
        return kind == Kind.DOMAIN || parentId == null;
    }

    /**
     * GB28181 的 {@code Parental}：0=叶子（监控通道），1=有子节点的目录节点。
     *
     * <p>只有通道是叶子。早期实现一律写 {@code 0}，开了设备层后设备下挂着通道还会被当成叶子，
     * 上级把它当监控点去点播，自然拉不到流。
     */
    public int parental() {
        return kind == Kind.CHANNEL ? 0 : 1;
    }
}
