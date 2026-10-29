package io.github.lunasaw.voglander.web.api.channel.vo;

import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import lombok.Data;

import java.io.Serializable;

/**
 * 设备通道 VO 模型
 * @author luna
 * @date 2024/01/30
 */
@Data
public class DeviceChannelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 创建时间 (unix时间戳，毫秒级)
     */
    private Long              createTime;

    /**
     * 更新时间 (unix时间戳，毫秒级)
     */
    private Long              updateTime;

    /**
     * 状态 1在线 0离线
     */
    private Integer status;

    /**
     * 状态名称
     */
    private String statusName;

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
     * 扩展字段
     */
    private String extend;

    /**
     * 扩展信息
     */
    private ExtendInfoVO extendInfo;

    /**
     * 获取状态显示名称
     */
    private static String getStatusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        return status == 1 ? "在线" : "离线";
    }

    /**
     * 将DeviceChannelDTO转换为DeviceChannelVO
     */
    public static DeviceChannelVO convertVO(DeviceChannelDTO dto) {
        if (dto == null) {
            return null;
        }

        DeviceChannelVO vo = new DeviceChannelVO();
        vo.setId(dto.getId());
        vo.setCreateTime(dto.createTimeToEpochMilli());
        vo.setUpdateTime(dto.updateTimeToEpochMilli());
        vo.setStatus(dto.getStatus());
        vo.setStatusName(getStatusName(dto.getStatus()));
        vo.setChannelId(dto.getChannelId());
        vo.setDeviceId(dto.getDeviceId());
        vo.setName(dto.getName());
        vo.setExtend(dto.getExtend());

        // 转换扩展信息
        if (dto.getExtendInfo() != null) {
            DeviceChannelDTO.ExtendInfo source = dto.getExtendInfo();
            ExtendInfoVO extendInfoVO = new ExtendInfoVO();
            extendInfoVO.setChannelInfo(source.getChannelInfo());
            extendInfoVO.setRtspUrl(source.getRtspUrl());
            extendInfoVO.setRtspSubUrl(source.getRtspSubUrl());
            extendInfoVO.setVideoCodec(source.getVideoCodec());
            extendInfoVO.setVideoWidth(source.getVideoWidth());
            extendInfoVO.setVideoHeight(source.getVideoHeight());
            extendInfoVO.setVendor(source.getVendor());
            extendInfoVO.setProxyProtocol(source.getProxyProtocol());
            extendInfoVO.setSourceIp(source.getSourceIp());
            extendInfoVO.setSourcePort(source.getSourcePort());
            extendInfoVO.setSourceChannel(source.getSourceChannel());
            extendInfoVO.setSourceModel(source.getSourceModel());
            extendInfoVO.setSourceFirmware(source.getSourceFirmware());
            vo.setExtendInfo(extendInfoVO);
        }

        return vo;
    }

    @Data
    public static class ExtendInfoVO {
        /**
         * 设备通道信息
         */
        private String channelInfo;

        // ================ 拉流类设备（RTSP/ONVIF）通道 ================

        /**
         * 通道主码流地址（含凭据）
         */
        private String  rtspUrl;

        /**
         * 通道辅码流地址（含凭据）
         */
        private String  rtspSubUrl;

        /**
         * 视频编码：H264 / H265 / UNKNOWN；点播成功后回填，前端据此选播放器
         */
        private String  videoCodec;

        /**
         * 视频宽度（像素）
         */
        private Integer videoWidth;

        /**
         * 视频高度（像素）
         */
        private Integer videoHeight;

        /**
         * 厂商标识
         */
        private String  vendor;

        /**
         * NVR 接入该通道所用协议
         */
        private String  proxyProtocol;

        /**
         * 源设备 IP
         */
        private String  sourceIp;

        /**
         * 源设备管理端口
         */
        private Integer sourcePort;

        /**
         * 源设备通道号
         */
        private Integer sourceChannel;

        /**
         * 源设备型号
         */
        private String  sourceModel;

        /**
         * 源设备固件版本
         */
        private String  sourceFirmware;
    }
}