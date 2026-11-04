package io.github.lunasaw.voglander.manager.assembler;

import com.alibaba.fastjson2.JSON;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DeviceChannel数据转换组装器
 * 负责DTO和DO之间的数据转换
 * 
 * @author luna
 * @date 2024/01/31
 */
@Component
public class DeviceChannelAssembler {

    /**
     * DTO转DO
     */
    public DeviceChannelDO dtoToDo(DeviceChannelDTO dto) {
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
        /* 归属与本地启停只有人工操作才会带上：目录同步来的 DTO 两者都是 null，
           而 updateBatchById 会跳过 null 列，用户配的组织与开关不会被同步流程冲掉 */
        deviceChannelDO.setOrgId(dto.getOrgId());
        deviceChannelDO.setEnabled(dto.getEnabled());
        deviceChannelDO.setLastSeenTime(dto.getLastSeenTime());
        deviceChannelDO.setStatusSource(dto.getStatusSource());
        deviceChannelDO.setMissingCount(dto.getMissingCount());
        /* 通道自己的码只由人工/编码规则写：目录同步来的 DTO 是 null，updateBatchById 跳过 null 列，
           已固化的码不会被同步流程冲掉 */
        deviceChannelDO.setCode(dto.getCode());

        // 扩展字段转换
        if (dto.getExtendInfo() != null) {
            deviceChannelDO.setExtend(JSON.toJSONString(dto.getExtendInfo()));
        } else {
            deviceChannelDO.setExtend(dto.getExtend());
        }

        return deviceChannelDO;
    }

    /**
     * DO转DTO
     */
    public DeviceChannelDTO doToDto(DeviceChannelDO deviceChannelDO) {
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
        dto.setOrgId(deviceChannelDO.getOrgId());
        dto.setEnabled(deviceChannelDO.getEnabled());
        dto.setExtend(deviceChannelDO.getExtend());
        dto.setLastSeenTime(deviceChannelDO.getLastSeenTime());
        dto.setStatusSource(deviceChannelDO.getStatusSource());
        dto.setMissingCount(deviceChannelDO.getMissingCount());
        dto.setCode(deviceChannelDO.getCode());

        // 扩展字段解析
        dto.setExtendInfo(parseExtendInfo(deviceChannelDO.getExtend()));

        return dto;
    }

    /**
     * DO列表转DTO列表
     */
    public List<DeviceChannelDTO> doListToDtoList(List<DeviceChannelDO> doList) {
        if (doList == null || doList.isEmpty()) {
            return Collections.emptyList();
        }
        return doList.stream()
            .map(this::doToDto)
            .collect(Collectors.toList());
    }

    /**
     * DTO列表转DO列表
     */
    public List<DeviceChannelDO> dtoListToDoList(List<DeviceChannelDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            return Collections.emptyList();
        }
        return dtoList.stream()
            .map(this::dtoToDo)
            .collect(Collectors.toList());
    }

    /**
     * 解析扩展字段信息
     */
    private DeviceChannelDTO.ExtendInfo parseExtendInfo(String extendStr) {
        if (StringUtils.isBlank(extendStr)) {
            return new DeviceChannelDTO.ExtendInfo();
        }

        String extend = Optional.ofNullable(extendStr).orElse(StringUtils.EMPTY);
        return Optional.ofNullable(JSON.parseObject(extend, DeviceChannelDTO.ExtendInfo.class))
            .orElse(new DeviceChannelDTO.ExtendInfo());
    }
}