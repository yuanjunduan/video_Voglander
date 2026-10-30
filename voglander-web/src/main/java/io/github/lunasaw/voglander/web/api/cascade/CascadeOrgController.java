package io.github.lunasaw.voglander.web.api.cascade;

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
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeOrgVO;
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

    @PostMapping
    @Operation(summary = "新增级联组织", description = "组织编码由后端按国标规则生成，返回新增记录 id")
    public AjaxResult<Long> add(@RequestBody CascadeOrgCreateReq req) {
        CascadeOrgDTO dto = cascadeWebAssembler.toDTO(req);
        Long id = cascadeOrgManager.add(dto);
        return AjaxResult.success(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新级联组织", description = "只允许改名称/排序/启停，父节点与编码不可变")
    public AjaxResult<Boolean> update(@PathVariable Long id, @RequestBody CascadeOrgUpdateReq req) {
        CascadeOrgDTO dto = cascadeWebAssembler.toDTO(req);
        dto.setId(id);
        boolean ok = cascadeOrgManager.update(dto);
        return AjaxResult.success(ok);
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
}
