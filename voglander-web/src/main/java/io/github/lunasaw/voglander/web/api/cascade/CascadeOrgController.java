package io.github.lunasaw.voglander.web.api.cascade;

import java.util.ArrayList;
import java.util.List;

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
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRevokePreviewDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgMoveReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeRevokeBatchReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeRevokeReq;
import io.github.lunasaw.voglander.web.api.cascade.resp.CascadeRevokeBatchResp;
import io.github.lunasaw.voglander.web.api.cascade.support.CascadeDeliveryGate;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeOrgVO;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeRevokePreviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 级联组织树管理 API。
 *
 * <p>组织是上级目录树的中间层：域节点 → 组织节点 → 通道节点。没有组织时通道只能直接挂在
 * 平台节点下，上级虽然收得到条目，但不会把它挂进任何业务分组。
 *
 * <p>组织编码（20 位国标编码）一律由后端生成、<b>不可变更</b>——上级按编码建树，
 * 改编码等价于删掉旧节点再建一个新节点，已绑定的通道会变成孤儿。
 *
 * @author luna
 */
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/cascade/org")
@Tag(name = "级联组织管理", description = "级联组织树 CRUD + 树形查询")
public class CascadeOrgController {

    @Autowired
    private CascadeOrgManager    cascadeOrgManager;

    @Autowired
    private CascadeWebAssembler  cascadeWebAssembler;

    @Autowired
    private CascadeDeliveryGate  cascadeDeliveryGate;

    @PostMapping
    @Operation(summary = "新增级联组织", description = "组织编码由后端按国标规则生成，返回新增记录 id")
    public AjaxResult<Long> add(@RequestBody CascadeOrgCreateReq req) {
        CascadeOrgDTO dto = cascadeWebAssembler.toDTO(req);
        Long id = cascadeOrgManager.add(dto);
        return AjaxResult.success(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新级联组织",
        description = "只允许改名称/排序/启停与通道处置策略；1→0 会连带整棵子树从上级目录移除")
    public AjaxResult<Boolean> update(@PathVariable Long id, @RequestBody CascadeOrgUpdateReq req) {
        CascadeOrgDTO dto = cascadeWebAssembler.toDTO(req);
        dto.setId(id);
        boolean ok = cascadeOrgManager.update(dto);
        return AjaxResult.success(ok);
    }

    /**
     * 移动组织：把整棵子树改挂到另一个父组织下。
     *
     * <p>之所以单独一个接口而不是复用 {@code PUT}：上级目录没有“移动节点”语义，快照对已存在的节点
     * 只做 upsert、不迁移父组织，所以这里必须走「按旧父删干净 → 按新父重推」这条链路。
     * 它与改名的代价完全不同（上级会重建整棵子树），混在一个接口里用户很难预期后果。
     */
    @PostMapping("/{id}/move")
    @Operation(summary = "移动级联组织（含整棵子树）",
        description = "targetParentId 为空表示移到平台根节点；上级侧会先按旧父删除再按新父重推，编码不变")
    public AjaxResult<Boolean> move(@PathVariable Long id, @RequestBody(required = false) CascadeOrgMoveReq req) {
        Long targetParentId = req == null ? null : req.getTargetParentId();
        return AjaxResult.success(cascadeOrgManager.move(id, targetParentId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除级联组织", description = "仍有子组织或已绑定通道时拒绝删除")
    public AjaxResult<Boolean> delete(@PathVariable Long id) {
        boolean ok = cascadeOrgManager.delete(id);
        return AjaxResult.success(ok);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询级联组织详情")
    public AjaxResult<CascadeOrgVO> getById(@PathVariable Long id) {
        CascadeOrgDTO dto = cascadeOrgManager.getById(id);
        return AjaxResult.success(cascadeWebAssembler.toVO(dto));
    }

    @GetMapping("/list")
    @Operation(summary = "平铺查询级联组织", description = "按 sortOrder 升序返回，不含 children")
    public AjaxResult<List<CascadeOrgVO>> list(@RequestParam String platformId) {
        List<CascadeOrgDTO> dtos = cascadeOrgManager.listByPlatformId(platformId);
        return AjaxResult.success(cascadeWebAssembler.toVOList(dtos));
    }

    @GetMapping("/tree")
    @Operation(summary = "树形查询级联组织",
        description = "子组织挂在 children 上，并带直属通道数；传 rootOrgId 时只返回该组织的子树，供前端懒加载")
    public AjaxResult<List<CascadeOrgVO>> tree(@RequestParam String platformId,
        @RequestParam(required = false) String rootOrgId) {
        List<CascadeOrgDTO> roots = cascadeOrgManager.tree(platformId, rootOrgId);
        return AjaxResult.success(cascadeWebAssembler.toVOList(roots));
    }

    @GetMapping("/{id}/tree")
    @Operation(summary = "以某组织为根的子树（树形）",
        description = "等价于 /tree?rootOrgId=，路径参数为主键 id；返回的顶层节点就是该组织自身")
    public AjaxResult<List<CascadeOrgVO>> subtree(@PathVariable Long id) {
        CascadeOrgDTO org = cascadeOrgManager.getById(id);
        if (org == null) {
            return AjaxResult.success(List.of());
        }
        List<CascadeOrgDTO> roots = cascadeOrgManager.tree(org.getPlatformId(), org.getCascadeOrgId());
        return AjaxResult.success(cascadeWebAssembler.toVOList(roots));
    }

    @GetMapping("/{id}/descendants")
    @Operation(summary = "平铺查询某组织下的全部子组织",
        description = "按 orgPath 前缀一次取全任意层级；includeSelf=true 时把自身也带上，供批量迁移/多选使用")
    public AjaxResult<List<CascadeOrgVO>> descendants(@PathVariable Long id,
        @RequestParam(defaultValue = "false") boolean includeSelf) {
        CascadeOrgDTO org = cascadeOrgManager.getById(id);
        if (org == null) {
            return AjaxResult.success(List.of());
        }
        List<CascadeOrgDTO> dtos = cascadeOrgManager.listSubtree(org.getPlatformId(), org.getCascadeOrgId(), includeSelf);
        return AjaxResult.success(cascadeWebAssembler.toVOList(dtos));
    }

    /**
     * 取消同步的影响面预览。<b>只算不写</b>，供前端弹窗先告知后果再让用户确认。
     *
     * <p>为什么必须单独一个接口：{@code enabled} 是“整棵子树不进目录”，一个开关背后可能是
     * 几十个节点消失。没有预览，用户点完才发现一片区域不见了，而且取消同步之后
     * 通道是「一起消失」还是「上浮到根节点」两种归宿完全相反，必须让人看到再选。
     */
    @GetMapping("/{id}/revoke/preview")
    @Operation(summary = "取消同步影响面预览",
        description = "返回将被移除的组织/通道清单与投递风险提示；policy 传 CASCADE/REROUTE 可预览不同策略的差异")
    public AjaxResult<CascadeRevokePreviewVO> previewRevoke(@PathVariable Long id,
        @RequestParam(required = false) String policy) {
        CascadeRevokePreviewDTO dto = cascadeOrgManager.previewRevoke(id, policy);
        CascadeRevokePreviewVO vo = cascadeWebAssembler.toVO(dto);
        CascadeDeliveryGate.Report report = cascadeDeliveryGate.check(dto.getPlatformId());
        vo.setPlatformOnline(report.platformOnline());
        vo.setCatalogSubscribed(report.catalogSubscribed());
        vo.setDeliverable(report.deliverable());
        vo.setWarnings(new ArrayList<>(report.warnings()));
        return AjaxResult.success(vo);
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "取消同步该组织",
        description = "整棵子树从上级目录移除，本地数据一条不删；policy 决定通道是一并移除还是改挂域节点")
    public AjaxResult<Boolean> revoke(@PathVariable Long id, @RequestBody(required = false) CascadeRevokeReq req) {
        String policy = req == null ? null : req.getPolicy();
        return AjaxResult.success(cascadeOrgManager.revoke(id, policy));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "恢复同步该组织",
        description = "上级目录靠快照 upsert 把整棵子树加回来；祖先处于停报状态时拒绝")
    public AjaxResult<Boolean> publish(@PathVariable Long id) {
        return AjaxResult.success(cascadeOrgManager.publish(id));
    }

    /**
     * 批量取消同步。
     *
     * <p>逐条独立执行、逐条回结果：报文是单向的，一条失败不回滚其他已经推给上级的 DEL，
     * 否则会出现「本地显示在上报、上级其实已经删了」的错位。
     */
    @PostMapping("/revoke/batch")
    @Operation(summary = "批量取消同步组织",
        description = "逐条独立执行，不做事务回滚；返回每条的成功/失败原因，便于精确重试")
    public AjaxResult<CascadeRevokeBatchResp> revokeBatch(@RequestBody CascadeRevokeBatchReq req) {
        CascadeRevokeBatchResp resp = new CascadeRevokeBatchResp();
        List<Long> ids = req == null || req.getIds() == null ? List.of() : req.getIds();
        resp.setTotal(ids.size());
        int succeeded = 0;
        for (Long id : ids) {
            CascadeRevokeBatchResp.Item item = new CascadeRevokeBatchResp.Item();
            item.setId(id);
            try {
                CascadeOrgDTO org = cascadeOrgManager.getById(id);
                item.setOrgName(org == null ? null : org.getOrgName());
                item.setOk(cascadeOrgManager.revoke(id, req.getPolicy()));
                if (Boolean.TRUE.equals(item.getOk())) {
                    succeeded++;
                }
            } catch (RuntimeException e) {
                /* 单条失败不能中断整批：后端记录已改的就改了，结果如实回传 */
                item.setOk(false);
                item.setMessage(e.getMessage());
            }
            resp.getItems().add(item);
        }
        resp.setSucceeded(succeeded);
        resp.setFailed(ids.size() - succeeded);
        return AjaxResult.success(resp);
    }
}
