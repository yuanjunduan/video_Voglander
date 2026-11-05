package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.Data;

/**
 * 级联上级平台 DTO
 */
@Data
public class CascadePlatformDTO {
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String        platformId;
    private String        platformIp;
    private Integer       platformPort;
    private String        platformDomain;
    /** 平台名称：只用于本地辨识，GB28181 报文里用的是 {@code platformId}；空=未命名 */
    private String        platformName;
    private String        username;
    private String        password;
    private String        localClientId;
    private String        localIp;
    private Integer       localPort;
    private Integer       enabled;
    private Integer       registerStatus;
    private Integer       keepaliveInterval;
    private Integer       registerExpires;
    private String        charset;
    private String        transport;

    /**
     * 目录是否带设备层：0=虚拟组织（通道直挂组织），1=域/组织/设备/通道。
     *
     * <p>为 {@code null} 时按 0 处理：历史数据与新建平台默认都是虚拟组织模式，
     * 设备层是开给特定上级的适配项，不能靠“忘了填”默认打开。
     */
    private Integer       catalogWithDeviceLayer;

    /* ---------------- 平台级七段编码规则（只放前 14 位里可配的段，类型位由对象类型决定） ---------------- */

    /** 省编码 2 位；全留空时回落组织规则与全局兜底 */
    private String        codeProvince;
    private String        codeCity;
    private String        codeCounty;
    private String        codeIndustry;
    /** 网络标识 1 位 */
    private String        codeNetwork;
    /** 起始序号（6 位）：只作发号下界 */
    private Integer       codeStartSeq;

    /**
     * 是否按平台七段规则生成上报编码：0=不启用（沿用组织规则与全局兜底），1=启用。
     *
     * <p>为 {@code null} 时按 0 处理：升级不能替存量平台换码，上级侧会把老对象当成新设备。
     */
    private Integer       codeRuleEnabled;

    /**
     * 发布策略：勾选组织时是否自动补推缺失的祖先链。0=不补推（缺祖先即报错），1=自动补推。
     *
     * <p>为 {@code null} 时按 0 处理：自动补推会一次性放出整条祖先链，只能由用户显式选择。
     */
    private Integer       publishAncestors;

    private String        extend;

    /** 是否按设备层组织目录；空值视为关。 */
    public boolean useDeviceLayer() {
        return catalogWithDeviceLayer != null && catalogWithDeviceLayer == 1;
    }

    /** 是否按平台七段规则生成上报编码；空值视为关。 */
    public boolean usePlatformCodeRule() {
        return codeRuleEnabled != null && codeRuleEnabled == 1;
    }

    /** 是否自动补推缺失的祖先链；空值视为关。 */
    public boolean usePublishAncestors() {
        return publishAncestors != null && publishAncestors == 1;
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
