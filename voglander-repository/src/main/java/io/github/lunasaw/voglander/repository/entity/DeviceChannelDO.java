package io.github.lunasaw.voglander.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @TableName tb_device_channel
 */
@TableName(value = "tb_device_channel")
@Data
public class DeviceChannelDO implements Serializable {
    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
    /**
     *
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 创建时间
     */
    private LocalDateTime     createTime;
    /**
     * 修改时间
     */
    private LocalDateTime     updateTime;
    /**
     * 状态 1在线 0离线
     */
    private Integer status;
    /**
     * 通道Id
     */
    private String channelId;
    /**
     * 设备ID
     */
    private String deviceId;
    /**
     * 通道名称
     */
    private String name;

    /**
     * 所属级联组织（{@code tb_cascade_org.id}），空 = 跟随设备归属。
     *
     * <p>GB28181 的业务分组/虚拟组织允许摄像机直接挂在组织结构下，不必经过设备层；
     * 自带归属才能表达“设备在 A 组织、它的某一路通道归 B 业务分组”。
     */
    private Long orgId;

    /**
     * 本地启用：1 启用、0 停用。
     *
     * <p>与 {@link #status} 是两件事：{@code status} 只影响报文里的 {@code Status} 字段，
     * {@code enabled=0} 才让通道从所有上级平台消失。
     */
    private Integer enabled;

    /**
     * 扩展字段
     */
    private String extend;

    /**
     * 通道最近一次被目录/会话感知的时间（1.0.4）
     */
    private LocalDateTime lastSeenTime;

    /**
     * 当前 status 的来源：CATALOG / OFFLINE_CASCADE / SESSION / MANUAL / MISSING（1.0.4）
     */
    private String statusSource;

    /**
     * 连续目录响应中未出现的次数（1.0.4 Stage 4）
     */
    private Integer missingCount;

    /**
     * 通道「自己的码」（1.10.3）：上级看到的是发布行的 {@code publish_code}，这里是本地的码。
     *
     * <p>编码规则接管后必须落库固化——现算会漂：号池一变，同一路通道下次就换号。
     * 空=尚未生成，读取侧回落到 {@link #channelId}。
     */
    private String code;
}