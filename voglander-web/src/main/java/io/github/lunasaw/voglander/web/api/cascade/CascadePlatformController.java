package io.github.lunasaw.voglander.web.api.cascade;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.common.constant.ApiConstant;
import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.common.util.SensitiveUtils;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeClientScheduler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.config.properties.VoglanderSipClientProperties;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadePlatformCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadePlatformPageReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadePlatformUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.resp.CascadePlatformListResp;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadePlatformVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联上级平台管理 API
 *
 * <p>
 * 遵循 device 模块 Web 范式：分页 POST /getPage + @RequestBody PageReq，返回 VO/ListResp；
 * 时间字段统一 Unix 毫秒；CRUD 入参用 Req + WebAssembler 转 DTO。
 * </p>
 *
 * <p>
 * 调度生命周期约定（与 {@link CascadeClientScheduler} 配合）：
 * <ul>
 *   <li>新增成功后若 enabled=1，立即启动注册调度，避免"已启用但离线"；</li>
 *   <li>更新成功后先停旧任务再按最新记录重建——调度任务持有的是 DTO 快照，不重建则新配置不生效；</li>
 *   <li>删除必须先停调度再删记录——stopPlatform 需要按 id 读记录才取消得了任务。</li>
 * </ul>
 * </p>
 *
 * @author luna
 */
@Slf4j
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/cascade/platform")
@Tag(name = "级联平台管理", description = "级联上级平台 CRUD + 注册调度启停")
public class CascadePlatformController {

    @Autowired
    private CascadePlatformManager    cascadePlatformManager;

    @Autowired
    private CascadeClientScheduler    cascadeClientScheduler;

    @Autowired
    private CascadeWebAssembler       cascadeWebAssembler;

    @Autowired
    private VoglanderSipClientProperties sipClientProperties;

    @PostMapping
    @Operation(summary = "新增上级平台")
    public AjaxResult<Long> add(@Valid @RequestBody CascadePlatformCreateReq req) {
        CascadePlatformDTO dto = cascadeWebAssembler.toDTO(req);
        applyDefaults(dto);

        /* 先查重给友好提示，唯一索引作为并发重复提交的兜底 */
        if (cascadePlatformManager.getByPlatformId(dto.getPlatformId()) != null) {
            return AjaxResult.error("平台ID已存在：" + dto.getPlatformId());
        }
        Long id;
        try {
            id = cascadePlatformManager.add(dto);
        } catch (DuplicateKeyException e) {
            log.warn("级联平台新增重复: platformId={}", dto.getPlatformId());
            return AjaxResult.error("平台ID已存在：" + dto.getPlatformId());
        }

        log.info("级联平台新增: id={}, platformId={}, target={}:{}, localClientId={}@{}:{}, transport={}, username={}, password={}",
            id, dto.getPlatformId(), dto.getPlatformIp(), dto.getPlatformPort(),
            dto.getLocalClientId(), dto.getLocalIp(), dto.getLocalPort(),
            dto.getTransport(), dto.getUsername(), SensitiveUtils.mask(dto.getPassword()));
        startSchedulerIfEnabled(id);
        return AjaxResult.success(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新上级平台")
    public AjaxResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody CascadePlatformUpdateReq req) {
        CascadePlatformDTO dto = cascadeWebAssembler.toDTO(req);
        dto.setId(id);
        if (StringUtils.isBlank(dto.getPlatformDomain())) {
            applyPlatformDomainDefault(dto);
        }
        /* 前端不再回显密码：留空表示保持原值 */
        boolean passwordChanged = StringUtils.isNotBlank(dto.getPassword());
        if (!passwordChanged) {
            CascadePlatformDTO existing = cascadePlatformManager.getById(id);
            if (existing == null) {
                return AjaxResult.error("上级平台不存在：" + id);
            }
            dto.setPassword(existing.getPassword());
        }

        boolean ok = cascadePlatformManager.update(dto);
        if (ok) {
            log.info("级联平台更新: id={}, platformId={}, target={}:{}, transport={}, domain={}, passwordChanged={}, newPassword={}",
                id, dto.getPlatformId(), dto.getPlatformIp(), dto.getPlatformPort(),
                dto.getTransport(), dto.getPlatformDomain(), passwordChanged,
                passwordChanged ? SensitiveUtils.mask(dto.getPassword()) : "keep");
            restartScheduler(id);
        }
        return AjaxResult.success(ok);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除上级平台")
    public AjaxResult<Boolean> delete(@PathVariable Long id) {
        /* 顺序不可颠倒：stopPlatform 需按 id 读记录才能取消任务，先删记录会导致任务泄漏并继续发包 */
        try {
            cascadeClientScheduler.stopPlatform(id);
        } catch (Exception e) {
            log.warn("级联平台删除前停止调度失败: id={}, err={}", id, e.getMessage());
        }
        boolean ok = cascadePlatformManager.delete(id);
        log.info("级联平台删除: id={}, ok={}", id, ok);
        return AjaxResult.success(ok);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询上级平台详情")
    public AjaxResult<CascadePlatformVO> getById(@PathVariable Long id) {
        CascadePlatformDTO dto = cascadePlatformManager.getById(id);
        return AjaxResult.success(CascadePlatformVO.convertVO(dto));
    }

    @PostMapping("/getPage")
    @Operation(summary = "分页查询上级平台", description = "POST 条件分页，返回 total + items（VO，时间毫秒）")
    public AjaxResult<CascadePlatformListResp> getPage(
        @RequestBody(required = false) CascadePlatformPageReq pageReq,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size) {
        CascadePlatformDTO query = cascadeWebAssembler.pageReqToQueryDto(pageReq);
        Page<CascadePlatformDTO> dtoPage = cascadePlatformManager.getPage(query, page, size);

        List<CascadePlatformVO> items = dtoPage.getRecords().stream()
            .map(CascadePlatformVO::convertVO)
            .collect(Collectors.toList());

        CascadePlatformListResp resp = new CascadePlatformListResp();
        resp.setTotal(dtoPage.getTotal());
        resp.setItems(items);
        return AjaxResult.success(resp);
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "启用上级平台（enabled=1 + 启动注册保活调度）")
    public AjaxResult<Boolean> enable(@PathVariable Long id) {
        CascadePlatformDTO platform = cascadePlatformManager.getById(id);
        if (platform == null) {
            return AjaxResult.error("上级平台不存在：" + id);
        }
        /* 启用前检查 localIp：前端未填则用当前 SIP 客户端绑定 IP 回填并持久化 */
        if (StringUtils.isBlank(platform.getLocalIp())) {
            CascadePlatformDTO patch = new CascadePlatformDTO();
            patch.setId(id);
            patch.setLocalIp(sipClientProperties.getDomain());
            patch.setLocalPort(sipClientProperties.getPort());
            cascadePlatformManager.update(patch);
        }
        boolean ok = cascadePlatformManager.enablePlatform(id);
        if (ok) {
            log.info("级联平台启用: id={}, platformId={}", id, platform.getPlatformId());
            startSchedulerIfEnabled(id);
        }
        return AjaxResult.success(ok);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "停用上级平台（enabled=0 + 停止注册保活调度）")
    public AjaxResult<Boolean> disable(@PathVariable Long id) {
        cascadeClientScheduler.stopPlatform(id);
        boolean ok = cascadePlatformManager.disablePlatform(id);
        log.info("级联平台停用: id={}, ok={}", id, ok);
        return AjaxResult.success(ok);
    }

    @PostMapping("/refresh")
    @Operation(summary = "批量刷新注册调度（加载 enabled=1 平台启动任务）")
    public AjaxResult<Void> refresh() {
        log.info("级联注册调度刷新：重新加载 enabled=1 的上级平台");
        cascadeClientScheduler.refreshRegistrations();
        return AjaxResult.success();
    }

    /**
     * 补齐未传的默认值：本端地址取 SIP 客户端实际绑定地址，平台域缺省取平台ID前 10 位。
     *
     * <p>platform_domain 在库中是 NOT NULL，而前端标为可选，这里统一兜底避免插入失败。</p>
     *
     * @param dto 待保存的平台配置
     */
    private void applyDefaults(CascadePlatformDTO dto) {
        if (StringUtils.isBlank(dto.getLocalIp())) {
            dto.setLocalIp(sipClientProperties.getDomain());
        }
        if (dto.getLocalPort() == null) {
            dto.setLocalPort(sipClientProperties.getPort());
        }
        applyPlatformDomainDefault(dto);
    }

    /**
     * 平台域缺省值：取 20 位平台ID的前 10 位（国标 SIP 域惯例）。
     *
     * @param dto 待补齐的平台配置
     */
    private void applyPlatformDomainDefault(CascadePlatformDTO dto) {
        if (StringUtils.isBlank(dto.getPlatformId()) || dto.getPlatformId().length() < 10) {
            return;
        }
        dto.setPlatformDomain(dto.getPlatformId().substring(0, 10));
    }

    /**
     * 保存成功后按库中最新的完整记录启动调度（仅 enabled=1）；调度异常不影响业务结果。
     *
     * @param id 平台主键
     */
    private void startSchedulerIfEnabled(Long id) {
        try {
            CascadePlatformDTO latest = cascadePlatformManager.getById(id);
            if (latest != null && Integer.valueOf(1).equals(latest.getEnabled())) {
                cascadeClientScheduler.startPlatform(latest);
                log.info("级联平台注册调度已启动: id={}, platformId={}", id, latest.getPlatformId());
            }
        } catch (Exception e) {
            log.warn("级联平台注册调度启动失败: id={}, err={}", id, e.getMessage());
        }
    }

    /**
     * 配置变更后重启调度：旧任务持有的是更新前的 DTO 快照，必须停掉后用最新记录重建。
     *
     * @param id 平台主键
     */
    private void restartScheduler(Long id) {
        try {
            cascadeClientScheduler.stopPlatform(id);
        } catch (Exception e) {
            log.warn("级联平台更新后停止旧调度失败: id={}, err={}", id, e.getMessage());
        }
        startSchedulerIfEnabled(id);
    }
}
