package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 级联通道批量推送请求（Web 入参）：一批本地通道 × 一批上级平台。
 *
 * <p>与 {@link CascadeChannelBatchBindReq} 的区别：那个只能「一次一个平台 + 一堆通道编码」，
 * 本请求把平台也变成集合，并多了「按组织取通道」的来源——就是「把这个组织下的通道全推上去」。
 *
 * @author luna
 */
@Data
public class CascadeChannelBatchPublishReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标上级平台国标 ID 集合（必填，可多选）。
     */
    private List<String>    platformIds;

    /**
     * 显式勾选的本地通道；给了 {@link #orgId} 时本字段被忽略。
     */
    private List<ChannelItem> channels;

    /**
     * 按组织推送：该组织下所有设备的通道。
     *
     * <p>{@code 0} 表示「未归组织」的设备（SIP 自动注册的设备是合法状态），不是错误值。
     */
    private Long            orgId;

    /**
     * 按组织推送时是否含下级组织；不传按 {@code true} 处理，与设备页组织树口径一致。
     */
    private Boolean         includeChildren;

    /**
     * 单条本地通道。
     */
    @Data
    public static class ChannelItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 本地设备国标 ID；留空时按通道编码回查（国标通道 ID 全局唯一）。
         */
        private String localDeviceId;

        /**
         * 本地通道国标 ID（必填）。
         */
        private String localChannelId;
    }
}
