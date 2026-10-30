package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

/**
 * 一条级联通道最终上报给上级的目录项元数据。
 *
 * <p>已合成完毕的值：覆盖值优先，缺失项已按设备通道数据派生并填好兜底，
 * 下游（目录组装、页面展示）直接使用，不再判断来源。
 *
 * @param manufacturer 厂商（Hik / Dahua / Jovision），恒非空
 * @param model        设备型号，取不到为 null
 * @param address      安装地址，兜底为固定值而非 null
 * @param ipAddress    设备 IP，取不到为 null（报文里整段省略）
 * @param ptzType      摄像机类型 1球机 2半球 3枪机，恒非空
 * @author luna
 */
public record CascadeChannelMeta(String manufacturer, String model, String address, String ipAddress, Integer ptzType) {

    /** 解析器兜底用的缺省值：厂商按 Hik、地址不空、类型按枪机；型号与 IP 允许为空。 */
    public static final CascadeChannelMeta DEFAULT = new CascadeChannelMeta(
        CascadeChannelMetaResolver.DEFAULT_MANUFACTURER, null, CascadeChannelMetaResolver.DEFAULT_ADDRESS, null,
        CascadeChannelMetaResolver.DEFAULT_PTZ_TYPE);
}
