package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.util.List;

import lombok.Data;

/**
 * 通道批量推送请求（Manager 入参）：一批本地通道 × 一批上级平台。
 *
 * <p><b>目标通道有两种给法，二选一</b>：
 * <ul>
 *   <li>{@link #channels}：列表里显式勾选的通道，适合「就推这几路」</li>
 *   <li>{@link #orgId} + {@link #includeChildren}：按组织解析，适合「这个组织下的通道全推上去」</li>
 * </ul>
 * 两者都给时以 {@code orgId} 为准——组织范围是更大的动作，静默降级成勾选集会让用户以为整个组织都推了。
 *
 * @author luna
 */
@Data
public class CascadeChannelBatchPublishDTO {

    /**
     * 目标上级平台编码集合（可多选）。空白项会被忽略，重复项去重。
     */
    private List<String>    platformIds;

    /**
     * 显式勾选的本地通道。
     */
    private List<ChannelRef> channels;

    /**
     * 按组织推送时的组织主键；{@code 0} 表示「未归组织」的设备。
     */
    private Long            orgId;

    /**
     * 按组织推送时是否含下级组织（默认 {@code true}，与设备页组织树口径一致）。
     */
    private Boolean         includeChildren;

    /**
     * 单条本地通道标识：只看本地资产，不含任何平台维度。
     */
    @Data
    public static class ChannelRef {

        /** 本地设备国标 ID；留空时按通道编码回查 */
        private String localDeviceId;

        /** 本地通道国标 ID（必填） */
        private String localChannelId;
    }
}
