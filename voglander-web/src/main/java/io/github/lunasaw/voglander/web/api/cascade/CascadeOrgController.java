package io.github.lunasaw.voglander.web.api.cascade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeReapplyResult;
import io.github.lunasaw.voglander.manager.code.CascadeCodeResolver;
import io.github.lunasaw.voglander.manager.code.CascadeCodeRuleService;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRevokePreviewDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.web.api.cascade.assembler.CascadeWebAssembler;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgMoveReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgPublishReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeRevokeBatchReq;
import io.github.lunasaw.voglander.web.api.cascade.resp.CascadeRevokeBatchResp;
import io.github.lunasaw.voglander.web.api.cascade.support.CascadeDeliveryGate;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeOrgVO;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadePlatformOrgCatalogVO;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadePublishVO;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeRevokePreviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 级联组织树管理 API。
 *
 * <p>组织是上级目录树的中间层：域节点 → 组织节点 → 通道节点。没有组织时通道只能直接挂在
 * 平台节点下，上级虽然收得到条目，但不会把它挂进任何业务分组。
 *
 * <p><b>本接口不带 {@code platformId}</b>：组织是本地资产，全库一棵树；「推给哪些上级」由
 * {@code /{id}/publish} 独立维护。把平台压进 CRUD 会让「改个名字」与「改发布范围」共用一个入口，
 * 也会让同一组织推给多个上级时的编码覆盖无处安放。
 *
 * <p><b>两套启停语义，不要混用</b>
 * <ul>
 *   <li>{@code enabled}（本接口）= 本地启停：这个组织不用了，其下设备与通道从<b>所有</b>平台消失</li>
 *   <li>发布行的 {@code enabled}=0 = 只不推给那个平台：组织在本地照常可用</li>
 * </ul>
 *
 * <p>组织编码（20 位国标编码）一律由后端按规则生成，<b>不接受前端直接填写</b>——上级按编码建树，
 * 改编码等价于删掉旧节点再建一个新节点。唯一的改写入口是 {@code /{id}/code/reapply}：
 * 它按当前段位配置整棵子树重算，并负责把上级侧的旧节点 {@code DEL} 干净。
 *
 * @author luna
 */
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/cascade/org")
@Tag(name = "级联组织管理", description = "本地组织树 CRUD + 树形查询 + 发布范围配置")
public class CascadeOrgController {

    @Autowired
    private CascadeOrgManager    cascadeOrgManager;

    @Autowired
    private CascadePublishManager cascadePublishManager;

    @Autowired
    private CascadeWebAssembler  cascadeWebAssembler;

    @Autowired
    private CascadeDeliveryGate  cascadeDeliveryGate;

    @Autowired
    private CascadeCodeRuleService cascadeCodeRuleService;

    @Autowired
    private CascadeCodeResolver    cascadeCodeResolver;

    @PostMapping
    @Operation(summary = "新增级联组织", description = "组织编码由后端按国标规则生成；platformIds 可同时配置发布范围")
    public AjaxResult<Long> add(@RequestBody CascadeOrgCreateReq req) {
        CascadeOrgDTO dto = cascadeWebAssembler.toDTO(req);
        Long id = cascadeOrgManager.add(dto);
        return AjaxResult.success(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新级联组织",
        description = "只允许改名称/排序/本地启停与编码段；本地停用会连带整棵子树从各平台目录移除")
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
        description = "targetParentId 为空表示移到根节点；上级侧会先按旧父删除再按新父重推，编码不变")
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
        CascadeOrgVO vo = dto == null ? null : cascadeWebAssembler.toVO(dto,
            cascadeOrgManager.catalogVisibility());
        return AjaxResult.success(vo);
    }

    @GetMapping("/list")
    @Operation(summary = "平铺查询级联组织", description = "按 sortOrder 升序返回，不含 children")
    public AjaxResult<List<CascadeOrgVO>> list() {
        List<CascadeOrgDTO> dtos = cascadeOrgManager.listAll();
        return AjaxResult.success(cascadeWebAssembler.toVOList(dtos,
            cascadeOrgManager.catalogVisibility()));
    }

    @GetMapping("/tree")
    @Operation(summary = "树形查询级联组织",
        description = "子组织挂在 children 上，并带直属通道数与已发布平台；传 rootOrgCode 时只返回该组织的子树，供前端懒加载")
    public AjaxResult<List<CascadeOrgVO>> tree(@RequestParam(required = false) String rootOrgCode) {
        List<CascadeOrgDTO> roots = StringUtils.isBlank(rootOrgCode)
            ? cascadeOrgManager.tree() : cascadeOrgManager.treeSubtree(rootOrgCode);
        return AjaxResult.success(cascadeWebAssembler.toVOList(roots,
            cascadeOrgManager.catalogVisibility()));
    }

    @GetMapping("/{id}/tree")
    @Operation(summary = "以某组织为根的子树（树形）",
        description = "等价于 /tree?rootOrgCode=，路径参数为主键 id；返回的顶层节点就是该组织自身")
    public AjaxResult<List<CascadeOrgVO>> subtree(@PathVariable Long id) {
        CascadeOrgDTO org = cascadeOrgManager.getById(id);
        if (org == null) {
            return AjaxResult.success(List.of());
        }
        return AjaxResult.success(cascadeWebAssembler.toVOList(
            cascadeOrgManager.treeSubtree(org.getOrgCode()), cascadeOrgManager.catalogVisibility()));
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
        List<CascadeOrgDTO> dtos = cascadeOrgManager.listSubtree(org.getOrgCode(), includeSelf);
        return AjaxResult.success(cascadeWebAssembler.toVOList(dtos,
            cascadeOrgManager.catalogVisibility()));
    }

    /**
     * 本地停用的影响面预览。<b>只算不写</b>，供前端弹窗先告知后果再让用户确认。
     *
     * <p>为什么必须单独一个接口：{@code enabled} 是“整棵子树不进目录”，一个开关背后可能是
     * 几十个节点消失。没有预览，用户点完才发现一片区域不见了，而且通道是「一起消失」还是
     * 「上浮到域节点」取决于<b>各平台各自的</b>发布策略，必须让人看到再选。
     */
    @GetMapping("/{id}/disable/preview")
    @Operation(summary = "本地停用影响面预览",
        description = "返回将被移除的组织/通道清单，以及每个已发布平台各自的通道处置策略与投递风险")
    public AjaxResult<CascadeRevokePreviewVO> previewDisable(@PathVariable Long id) {
        CascadeRevokePreviewDTO dto = cascadeOrgManager.previewDisable(id);
        CascadeRevokePreviewVO vo = cascadeWebAssembler.toVO(dto);
        for (String platformId : dto.getPublishedPlatformIds()) {
            vo.getDeliveries().add(buildDelivery(platformId, dto.getRevokePolicyByPlatform().get(platformId), vo));
        }
        if (vo.getDeliveries().isEmpty()) {
            vo.getWarnings().add("该组织尚未发布到任何上级平台，停用不会发出任何报文");
        }
        return AjaxResult.success(vo);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "本地停用该组织",
        description = "整棵子树从各平台目录移除，本地数据一条不删；通道按各平台的发布策略一并移除或上浮")
    public AjaxResult<Boolean> disable(@PathVariable Long id) {
        return AjaxResult.success(cascadeOrgManager.disable(id));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "本地启用该组织",
        description = "上级目录靠快照 upsert 把整棵子树加回来；祖先处于停用状态时拒绝")
    public AjaxResult<Boolean> enable(@PathVariable Long id) {
        return AjaxResult.success(cascadeOrgManager.enable(id));
    }

    /**
     * 从单个上级平台撤回该组织。
     *
     * <p><b>为什么必须单独一个接口</b>：『取消勾选某个平台』只翻发布行的 {@code enabled}、不发报文，
     * 上级目录里那棵树会一直留着（快照是增量 upsert 语义）。撤回要先把 {@code Event=DEL} 推过去
     * 并等上级确认，确认后才改本地，且只影响这一路平台。
     */
    @PostMapping("/{id}/revoke")
    @Operation(summary = "从单个上级平台撤回组织",
        description = "整棵子树在该平台目录删除，其它平台不受影响；上级未确认时本地配置不变")
    public AjaxResult<Boolean> revoke(@PathVariable Long id, @RequestParam String platformId) {
        return AjaxResult.success(cascadeOrgManager.revokeFromPlatform(id, platformId));
    }

    /**
     * 按当前编码规则重算该组织子树的国标编码。
     *
     * <p><b>为什么不复用 {@code PUT}</b>：改一个节点的段位只是一个字段，而重算会同时改写整棵子树的
     * {@code org_code}、{@code org_path}、发布行的 {@code object_key} 与通道的 {@code parent_key}，
     * 并逐平台推 {@code DEL} 让上级重建。代价与改名完全不是一个量级，必须单独入口、单独预览。
     *
     * <p><b>{@code dryRun} 默认 true</b>：这是高危批量覆盖，宁可多一次点击，也不要因为前端漏传参数
     * 就在生产上直接改掉一批编码。
     *
     * <p>只重算组织，不动设备与通道编码——通道编码一旦上报过，就是上级侧历史录像、订阅与点播的
     * 引用键，跟着换会让每路摄像机在上级变成一台“新设备”。
     */
    @PostMapping("/{id}/code/reapply")
    @Operation(summary = "按当前编码规则重算组织编码",
        description = "dryRun=true 只预览不落库；冲突时整批拒绝并在 conflicts 中一次列全；只重算组织，通道编码保持稳定")
    public AjaxResult<CascadeCodeReapplyResult> reapplyCode(@PathVariable Long id,
        @RequestParam(defaultValue = "true") boolean dryRun) {
        return AjaxResult.success(cascadeCodeRuleService.reapply(id, dryRun));
    }

    /**
     * 批量本地停用。
     *
     * <p>逐条独立执行、逐条回结果：报文是单向的，一条失败不回滚其他已经推给上级的 DEL，
     * 否则会出现「本地显示在上报、上级其实已经删了」的错位。
     */
    @PostMapping("/disable/batch")
    @Operation(summary = "批量本地停用组织",
        description = "逐条独立执行，不做事务回滚；返回每条的成功/失败原因，便于精确重试")
    public AjaxResult<CascadeRevokeBatchResp> disableBatch(@RequestBody CascadeRevokeBatchReq req) {
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
                item.setOk(cascadeOrgManager.disable(id));
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

    /**
     * 查询某上级平台目录里的组织（只读）。
     *
     * <p><b>为什么必须有这个出口</b>：{@code /tree} 是本地视角，把「推给谁」交给每行的发布配置；
     * 用户真正想问的是「那个上级到底收到了哪些组织」。两者被发布层与本地启停同时过滤，
     * 没有这个接口只能逐行人工比对，或者让用户跑到上级平台上去看。
     *
     * <p>返回两份清单：目录内（上级这一刻真实存在的节点，带该平台的编码与名称覆盖）与
     * 阻断（勾了发布却立不起来），后者就是「我明明勾了为什么上级没有」的答案。
     *
     * <p>路径不用 {@code /{id}/published}：主语是<b>平台目录</b>而不是某个本地组织；
     * 平台页的「已推送组织」与组织页的「平台视角」共用这一个出口。
     */
    @GetMapping("/published")
    @Operation(summary = "查询某上级平台目录里的组织",
        description = "返回该平台目录内立得起来的组织（含平台编码/名称覆盖）与「已勾选但立不起来」的阻断清单")
    public AjaxResult<CascadePlatformOrgCatalogVO> listPublished(@RequestParam String platformId) {
        return AjaxResult.success(
            cascadeWebAssembler.toVO(cascadeOrgManager.platformOrgCatalog(platformId), platformId));
    }

    /**
     * 查询该组织的发布配置（含已停用的发布行）。
     *
     * <p>含停用行是刻意的：页面要区分「配了但没开」与「根本没配」，前者打开开关即可，
     * 后者要先勾选平台。
     */
    @GetMapping("/{id}/publish")
    @Operation(summary = "查询组织发布配置", description = "返回该组织在各上级平台的可见性、编码覆盖与通道处置策略")
    public AjaxResult<List<CascadePublishVO>> listPublish(@PathVariable Long id) {
        CascadeOrgDTO org = cascadeOrgManager.getById(id);
        if (org == null) {
            throw new IllegalArgumentException("级联组织不存在: id=" + id);
        }
        List<CascadePublishDTO> publishes = cascadePublishManager.listByObject(PublishObjectType.ORG.name(),
            org.getOrgCode());
        return AjaxResult.success(cascadeWebAssembler.toPublishVOList(publishes));
    }

    /**
     * 配置该组织发布到哪些平台、用什么编码与策略。
     *
     * <p><b>不删行只置停用</b>：删掉等于把编码覆盖一起丢掉，重新勾选时用户得重填一遍。
     * 真正要清理时删组织，发布的残行随 {@code deleteByObject} 一起走。
     *
     * <p><b>写入前先校验父链</b>：只勾选叶子组织时它在上级目录里立不起来，组装器会静默剔除——
     * 页面显示「推送中」而上级一条都收不到。这里直接报错，让用户知道还要补推哪一级。
     */
    @PutMapping("/{id}/publish")
    @Operation(summary = "配置组织发布范围",
        description = "platformIds 为本次要发布的平台；未勾选的平台置为停用但保留编码覆盖；祖先未发布时拒绝")
    public AjaxResult<List<CascadePublishVO>> configurePublish(@PathVariable Long id,
        @RequestBody(required = false) CascadeOrgPublishReq req) {
        CascadeOrgDTO org = cascadeOrgManager.getById(id);
        if (org == null) {
            throw new IllegalArgumentException("级联组织不存在: id=" + id);
        }
        List<String> platformIds = req == null || req.getPlatformIds() == null
            ? List.of() : req.getPlatformIds();
        /* 必须在写发布行之前：先写再报错会让页面回读到「已勾选但上级看不到」的半成品 */
        cascadeOrgManager.assertAncestorsPublished(id, platformIds);
        cascadePublishManager.configurePlatforms(PublishObjectType.ORG.name(), org.getOrgCode(), platformIds);

        if (req != null && !platformIds.isEmpty()) {
            applyRevokePolicies(org.getOrgCode(), platformIds, req.getRevokePolicyByPlatform());
            applyCodeOverrides(org.getOrgCode(), platformIds, req.getPublishCodeByPlatform(),
                req.getPublishNameByPlatform());
        }
        /* 生成必须排在 override 之后：applyCodeOverrides 写的就是 publish_code，先算后覆盖会把
           AUTO 标记落到人工值上；反过来，已填的覆盖码会被 resolve 原样返回，不会再被规则改写 */
        cascadeCodeResolver.resolveAll(PublishObjectType.ORG.name(), org.getOrgCode(), platformIds);
        /* 回读整份配置：前端拿到的是落库后的真实值（含未勾选但保留覆盖的行） */
        return AjaxResult.success(cascadeWebAssembler.toPublishVOList(
            cascadePublishManager.listByObject(PublishObjectType.ORG.name(), org.getOrgCode())));
    }

    /** 逐平台下发通道处置策略；只处理本次勾选的平台，避免顺手改掉已停用行的策略。 */
    private void applyRevokePolicies(String orgCode, List<String> platformIds, Map<String, String> policyByPlatform) {
        if (policyByPlatform == null || policyByPlatform.isEmpty()) {
            return;
        }
        for (String platformId : platformIds) {
            if (policyByPlatform.containsKey(platformId)) {
                cascadePublishManager.setRevokePolicy(platformId, PublishObjectType.ORG.name(), orgCode,
                    policyByPlatform.get(platformId));
            }
        }
    }

    /**
     * 逐平台下发编码/名称覆盖；值留空表示沿用本地值。
     *
     * <p>只对本次勾选的平台写：给一个没勾选的平台写覆盖会顺手把它的发布行建出来，
     * 等于在用户不知情的情况下把一个组织推给了那个上级。
     *
     * <p><b>写进去的码是人工值</b>：{@code CascadePublishManager#publish} 会标
     * {@code code_source=MANUAL}，后面那次 {@code resolveAll} 只会给仍为空的行发号，
     * 不会把用户刚填的值换成规则码（2.5）。
     */
    private void applyCodeOverrides(String orgCode, List<String> platformIds, Map<String, String> codeByPlatform,
        Map<String, String> nameByPlatform) {
        for (String platformId : platformIds) {
            boolean hasCode = codeByPlatform != null && codeByPlatform.containsKey(platformId);
            boolean hasName = nameByPlatform != null && nameByPlatform.containsKey(platformId);
            if (!hasCode && !hasName) {
                continue;
            }
            CascadePublishDTO publish = new CascadePublishDTO();
            publish.setPlatformId(platformId);
            publish.setObjectType(PublishObjectType.ORG.name());
            publish.setObjectKey(orgCode);
            if (hasCode) {
                publish.setPublishCode(codeByPlatform.get(platformId));
            }
            if (hasName) {
                publish.setPublishName(nameByPlatform.get(platformId));
            }
            cascadePublishManager.publish(publish);
        }
    }

    /** 平台维度的投递条件：在线 / 目录订阅 / 风险提示，汇总到预览出参。 */
    private CascadeRevokePreviewVO.DeliveryVO buildDelivery(String platformId, String revokePolicy,
        CascadeRevokePreviewVO vo) {
        CascadeRevokePreviewVO.DeliveryVO delivery = new CascadeRevokePreviewVO.DeliveryVO();
        delivery.setPlatformId(platformId);
        delivery.setRevokePolicy(revokePolicy);
        CascadeDeliveryGate.Report report = cascadeDeliveryGate.check(platformId);
        delivery.setPlatformOnline(report.platformOnline());
        delivery.setCatalogSubscribed(report.catalogSubscribed());
        delivery.setDeliverable(report.deliverable());
        delivery.setWarnings(new ArrayList<>(report.warnings()));
        /* 顶层提示带上平台前缀：多个平台各有各的问题，混在一起会分不清该修哪个 */
        for (String warning : report.warnings()) {
            vo.getWarnings().add("[" + platformId + "] " + warning);
        }
        return delivery;
    }
}
