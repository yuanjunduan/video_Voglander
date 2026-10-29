package io.github.lunasaw.voglander.manager.domaon.dto;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.annotation.TableField;
import com.luna.common.text.CharsetUtil;
import io.github.lunasaw.gb28181.common.entity.enums.StreamModeEnum;
import io.github.lunasaw.voglander.client.domain.device.qo.DeviceRegisterReq;
import io.github.lunasaw.voglander.common.constant.device.DeviceConstant;
import io.github.lunasaw.voglander.common.enums.DeviceAgreementEnum;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * @author chenzhangyue
 * @since 2023-12-28 11:11:54
 */
@SuppressWarnings("serial")
@Data
public class DeviceDTO implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
    private Long id;
    private LocalDateTime     createTime;
    private LocalDateTime     updateTime;
    //设备ID
    private String deviceId;
    //状态 1在线 0离线
    private Integer status;
    //自定义名称
    private String name;
    //IP
    private String ip;
    //端口
    private Integer port;
    //注册时间
    private LocalDateTime     registerTime;
    //心跳时间
    private LocalDateTime     keepaliveTime;
    //注册节点
    private String serverIp;
    /**
     * 协议类型 {@link DeviceAgreementEnum}
     */
    private Integer type;
    //扩展字段
    private String extend;

    private ExtendInfo extendInfo;

    public static DeviceDTO req2dto(DeviceRegisterReq deviceReq) {
        DeviceDTO dto = new DeviceDTO();

        dto.setDeviceId(deviceReq.getDeviceId());
        dto.setStatus(DeviceConstant.Status.ONLINE);
        dto.setIp(deviceReq.getRemoteIp());
        dto.setPort(deviceReq.getRemotePort());
        dto.setRegisterTime(deviceReq.getRegisterTime());
        dto.setKeepaliveTime(LocalDateTime.now());
        dto.setServerIp(deviceReq.getLocalIp());
        dto.setType(deviceReq.getType());
        ExtendInfo extendInfo = new ExtendInfo();
        extendInfo.setTransport(deviceReq.getTransport());
        extendInfo.setExpires(deviceReq.getExpire());
        dto.setExtendInfo(extendInfo);
        return dto;
    }

    public static DeviceDO convertDO(DeviceDTO dto) {
        if (dto == null) {
            return null;
        }
        DeviceDO deviceDO = new DeviceDO();
        deviceDO.setId(dto.getId());
        deviceDO.setCreateTime(dto.getCreateTime());
        deviceDO.setUpdateTime(dto.getUpdateTime());
        deviceDO.setDeviceId(dto.getDeviceId());
        deviceDO.setStatus(dto.getStatus());
        deviceDO.setName(dto.getName());
        deviceDO.setIp(dto.getIp());
        deviceDO.setPort(dto.getPort());
        deviceDO.setRegisterTime(dto.getRegisterTime());
        deviceDO.setKeepaliveTime(dto.getKeepaliveTime());
        deviceDO.setServerIp(dto.getServerIp());
        deviceDO.setType(dto.getType());
        deviceDO.setExtend(JSON.toJSONString(dto.getExtendInfo()));
        return deviceDO;
    }

    public static DeviceDTO convertDTO(DeviceDO deviceDO) {
        if (deviceDO == null) {
            return null;
        }
        DeviceDTO deviceDTO = new DeviceDTO();
        deviceDTO.setId(deviceDO.getId());
        deviceDTO.setCreateTime(deviceDO.getCreateTime());
        deviceDTO.setUpdateTime(deviceDO.getUpdateTime());
        deviceDTO.setDeviceId(deviceDO.getDeviceId());
        deviceDTO.setStatus(deviceDO.getStatus());
        deviceDTO.setName(deviceDO.getName());
        deviceDTO.setIp(deviceDO.getIp());
        deviceDTO.setPort(deviceDO.getPort());
        deviceDTO.setRegisterTime(deviceDO.getRegisterTime());
        deviceDTO.setKeepaliveTime(deviceDO.getKeepaliveTime());
        deviceDTO.setServerIp(deviceDO.getServerIp());
        deviceDTO.setType(deviceDO.getType());
        deviceDTO.setExtend(deviceDO.getExtend());

        ExtendInfo extendObj = getExtendObj(deviceDO.getExtend());
        if (extendObj.getCharset() == null) {
            extendObj.setCharset(CharsetUtil.UTF_8);
        }
        if (extendObj.getStreamMode() == null) {
            extendObj.setStreamMode(StreamModeEnum.UDP.getType());
        }
        deviceDTO.setExtendInfo(extendObj);
        return deviceDTO;
    }

    private static ExtendInfo getExtendObj(String extentInfo) {
        if (StringUtils.isBlank(extentInfo)) {
            return new ExtendInfo();
        }
        String extend = Optional.of(extentInfo).orElse(StringUtils.EMPTY);
        return Optional.ofNullable(JSON.parseObject(extend, ExtendInfo.class)).orElse(new ExtendInfo());
    }

    /**
     * 以库中现值为基底合并本次变更：仅用 {@code patch} 中的非空项覆盖。
     *
     * <p>
     * {@link #convertDO(DeviceDTO)} 会把整个 ExtendInfo 序列化写库，更新请求未携带的字段
     * 会以 null / 0 落库，从而清空 serialNumber、transport、rtspUrl 等既有配置；
     * 两条更新链路（{@code update}/{@code updateById}）必须先用本方法合并库中现值。
     * </p>
     *
     * @param base  库中现值（为空时新建）
     * @param patch 本次提交的变更项（为空时原样返回 base）
     * @return 合并后的扩展信息（就地修改 base）
     */
    public static ExtendInfo mergeExtend(ExtendInfo base, ExtendInfo patch) {
        ExtendInfo target = base != null ? base : new ExtendInfo();
        if (patch == null) {
            return target;
        }
        if (patch.getSerialNumber() != null) {
            target.setSerialNumber(patch.getSerialNumber());
        }
        if (patch.getTransport() != null) {
            target.setTransport(patch.getTransport());
        }
        // expires 为基本类型，0 视为"未设置"（与 saveOrUpdate 的合并语义一致）
        if (patch.getExpires() > 0) {
            target.setExpires(patch.getExpires());
        }
        if (patch.getPassword() != null) {
            target.setPassword(patch.getPassword());
        }
        if (patch.getStreamMode() != null) {
            target.setStreamMode(patch.getStreamMode());
        }
        if (patch.getCharset() != null) {
            target.setCharset(patch.getCharset());
        }
        if (patch.getRtspUrl() != null) {
            target.setRtspUrl(patch.getRtspUrl());
        }
        if (patch.getRtspSubUrl() != null) {
            target.setRtspSubUrl(patch.getRtspSubUrl());
        }
        if (patch.getDeviceInfo() != null) {
            target.setDeviceInfo(patch.getDeviceInfo());
        }
        // 设备应答快照字段：非空时更新
        if (patch.getDeviceStatus() != null) {
            target.setDeviceStatus(patch.getDeviceStatus());
        }
        if (patch.getPtzPosition() != null) {
            target.setPtzPosition(patch.getPtzPosition());
        }
        if (patch.getPresets() != null) {
            target.setPresets(patch.getPresets());
        }
        if (patch.getConfig() != null) {
            target.setConfig(patch.getConfig());
        }
        if (patch.getConfigDownload() != null) {
            target.setConfigDownload(patch.getConfigDownload());
        }
        if (patch.getSdCardStatus() != null) {
            target.setSdCardStatus(patch.getSdCardStatus());
        }
        return target;
    }

    /**
     * 合并库中现值与本次变更，并序列化为 extend JSON 串。
     *
     * @param existingJson 库中现值 JSON 串（可空）
     * @param patch        本次提交的变更项（可空）
     * @return 合并后的 JSON 串
     */
    public static String mergeExtendJson(String existingJson, ExtendInfo patch) {
        return JSON.toJSONString(mergeExtend(getExtendObj(existingJson), patch));
    }

    @Data
    public static class ExtendInfo {

        /**
         * 设备序列号
         */
        private String serialNumber;

        /**
         * 传输协议
         * UDP/TCP
         */
        private String transport;

        /**
         * 注册有效期
         */
        private int expires;

        /**
         * 密码
         */
        private String password;

        /**
         * 数据流传输模式
         * UDP:udp传输
         * TCP-ACTIVE：tcp主动模式
         * TCP-PASSIVE：tcp被动模式
         */
        private String streamMode;

        /**
         * 编码
         */
        private String charset;

        /**
         * RTSP 拉流地址（RTSP/ONVIF 协议设备专用），形如
         * {@code rtsp://user:pwd@1.2.3.4:554/Streaming/Channels/101}；由 ZLM addStreamProxy 主动拉流。
         */
        private String rtspUrl;

        /**
         * RTSP 辅码流（子码流）拉流地址，形如
         * {@code rtsp://user:pwd@1.2.3.4:554/Streaming/Channels/102}；为空表示该设备不支持/未配置辅码流。
         * <p>
         * 由前端"辅码流"切换时使用（{@code streamProfile=sub}）。各厂商路径不同，故不做 URL 规则推导，
         * 一律显式配置，避免给非海康/大华设备造出错误地址。
         * </p>
         */
        private String rtspSubUrl;

        /**
         * 设备信息
         */
        private String deviceInfo;

        // ================ 设备应答快照（S3 入站响应回填，FastJSON2 序列化原始响应实体 → JSON 字符串）================

        /**
         * 设备状态响应 Response.DeviceStatus（DeviceStatus）
         */
        private String deviceStatus;

        /**
         * 云台位置响应 Response.PtzPosition（PTZPositionResponse）
         */
        private String ptzPosition;

        /**
         * 预置位响应 Response.PresetQuery（PresetQueryResponse）
         */
        private String presets;

        /**
         * 设备配置响应 Response.Config（DeviceConfigResponse）
         */
        private String config;

        /**
         * 配置下载响应 Response.ConfigDownload（DeviceConfigDownloadResponse）
         */
        private String configDownload;

        /**
         * SD 卡状态响应 Response.SdCardStatus（SDCardStatusResponse，预留）
         */
        private String sdCardStatus;

    }

    // ================ 时间转换领域方法 ================

    /**
     * 获取创建时间的毫秒级时间戳
     *
     * @return unix时间戳（毫秒级）
     */
    public Long createTimeToEpochMilli() {
        return createTime != null ? createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }

    /**
     * 获取更新时间的毫秒级时间戳
     *
     * @return unix时间戳（毫秒级）
     */
    public Long updateTimeToEpochMilli() {
        return updateTime != null ? updateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }

    /**
     * 获取注册时间的毫秒级时间戳
     *
     * @return unix时间戳（毫秒级）
     */
    public Long registerTimeToEpochMilli() {
        return registerTime != null ? registerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }

    /**
     * 获取心跳时间的毫秒级时间戳
     *
     * @return unix时间戳（毫秒级）
     */
    public Long keepaliveTimeToEpochMilli() {
        return keepaliveTime != null ? keepaliveTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }

}
