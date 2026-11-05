package io.github.lunasaw.voglander.repository.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 级联上级平台表
 */
@Data
@TableName("tb_cascade_platform")
public class CascadePlatformDO implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 上级国标ID (SIP Server ID) */
    private String        platformId;
    private String        platformIp;
    private Integer       platformPort;
    private String        platformDomain;
    /** 平台名称：只用于本地辨识（GB28181 报文里用的是 platformId），空=未命名 */
    private String        platformName;
    private String        username;
    private String        password;

    /** 本地模拟客户端ID */
    private String        localClientId;
    private String        localIp;
    private Integer       localPort;

    /** 1-启用 0-禁用 */
    private Integer       enabled;
    /** 0-离线 1-在线 2-注册中 3-失败 */
    private Integer       registerStatus;
    private Integer       keepaliveInterval;
    private Integer       registerExpires;
    private String        charset;
    private String        transport;

    /**
     * 目录是否带设备层：0=虚拟组织（通道直挂组织），1=域/组织/设备/通道。
     *
     * <p>同一个上级可以有不同习惯：海康 iSecure 的目录树能接受设备节点，
     * 而部分平台把每个通道当独立节点。这是<b>对端画像</b>，不是本地结构，
     * 所以它挂在平台配置上，本地组织树不因此分叉。
     */
    private Integer       catalogWithDeviceLayer;

    /* ---------------- 平台级七段编码规则（只放前 14 位里可配的段，类型位由对象类型决定） ---------------- */

    /** 省编码 2 位；全部留空时回落组织规则与全局兜底 */
    private String        codeProvince;
    private String        codeCity;
    private String        codeCounty;
    private String        codeIndustry;
    /** 网络标识 1 位 */
    private String        codeNetwork;
    /** 起始序号（6 位）：只作发号下界，不会把已用号码降回去 */
    private Integer       codeStartSeq;
    /** 1=按平台七段规则生成上报编码；默认 0（升级不改变既有上报编码） */
    private Integer       codeRuleEnabled;
    /** 1=勾选组织时自动补推缺失的祖先链；默认 0（缺祖先即报错） */
    private Integer       publishAncestors;

    private String        extend;
}
