package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import lombok.Data;

/**
 * 级联上报通道新增请求（Web 入参）。
 *
 * @author luna
 * @date 2026/06/16
 */
@Data
public class CascadeChannelCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属上级平台国标 ID（必填）
     */
    private String platformId;

    /**
     * 本地设备国标 ID（必填）
     */
    private String localDeviceId;

    /**
     * 本地通道国标 ID（必填）
     */
    private String localChannelId;

    /**
     * 对上级暴露的级联通道编码（国标 20 位，必填）
     */
    private String cascadeChannelId;

    /**
     * 所属级联组织编码（选填）。
     *
     * <p>为空表示直接挂在平台根节点下。填了就必须是该上级平台下<b>已启用</b>的组织编码，
     * 否则通道会成为孤儿节点——上级照收条目却不建节点。
     */
    private String cascadeOrgId;

    /**
     * 对上级暴露的通道名称
     */
    private String cascadeName;

    /**
     * 目录项元数据（均选填）：留空则按设备通道数据自动派生。
     *
     * <p>只在设备侧确实拿不到时手填——例如 RTSP 设备没有安装地址、老国标设备目录不带型号。
     */
    private String manufacturer;

    private String model;

    private String address;

    private String ipAddress;

    /**
     * 摄像机类型：1球机 / 2半球 / 3枪机；空或 0 表示自动
     */
    private Integer ptzType;
}
