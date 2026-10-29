package io.github.lunasaw.voglander.web.api.live.assembler;

import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.service.live.dto.LivePlayDTO;
import io.github.lunasaw.voglander.service.live.dto.LiveStartDTO;
import io.github.lunasaw.voglander.service.live.dto.LiveStreamStatsDTO;
import io.github.lunasaw.voglander.web.api.live.domain.LivePlayVO;
import io.github.lunasaw.voglander.web.api.live.domain.LiveStartReq;
import io.github.lunasaw.voglander.web.api.live.domain.LiveStatsVO;

@Component
public class LiveWebAssembler {

    public LiveStartDTO startReqToDto(LiveStartReq req) {
        LiveStartDTO dto = new LiveStartDTO();
        dto.setDeviceId(req.getDeviceId());
        dto.setChannelId(req.getChannelId());
        dto.setProtocol(req.getProtocol());
        dto.setStreamMode(req.getStreamMode());
        dto.setStreamProfile(req.getStreamProfile());
        dto.setReplacePrevious(req.isReplacePrevious());
        return dto;
    }

    public LivePlayVO dtoToVo(LivePlayDTO dto) {
        if (dto == null) return null;
        LivePlayVO vo = new LivePlayVO();
        vo.setStreamId(dto.getStreamId());
        vo.setCallId(dto.getCallId());
        vo.setStatus(dto.getStatus());
        vo.setPlayUrls(dto.getPlayUrls());
        vo.setRefCount(dto.getRefCount());
        vo.setVideoCodec(dto.getVideoCodec());
        vo.setVideoWidth(dto.getVideoWidth());
        vo.setVideoHeight(dto.getVideoHeight());
        return vo;
    }

    public LiveStatsVO statsDtoToVo(LiveStreamStatsDTO dto) {
        if (dto == null) return null;
        LiveStatsVO vo = new LiveStatsVO();
        vo.setStreamId(dto.getStreamId());
        vo.setOnline(dto.isOnline());
        vo.setCodec(dto.getCodec());
        vo.setWidth(dto.getWidth());
        vo.setHeight(dto.getHeight());
        vo.setFps(dto.getFps());
        vo.setBytesSpeed(dto.getBytesSpeed());
        vo.setReaderCount(dto.getReaderCount());
        vo.setTotalReaderCount(dto.getTotalReaderCount());
        vo.setAliveSecond(dto.getAliveSecond());
        vo.setOriginUrl(dto.getOriginUrl());
        return vo;
    }
}
