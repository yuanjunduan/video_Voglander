package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 发布配置 DTO：某个本地对象在某个上级平台上的可见性与编码覆盖。
 *
 * <p>「空 = 继承本地值」是本类的核心约定：{@code publishCode} / {@code publishName} /
 * {@code sortOrder} 与元数据列留空即回落本地资产的值，只有真正需要按平台区分的字段才填。
 */
@Data
public class CascadePublishDTO {

    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 上级平台编码 */
    private String        platformId;
    /** 发布对象类型：ORG / DEVICE / CHANNEL */
    private String        objectType;
    /** 对象业务键：ORG=org_code、DEVICE=device_id、CHANNEL=device_id:channel_id */
    private String        objectKey;
    /** 该平台下的父节点业务键，空=按本地结构推导 */
    private String        parentKey;

    /** 上报编码，空=沿用本地编码 */
    private String        publishCode;
    /** 编码来源 AUTO/MANUAL，空=历史数据未标记；只有 AUTO 允许被规则重算 */
    private String        codeSource;
    /** 自动生成时间；人工填写为空 */
    private LocalDateTime codeGeneratedTime;
    /** 上报名称，空=沿用本地名称 */
    private String        publishName;
    /** 是否推给该平台 */
    private Integer       enabled;
    /** 该平台目录内排序，空=沿用本地排序 */
    private Integer       sortOrder;

    private String        manufacturer;
    private String        model;
    private String        address;
    private String        ipAddress;
    private Integer       ptzType;
    private String        civilCode;
    /** CASCADE / REROUTE，空按 CASCADE */
    private String        revokePolicy;

    /* ---------------- 按设备拆键与推送结论（1.10.3） ---------------- */

    /** CHANNEL 行冗余的设备段，空=未回填；由发布层按 {@code PublishKeys.channelDeviceId} 统一写入 */
    private String        deviceKey;
    /** 最近一次向该平台推送的时间；空=从未推送 */
    private LocalDateTime lastPushTime;
    /** 最近一次推送结论 OK/FAILED/TIMEOUT/SKIPPED；空=从未推送 */
    private String        lastPushStatus;
    /** 最近一次推送失败原因 */
    private String        lastPushMessage;

    private String        extend;
}
