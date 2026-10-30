package io.github.lunasaw.voglander.manager.domaon.dto.sys;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.Data;

/**
 * 系统配置项 DTO：键定义（来自 {@code SysConfigCatalog}）+ 当前生效值。
 *
 * <p>{@code configValue} 是<b>生效值</b>而不是库里的裸值：有覆盖值用覆盖值，
 * 否则用 yml 显式配置或代码内默认值。页面看到的就是程序实际在用的值。
 *
 * @author luna
 */
@Data
public class SysConfigDTO {

    /** 配置键 */
    private String        configKey;
    /** 当前生效值 */
    private String        configValue;
    /** 代码内默认值（"恢复默认"后回到这里，未配 yml 时等于生效值） */
    private String        defaultValue;
    /** 分组名 */
    private String        configGroup;
    /** STRING / INT / LONG / BOOLEAN */
    private String        valueType;
    /** 说明：改它会发生什么 */
    private String        description;
    /** 1=可热改 0=需重启 */
    private Integer       editable;
    /** 数值型下限，非数值型为空 */
    private Long          min;
    /** 数值型上限，非数值型为空 */
    private Long          max;
    /** 库里是否有覆盖值（false 时生效值来自 yml 或默认值） */
    private Boolean       overridden;
    /** 覆盖值最后修改时间 */
    private LocalDateTime updateTime;

    /**
     * 修改时间转 Unix 毫秒时间戳（Web VO 出参规约）。
     *
     * @return unix 时间戳（毫秒），updateTime 为空返回 null
     */
    public Long updateTimeToEpochMilli() {
        return updateTime != null ? updateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }
}
