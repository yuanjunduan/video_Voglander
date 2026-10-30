package io.github.lunasaw.voglander.repository.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 级联上报通道表
 */
@Data
@TableName("tb_cascade_channel")
public class CascadeChannelDO implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private String        platformId;
    private String        localDeviceId;
    private String        localChannelId;

    /** 上报给上级平台时使用的通道ID，默认同 localChannelId */
    private String        cascadeChannelId;
    private String        cascadeName;
    /** 所属上级组织编码（tb_cascade_org.cascade_org_id），空=直接挂平台根节点 */
    private String        cascadeOrgId;
    /** 1-上报 0-不上报 */
    private Integer       enabled;

    /** 厂商（Hik / Dahua / Jovision）；空=按设备通道数据自动派生 */
    private String        manufacturer;
    /** 设备真实型号；空=自动派生 */
    private String        model;
    /** 设备安装地址；空=自动派生（GB28181 目录 Address，取不到时回落固定值） */
    private String        address;
    /** 设备 IP；空=自动派生（目录 IPAddress → 源设备 IP → tb_device.ip） */
    private String        ipAddress;
    /** 摄像机类型 1球机 2半球 3枪机；空或 0=自动派生（缺省按枪机 3） */
    private Integer       ptzType;
}
