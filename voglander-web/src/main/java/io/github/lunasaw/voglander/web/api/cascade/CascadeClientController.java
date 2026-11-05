package io.github.lunasaw.voglander.web.api.cascade;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.validation.Valid;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.lunasaw.voglander.common.constant.ApiConstant;
import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeClientScheduler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeClientDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeClientManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeClientCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeClientUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeClientVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联本端客户端管理 API
 *
 * <p>本端客户端是「本端以哪个身份向上级注册」的唯一来源：20 位平台编码 + Contact 头对外通告 IP。
 * 上级平台只存它的外键，因此这里改一次 IP，所有引用它的平台同时生效。
 *
 * <p>删除会被仍引用它的平台挡住（由 Manager 抛 {@code IllegalStateException}）：
 * 平台表里只存外键，删掉客户端会让那些平台在组 From 头时取不到身份，
 * 表现为「配置看得到、注册一律失败」。
 *
 * @author luna
 */
@Slf4j
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/cascade/client")
@Tag(name = "级联本端客户端", description = "本端注册身份（编码 + 对外通告IP）管理")
public class CascadeClientController {

    @Autowired
    private CascadeClientManager     cascadeClientManager;

    @Autowired
    private CascadePlatformManager   cascadePlatformManager;

    @Autowired
    private CascadeClientScheduler   cascadeClientScheduler;

    @Autowired
    private CascadeWebAssembler      cascadeWebAssembler;

    /**
     * 客户端列表，默认项排最前。
     *
     * @param onlyEnabled true=只返回启用的（新增上级平台的下拉框用）
     * @return 客户端列表（含引用它的平台数量）
     */
    @GetMapping("/list")
    @Operation(summary = "本端客户端列表", description = "onlyEnabled=true 时只返回启用的，供上级平台表单下拉使用")
    public AjaxResult<List<CascadeClientVO>> list(@RequestParam(defaultValue = "false") boolean onlyEnabled) {
        Map<Long, Long> refs = cascadeClientManager.countPlatformRefsGrouped();
        List<CascadeClientVO> items = cascadeClientManager.listAll(onlyEnabled).stream()
            .map(dto -> CascadeClientVO.convertVO(dto, refs.getOrDefault(dto.getId(), 0L)))
            .toList();
        return AjaxResult.success(items);
    }

    @GetMapping("/{id}")
    @Operation(summary = "本端客户端详情")
    public AjaxResult<CascadeClientVO> getById(@PathVariable Long id) {
        CascadeClientDTO dto = cascadeClientManager.getById(id);
        if (dto == null) {
            return AjaxResult.error("本端客户端不存在：" + id);
        }
        return AjaxResult.success(CascadeClientVO.convertVO(dto, cascadeClientManager.countPlatformRefs(id)));
    }

    @PostMapping
    @Operation(summary = "新增本端客户端", description = "编码创建后不可改；库里没有客户端时第一条自动成为默认")
    public AjaxResult<Long> add(@Valid @RequestBody CascadeClientCreateReq req) {
        CascadeClientDTO dto = cascadeWebAssembler.toDTO(req);
        try {
            Long id = cascadeClientManager.add(dto);
            log.info("本端客户端新增: id={}, clientId={}, ip={}, isDefault={}", id, dto.getClientId(),
                dto.getClientIp(), dto.getIsDefault());
            return AjaxResult.success(id);
        } catch (IllegalArgumentException e) {
            /* 编码重复/位宽不对是用户填错，不是系统异常：回可读原因，不留给全局异常处理记 ERROR */
            log.warn("本端客户端新增被拦: clientId={}, reason={}", dto.getClientId(), e.getMessage());
            return AjaxResult.error(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新本端客户端", description = "patch 语义：null=不改、空串=清空；改 IP 会重建引用平台的注册调度")
    public AjaxResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody CascadeClientUpdateReq req) {
        CascadeClientDTO existed = cascadeClientManager.getById(id);
        if (existed == null) {
            return AjaxResult.error("本端客户端不存在：" + id);
        }
        CascadeClientDTO dto = cascadeWebAssembler.toDTO(req);
        dto.setId(id);
        try {
            boolean ok = cascadeClientManager.update(dto);
            if (ok && ipChanged(existed, dto)) {
                /* 调度任务里存的是拼好 From 头的 DTO 快照：不重建就还在用旧地址发注册，
                   而页面上已经显示新地址，这种不一致最难排查 */
                restartReferencedPlatforms(id);
            }
            log.info("本端客户端更新: id={}, ok={}, ipChanged={}", id, ok, ipChanged(existed, dto));
            return AjaxResult.success(ok);
        } catch (IllegalArgumentException e) {
            log.warn("本端客户端更新被拦: id={}, reason={}", id, e.getMessage());
            return AjaxResult.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除本端客户端", description = "仍被上级平台引用时拒绝删除")
    public AjaxResult<Boolean> delete(@PathVariable Long id) {
        try {
            boolean ok = cascadeClientManager.delete(id);
            log.info("本端客户端删除: id={}, ok={}", id, ok);
            return AjaxResult.success(ok);
        } catch (IllegalStateException e) {
            log.warn("本端客户端删除被拦: id={}, reason={}", id, e.getMessage());
            return AjaxResult.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/default")
    @Operation(summary = "设为默认本端客户端", description = "同时清掉其他行的默认标记，全局最多一条")
    public AjaxResult<Boolean> setDefault(@PathVariable Long id) {
        try {
            boolean ok = cascadeClientManager.setDefault(id);
            log.info("本端客户端设为默认: id={}, ok={}", id, ok);
            return AjaxResult.success(ok);
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 本次提交是否真的改了通告 IP（名称、备注变化不影响 SIP 报文，不必重启注册） */
    private boolean ipChanged(CascadeClientDTO existed, CascadeClientDTO submitted) {
        if (submitted.getClientIp() == null) {
            return false;
        }
        return !Objects.equals(StringUtils.trimToEmpty(existed.getClientIp()),
            StringUtils.trim(submitted.getClientIp()));
    }

    /**
     * 重建引用了该客户端的平台的注册调度（仅 enabled=1）。
     *
     * <p>单条失败只告警不中断：一个平台注册失败不应该让其他平台的配置变更一起失败。
     *
     * @param clientRefId 客户端主键
     */
    private void restartReferencedPlatforms(Long clientRefId) {
        for (CascadePlatformDTO platform : cascadePlatformManager.listByClientRefId(clientRefId)) {
            if (!Integer.valueOf(1).equals(platform.getEnabled())) {
                continue;
            }
            try {
                cascadeClientScheduler.stopPlatform(platform.getId());
                cascadeClientScheduler.startPlatform(platform);
                log.info("本端客户端变更，已重建注册调度: clientRefId={}, platformId={}", clientRefId,
                    platform.getPlatformId());
            } catch (Exception e) {
                log.warn("本端客户端变更后重建注册调度失败: clientRefId={}, platformId={}, err={}", clientRefId,
                    platform.getPlatformId(), e.getMessage());
            }
        }
    }
}

