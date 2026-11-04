package io.github.lunasaw.voglander.repository.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 级联发布配置表：每个上级平台一套可见性视图。
 *
 * <p><b>只存差异</b>——本表的 {@code publishCode} / {@code publishName} / {@code sortOrder} /
 * 目录元数据列全部允许为空，空即「继承本地资产的值」。树形结构不在这里：
 * 组织按 {@code tb_cascade_org.parent_id}、设备按 {@code tb_device.org_id}、
 * 通道按 {@code tb_device_channel.device_id}，本表只回答「这个节点在该平台看不看得到、用什么码」。
 *
 * <p>这张表是三层的枢纽：同一台设备推给海康与大华，就是两行互不影响的记录；
 * 本地移动组织、改名称，不需要逐个平台跟着改。
 */
@Data
@TableName("tb_cascade_publish")
public class CascadePublishDO implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 上级平台编码 */
    private String        platformId;
    /** 发布对象类型：ORG / DEVICE / CHANNEL */
    private String        objectType;

    /**
     * 对象业务键（不是自增主键）：ORG=org_code、DEVICE=device_id、CHANNEL=device_id:channel_id。
     *
     * <p><b>为什么不用 id</b>：id 是存储细节——设备/通道被物理删重建后 id 会变，发布关系就成孤儿；
     * 上级点播反查也要拿本地通道标识，用业务键一次查询即可，不必二次回表。
     * 三类业务键在本地都已有唯一索引（uk_cascade_org_code / uk_device / idx_channel_device）。
     */
    private String        objectKey;

    /**
     * 该平台下的父节点业务键；空=按本地结构推导。
     *
     * <p>这是「三类资产独立」与「不同上级挂不同父节点」唯一的让步点：集团平台可以把某组织挂在「总部」下，
     * 区级平台对应挂在「某小区」下，而本地组织树只有一份。迁移旧数据时也靠它不丢信息。
     */
    private String        parentKey;

    /**
     * 上报编码；空=沿用本地编码。
     *
     * <p>空值不占用平台的编码空间（{@code UNIQUE (platform_id, publish_code)} 对 NULL 放行），
     * 所以只有真正需要覆盖的对象才填这一列。
     */
    private String        publishCode;
    /**
     * 编码来源：AUTO=规则生成并固化 / MANUAL=人工填写；空=历史数据未标记。
     *
     * <p>只有 {@code AUTO} 的码允许被规则重算，人工值与未标记值一律不动。
     */
    private String        codeSource;
    /** 自动生成时间；人工填写为空（用于审计「这个码什么时候定的」） */
    private LocalDateTime codeGeneratedTime;
    /** 上报名称；空=沿用本地名称 */
    private String        publishName;
    /** 是否推给该平台：1-推 0-不推（投影生成后先默认 0，人工核对再开） */
    private Integer       enabled;
    /** 该平台目录内的排序；空=沿用本地排序 */
    private Integer       sortOrder;

    /* ---------------- 目录元数据覆盖（DEVICE / CHANNEL 有意义，空则从本地派生） ---------------- */

    private String        manufacturer;
    private String        model;
    private String        address;
    private String        ipAddress;
    private Integer       ptzType;
    private String        civilCode;

    /**
     * 取消该组织在此平台的发布后，其下通道的处置策略：CASCADE / REROUTE，空按 CASCADE。
     *
     * <p>放在发布层而不是组织表：同一组织推给两个上级，可以一边连带删通道、一边让通道上浮。
     */
    private String        revokePolicy;

    /* ---------------- 按设备拆键与推送结论（1.10.3） ---------------- */

    /**
     * CHANNEL 行冗余的设备段，空=尚未回填（回落到按 {@code objectKey} 归组的旧行为）。
     *
     * <p>为什么不用 {@code objectKey} 过滤：它是 {@code deviceId:channelId} 拼接键，
     * 拿它做前缀匹配等于用字符串推断标识（§6.3），设备 Number 里一旦含分隔符就误伤。
     * 口径与 {@code PublishKeys.channelDeviceId} 一致；ORG/DEVICE 行为空。
     */
    private String        deviceKey;

    /** 最近一次向该平台推送的时间；空=从未推送 */
    private LocalDateTime lastPushTime;

    /** 最近一次推送结论：OK / FAILED / TIMEOUT / SKIPPED；空=从未推送 */
    private String        lastPushStatus;

    /** 最近一次推送失败原因（限长 255，存摘要）；成功为空 */
    private String        lastPushMessage;

    /** 扩展字段（JSON 文本，留给厂商差异化参数） */
    private String        extend;
}
