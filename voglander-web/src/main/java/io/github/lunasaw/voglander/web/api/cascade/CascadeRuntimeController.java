package io.github.lunasaw.voglander.web.api.cascade;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.lunasaw.voglander.common.constant.ApiConstant;
import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeRuntimeMonitor;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeRuntimeSnapshotVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 级联运行状态 API。
 *
 * <p>只读：暴露目录分片进度、在途分片、媒体会话、待确认推送与生效订阅。
 * 这些都是内存中的瞬时状态，刷新即取最新值，不做缓存也不落库。
 *
 * @author luna
 */
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/cascade/runtime")
@Tag(name = "级联运行状态", description = "目录分片 / 媒体会话 / 待确认推送的只读快照")
public class CascadeRuntimeController {

    @Autowired
    private CascadeRuntimeMonitor cascadeRuntimeMonitor;

    @GetMapping("/snapshot")
    @Operation(summary = "采集运行状态快照",
        description = "含目录分片批次、在途分片、媒体会话（PENDING/ACTIVE）、待确认推送与生效订阅；各列表恒非空")
    public AjaxResult<CascadeRuntimeSnapshotVO> snapshot() {
        return AjaxResult.success(CascadeRuntimeSnapshotVO.convertVO(cascadeRuntimeMonitor.snapshot()));
    }
}
