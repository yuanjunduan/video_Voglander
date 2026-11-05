package io.github.lunasaw.voglander.web.api.channel.req;

import lombok.Data;

import java.io.Serializable;

/**
 * 设备通道查询请求对象
 * 
 * @author luna
 * @date 2024/01/31
 */
@Data
public class DeviceChannelQueryReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 数据库主键ID
     */
    private Long              id;

    /**
     * 通道Id
     */
    private String            channelId;

    /**
     * 设备ID
     */
    private String            deviceId;

    /**
     * 通道名称
     */
    private String            name;

    /**
     * 状态 1在线 0离线
     */
    private Integer           status;

    /**
     * 本地启用 1启用 / 0停用（与在线状态无关）
     */
    private Integer           enabled;

    /**
     * 所属级联组织 {@code tb_cascade_org.id}；{@code 0} 表示"未归组织"
     *
     * <p>口径按<b>所属设备</b>的组织算：通道行上的 org_id 已停写（恒 NULL），
     * 直接拿它过滤会什么都查不到。
     */
    private Long              orgId;

    /**
     * 按组织筛选时是否包含下级组织（默认否）
     */
    private Boolean           includeChildren;
}