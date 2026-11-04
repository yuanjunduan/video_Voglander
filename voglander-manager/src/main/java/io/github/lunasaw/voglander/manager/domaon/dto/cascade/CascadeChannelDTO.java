package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.Data;

/**
 * 级联上报通道 DTO
 */
@Data
public class CascadeChannelDTO {
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String        platformId;
    private String        localDeviceId;
    private String        localChannelId;
    private String        cascadeChannelId;
    private String        cascadeName;
    /**
     * 通道<b>自己</b>归属的级联组织编码（{@code tb_device_channel.org_id} 对应的 {@code org_code}）。
     *
     * <p>为空表示跟随设备归属，不是错误：GB28181 的虚拟组织里，通道默认就挂在设备所在组织下。
     * 只有需要把一个通道直属到另一个业务分组时，才给通道自己设一个组织。
     *
     * <p><b>与发布层的关系</b>：本字段始终是<b>本地</b>编码，同一通道推给两个平台时值相同；
     * 某个平台要挂到别处，走 {@link #parentKey}，不要改这里。
     */
    private String        cascadeOrgId;

    /**
     * 设备归属的级联组织编码（{@code tb_device.org_id} 对应的 {@code org_code}）。
     *
     * <p>只在通道没有自己的 {@link #cascadeOrgId} 时生效：无设备层时它决定通道挂哪个组织，
     * 有设备层时它决定设备节点挂哪（设备不可见则通道也不可见）。
     */
    private String        deviceOrgCode;

    /**
     * 发布行上的父节点覆盖（{@code tb_cascade_publish.parent_key}），空=按本地结构推导。
     *
     * <p>这是「同一通道在不同上级挂不同父节点」的唯一入口。父节点可以是组织，也可以是设备（
     * 开了设备层时），所以它存的是业务键而不是组织编码。
     */
    private String        parentKey;

    private Integer       enabled;

    /**
     * 目录项元数据（1.0.12）：留空时由 {@code CascadeChannelMetaResolver} 按设备通道数据派生，
     * 填了则优先生效。上级（海康 iSecure）按 Manufacturer / Model / IPAddress / PTZType
     * 区分监控点类型与点播入口，仅靠 ParentID 建树是不够的。
     */
    private String        manufacturer;
    private String        model;
    private String        address;
    private String        ipAddress;
    /** 1球机 2半球 3枪机；空或 0 视为未指定 */
    private Integer       ptzType;

    /**
     * 创建时间转 Unix 毫秒时间戳（Web VO 出参规约）。
     *
     * @return unix 时间戳（毫秒），createTime 为空返回 null
     */
    public Long createTimeToEpochMilli() {
        return createTime != null ? createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }

    /**
     * 更新时间转 Unix 毫秒时间戳（Web VO 出参规约）。
     *
     * @return unix 时间戳（毫秒），updateTime 为空返回 null
     */
    public Long updateTimeToEpochMilli() {
        return updateTime != null ? updateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }
}
