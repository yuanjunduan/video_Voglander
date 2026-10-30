package io.github.lunasaw.voglander.web.api.config.vo;

import java.io.Serializable;

import io.github.lunasaw.voglander.manager.domaon.dto.sys.SysConfigDTO;
import lombok.Data;

/**
 * 系统配置项 VO（Web 出参，时间字段统一 Unix 毫秒）。
 *
 * @author luna
 */
@Data
public class SysConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 配置键 */
    private String  configKey;

    /** 当前生效值（覆盖值 → yml → 默认值） */
    private String  configValue;

    /** 代码内默认值 */
    private String  defaultValue;

    /** 分组名 */
    private String  configGroup;

    /** STRING / INT / LONG / BOOLEAN */
    private String  valueType;

    /** 说明：改它会发生什么 */
    private String  description;

    /** 1=可热改 0=需重启 */
    private Integer editable;

    /** 数值型下限 */
    private Long    min;

    /** 数值型上限 */
    private Long    max;

    /** 是否有库内覆盖值 */
    private Boolean overridden;

    /** 覆盖值最后修改时间（unix 时间戳，毫秒级） */
    private Long    updateTime;

    /**
     * DTO → VO 转换。
     *
     * @param dto 配置项 DTO
     * @return VO；入参为空返回 null
     */
    public static SysConfigVO convertVO(SysConfigDTO dto) {
        if (dto == null) {
            return null;
        }
        SysConfigVO vo = new SysConfigVO();
        vo.setConfigKey(dto.getConfigKey());
        vo.setConfigValue(dto.getConfigValue());
        vo.setDefaultValue(dto.getDefaultValue());
        vo.setConfigGroup(dto.getConfigGroup());
        vo.setValueType(dto.getValueType());
        vo.setDescription(dto.getDescription());
        vo.setEditable(dto.getEditable());
        vo.setMin(dto.getMin());
        vo.setMax(dto.getMax());
        vo.setOverridden(dto.getOverridden());
        vo.setUpdateTime(dto.updateTimeToEpochMilli());
        return vo;
    }
}
