package io.github.lunasaw.voglander.repository.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 系统配置覆盖值表。
 *
 * <p>本表<b>只存覆盖值</b>：配置键的定义（分组/默认值/类型/说明/是否可热改）集中在
 * {@code SysConfigCatalog}，表里没有记录就表示"用代码内默认值（含 yml 覆盖）"。
 * 这样新增一个可配置项不需要插数据、老库也不会因为缺记录而页面空白。
 *
 * @author luna
 */
@Data
@TableName("tb_sys_config")
public class SysConfigDO implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 配置键（全表唯一，与 yml 属性名一致） */
    private String        configKey;
    /** 配置值（空=用代码内默认值） */
    private String        configValue;
    /** 配置分组，页面按组展示 */
    private String        configGroup;
    /** STRING / INT / LONG / BOOLEAN */
    private String        valueType;
    /** 说明（键定义里的说明为准，本列仅供人工排查） */
    private String        description;
    /** 1=可热改 0=需重启 */
    private Integer       editable;
}
