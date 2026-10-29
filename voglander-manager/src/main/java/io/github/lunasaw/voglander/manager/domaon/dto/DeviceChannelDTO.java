package io.github.lunasaw.voglander.manager.domaon.dto;

import com.alibaba.fastjson2.JSON;
import io.github.lunasaw.voglander.client.domain.device.qo.DeviceChannelReq;
import io.github.lunasaw.voglander.common.constant.device.DeviceConstant;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * @author luna
 * @date 2023/12/31
 */
@Data
public class DeviceChannelDTO {

    private Long id;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    /**
     * 修改时间
     */
    private LocalDateTime updateTime;
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
     * 扩展字段
     */
    private String extend;


    private ExtendInfo extendInfo;

    /**
     * 通道最近一次被目录/会话感知的时间（1.0.4）
     */
    private LocalDateTime lastSeenTime;

    /**
     * 当前 status 的来源（1.0.4）
     */
    private String statusSource;

    /**
     * 连续目录响应未出现次数（1.0.4 Stage 4）
     */
    private Integer missingCount;

    /** @deprecated 改用 {@link io.github.lunasaw.voglander.manager.assembler.DeviceChannelAssembler#doToDto} */
    @Deprecated
    public static DeviceChannelDTO convertDTO(DeviceChannelDO deviceChannelDO) {
        if (deviceChannelDO == null) {
            return null;
        }
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setId(deviceChannelDO.getId());
        dto.setCreateTime(deviceChannelDO.getCreateTime());
        dto.setUpdateTime(deviceChannelDO.getUpdateTime());
        dto.setStatus(deviceChannelDO.getStatus());
        dto.setChannelId(deviceChannelDO.getChannelId());
        dto.setDeviceId(deviceChannelDO.getDeviceId());
        dto.setName(deviceChannelDO.getName());
        dto.setExtend(deviceChannelDO.getExtend());
        dto.setExtendInfo(getExtendObj(deviceChannelDO.getExtend()));
        dto.setLastSeenTime(deviceChannelDO.getLastSeenTime());
        dto.setStatusSource(deviceChannelDO.getStatusSource());
        dto.setMissingCount(deviceChannelDO.getMissingCount());
        return dto;
    }

    /** @deprecated 改用 {@link io.github.lunasaw.voglander.manager.assembler.DeviceChannelAssembler#dtoToDo} */
    @Deprecated
    public static DeviceChannelDO convertDO(DeviceChannelDTO dto) {
        if (dto == null) {
            return null;
        }
        DeviceChannelDO deviceChannelDO = new DeviceChannelDO();
        deviceChannelDO.setId(dto.getId());
        deviceChannelDO.setCreateTime(dto.getCreateTime());
        deviceChannelDO.setUpdateTime(dto.getUpdateTime());
        deviceChannelDO.setStatus(dto.getStatus());
        deviceChannelDO.setChannelId(dto.getChannelId());
        deviceChannelDO.setDeviceId(dto.getDeviceId());
        deviceChannelDO.setName(dto.getName());
        // extendInfo 为空时不能序列化：JSON.toJSONString(null) 会得到字符串 "null"，
        // 由 updateById 落库后就把库里的通道地址/编码/分辨率整串冲掉
        if (dto.getExtendInfo() != null) {
            deviceChannelDO.setExtend(JSON.toJSONString(dto.getExtendInfo()));
        } else {
            deviceChannelDO.setExtend(dto.getExtend());
        }
        deviceChannelDO.setLastSeenTime(dto.getLastSeenTime());
        deviceChannelDO.setStatusSource(dto.getStatusSource());
        deviceChannelDO.setMissingCount(dto.getMissingCount());
        return deviceChannelDO;
    }

    public static DeviceChannelDTO req2dto(DeviceChannelReq req) {
        if (req == null) {
            return null;
        }
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setStatus(DeviceConstant.Status.ONLINE);
        dto.setDeviceId(req.getDeviceId());
        dto.setName(req.getChannelName());
        dto.setChannelId(req.getChannelId());
        ExtendInfo extendInfo = new ExtendInfo();
        extendInfo.setChannelInfo(req.getChannelInfo());
        dto.setExtendInfo(extendInfo);

        return dto;
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
     * 更新链路（{@code updateDeviceChannel}）的入参通常只带 name/status，而扩展字段里的
     * RTSP 地址、编码、分辨率由通道发现与点播回填写入。若直接整串覆盖，这些探测结果会被清空；
     * 故两条更新链路都必须先用本方法合并库中现值（与 {@code DeviceDTO.mergeExtend} 同一约定）。
     * </p>
     *
     * @param existingJson 库中现值 JSON 串（可空）
     * @param patch        本次提交的变更项（为空时原样返回库中现值）
     * @return 合并后的扩展信息
     */
    public static ExtendInfo mergeExtend(String existingJson, ExtendInfo patch) {
        ExtendInfo target = getExtendObj(existingJson);
        if (patch == null) {
            return target;
        }
        if (patch.getChannelInfo() != null) {
            target.setChannelInfo(patch.getChannelInfo());
        }
        if (patch.getRtspUrl() != null) {
            target.setRtspUrl(patch.getRtspUrl());
        }
        if (patch.getRtspSubUrl() != null) {
            target.setRtspSubUrl(patch.getRtspSubUrl());
        }
        if (patch.getVideoCodec() != null) {
            target.setVideoCodec(patch.getVideoCodec());
        }
        if (patch.getVideoWidth() != null) {
            target.setVideoWidth(patch.getVideoWidth());
        }
        if (patch.getVideoHeight() != null) {
            target.setVideoHeight(patch.getVideoHeight());
        }
        if (patch.getVendor() != null) {
            target.setVendor(patch.getVendor());
        }
        if (patch.getProxyProtocol() != null) {
            target.setProxyProtocol(patch.getProxyProtocol());
        }
        if (patch.getSourceIp() != null) {
            target.setSourceIp(patch.getSourceIp());
        }
        if (patch.getSourcePort() != null) {
            target.setSourcePort(patch.getSourcePort());
        }
        if (patch.getSourceChannel() != null) {
            target.setSourceChannel(patch.getSourceChannel());
        }
        if (patch.getSourceModel() != null) {
            target.setSourceModel(patch.getSourceModel());
        }
        if (patch.getSourceFirmware() != null) {
            target.setSourceFirmware(patch.getSourceFirmware());
        }
        return target;
    }

    @Data
    public static class ExtendInfo {
        /**
         * 设备通道信息（GB28181 目录应答原样）
         */
        private String channelInfo;

        // ================ 拉流类设备（RTSP/ONVIF）通道扩展 ================
        // 由 ChannelDiscoverer 发现通道时写入；GB28181 通道这些字段全为空，互不影响。
        // 存的是通道级地址而非模板：P0 的目标是"发现即可点播"，模板化留到多 NVR 场景再收敛。

        /**
         * 通道主码流地址（含凭据）。点播时若带 channelId 则优先于设备级 extendInfo.rtspUrl
         */
        private String rtspUrl;
        /**
         * 通道辅码流地址（含凭据）
         */
        private String rtspSubUrl;
        /**
         * 视频编码：H264 / H265 / UNKNOWN。点播成功后回填，作为前端选播放器的依据
         */
        private String videoCodec;
        /**
         * 视频宽度（像素），点播成功后回填
         */
        private Integer videoWidth;
        /**
         * 视频高度（像素），点播成功后回填
         */
        private Integer videoHeight;
        /**
         * 厂商标识：hikvision / dahua / onvif
         */
        private String vendor;
        /**
         * NVR 侧对该通道使用的接入协议（如 ISAPI 的 proxyProtocol：HIKVISION / ONVIF）
         */
        private String proxyProtocol;
        /**
         * 源设备 IP（NVR 代理接入的那台 IPC）
         */
        private String sourceIp;
        /**
         * 源设备管理端口（80 / 8000）
         */
        private Integer sourcePort;
        /**
         * 源设备上的通道号
         */
        private Integer sourceChannel;
        /**
         * 源设备型号
         */
        private String sourceModel;
        /**
         * 源设备固件版本
         */
        private String sourceFirmware;
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
}
