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
     * 所属级联组织编码（{@code tb_cascade_org.cascade_org_id}）。
     *
     * <p>为空表示通道直接挂在平台根节点下——这是兜底而非错误：组织被删或被停用时，
     * 通道仍应出现在上级目录里，只是层级变浅。
     */
    private String        cascadeOrgId;
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
