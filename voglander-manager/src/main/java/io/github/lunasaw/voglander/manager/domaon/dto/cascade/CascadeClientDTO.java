package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.Data;

/**
 * 级联本端客户端 DTO
 *
 * <p>一条记录代表「本端可以以哪个身份向上级注册」：编码 + 对外通告 IP。
 * 上级平台通过 {@code clientRefId} 引用它，取代过去在平台表里逐条手填三个字段。
 */
@Data
public class CascadeClientDTO {
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 本端平台编码（20 位国标 ID）：创建后不可改，全局唯一 */
    private String        clientId;
    /** 便于本地辨识的名称；空=未命名，前端按 name || clientId 回落 */
    private String        clientName;
    /** Contact 头对外通告的 IP；空=未填写 */
    private String        clientIp;
    /** 1=新增平台时的默认选项；全局最多一行 */
    private Integer       isDefault;
    /** 1-启用 0-禁用 */
    private Integer       enabled;
    private String        remark;
    private String        extend;

    /** 是否默认客户端；空值视为否。 */
    public boolean defaultClient() {
        return isDefault != null && isDefault == 1;
    }

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
