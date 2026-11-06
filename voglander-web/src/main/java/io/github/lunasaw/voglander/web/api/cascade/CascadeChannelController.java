package io.github.lunasaw.voglander.web.api.cascade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.common.constant.ApiConstant;
import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMeta;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeChannelMetaResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelBatchPublishResultDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CatalogVisibility;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelBatchBindReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelBatchPublishReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelPageReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.resp.CascadeChannelListResp;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeChannelBatchPublishResultVO;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeChannelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 级联通道映射管理 API
 *
 * <p>
 * 遵循 device 模块 Web 范式：分页 POST /getPage + @RequestBody PageReq，返回 VO/ListResp；
 * 时间字段统一 Unix 毫秒；CRUD 入参用 Req + WebAssembler 转 DTO。
 * </p>
 *
 * @author luna
 */
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/cascade/channel")
@Tag(name = "级联通道管理", description = "级联通道映射 CRUD + 分页查询")
public class CascadeChannelController {

    @Autowired
    private CascadeChannelManager cascadeChannelManager;

    @Autowired
    private CascadeWebAssembler   cascadeWebAssembler;

    @Autowired
    private CascadeChannelMetaResolver channelMetaResolver;

    @Autowired
    private CascadeOrgManager           cascadeOrgManager;

    @PostMapping
    @Operation(summary = "新增级联通道映射")
    public AjaxResult<Long> add(@RequestBody CascadeChannelCreateReq req) {
        CascadeChannelDTO dto = cascadeWebAssembler.toDTO(req);
        Long id = cascadeChannelManager.add(dto);
        return AjaxResult.success(id);
    }

    @PostMapping("/batchBind")
    @Operation(summary = "批量绑定级联通道", description = "同一上级平台下批量绑定本地通道，已存在的跳过，返回新增条数")
    public AjaxResult<Integer> batchBind(@RequestBody CascadeChannelBatchBindReq req) {
        int added = cascadeChannelManager.batchBind(cascadeWebAssembler.toBatchDTOList(req));
        return AjaxResult.success(added);
    }

    /**
     * 批量推送：一批本地通道 × 一批上级平台。
     *
     * <p>与 {@code /batchBind} 的分工：那个是「单平台 + 通道编码列表」，回答不了「一次推给多个上级」
     * 和「把这个组织下的通道全推上去」。这里两种目标通道来源二选一（{@code channels} 或 {@code orgId}），
     * 平台可以多选，返回逐类计数与失败明细——少掉的那些是「早推过」还是「真失败」，页面要能分开讲。
     */
    @PostMapping("/batchPublish")
    @Operation(summary = "批量推送级联通道",
        description = "多平台批量推送：channels 为勾选通道，orgId+includeChildren 为按组织取通道（二选一，orgId 优先）；"
            + "已存在只跳过不自动改回上报，返回新建/跳过/失败明细")
    public AjaxResult<CascadeChannelBatchPublishResultVO> batchPublish(
        @RequestBody CascadeChannelBatchPublishReq req) {
        CascadeChannelBatchPublishResultDTO result =
            cascadeChannelManager.batchPublish(cascadeWebAssembler.toBatchPublishDTO(req));
        return AjaxResult.success(cascadeWebAssembler.toBatchPublishVO(result));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新级联通道映射",
        description = "enabled 1→0 会向上一级推 DEL 让通道消失，0→1 推 ADD；改组织归属会重推整棵目录")
    public AjaxResult<Boolean> update(@PathVariable Long id, @RequestBody CascadeChannelUpdateReq req) {
        CascadeChannelDTO dto = cascadeWebAssembler.toDTO(req);
        dto.setId(id);
        boolean ok = cascadeChannelManager.update(dto);
        return AjaxResult.success(ok);
    }

    /**
     * 取消单条通道的上报：通道在上级目录里消失，映射记录保留可恢复。
     *
     * <p>与删除的区别：删除是物理删除且不可恢复；停用随时可以恢复，
     * 上级目录里的节点靠显式 DEL 移除（快照 upsert 不会因为节点「没出现」就删）。
     */
    @PostMapping("/{id}/disable")
    @Operation(summary = "取消该通道上报", description = "向上级推 Catalog Event=DEL，映射记录保留可恢复")
    public AjaxResult<Boolean> disable(@PathVariable Long id) {
        return AjaxResult.success(cascadeChannelManager.revoke(id));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "恢复该通道上报",
        description = "向上级推 Catalog Event=ADD；所属组织不会出现在该平台目录里时拒绝，避免产生孤儿节点")
    public AjaxResult<Boolean> enable(@PathVariable Long id) {
        return AjaxResult.success(cascadeChannelManager.publish(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除级联通道映射")
    public AjaxResult<Boolean> delete(@PathVariable Long id) {
        boolean ok = cascadeChannelManager.delete(id);
        return AjaxResult.success(ok);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询级联通道详情")
    public AjaxResult<CascadeChannelVO> getById(@PathVariable Long id) {
        CascadeChannelDTO dto = cascadeChannelManager.getById(id);
        if (dto == null) {
            /* 必须强转：success(null) 会被重载决议到 success(String)，返回类型就成了 AjaxResult<Void> */
            return AjaxResult.success((CascadeChannelVO) null);
        }
        Map<String, CascadeChannelMeta> metaMap = channelMetaResolver.resolve(List.of(dto));
        return AjaxResult.success(toVO(dto, metaMap, cascadeOrgManager.catalogVisibility(),
            cascadeOrgManager.platformOrgView(dto.getPlatformId())));
    }

    @PostMapping("/getPage")
    @Operation(summary = "分页查询级联通道",
        description = "POST 条件分页，返回 total + items（VO，时间毫秒）；includeSubOrg=true 时 cascadeOrgId 按整棵子树过滤")
    public AjaxResult<CascadeChannelListResp> getPage(
        @RequestBody(required = false) CascadeChannelPageReq pageReq,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size) {
        CascadeChannelDTO query = cascadeWebAssembler.pageReqToQueryDto(pageReq);
        boolean includeSubOrg = pageReq != null && Boolean.TRUE.equals(pageReq.getIncludeSubOrg());
        Page<CascadeChannelDTO> dtoPage = cascadeChannelManager.getPage(query, page, size, includeSubOrg);

        List<CascadeChannelDTO> records = dtoPage.getRecords();
        /* 五项目录元数据要显示“上级实际会收到什么”，因此按覆盖值+设备派生一次性合成，不展出库里的裸值 */
        Map<String, CascadeChannelMeta> metaMap = channelMetaResolver.resolve(records);
        /* 可见性拆两层：本地可达性全库一份；「是否已发布到该平台」按平台各一份，同一平台只算一次 */
        CatalogVisibility localVisibility = cascadeOrgManager.catalogVisibility();
        Map<String, CascadePlatformOrgView> viewByPlatform = new HashMap<>();
        List<CascadeChannelVO> items = records.stream()
            .map(dto -> toVO(dto, metaMap, localVisibility, viewByPlatform.computeIfAbsent(
                dto.getPlatformId(), cascadeOrgManager::platformOrgView)))
            .collect(Collectors.toList());

        CascadeChannelListResp resp = new CascadeChannelListResp();
        resp.setTotal(dtoPage.getTotal());
        resp.setItems(items);
        return AjaxResult.success(resp);
    }

    /**
     * DTO → VO，并用解析结果覆盖五项目录元数据，同时补齐上级目录可见性。
     *
     * <p>直接展示库里的值会误导人：留空表示“自动派生”，页面必须看到派生后的真值。
     *
     * <p><b>“上级能不能看到”要两个条件同时成立</b>：通道自身开上报，且它所在的组织确实在该平台
     * 目录里（{@code platformOrgView}：本地启用链完整 ∩ 已发布，或虽立不起来但配置了 REROUTE）。
     * 只看通道自己的 {@code enabled} 会得到「页面显示上报中、上级一条也没收到」。
     */
    private CascadeChannelVO toVO(CascadeChannelDTO dto, Map<String, CascadeChannelMeta> metaMap,
        CatalogVisibility localVisibility, CascadePlatformOrgView platformView) {
        CascadeChannelVO vo = CascadeChannelVO.convertVO(dto);
        if (vo == null) {
            return null;
        }
        boolean selfEnabled = dto.getEnabled() != null && dto.getEnabled() == 1;
        boolean orgVisible = orgInCatalog(dto.getCascadeOrgId(), platformView);
        vo.setEffective(selfEnabled && orgVisible);
        /* 自身开关关掉不是组织的锅；组织不在目录里才归因到「被哪一级连带」 */
        vo.setBlockedByOrgName(orgVisible || localVisibility == null
            ? null : localVisibility.blockedOrgName(dto.getCascadeOrgId()));
        CascadeChannelMeta meta = metaMap.get(
            DeviceChannelManager.channelKey(dto.getLocalDeviceId(), dto.getLocalChannelId()));
        if (meta == null) {
            meta = CascadeChannelMeta.DEFAULT;
        }
        vo.setManufacturer(meta.manufacturer());
        vo.setModel(meta.model());
        vo.setAddress(meta.address());
        vo.setIpAddress(meta.ipAddress());
        vo.setPtzType(meta.ptzType());
        return vo;
    }

    /** 组织是否会在该平台目录里出现（不挂组织的通道挂域节点，同样可见）。 */
    private boolean orgInCatalog(String orgCode, CascadePlatformOrgView platformView) {
        if (StringUtils.isBlank(orgCode)) {
            return true;
        }
        if (platformView == null) {
            return false;
        }
        for (CascadePlatformOrgDTO org : platformView.catalogOrgs()) {
            if (orgCode.equals(org.getLocalCode())) {
                return true;
            }
        }
        return platformView.reroutedLocalCodes().contains(orgCode);
    }
}
