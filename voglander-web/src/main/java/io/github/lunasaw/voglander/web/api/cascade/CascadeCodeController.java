package io.github.lunasaw.voglander.web.api.cascade;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.lunasaw.voglander.common.constant.ApiConstant;
import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBatchFillResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 级联上报编码 API：按编码规则给「还没有码」的发布行补码。
 *
 * <p><b>为什么不挂在组织/通道接口下</b>：补码的对象类型是参数（ORG / DEVICE / CHANNEL），
 * 一次扫描的是某个平台下的一整类发布行，不属于任何单个组织或通道的 CRUD。
 *
 * <p><b>为什么 {@code dryRun} 默认 true</b>：这是一次批量写 {@code publish_code} 的动作，
 * 而编码一旦上报过就是上级侧历史录像、订阅与点播的引用键，不存在「改回来」的报文语义。
 * 宁可多一次点击，也不要因为前端漏传参数就在生产上直接改掉一批码。
 *
 * @author luna
 */
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/cascade/code")
@Tag(name = "级联编码管理", description = "按编码规则批量补齐上报编码（默认只预览）")
public class CascadeCodeController {

    @Autowired
    private CascadeCodeResolver cascadeCodeResolver;

    @PostMapping("/batch-fill")
    @Operation(summary = "批量补齐上报编码",
        description = "只补 publish_code 为空的行；平台与归属组织都未开规则的行跳过；dryRun=true 只预览（同样占号）不落库")
    public AjaxResult<CascadeCodeBatchFillResult> batchFill(@RequestParam String platformId,
        @RequestParam(defaultValue = "CHANNEL") String objectType,
        @RequestParam(defaultValue = "true") boolean dryRun) {
        return AjaxResult.success(cascadeCodeResolver.batchFill(platformId, objectType, dryRun));
    }
}
