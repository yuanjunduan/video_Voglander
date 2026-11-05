package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.io.Serializable;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import lombok.Data;

/**
 * 级联上级平台 VO（Web 出参，时间字段统一 Unix 毫秒）。
 *
 * <p>
 * 字段名与 {@code tb_cascade_platform} / {@link CascadePlatformDTO} 对齐：
 * platformIp / platformPort / platformDomain / localIp / localPort（非 serverIp 等臆造名）。
 * </p>
 *
 * @author luna
 * @date 2026/06/16
 */
@Data
public class CascadePlatformVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long    id;

    /**
     * 创建时间（unix 时间戳，毫秒级）
     */
    private Long    createTime;

    /**
     * 更新时间（unix 时间戳，毫秒级）
     */
    private Long    updateTime;

    /**
     * 上级平台国标 ID（20 位）
     */
    private String  platformId;

    /**
     * 上级平台 IP
     */
    private String  platformIp;

    /**
     * 上级平台端口
     */
    private Integer platformPort;

    /**
     * 上级平台域（SIP domain）
     */
    private String  platformDomain;

    /**
     * 平台名称：本地辨识用，空串=未命名（前端回落显示平台ID）。
     *
     * <p>这里刻意不归一成 null：编辑弹窗回填空串，用户清空后直接回传空串即可清掉名字；
     * 若归一成 null，而写入侧「null=不改」，清空会静默失效。
     */
    private String  platformName;

    /**
     * 认证用户名
     */
    private String  username;

    /**
     * 认证密码：仅入不出。
     *
     * <p>密码属于敏感凭据，接口不再回传给前端；编辑弹窗留空即表示"不修改"，
     * 由 Controller 在更新时回填库中原值。</p>
     */
    @JsonIgnore
    private String  password;

    /**
     * 本端客户端主键（编辑时回传给下拉框）
     */
    private Long    clientRefId;

    /**
     * 本端平台编码（只读派生值：来自 clientRefId 指向的客户端）
     */
    private String  localClientId;

    /**
     * Contact 头对外通告 IP（只读派生值）
     */
    private String  localIp;

    /**
     * SIP 监听端口（只读派生值，恒为 sip.client.port）
     */
    private Integer localPort;

    /**
     * 启用状态 1启用 / 0停用
     */
    private Integer enabled;

    /**
     * 注册状态 0离线 / 1在线 / 2注册中 / 3失败
     */
    private Integer registerStatus;

    /**
     * 注册状态显示名称
     */
    private String  registerStatusName;

    /**
     * 保活心跳间隔（秒）
     */
    private Integer keepaliveInterval;

    /**
     * 注册有效期（秒）
     */
    private Integer registerExpires;

    /**
     * 编码（GB2312/UTF-8）
     */
    private String  charset;

    /**
     * 传输协议 UDP / TCP
     */
    private String  transport;

    /**
     * 目录是否带设备层：0=虚拟组织（通道直挂组织），1=域/组织/设备/通道。
     *
     * <p>前端用它决定「级联通道」页要不要展示设备分组：开启后通道节点会挂在设备节点下。
     */
    private Integer catalogWithDeviceLayer;

    /* ---------------- 平台编码规则（前 14 位里可配的五段；类型位由对象类型决定，前端只读展示） ---------------- */

    /**
     * 省编码（2 位）；未配置为 null
     */
    private String  codeProvince;

    /**
     * 市编码（2 位）；未配置为 null
     */
    private String  codeCity;

    /**
     * 县区编码（4 位）；未配置为 null
     */
    private String  codeCounty;

    /**
     * 行业编码（2 位）；未配置为 null
     */
    private String  codeIndustry;

    /**
     * 网络标识（1 位）；未配置为 null
     */
    private String  codeNetwork;

    /**
     * 起始序号（6 位，只作发号下界）
     */
    private Integer codeStartSeq;

    /**
     * 平台编码规则开关：0=不启用 1=启用（空值按 0 下发）
     */
    private Integer codeRuleEnabled;

    /**
     * 发布策略：0=不自动补推祖先 1=自动补推（空值按 0 下发）
     */
    private Integer publishAncestors;

    /**
     * 最近一次注册失败原因（来自 extend.lastError；空表示当前无错误）
     */
    private String  lastError;

    /**
     * 最近一次注册失败/恢复时间（unix 时间戳，毫秒级）
     */
    private Long    lastErrorAt;

    /**
     * 扩展字段
     */
    private String  extend;

    /**
     * DTO → VO 转换（时间转 Unix 毫秒，registerStatus 派生中文名）。
     *
     * @param dto 平台 DTO
     * @return VO；入参为空返回 null
     */
    public static CascadePlatformVO convertVO(CascadePlatformDTO dto) {
        if (dto == null) {
            return null;
        }
        CascadePlatformVO vo = new CascadePlatformVO();
        vo.setId(dto.getId());
        vo.setCreateTime(dto.createTimeToEpochMilli());
        vo.setUpdateTime(dto.updateTimeToEpochMilli());
        vo.setPlatformId(dto.getPlatformId());
        vo.setPlatformIp(dto.getPlatformIp());
        vo.setPlatformPort(dto.getPlatformPort());
        vo.setPlatformDomain(dto.getPlatformDomain());
        vo.setPlatformName(dto.getPlatformName());
        vo.setUsername(dto.getUsername());
        vo.setClientRefId(dto.getClientRefId());
        /* 密码不回传：编辑时留空由后端保留原值 */
        vo.setLocalClientId(dto.getLocalClientId());
        vo.setLocalIp(dto.getLocalIp());
        vo.setLocalPort(dto.getLocalPort());
        vo.setEnabled(dto.getEnabled());
        vo.setRegisterStatus(dto.getRegisterStatus());
        vo.setRegisterStatusName(getRegisterStatusName(dto.getRegisterStatus()));
        vo.setKeepaliveInterval(dto.getKeepaliveInterval());
        vo.setRegisterExpires(dto.getRegisterExpires());
        vo.setCharset(dto.getCharset());
        vo.setTransport(dto.getTransport());
        /* 空值按 0 下发：前端不需要再判 null，也不会因为 null 把开关渲染成未初始化 */
        vo.setCatalogWithDeviceLayer(dto.getCatalogWithDeviceLayer() == null ? 0 : dto.getCatalogWithDeviceLayer());
        /* 五段对外只保留一种「未配置」表示：通用更新走 updateById 会把空串原样写库，
           规则保存写的是 NULL；不归一的话前端要同时判 null 与 "" 才算得对 */
        vo.setCodeProvince(StringUtils.trimToNull(dto.getCodeProvince()));
        vo.setCodeCity(StringUtils.trimToNull(dto.getCodeCity()));
        vo.setCodeCounty(StringUtils.trimToNull(dto.getCodeCounty()));
        vo.setCodeIndustry(StringUtils.trimToNull(dto.getCodeIndustry()));
        vo.setCodeNetwork(StringUtils.trimToNull(dto.getCodeNetwork()));
        vo.setCodeStartSeq(dto.getCodeStartSeq());
        vo.setCodeRuleEnabled(dto.getCodeRuleEnabled() == null ? 0 : dto.getCodeRuleEnabled());
        vo.setPublishAncestors(dto.getPublishAncestors() == null ? 0 : dto.getPublishAncestors());
        vo.setExtend(dto.getExtend());
        fillLastError(vo, dto.getExtend());
        return vo;
    }

    /**
     * 从 extend 中解析失败原因与时间，供前端直接展示"为什么离线"。
     *
     * <p>extend 非 JSON 或缺少键时静默跳过，不影响列表展示。</p>
     *
     * @param vo    待填充的 VO
     * @param extend 平台扩展字段
     */
    private static void fillLastError(CascadePlatformVO vo, String extend) {
        if (extend == null || extend.isBlank()) {
            return;
        }
        try {
            JSONObject ext = JSON.parseObject(extend);
            vo.setLastError(ext.getString(CascadeConstant.EXTEND_LAST_ERROR));
            vo.setLastErrorAt(ext.getLong(CascadeConstant.EXTEND_LAST_ERROR_AT));
        } catch (Exception ignored) {
            // extend 不是合法 JSON（历史脏数据）：保持 lastError 为空
        }
    }

    /**
     * 注册状态显示名称映射。
     *
     * @param registerStatus 注册状态码
     * @return 中文名称
     */
    private static String getRegisterStatusName(Integer registerStatus) {
        if (registerStatus == null) {
            return "未知";
        }
        switch (registerStatus) {
            case 0:
                return "离线";
            case 1:
                return "在线";
            case 2:
                return "注册中";
            case 3:
                return "注册失败";
            default:
                return "未知";
        }
    }
}
