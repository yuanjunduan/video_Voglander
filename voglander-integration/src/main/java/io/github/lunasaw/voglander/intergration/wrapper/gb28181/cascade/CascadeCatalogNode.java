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
        /** 组织节点：通道的父节点 */
        ORG,
        /** 通道节点：目录树的叶子 */
        CHANNEL
    }

    /** 是否为根节点（域节点没有父节点）。 */
    public boolean isRoot() {
        return kind == Kind.DOMAIN || parentId == null;
    }
}
