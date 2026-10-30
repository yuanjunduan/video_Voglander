package io.github.lunasaw.voglander.web.api.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.lunasaw.voglander.common.constant.ApiConstant;
import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.manager.domaon.dto.sys.SysConfigDTO;
import io.github.lunasaw.voglander.manager.manager.SysConfigManager;
import io.github.lunasaw.voglander.web.api.config.req.SysConfigUpdateReq;
import io.github.lunasaw.voglander.web.api.config.vo.SysConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 系统配置 API。
 *
 * <p>只暴露「键定义清单 + 当前生效值」与批量保存两个动作：可配项由后端
 * {@code SysConfigCatalog} 决定，前端不硬编码任何配置键，新增配置项无需改页面。
 *
 * @author luna
 */
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/system/config")
@Tag(name = "系统配置", description = "运行时配置项查询与批量保存")
public class SysConfigController {

    @Autowired
    private SysConfigManager sysConfigManager;

    @GetMapping("/list")
    @Operation(summary = "查询系统配置项", description = "按分组返回配置项定义与当前生效值；group 为空返回全部")
    public AjaxResult<List<SysConfigVO>> list(@RequestParam(required = false) String group) {
        List<SysConfigVO> items = sysConfigManager.listByGroup(group).stream()
            .map(SysConfigVO::convertVO)
            .toList();
        return AjaxResult.success(items);
    }

    @PutMapping
    @Operation(summary = "批量保存系统配置", description = "值传空串表示删除覆盖、回落默认；任一项校验失败整批拒绝")
    public AjaxResult<Integer> save(@RequestBody SysConfigUpdateReq req) {
        List<SysConfigDTO> items = req == null || req.getItems() == null
            ? List.of()
            : req.getItems().stream().map(SysConfigController::toDTO).toList();
        return AjaxResult.success(sysConfigManager.saveBatch(items));
    }

    private static SysConfigDTO toDTO(SysConfigUpdateReq.Item item) {
        SysConfigDTO dto = new SysConfigDTO();
        if (item != null) {
            dto.setConfigKey(item.getConfigKey());
            dto.setConfigValue(item.getConfigValue());
        }
        return dto;
    }
}
