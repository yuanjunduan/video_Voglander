package io.github.lunasaw.voglander.web.api.cascade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CatalogVisibility;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelBatchBindReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelPageReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.resp.CascadeChannelListResp;
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
     * 取消单条通道同步：不再上报，映射记录保留可恢复。
     *
     * <p>与删除的区别：删除是物理删除且不可恢复；取消同步随时可以恢复，
     * 上级目录里的节点靠显式 DEL 移除（快照 upsert 不会因为节点「没出现」就删）。
     */
    @PostMapping("/{id}/revoke")
    @Operation(summary = "取消该通道同步", description = "向上级推 Catalog Event=DEL，记录保留可恢复")
    public AjaxResult<Boolean> revoke(@PathVariable Long id) {
        return AjaxResult.success(cascadeChannelManager.revoke(id));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "恢复该通道同步",
        description = "向上级推 Catalog Event=ADD；所属组织处于停报状态时拒绝，避免产生孤儿节点")
    public AjaxResult<Boolean> publish(@PathVariable Long id) {
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
        Map<String, CascadeChannelMeta> metaMap = dto == null
            ? Map.of() : channelMetaResolver.resolve(List.of(dto));
        CatalogVisibility visibility = dto == null ? null : cascadeOrgManager.catalogVisibility(dto.getPlatformId());
        return AjaxResult.success(toVO(dto, metaMap, visibility));
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
        /* 可见性按平台缓存：分页可能跨平台，但同一个平台只算一次 */
        Map<String, CatalogVisibility> visibilityByPlatform = new HashMap<>();
        List<CascadeChannelVO> items = records.stream()
            .map(dto -> toVO(dto, metaMap, visibilityByPlatform.computeIfAbsent(
                dto.getPlatformId(), cascadeOrgManager::catalogVisibility)))
            .collect(Collectors.toList());

        CascadeChannelListResp resp = new CascadeChannelListResp();
        resp.setTotal(dtoPage.getTotal());
        resp.setItems(items);
        return AjaxResult.success(resp);
    }

    /**
     * DTO → VO，并用解析结果覆盖五项目录元数据，同时补齐上级目录可见性。
     *
     * <p>直接展示库里的值会误导人：留空表示“自动派生”，页面必须看到派生后的真值；
     * 同理只看 {@code enabled} 会把「组织停报连带」的通道误显示成上报中。
     */
    private CascadeChannelVO toVO(CascadeChannelDTO dto, Map<String, CascadeChannelMeta> metaMap,
        CatalogVisibility visibility) {
        CascadeChannelVO vo = CascadeChannelVO.convertVO(dto);
        if (vo == null) {
            return null;
        }
        if (visibility != null) {
            boolean selfEnabled = dto.getEnabled() != null && dto.getEnabled() == 1;
            boolean orgVisible = visibility.isChannelVisible(dto.getCascadeOrgId());
            vo.setEffective(selfEnabled && orgVisible);
            /* 自身开关关掉不是组织的锅，不归到「被上级组织连带」里 */
            vo.setBlockedByOrgName(orgVisible ? null : visibility.blockedOrgName(dto.getCascadeOrgId()));
        }
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
}
