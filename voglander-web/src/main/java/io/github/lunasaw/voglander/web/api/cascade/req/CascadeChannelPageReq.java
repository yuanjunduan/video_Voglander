package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import lombok.Data;

/**
 * 级联上报通道分页条件查询请求（Web 入参）。
 *
 * @author luna
 * @date 2026/06/16
 */
@Data
public class CascadeChannelPageReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属上级平台国标 ID（精确匹配）
     */
    private String platformId;

    /**
     * 本地设备国标 ID（精确匹配）
     */
    private String localDeviceId;

    /**
     * 本地通道国标 ID（精确匹配）
     */
    private String localChannelId;

    /**
     * 级联通道编码（精确匹配）
     */
    private String cascadeChannelId;

    /**
     * 所属级联组织编码
     */
    private String cascadeOrgId;

    /**
     * 是否把子组织下的通道一并纳入（默认 false，只取直属该组织的通道）。
     *
     * <p>置 true 时 {@code cascadeOrgId} 变成"子树过滤"：组织树有多少层都能一次查全，
     * 前端不必逐层请求。此时必须同时给 {@code platformId}（编码只在平台内唯一）。
     */
    private Boolean includeSubOrg;
}
