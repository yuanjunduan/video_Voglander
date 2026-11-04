package io.github.lunasaw.voglander.manager.manager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.RevokePolicy;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.assembler.CascadeAssembler;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder.Segments;
import io.github.lunasaw.voglander.manager.code.CascadeCodeResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeBlockedOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgCatalog;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRevokePreviewDTO;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联组织树管理器——<b>本地资产</b>视角，整库只维护一棵组织树。
 *
 * <p><b>为什么组织不再属于某个平台</b>：行业里「每个上级一套组织树」的做法，本质是给
 * 「不同上级要求不同层级」留余地；实际部署中绝大多数组织结构是同一套（园区/楼栋/部门），
 * 重复建树的维护成本远大于那点灵活性。真正需要按平台区分的只有<b>编码</b>与<b>是否上报</b>，
 * 不是<b>结构</b>。所以本类的方法签名里一个 {@code platformId} 都没有，
 * 平台维度全部交给 {@link CascadePublishManager}。
 *
 * <p><b>编码规则</b>：{@code 中心编码(8) + 行业编码(2) + 类型编码(3) + 网络标识(1) + 序号(6)}，
 * 类型编码默认 215（业务分组），可由 {@code gateway.cascade.org-type} 调整为 216（虚拟组织）；
 * 中心编码前缀取 {@code gateway.cascade.code-prefix}（默认 3402000000）。
 * 这是<b>本地</b>编码，推给不同上级用不同编码时写到该平台的 {@code publish_code}。
 *
 * <p><b>停用与不发布的区别</b>：{@code enabled=0} 是本地语义——这个组织不用了，其下设备与通道
 * 从<b>所有</b>平台的目录里消失；「不推给某个平台」是发布行 {@code enabled=0}，组织在本地照常可用。
 * 两者混用会让「上级看不到」变成一件无法归因的事。
 */
@Slf4j
@Component
public class CascadeOrgManager {

    /** 编码前缀兜底值（中心 8 位 + 行业 2 位） */
    private static final String DEFAULT_PREFIX = "3402000000";
    /** 国标编码中参与「前缀」的长度：中心 8 位 + 行业 2 位 */
    private static final int    PREFIX_LENGTH  = 10;
    /** 物化路径分隔符：org_path 形如 {@code /A/B/}，前后都带分隔符，前缀匹配才能恰好覆盖整层 */
    private static final String PATH_SEPARATOR = "/";
    /** {@code org_path} 列宽上限（VARCHAR(512)，约 24 层国标编码），移动后不得越界 */
    private static final int    ORG_PATH_MAX_LENGTH = 512;
    /**
     * 两阶段改编码的中间前缀。
     *
     * <p>不能是数字：{@code org_code} 全局唯一，中间值一旦也是合法国标编码，就可能撞上
     * 另一棵子树里真实存在的编码。{@code T<id>} 长度不超过 20，也永远不参与发号。
     */
    private static final String TMP_CODE_PREFIX = "T";

    @Autowired
    private CascadeOrgService     cascadeOrgService;

    @Autowired
    private CascadePublishManager  cascadePublishManager;

    @Autowired
    private CascadePublishedAssetResolver cascadePublishedAssetResolver;

    @Autowired
    private DeviceManager          deviceManager;

    @Autowired
    private DeviceChannelManager   deviceChannelManager;

    /**
     * 删除本地资产时的级联撤销与发布层清理（先取消上报、确认后才删本地）
     */
    @Autowired
    private CascadeAssetCleanup   cascadeAssetCleanup;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 编码生成器：本类建完发布行后要立即按规则发号并固化。
     *
     * <p>它与本类是<b>双向引用</b>（解析器要读组织级编码段与全局兜底），Spring Boot 默认禁止
     * 循环依赖，故用 {@link Lazy} 断环；生成逻辑不能在本类再写一份，否则两份回落实现必然分叉，
     * 同一组织在两个入口会拿到不同的码。
     */
    @Lazy
    @Autowired
    private CascadeCodeResolver cascadeCodeResolver;

    /**
     * 本地组织编码前缀（前 10 位：中心 8 + 行业 2）。
     *
     * <p>取本地配置而不是某个上级平台的 {@code local_client_id}：组织是本地资产，
     * 它的编码不该随「先对接了哪个上级」而变。推给要求其它编码的上级时用 {@code publish_code} 覆盖。
     */
    @Value("${gateway.cascade.code-prefix:" + DEFAULT_PREFIX + "}")
    private String codePrefix = DEFAULT_PREFIX;

    /** 组织节点的类型编码：215 业务分组（默认）/ 216 虚拟组织 */
    @Value("${gateway.cascade.org-type:215}")
    private String orgType = "215";

    /**
     * 编码规则总闸，默认关闭：关闭时组织编码与改造前逐字节一致。
     *
     * <p>与组织级的 {@code code_rule_enabled} 是<b>与</b>的关系，不是替代：总闸用于「整体不启用新规则」，
     * 组织开关用于「逐个组织放行」。两道锁都在，升级后才不会因为某个组织误开就让上级目录集体跳编码——
     * 而编码报文是单向的，跳错了没有回滚通道。
     */
    @Value("${gateway.cascade.code-rule.enabled:false}")
    private boolean codeRuleGlobalEnabled;

    /**
     * 新增组织，编码由本方法生成。
     *
     * @param dto orgName 必填；{@code parentId} 为空表示根组织；
     *            {@code platformIds} 非空时同时在创建时配置发布范围
     * @return 新增记录 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long add(CascadeOrgDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.hasText(dto.getOrgName(), "orgName 不能为空");
        CascadeOrgDTO parent = assertParentExists(dto.getParentId());

        /* 编码一律由后端生成：手填容易出现重复、类型位错误，上级判为非法节点 */
        dto.setOrgCode(nextOrgCode(dto, parent));
        /* 路径与编码同时生成：两者都是结构标识，不允许外部传入 */
        dto.setOrgPath(buildOrgPath(dto.getParentId(), dto.getOrgCode()));
        if (dto.getEnabled() == null) {
            dto.setEnabled(1);
        }
        /* 父链停用时新组织跟着落成停用：否则页面显示启用，实际因祖先被剪掉而看不到 */
        if (dto.getEnabled() != 0 && parent != null && !isEffectivelyEnabled(parent.getOrgCode())) {
            dto.setEnabled(0);
            log.info("父组织链路处于停用状态，新增组织按停用落库: parentId={}, parentCode={}",
                dto.getParentId(), parent.getOrgCode());
        }
        if (dto.getSortOrder() == null) {
            dto.setSortOrder(0);
        }
        CascadeOrgDO doObj = CascadeAssembler.toDO(dto);
        /* 六段「未配置」在库里只留 NULL 一种表示：Req 把纯空白归一成空串，原样落库会让「没配」
           同时存在 '' 与 NULL，将来按 IS NULL 写的统计会漏掉这些行。发号侧 CascadeCodeBuilder.resolve
           已逐段 trimToNull，这里补的是落库这一环 */
        doObj.setCodeProvince(StringUtils.trimToNull(dto.getCodeProvince()));
        doObj.setCodeCity(StringUtils.trimToNull(dto.getCodeCity()));
        doObj.setCodeCounty(StringUtils.trimToNull(dto.getCodeCounty()));
        doObj.setCodeIndustry(StringUtils.trimToNull(dto.getCodeIndustry()));
        doObj.setCodeType(StringUtils.trimToNull(dto.getCodeType()));
        doObj.setCodeNetwork(StringUtils.trimToNull(dto.getCodeNetwork()));
        cascadeOrgService.save(doObj);

        /* 先落发布范围再发事件：事件按发布行分发，顺序反了新组织一条报文都发不出去 */
        if (dto.getPlatformIds() != null) {
            cascadePublishManager.configurePlatforms(PublishObjectType.ORG.name(), doObj.getOrgCode(),
                dto.getPlatformIds());
            /* 发布行建好立刻按规则固化编码：与「配置发布范围」同一时序（先建行、后生成），
               等目录变更事件发出时上报码已经是钉住的，不会出现两条报文带两个码 */
            cascadeCodeResolver.resolveAll(PublishObjectType.ORG.name(), doObj.getOrgCode(), dto.getPlatformIds());
        }
        log.info("新增级联组织: orgName={}, orgCode={}, parentId={}, enabled={}",
            dto.getOrgName(), dto.getOrgCode(), dto.getParentId(), dto.getEnabled());
        /* 停用的组织上级本来就看不到，推 ADD 只会让事件桥白跑一次快照 */
        if (dto.getEnabled() != null && dto.getEnabled() == 1) {
            notifyCatalogChange(dto.getOrgCode(), "ADD");
        }
        return doObj.getId();
    }

    /**
     * 更新组织。只允许改名称 / 排序 / 启停 / 编码段规则。
     *
     * <p><b>父节点与编码都不可变</b>：改父节点等于在上级侧把节点从一棵树挪到另一棵，
     * 上级没有"移动"语义，只会表现为旧节点消失、新节点出现，设备与通道引用随之失效。
     * 真要调整层级，走 {@link #move}（会重推整棵子树）。
     *
     * <p><b>停用不只是改字段</b>：{@code 1 -> 0} 会把整棵子树从各平台目录里移除，必须显式推
     * {@code Event=DEL}（快照是增量 upsert 语义，不会因为节点「这次没出现」就删掉它），
     * 因此这里直接转 {@link #disable} 的处理链路；{@code 0 -> 1} 则重推快照让节点回来。
     *
     * <p><b>不要给本方法加 {@code @Transactional}</b>：{@code notifyCatalogChange} / {@code publishRevoke}
     * 是直接 publish 给 {@code @Async} 的 {@code CascadeEventBridge}，没有 afterCommit 保护——加上事务后
     * 异步线程会在提交前读库，静默发出旧快照（对比 {@link #move} 的 runAfterCommit）。代价是「
     * {@code updateById} 成功、段位归一的显式 SET 失败」这个极小窗口：此时一条事件都不会发，
     * 失败对调用方可见且可重试；比「加事务后静默发旧数据」可控，属刻意保留的取舍。
     */
    public boolean update(CascadeOrgDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.notNull(dto.getId(), "id 不能为空");
        CascadeOrgDTO existed = getById(dto.getId());
        Assert.notNull(existed, "组织不存在: id=" + dto.getId());
        /* 父节点变更走 move()：静默忽略会让调用方以为改成功了 */
        Assert.isTrue(dto.getParentId() == null, "编辑接口不支持修改父组织，请使用移动接口");

        CascadeOrgDO update = new CascadeOrgDO();
        update.setId(dto.getId());
        update.setOrgName(StringUtils.defaultIfBlank(dto.getOrgName(), existed.getOrgName()));
        update.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : existed.getSortOrder());
        update.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : existed.getEnabled());
        /* 编码段是预留配置：null 不动（updateById 跳过 null），空串清空 */
        applyCodeRule(update, dto);

        boolean wasEnabled = existed.getEnabled() == null || existed.getEnabled() == 1;
        boolean nowEnabled = update.getEnabled() == null || update.getEnabled() == 1;
        /* 0 -> 1 是「重新启用」：父及所有上级必须都启用，否则写进去只是一棵挂不上去的子树 */
        if (!wasEnabled && nowEnabled) {
            assertAncestorsEnabled(existed);
        }
        boolean ok = cascadeOrgService.updateById(update);
        /* 六段再收口一次：updateById 的 null 是「不改」，写不出「清空」，理由见 normalizeSubmittedSegments */
        normalizeSubmittedSegments(dto);
        log.info("更新级联组织: id={}, orgCode={}, orgName={}, enabled={}",
            dto.getId(), existed.getOrgCode(), update.getOrgName(), update.getEnabled());

        /* 改名只影响节点自身，但启停会连带整棵子树进出目录，两条链路完全不同 */
        if (ok) {
            if (wasEnabled && !nowEnabled) {
                publishRevoke(existed);
            } else {
                notifyCatalogChange(existed.getOrgCode(), "UPDATE");
            }
        }
        return ok;
    }

    /**
     * 移动组织：把整棵子树改挂到另一个父节点下（{@code targetParentId} 为空表示回到根节点）。
     *
     * <p><b>为什么要单独一个方法</b>：上级目录没有“移动节点”语义，快照对已存在的节点只做 upsert、
     * 不迁移父组织（海康实测），因此在上级侧只能「先按旧父删干净、再按新父重建」。
     * 这条链路的时序必须由一处控制：删除清单用<b>移动前</b>的旧父编码，重命名/排序类更新绝不能走这里。
     *
     * <p><b>编码不变</b>：{@code orgCode} 保持不变，发布行的编码覆盖与上级对节点的引用都不受影响；
     * 变的只有 {@code parent_id} 与整棵子树的 {@code org_path}。这正是「结构一份」的收益——
     * 组织移动只改本地一次，各平台按新路径重新组装即可。
     *
     * @param id             被移动的组织主键
     * @param targetParentId 目标父组织主键；{@code null} 表示移到根节点
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean move(Long id, Long targetParentId) {
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);
        Assert.isTrue(!Objects.equals(org.getParentId(), targetParentId),
            "目标父组织与当前父组织相同，无需移动");

        String selfCode = org.getOrgCode();
        String oldPath  = ensureOrgPath(org);
        Assert.hasText(oldPath, "组织物化路径缺失，无法安全移动: id=" + id);

        String newBasePath = PATH_SEPARATOR;
        if (targetParentId != null) {
            CascadeOrgDTO target = getById(targetParentId);
            Assert.notNull(target, "目标父组织不存在: id=" + targetParentId);
            String targetPath = ensureOrgPath(target);
            Assert.hasText(targetPath, "目标父组织物化路径缺失: id=" + targetParentId);
            /* 挂到自己或自己的子孙下会让整棵子树从树上脱落：子孙路径必然是自身路径的前缀延伸 */
            Assert.isTrue(!targetPath.startsWith(oldPath), "不能把组织移到它自己或它的子组织下");
            newBasePath = targetPath;
        }

        /* 子树与「旧父」节点清单必须在写库前算好：写完之后父编码已是新父，DEL 会删错树 */
        List<CascadeOrgDTO> subtree = postOrderSubtree(org);
        Map<Long, CascadeOrgDTO> oldIndex = indexById(listAll());
        Map<Long, String> newPathById = buildNewPaths(org, subtree, newBasePath);

        LambdaUpdateWrapper<CascadeOrgDO> uw = new LambdaUpdateWrapper<>();
        uw.eq(CascadeOrgDO::getId, id)
            .set(CascadeOrgDO::getParentId, targetParentId)
            .set(CascadeOrgDO::getOrgPath, newPathById.get(id));
        Assert.isTrue(cascadeOrgService.update(uw), "移动组织失败: id=" + id);
        for (CascadeOrgDTO node : subtree) {
            if (Objects.equals(node.getId(), id)) {
                continue;
            }
            CascadeOrgDO patch = new CascadeOrgDO();
            patch.setId(node.getId());
            patch.setOrgPath(newPathById.get(node.getId()));
            cascadeOrgService.updateById(patch);
        }
        log.info("移动级联组织: id={}, orgCode={}, 旧父={}, 新父={}, 子树节点数={}",
            id, selfCode, org.getParentId(), targetParentId, subtree.size());

        /* 提交后才发：异步监听者若在提交前读库，会读到移动前的旧数据 */
        publishStructureRepush(selfCode, subtree, oldIndex);
        return true;
    }

    /**
     * 停用组织：整棵子树从<b>所有</b>平台的目录里移除（本地数据一条不删）。
     *
     * <p>与 {@link #delete} 的区别：删除是物理删除且不可恢复，还要先满足"无子组织、无绑定通道"；
     * 停用只是不再上报，组织树与发布配置全部保留，随时可以 {@link #enable} 恢复。
     *
     * @param id 组织主键
     * @return 是否变更（已处于停用状态时幂等返回 true，且不再重复推 DEL）
     */
    public boolean disable(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);
        if (org.getEnabled() != null && org.getEnabled() == 0) {
            return true;
        }
        CascadeOrgDO patch = new CascadeOrgDO();
        patch.setId(id);
        patch.setEnabled(0);
        boolean ok = cascadeOrgService.updateById(patch);
        if (!ok) {
            return false;
        }
        log.info("停用级联组织: id={}, orgCode={}, orgName={}", id, org.getOrgCode(), org.getOrgName());
        publishRevoke(org);
        return true;
    }

    /**
     * 启用组织：重推快照，上级目录靠 upsert 把节点加回来。
     *
     * <p>不需要反向发 {@code ADD}：快照对上级就是新增/更新，重推一次最稳。
     *
     * @param id 组织主键
     * @return 是否变更（已处于启用状态时幂等返回 true）
     */
    public boolean enable(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);
        /* 祖先是停用状态时坚持启用只会得到一棵挂不上去的子树，必须在入口拦下 */
        assertAncestorsEnabled(org);
        if (org.getEnabled() != null && org.getEnabled() == 1) {
            return true;
        }
        CascadeOrgDO patch = new CascadeOrgDO();
        patch.setId(id);
        patch.setEnabled(1);
        boolean ok = cascadeOrgService.updateById(patch);
        if (ok) {
            log.info("启用级联组织: id={}, orgCode={}, orgName={}", id, org.getOrgCode(), org.getOrgName());
            notifyCatalogChange(org.getOrgCode(), "UPDATE");
        }
        return ok;
    }

    /**
     * 从<b>单个</b>上级平台撤回组织：整棵子树在该平台目录里删除，其它平台完全不受影响。
     *
     * <p><b>为什么不复用「取消勾选保存」</b>：{@code configurePlatforms} 只翻 {@code enabled}、不发报文，
     * 上级目录里那棵树会一直留着（快照是增量 upsert 语义，不会因为节点「这次没出现」就删掉）。
     * 撤回必须先推 DEL 并等上级确认，再动本地。
     *
     * <p><b>为什么只置该组织自己那一行停用</b>：可见性要求整条祖先链都已发布，本节点停用后它的子孙
     * 天然不再上该平台目录；反过来重新勾选时整棵子树一起回来，不用逐个平台重配。
     *
     * <p><b>通道按该平台的策略</b>：{@code CASCADE} 与组织一起删；{@code REROUTE} 不删，提交后
     * 改挂到域节点。两者都是上级确认后才落本地。
     *
     * @param id         组织主键
     * @param platformId 目标上级平台编码
     * @return 是否发生变更（已处于撤回状态时幂等返回 true）
     * @throws IllegalArgumentException 组织从未发布到该平台，或上级未确认
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean revokeFromPlatform(Long id, String platformId) {
        Assert.notNull(id, "id 不能为空");
        Assert.hasText(platformId, "platformId 不能为空");
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);
        CascadePublishDTO publish = cascadePublishManager.get(platformId, PublishObjectType.ORG.name(),
            org.getOrgCode());
        Assert.notNull(publish, () -> "组织【" + org.getOrgName() + "】未发布到平台 " + platformId + "，无需撤回");
        if (!Integer.valueOf(1).equals(publish.getEnabled())) {
            return true;
        }

        List<CascadeOrgDTO> subtree = postOrderSubtree(org);
        Map<Long, CascadeOrgDTO> byId = indexById(listAll());
        List<LocalCascadeCatalogChangeEvent.Node> orgNodes = platformOrgNodes(platformId, subtree, byId);
        List<LocalCascadeCatalogChangeEvent.Node> channelNodes = listEffectiveChannels(platformId, subtree)
            .stream().map(this::channelNode).collect(java.util.stream.Collectors.toList());

        boolean reroute = RevokePolicy.REROUTE.equals(resolveRevokePolicy(platformId, org.getOrgCode()));
        /* CASCADE 时通道必须排在组织之前：祖先先消失会让通道删除被部分上级当孤儿忽略 */
        List<LocalCascadeCatalogChangeEvent.Node> delNodes = new ArrayList<>();
        if (!reroute) {
            delNodes.addAll(channelNodes);
        }
        delNodes.addAll(orgNodes);

        cascadeAssetCleanup.revokeCatalogNodes(platformId, delNodes,
            "从平台 " + platformId + " 撤回组织【" + org.getOrgName() + "】");

        /* 上级确认后才动本地：这一步失败时行仍启用，重试会再发一次 DEL（对已删节点仍回 2xx） */
        cascadePublishManager.setEnabled(platformId, PublishObjectType.ORG.name(), org.getOrgCode(), 0);
        log.info("组织已从单个平台撤回: id={}, orgCode={}, platformId={}, 通道策略={}, 删除节点={}",
            id, org.getOrgCode(), platformId, reroute ? RevokePolicy.REROUTE : RevokePolicy.CASCADE,
            delNodes.size());

        if (reroute && !channelNodes.isEmpty()) {
            /* 通道不删、改挂域节点：投递失败不回滚（下次快照会再推一遍），因此不进两段式 */
            runAfterCommit(() -> eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(
                platformId, "CHANNEL", org.getOrgCode(), "REPARENT", channelNodes)));
        }
        return true;
    }

    /**
     * 校验该组织的<b>祖先链</b>在目标平台上都已发布且本地启用，缺失则一次性列全后拒绝。
     *
     * <p><b>为什么必须拦在写入前</b>：发布是逐个对象配的，只勾选叶子组织时它在上级目录里根本立不起来
     * （{@code ParentID} 指向一个上级没有的节点），组装器会静默剔除。页面显示「推送中」、
     * 上级却一条都收不到，而报文层没有任何报错可以提醒用户。
     *
     * <p>报最近那一级而不是整条链：往上每一级都是同一个原因，一次报一长串反而不知道该先动哪个。
     *
     * @param id          组织主键
     * @param platformIds 目标平台集合；空集合（全部取消勾选）不校验
     * @throws IllegalArgumentException 任一平台的祖先链不完整
     */
    public void assertAncestorsPublished(Long id, List<String> platformIds) {
        if (platformIds == null || platformIds.isEmpty()) {
            return;
        }
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);
        Map<Long, CascadeOrgDTO> byId = indexById(listAll());
        List<String> problems = new ArrayList<>();
        for (String platformId : new LinkedHashSet<>(platformIds)) {
            if (StringUtils.isBlank(platformId)) {
                continue;
            }
            String problem = firstAncestorProblem(org, byId, platformId.trim());
            if (problem != null) {
                problems.add(problem);
            }
        }
        Assert.isTrue(problems.isEmpty(), () -> String.join("；", problems));
    }

    /**
     * 祖先链上第一个「立不起来」的原因，自下向上找。
     *
     * <p>与 {@code firstPlatformBlocker} 同口径，但只回答祖先这一段（不含自身）：
     * 自身的发布行由本次保存负责建出来，不需要提前拦住。
     */
    private String firstAncestorProblem(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId, String platformId) {
        Map<String, CascadePublishDTO> enabledByKey = cascadePublishManager.indexByObjectKey(
            platformId, PublishObjectType.ORG.name(), true);
        Set<Long> guard = new HashSet<>();
        Long parentId = org.getParentId();
        while (parentId != null && guard.add(parentId)) {
            CascadeOrgDTO parent = byId.get(parentId);
            if (parent == null) {
                return null;
            }
            if (parent.getEnabled() == null || parent.getEnabled() != 1) {
                return "平台 " + platformId + "：上级组织【" + parent.getOrgName() + "】处于停用状态，"
                    + "启用后才能推送其下级";
            }
            if (!enabledByKey.containsKey(parent.getOrgCode())) {
                return "平台 " + platformId + "：上级组织【" + parent.getOrgName() + "】尚未推送到该平台，"
                    + "请先勾选它并保存";
            }
            parentId = parent.getParentId();
        }
        return null;
    }

    /**
     * 停用影响面预览：算出这次会把哪些节点从上级目录里摘掉、涉及哪些平台。
     *
     * <p>只做计算，不落库不发报文——用户在确认框里看到数量才决定要不要执行。
     *
     * @param id 组织主键
     * @return 影响面；组织不存在时抛 {@link IllegalArgumentException}
     */
    public CascadeRevokePreviewDTO previewDisable(Long id) {
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);

        CascadeRevokePreviewDTO preview = new CascadeRevokePreviewDTO();
        preview.setOrgId(id);
        preview.setOrgCode(org.getOrgCode());
        preview.setOrgName(org.getOrgName());

        List<String> platformIds = cascadePublishManager.platformIdsOf(PublishObjectType.ORG.name(),
            org.getOrgCode());
        preview.setPublishedPlatformIds(platformIds);
        Map<String, String> policyByPlatform = new LinkedHashMap<>();
        for (String platformId : platformIds) {
            policyByPlatform.put(platformId, resolveRevokePolicy(platformId, org.getOrgCode()));
        }
        preview.setRevokePolicyByPlatform(policyByPlatform);

        List<CascadeOrgDTO> subtree = postOrderSubtree(org);
        Map<Long, CascadeOrgDTO> byId = indexById(subtree);
        for (CascadeOrgDTO node : subtree) {
            preview.getOrgs().add(orgNode(node, byId));
        }
        /* 各平台的通道集可能不同（有的平台没发布某条通道），按编码去重后合并展示 */
        Map<String, LocalCascadeCatalogChangeEvent.Node> channelNodes = new LinkedHashMap<>();
        for (String platformId : platformIds) {
            for (CascadeChannelDTO channel : listEffectiveChannels(platformId, subtree)) {
                channelNodes.putIfAbsent(channel.getCascadeChannelId(), channelNode(channel));
            }
        }
        preview.getChannels().addAll(channelNodes.values());
        return preview;
    }

    /**
     * 删除组织：先向上级取消上报并拿到确认，才删本地。
     *
     * <p>仍有子组织或已绑定通道时拒绝——否则上级侧会留下空壳节点，
     * 而本地又查不到对应记录，后续无法再推 DEL 通知清理。
     *
     * <p>取消上报未获确认时抛出 {@link IllegalArgumentException}，组织本地记录与发布行都保持原样；
     * 先删本地再通知上级会留下「本地已无、上级仍有」的节点，上级还能对着它下发指令。
     *
     * <p>事务边界：这里不开事务——撤销要同步等 SIP 回应（最长 10s），不能占着数据库连接；
     * 剩下的两次删除（发布行、组织行）各自幂等，中途失败时组织只是变成「未发布」，重试即可收敛。
     */
    public boolean delete(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeOrgDTO existed = getById(id);
        if (existed == null) {
            return false;
        }
        long children = listAll().stream()
            .filter(o -> id.equals(o.getParentId()))
            .count();
        Assert.isTrue(children == 0, "该组织下还有 " + children + " 个子组织，请先删除子组织");

        long devices = deviceManager.countByOrgId(id);
        Assert.isTrue(devices == 0, "该组织下还有 " + devices + " 台设备，请先把设备迁到其他组织或留空");

        long channels = countOccupiedChannels(id);
        Assert.isTrue(channels == 0, "该组织下还有 " + channels + " 路通道，请先把通道迁到其他组织或留空");

        /* 撤销内部会清掉发布行：本地都查不到编码了，上级目录必须先跟着干净 */
        cascadeAssetCleanup.revokeOrg(existed.getOrgCode());
        return cascadeOrgService.removeById(id);
    }

    public CascadeOrgDTO getById(Long id) {
        if (id == null) {
            return null;
        }
        return CascadeAssembler.toDTO(cascadeOrgService.getById(id));
    }

    /** 按本地组织编码取组织（编码全局唯一，不再需要平台前缀）。 */
    public CascadeOrgDTO getByCode(String orgCode) {
        if (StringUtils.isBlank(orgCode)) {
            return null;
        }
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeOrgDO::getOrgCode, orgCode);
        return CascadeAssembler.toDTO(cascadeOrgService.getOne(qw));
    }

    /** 列出整棵组织树（平铺，按 sortOrder 升序）。 */
    public List<CascadeOrgDTO> listAll() {
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.orderByAsc(CascadeOrgDO::getSortOrder)
            .orderByAsc(CascadeOrgDO::getId);
        return cascadeOrgService.list(qw).stream().map(CascadeAssembler::toDTO).collect(java.util.stream.Collectors.toList());
    }

    /**
     * 组织 id → 展示名索引（设备/通道列表回填用，一次取全树避免 N+1）。
     *
     * <p>名称为空时回落编码，与组织树的展示保持一致。
     */
    public Map<Long, String> nameIndexById() {
        Map<Long, String> index = new HashMap<>();
        for (CascadeOrgDTO org : listAll()) {
            if (org.getId() != null) {
                index.put(org.getId(), StringUtils.defaultIfBlank(org.getOrgName(), org.getOrgCode()));
            }
        }
        return index;
    }

    /**
     * 取某组织下的整棵子树（按 {@code orgPath} 前缀匹配，一条 SQL 覆盖任意层级）。
     *
     * <p>这正是引入物化路径的目的：邻接表要靠递归 CTE 或应用层循环才能拿到十几层以下的节点，
     * 而 {@code orgPath LIKE '/A/B/%'} 一次就能取全，且能走 {@code idx_cascade_org_path} 索引。
     *
     * @param rootOrgCode 子树根的组织编码；不存在或未回填路径时返回空列表
     * @param includeSelf 是否包含子树根自身
     */
    public List<CascadeOrgDTO> listSubtree(String rootOrgCode, boolean includeSelf) {
        if (StringUtils.isBlank(rootOrgCode)) {
            return new ArrayList<>();
        }
        String rootPath = ensureOrgPath(getByCode(rootOrgCode));
        if (StringUtils.isBlank(rootPath)) {
            log.warn("组织不存在或路径未回填，无法取子树: orgCode={}", rootOrgCode);
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.likeRight(CascadeOrgDO::getOrgPath, rootPath)
            .orderByAsc(CascadeOrgDO::getSortOrder)
            .orderByAsc(CascadeOrgDO::getId);
        List<CascadeOrgDTO> subtree = cascadeOrgService.list(qw).stream()
            .map(CascadeAssembler::toDTO)
            .collect(java.util.stream.Collectors.toList());
        if (includeSelf) {
            return subtree;
        }
        return subtree.stream()
            .filter(o -> !rootOrgCode.equals(o.getOrgCode()))
            .collect(java.util.stream.Collectors.toList());
    }

    /** 子树内全部组织编码，供通道按编码批量过滤（{@code IN (...)}）。 */
    public List<String> listSubtreeCodes(String rootOrgCode, boolean includeSelf) {
        return listSubtree(rootOrgCode, includeSelf).stream()
            .map(CascadeOrgDTO::getOrgCode)
            .filter(StringUtils::isNotBlank)
            .collect(java.util.stream.Collectors.toList());
    }

    /** 树形出参：children 挂在父节点上，并带直属通道数与已发布平台。 */
    public List<CascadeOrgDTO> tree() {
        return buildTree(listAll());
    }

    /**
     * 以某组织为根的子树（树形）。
     *
     * <p>懒加载树靠这个入口：前端点开一个节点只取它下面那一支，不必把整棵树与通道一起拉下来。
     * 子树根自身的 {@code parentId} 指向集合外的节点，因此会作为本层的根返回。
     *
     * @param rootOrgCode 子树根的组织编码
     */
    public List<CascadeOrgDTO> treeSubtree(String rootOrgCode) {
        if (StringUtils.isBlank(rootOrgCode)) {
            return new ArrayList<>();
        }
        return buildTree(listSubtree(rootOrgCode, true));
    }

    private List<CascadeOrgDTO> buildTree(List<CascadeOrgDTO> all) {
        if (all.isEmpty()) {
            return all;
        }
        Map<Long, Long> countByOrgId = countChannelsByOrgId();
        /* 发布关系按组织业务键（org_code）存，不是按自增 id：id 是存储细节，重建后发布关系会变成孤儿 */
        Map<String, List<String>> publishByCode = cascadePublishManager.platformIdsByObjectKey(
            PublishObjectType.ORG.name(), codes(all), false);
        Map<Long, CascadeOrgDTO> byId = indexById(all);

        /* 先把所有节点的 children 初始化好再挂父子：列表按 sortOrder/id 排序，子组织完全可能
           排在父组织之前（排序值更小、或移动后 id 比新父更早），边遍历边挂会拿到还没初始化的 null */
        for (CascadeOrgDTO org : all) {
            org.setChildren(new ArrayList<>());
            org.setChannelCount(countByOrgId.getOrDefault(org.getId(), 0L).intValue());
            org.setPublishedPlatformIds(new ArrayList<>(
                publishByCode.getOrDefault(org.getOrgCode(), List.of())));
        }
        List<CascadeOrgDTO> roots = new ArrayList<>();
        for (CascadeOrgDTO org : all) {
            /* 父节点不在本次集合里（子树根，或父节点已删除）时，它就是这一层的根 */
            CascadeOrgDTO parent = org.getParentId() == null ? null : byId.get(org.getParentId());
            if (parent == null || parent == org) {
                roots.add(org);
            } else {
                parent.getChildren().add(org);
            }
        }
        return promoteUnreachable(roots, all);
    }

    /**
     * 环保护：父子关系成环时，环上的节点既不是根、也不会被任何根挂上，
     * 直接返回会让它们从界面整段消失。这里把不可达节点提升为根并告警，
     * 让「数据有问题」表现为层级异常，而不是数据凭空丢失。
     */
    private List<CascadeOrgDTO> promoteUnreachable(List<CascadeOrgDTO> roots, List<CascadeOrgDTO> all) {
        Set<Long> reachable = new HashSet<>();
        Deque<CascadeOrgDTO> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            CascadeOrgDTO cur = queue.poll();
            if (cur.getId() == null || !reachable.add(cur.getId())) {
                continue;
            }
            queue.addAll(cur.getChildren());
        }
        List<CascadeOrgDTO> unreachable = all.stream()
            .filter(o -> o.getId() != null && !reachable.contains(o.getId()))
            .collect(java.util.stream.Collectors.toList());
        if (unreachable.isEmpty()) {
            return roots;
        }
        log.warn("级联组织存在环形或断裂的父子关系，已提升为根节点避免整段消失: orgIds={}",
            unreachable.stream().map(CascadeOrgDTO::getId).collect(java.util.stream.Collectors.toList()));
        roots.addAll(unreachable);
        return roots;
    }

    /**
     * 取<b>本地有效</b>的组织：自身启用，且祖先链上全部启用。
     *
     * <p>这是「本地结构是否可达」的判断，不含平台维度；某个平台能否看到，还要看
     * {@code tb_cascade_publish} 里有没有启用的发布行（组装器负责叠加这层过滤）。
     */
    public List<CascadeOrgDTO> listEffective() {
        List<CascadeOrgDTO> all = listAll();
        if (all.isEmpty()) {
            return all;
        }
        Map<Long, CascadeOrgDTO> byId = indexById(all);
        List<CascadeOrgDTO> effective = new ArrayList<>();
        for (CascadeOrgDTO org : all) {
            if (isEffectivelyEnabled(org, byId)) {
                effective.add(org);
            }
        }
        effective.sort(Comparator.comparing((CascadeOrgDTO o) -> o.getSortOrder() == null ? 0 : o.getSortOrder())
            .thenComparing(o -> o.getId() == null ? 0L : o.getId()));
        return effective;
    }

    /**
     * 某上级平台的组织视图：本地启用链完整 ∩ 该平台已发布，编码/名称/排序按发布行覆盖。
     *
     * <p><b>这是「结构一份、可见性 N 份」的落点</b>：本地组织树只有一棵，每个平台看到的是
     * 它自己那份发布集。协议层不再直接读组织表，而是读本方法的产出——否则「本地改个名字」
     * 与「只推给一个平台」这两件事会各写一套过滤，长期漂移。
     *
     * <p><b>为什么要算祖先闭包</b>：发布是逐个对象配的，用户完全可能只勾选叶子组织。
     * 叶子在目录里立不起来（它的 {@code ParentID} 指向一个上级没有的节点），
     * 因此「自己已发布」不足以上目录，必须整条祖先链都已发布且在本地启用。
     * 立不起来的组织若配置了 {@code REROUTE}，其通道改挂域节点继续上报；
     * 其余按 {@code CASCADE} 连通道一起不上报。
     *
     * @param platformId 上级平台编码；空白返回空视图
     */
    public CascadePlatformOrgView platformOrgView(String platformId) {
        return computePlatformCatalog(platformId, false).toView();
    }

    /**
     * 某上级平台的「组织视角全貌」：目录内组织 + 通道上浮集合 + 勾了却立不起来的组织。
     *
     * <p>页面上的两个问题共用这一份数据：「这个平台收到了哪些组织」「我勾了推送为什么上级没有」。
     * 它与 {@link #platformOrgView} 走同一次计算——分开算会出现「既不在目录、也不在阻断清单」的幽灵节点。
     *
     * <p>阻断清单只收<b>用户确实勾了</b>的组织（发布行 {@code enabled=1}）：没勾的不算阻断，
     * 那是尚未配置。阻断节点可能是它自己（本地停用）或某个祖先（本地停用 / 未发布到该平台），
     * 两者在上级的结果一样，但用户要修的地方不同，所以逐条带出阻断节点的编码与名称。
     *
     * @param platformId 上级平台编码；空白返回空视图
     */
    public CascadePlatformOrgCatalog platformOrgCatalog(String platformId) {
        return computePlatformCatalog(platformId, true);
    }

    /**
     * 目录与阻断清单的唯一计算入口。
     *
     * @param withBlocked 协议层只要目录，置 {@code false} 跳过阻断清单的对象构造与排序
     */
    private CascadePlatformOrgCatalog computePlatformCatalog(String platformId, boolean withBlocked) {
        if (StringUtils.isBlank(platformId)) {
            return CascadePlatformOrgCatalog.empty();
        }
        Map<String, CascadePublishDTO> publishByKey = cascadePublishManager.indexByObjectKey(
            platformId, PublishObjectType.ORG.name(), false);
        if (publishByKey.isEmpty()) {
            log.debug("平台未配置任何组织发布，目录只有域节点: platformId={}", platformId);
            return CascadePlatformOrgCatalog.empty();
        }

        /* 遍历全量而不是 listEffective()：本地停用的组织同样要让通道决定去留，
           只扫有效组织会让 REROUTE 的那部分通道凭空消失 */
        List<CascadeOrgDTO> all = listAll();
        Map<Long, CascadeOrgDTO> allById = indexById(all);
        List<CascadePlatformOrgDTO> catalog = new ArrayList<>();
        Set<String> rerouted = new HashSet<>();
        List<CascadeBlockedOrgDTO> blocked = new ArrayList<>();
        for (CascadeOrgDTO org : all) {
            CascadeOrgDTO blocker = firstPlatformBlocker(org, publishByKey, allById);
            if (blocker == null) {
                catalog.add(toPlatformOrg(org, publishByKey.get(org.getOrgCode())));
                continue;
            }
            /* 阻断节点的策略决定通道归宿：它才是「上级这一刻会收到什么」的直接原因。
               上浮集合与阻断明细各用一次：只有已勾选的组织才进阻断清单 */
            boolean channelRerouted = RevokePolicy.REROUTE.equals(policyOf(publishByKey.get(blocker.getOrgCode())));
            if (channelRerouted) {
                rerouted.add(org.getOrgCode());
            }
            if (withBlocked && isPublishEnabled(publishByKey.get(org.getOrgCode()))) {
                blocked.add(toBlockedOrg(org, blocker, channelRerouted));
            }
        }

        Map<Long, String> codeById = new HashMap<>();
        for (CascadePlatformOrgDTO org : catalog) {
            codeById.put(org.getId(), org.getPlatformCode());
        }
        for (CascadePlatformOrgDTO org : catalog) {
            org.setParentPlatformCode(org.getParentId() == null ? null : codeById.get(org.getParentId()));
        }
        catalog.sort(Comparator.comparing((CascadePlatformOrgDTO o) -> o.getSortOrder() == null ? 0 : o.getSortOrder())
            .thenComparing(o -> o.getId() == null ? 0L : o.getId()));
        blocked.sort(Comparator.comparing(o -> o.getId() == null ? 0L : o.getId()));
        log.debug("平台组织视图: platformId={}, 目录内={}, 通道上浮={}, 已勾选但阻断={}",
            platformId, catalog.size(), rerouted.size(), blocked.size());
        return new CascadePlatformOrgCatalog(catalog, rerouted, blocked);
    }

    /** 发布行是否为「用户勾了要推」；没有发布行即未配置，不算阻断。 */
    private static boolean isPublishEnabled(CascadePublishDTO publish) {
        return publish != null && Integer.valueOf(1).equals(publish.getEnabled());
    }

    /** 已勾选发布却立不起来的组织 → 阻断明细，阻断节点自带编码与名称，页面不必再查一次。 */
    private CascadeBlockedOrgDTO toBlockedOrg(CascadeOrgDTO org, CascadeOrgDTO blocker, boolean rerouted) {
        CascadeBlockedOrgDTO dto = new CascadeBlockedOrgDTO();
        dto.setId(org.getId());
        dto.setLocalCode(org.getOrgCode());
        dto.setName(org.getOrgName());
        dto.setBlockedByCode(blocker.getOrgCode());
        dto.setBlockedByName(blocker.getOrgName());
        dto.setReason(blockerReason(org, blocker));
        dto.setRerouted(rerouted);
        return dto;
    }

    /**
     * 阻断原因：自己停用与祖先阻断的修复入口完全不同（一个改本地启停、一个补发布），必须分开报。
     *
     * <p>「自己未发布」走不到这里——调用方已过滤成「发布行 enabled=1」；保留该分支是为了
     * 将来放开过滤时不会静默给出错误原因。
     */
    private String blockerReason(CascadeOrgDTO org, CascadeOrgDTO blocker) {
        boolean localDisabled = blocker.getEnabled() == null || blocker.getEnabled() != 1;
        if (Objects.equals(org.getId(), blocker.getId())) {
            return localDisabled ? CascadeBlockedOrgDTO.REASON_LOCAL_DISABLED
                : CascadeBlockedOrgDTO.REASON_ANCESTOR_NOT_PUBLISHED;
        }
        return localDisabled ? CascadeBlockedOrgDTO.REASON_ANCESTOR_LOCAL_DISABLED
            : CascadeBlockedOrgDTO.REASON_ANCESTOR_NOT_PUBLISHED;
    }

    /**
     * 组织在该平台目录里「立不起来」的第一个原因节点（含自身），自下向上找。
     *
     * <p>两类阻断同等对待：本地停用（{@code tb_cascade_org.enabled=0}）与未发布到该平台
     * （没有发布行、或发布行已停用）。对上级来说两者结果一样——这个节点不在目录里。
     *
     * @return 阻断节点；整条链都可上目录返回 null
     */
    private CascadeOrgDTO firstPlatformBlocker(CascadeOrgDTO org, Map<String, CascadePublishDTO> publishByKey,
        Map<Long, CascadeOrgDTO> allById) {
        Set<Long> guard = new HashSet<>();
        CascadeOrgDTO cur = org;
        while (cur != null) {
            if (cur.getEnabled() == null || cur.getEnabled() != 1) {
                return cur;
            }
            CascadePublishDTO publish = publishByKey.get(cur.getOrgCode());
            if (publish == null || !Integer.valueOf(1).equals(publish.getEnabled())) {
                return cur;
            }
            Long parentId = cur.getParentId();
            if (parentId == null) {
                return null;
            }
            if (!guard.add(parentId)) {
                log.warn("级联组织存在环形父子关系，按不上目录处理: orgId={}", org.getId());
                return cur;
            }
            cur = allById.get(parentId);
        }
        return null;
    }

    /** 组织 + 该平台发布行 → 目录节点投影；发布行的覆盖值留空即沿用本地值。 */
    private CascadePlatformOrgDTO toPlatformOrg(CascadeOrgDTO org, CascadePublishDTO publish) {
        CascadePlatformOrgDTO view = new CascadePlatformOrgDTO();
        view.setId(org.getId());
        view.setParentId(org.getParentId());
        view.setLocalCode(org.getOrgCode());
        view.setPlatformCode(CascadeCodeBuilder.effectiveCode(
            publish == null ? null : publish.getPublishCode(), org.getOrgCode()));
        view.setName(StringUtils.defaultIfBlank(publish == null ? null : publish.getPublishName(),
            org.getOrgName()));
        Integer sortOrder = publish == null ? null : publish.getSortOrder();
        view.setSortOrder(sortOrder != null ? sortOrder : (org.getSortOrder() == null ? 0 : org.getSortOrder()));
        return view;
    }

    /**
     * 解析某组织上报给上级的 {@code ParentID}：根组织返回域编码，子组织返回父级组织编码。
     *
     * <p>只服务于<b>本地</b>视角（停用预览等）；协议层组装目录请走 {@link #platformOrgView}，
     * 那里带的是该平台的编码覆盖。
     *
     * @param byId     全量组织索引（id → dto）
     * @param domainId 域节点编码（本端平台编码）
     */
    public String resolveParentCode(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId, String domainId) {
        if (org == null || org.getParentId() == null) {
            return domainId;
        }
        CascadeOrgDTO parent = byId.get(org.getParentId());
        return parent != null ? parent.getOrgCode() : domainId;
    }

    /** 按 id 建索引，供调用方解析父节点编码时复用，避免逐条回表。 */
    public Map<Long, CascadeOrgDTO> indexById(List<CascadeOrgDTO> orgs) {
        return orgs.stream()
            .filter(o -> o.getId() != null)
            .collect(java.util.stream.Collectors.toMap(CascadeOrgDTO::getId, o -> o, (a, b) -> a));
    }

    /**
     * 组织在<b>本地</b>是否真的可用：自身与整条祖先链都是启用状态。
     *
     * <p>{@link #isEnabledOrg} 只看自身开关，回答的是「这个组织自己配了没配」；
     * 凡是会真的落成「上级能看到」的地方（通道归属校验、组织启用）都必须用本方法：
     * 祖先停用后它的子孙早已被 DEL 删掉，此时再把通道挂上去只会得到挂不住的孤儿节点。
     *
     * @return 编码为空或组织不存在时返回 false
     */
    public boolean isEffectivelyEnabled(String orgCode) {
        if (StringUtils.isBlank(orgCode)) {
            return false;
        }
        List<CascadeOrgDTO> all = listAll();
        CascadeOrgDTO org = findByCode(all, orgCode);
        return org != null && firstDisabled(org, indexById(all)) == null;
    }

    /**
     * 组织链上第一个停用的节点（含自身），用于把「为什么上级看不到」说清楚。
     *
     * @return 停用节点；组织不存在或整条链都启用返回 null
     */
    public CascadeOrgDTO findDisabledInChain(String orgCode) {
        if (StringUtils.isBlank(orgCode)) {
            return null;
        }
        List<CascadeOrgDTO> all = listAll();
        CascadeOrgDTO org = findByCode(all, orgCode);
        return org == null ? null : firstDisabled(org, indexById(all));
    }

    /**
     * 全库组织的本地可见性快照：组织是否有效、被哪一级连带停用。
     *
     * <p>整库只查一次库，供列表出参填充，避免逐行回表；判定口径必须与
     * {@code CascadeCatalogAssembler} 组装快照时一致，否则「页面显示的」和
     * 「上级收到的」会长期漂移。
     */
    public CatalogVisibility catalogVisibility() {
        List<CascadeOrgDTO> all = listAll();
        Map<Long, CascadeOrgDTO> byId = indexById(all);
        Map<String, CascadeOrgDTO> orgByCode = new LinkedHashMap<>();
        Map<String, String> blockedByOrgName = new LinkedHashMap<>();
        Set<String> effectiveCodes = new HashSet<>();
        for (CascadeOrgDTO org : all) {
            String code = org.getOrgCode();
            if (StringUtils.isBlank(code)) {
                continue;
            }
            orgByCode.put(code, org);
            CascadeOrgDTO disabled = firstDisabled(org, byId);
            if (disabled == null) {
                effectiveCodes.add(code);
            } else {
                blockedByOrgName.put(code, orgLabel(disabled));
            }
        }
        return new CatalogVisibility(orgByCode, blockedByOrgName, effectiveCodes);
    }

    /** 本地有效组织编码集合：自身与整条祖先链都启用。 */
    public Set<String> listEffectiveCodes() {
        return catalogVisibility().effectiveCodes();
    }

    /** 从平铺列表里按编码取组织，避免逐个回表。 */
    private CascadeOrgDTO findByCode(List<CascadeOrgDTO> all, String orgCode) {
        for (CascadeOrgDTO org : all) {
            if (orgCode.equals(org.getOrgCode())) {
                return org;
            }
        }
        return null;
    }

    /**
     * 组织编码是否存在且<b>自身</b>启用。
     *
     * <p>只回答「这个组织自己的开关开着没」，不看祖先链——要判断能否真正推给上级，
     * 用 {@link #isEffectivelyEnabled}。
     */
    public boolean isEnabledOrg(String orgCode) {
        if (StringUtils.isBlank(orgCode)) {
            return false;
        }
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeOrgDO::getOrgCode, orgCode)
            .eq(CascadeOrgDO::getEnabled, 1);
        return cascadeOrgService.count(qw) > 0;
    }

    /**
     * 生成下一个组织编码（旧口径入口，等价于不启用编码规则）。
     */
    public String nextOrgCode() {
        return nextOrgCode(null, null);
    }

    /**
     * 生成下一个组织编码：{@code 省(2)+市(2)+县(4)+行业(2)+类型(3)+网络(1)+序号(6)}。
     *
     * <p><b>两条口径</b>：编码规则未启用时沿用旧实现——配置前缀(10) + 类型(3) + 网络位(0) + 序号，
     * 与改造前逐字节一致；启用后段位由「自身 → 父组织 → 全局配置」逐段回落得到，
     * 现场只要改根节点的省市区，整棵子树就跟着走。
     *
     * <p><b>为什么序号总是看全库</b>：{@code uk_cascade_org_code} 是全局唯一键，按组织号段
     * 各自从起点递增迟早会撞，撞了也只能退回全局最大值重试。{@code code_start_seq} 只作下界，
     * 不会把已经用掉的号码降回去。
     *
     * <p><b>为什么按 13 位匹配号池</b>：网络位是编码的一部分，却不是发号维度；忽略它与改造前的口径一致，
     * 也能把历史库里网络位非 0 的编码纳入计数，否则新号会直接和它们撞车。
     *
     * @param self   待创建组织；{@code null} 或不启用规则时走旧口径
     * @param parent 父组织，仅用于段回落
     */
    public String nextOrgCode(CascadeOrgDTO self, CascadeOrgDTO parent) {
        boolean ruleEnabled = codeRuleGlobalEnabled && ruleEnabled(self);
        Segments resolved = CascadeCodeBuilder.resolve(
            ruleEnabled ? segmentsOf(self) : null,
            ruleEnabled ? segmentsOf(parent) : null,
            globalCodeSegments());
        int seq = CascadeCodeBuilder.nextSeq(codes(listAll()), resolved.prefix() + resolved.type(),
            ruleEnabled && self != null ? self.getCodeStartSeq() : null);
        return CascadeCodeBuilder.join(resolved.head(), seq);
    }

    /**
     * 某组织生效的编码规则：段位已逐段回落完成，外加该组织自己的号段起点。
     *
     * @param segments 段位，设备/通道在此基础上换类型位
     * @param startSeq 号段起点下界，{@code null} 表示不限
     */
    public record OrgCodeRule(Segments segments, Integer startSeq) {
    }

    /**
     * 取某组织生效的编码规则，供设备/通道编码派生。
     *
     * <p>该组织未启用规则、或总闸关闭时返回 {@code null}——调用方据此回退本地编码，
     * 而不是拿一个「看起来像国标」的假编码去上报。
     *
     * <p>号段起点随组织下发给它的设备与通道：一个园区把自己的号段从 1000 开始，
     * 这个意图对园区的摄像机同样成立。
     */
    public OrgCodeRule effectiveCodeRule(Long orgId) {
        if (!codeRuleGlobalEnabled || orgId == null) {
            return null;
        }
        Map<Long, CascadeOrgDTO> byId = indexById(listAll());
        CascadeOrgDTO org = byId.get(orgId);
        if (org == null || !ruleEnabled(org)) {
            return null;
        }
        CascadeOrgDTO parent = org.getParentId() == null ? null : byId.get(org.getParentId());
        return new OrgCodeRule(
            CascadeCodeBuilder.resolve(segmentsOf(org), segmentsOf(parent), globalCodeSegments()),
            org.getCodeStartSeq());
    }

    /**
     * 该组织自身是否已启用七段编码规则（总闸与组织开关同时成立）。
     *
     * <p>重算入口靠它把「规则没开」与「编码已经对了」区分开：两者的 {@code changes}
     * 都是空，但用户下一步要做的事完全不同——一个去打开开关，一个什么都不用做。
     */
    public boolean codeRuleEnabled(Long orgId) {
        if (!codeRuleGlobalEnabled || orgId == null) {
            return false;
        }
        return ruleEnabled(getById(orgId));
    }

    /**
     * 单个组织的编码变更。
     *
     * @param before 旧编码，推送 DEL 时必须用它（改完库里就查不到旧值了）
     * @param after  新编码；与 {@code before} 相等表示「已符合规则，本次不动」
     */
    public record OrgCodeChange(Long id, String orgName, String before, String after, Integer startSeq) {

        public boolean changed() {
            return !Objects.equals(before, after);
        }
    }

    /**
     * 重算计划：{@code changes} 含未变更项（便于页面回显「哪些本来就不用改」），
     * {@code conflicts} 非空即整批拒绝。
     */
    public record CodeReapplyPlan(Long rootId, List<OrgCodeChange> changes, List<String> conflicts) {

        public boolean hasConflict() {
            return conflicts != null && !conflicts.isEmpty();
        }
    }

    /**
     * 规划一次组织编码重算：<b>只算不写</b>。
     *
     * <p><b>只重算「不符合当前规则」的节点</b>：编码前 14 位已经等于当前段位拼出的 head 就保持原样。
     * 否则每点一次「重编码」，同一批组织就会整体换号，而上级侧要跟着做一次全量删除重建——
     * 这次点击原本只想让改过的那几层生效。
     *
     * <p>号池用「全库组织编码」并在分配时实时追加：一次重算多个节点时若各自独立取 max+1，
     * 它们会算出同一个号。
     *
     * <p>冲突只查平台侧：本地唯一性由号池保证，而 {@code uk_publish_code} 不含
     * {@code object_type}，新编码只要被任何对象的发布行占着，落库就会撞键。
     */
    public CodeReapplyPlan planCodeReapply(Long rootId) {
        Assert.notNull(rootId, "orgId 不能为空");
        Assert.isTrue(codeRuleGlobalEnabled, "编码规则总闸未打开，不允许重算");
        CascadeOrgDTO root = getById(rootId);
        Assert.notNull(root, "组织不存在: id=" + rootId);

        List<CascadeOrgDTO> all = listAll();
        Map<Long, CascadeOrgDTO> byId = indexById(all);
        List<String> pool = new ArrayList<>(codes(all));
        List<OrgCodeChange> changes = new ArrayList<>();
        for (CascadeOrgDTO node : postOrderSubtree(root)) {
            if (!ruleEnabled(node)) {
                continue;
            }
            CascadeOrgDTO parent = node.getParentId() == null ? null : byId.get(node.getParentId());
            Segments resolved = CascadeCodeBuilder.resolve(segmentsOf(node), segmentsOf(parent),
                globalCodeSegments());
            String head = resolved.head();
            String after = node.getOrgCode();
            if (CascadeCodeBuilder.seqOf(after, head) <= 0) {
                after = CascadeCodeBuilder.join(head, CascadeCodeBuilder.nextSeq(pool,
                    resolved.prefix() + resolved.type(), node.getCodeStartSeq()));
                pool.add(after);
            }
            changes.add(new OrgCodeChange(node.getId(), node.getOrgName(), node.getOrgCode(), after,
                node.getCodeStartSeq()));
        }

        List<OrgCodeChange> effective = changes.stream().filter(OrgCodeChange::changed)
            .collect(java.util.stream.Collectors.toList());
        return new CodeReapplyPlan(rootId, changes, publishConflicts(effective));
    }

    /**
     * 落库执行重算计划：单事务、两阶段改编码、提交后逐平台推 DEL。
     *
     * <p><b>为什么要两阶段</b>：两个组织互换编码时（A→B、B→A），任何一条先写都会撞
     * {@code uk_cascade_org_code}。先把整批挪到 {@code T<id>}，再写目标值，中间态从外部看
     * 只是「编码暂时不可识别」，不会短暂重号。
     *
     * <p><b>为什么要同步发布行</b>：{@code object_key} 就是 {@code org_code}，通道的
     * {@code parent_key} 也可能指着它。只改组织表等于把发布配置全部变成孤儿。
     * 但 {@code publish_code} 一律不动（连同 {@code code_source} / {@code code_generated_time}）——
     * 那是人工覆盖或已上报的编码，重算组织编码不是改它的理由。重算的输入是组织段位，
     * 不是平台的共享码：要给某平台换码只能去发布配置里显式改（2.5 的人工值保护依赖这一点）。
     *
     * <p><b>为什么 DEL 在提交后发</b>：DEL 之后 {@code CascadeEventBridge} 会立刻重推快照，
     * 提交前发等于让上级读到旧编码、删完又把旧树推回去。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyCodeReapply(CodeReapplyPlan plan) {
        Assert.notNull(plan, "重算计划不能为空");
        Assert.isTrue(!plan.hasConflict(), () -> "存在编码冲突，已整批拒绝: " + plan.conflicts());
        List<OrgCodeChange> changed = plan.changes().stream().filter(OrgCodeChange::changed)
            .collect(java.util.stream.Collectors.toList());
        if (changed.isEmpty()) {
            return;
        }

        CascadeOrgDTO root = getById(plan.rootId());
        Assert.notNull(root, "组织不存在: id=" + plan.rootId());
        List<CascadeOrgDTO> all = listAll();
        Map<Long, CascadeOrgDTO> byId = indexById(all);
        List<CascadeOrgDTO> subtree = postOrderSubtree(root);
        Map<Long, String> codeById = new LinkedHashMap<>();
        for (CascadeOrgDTO node : subtree) {
            codeById.put(node.getId(), node.getOrgCode());
        }
        for (OrgCodeChange change : changed) {
            codeById.put(change.id(), change.after());
        }

        /* 旧节点清单与平台集合必须在改库前取：改完之后 parentCode 已是新值，DEL 会删错树；
           且编码要按各平台的 publish_code 解析，否则配过覆盖的平台删不掉旧节点 */
        Map<String, List<LocalCascadeCatalogChangeEvent.Node>> nodesByPlatform = new LinkedHashMap<>();
        for (String platformId : platformIdsOf(subtree)) {
            nodesByPlatform.put(platformId, platformOrgNodes(platformId, subtree, byId));
        }

        for (OrgCodeChange change : changed) {
            CascadeOrgDO tmp = new CascadeOrgDO();
            tmp.setId(change.id());
            tmp.setOrgCode(TMP_CODE_PREFIX + change.id());
            cascadeOrgService.updateById(tmp);
        }
        Set<Long> changedIds = changed.stream().map(OrgCodeChange::id).collect(java.util.stream.Collectors.toSet());
        Map<Long, String> paths = buildCodePaths(root, codeById, byId);
        for (CascadeOrgDTO node : subtree) {
            CascadeOrgDO patch = new CascadeOrgDO();
            patch.setId(node.getId());
            patch.setOrgPath(paths.get(node.getId()));
            if (changedIds.contains(node.getId())) {
                patch.setOrgCode(codeById.get(node.getId()));
            }
            cascadeOrgService.updateById(patch);
        }
        for (OrgCodeChange change : changed) {
            cascadePublishManager.renameObjectKey(PublishObjectType.ORG.name(), change.before(), change.after());
            cascadePublishManager.renameParentKey(change.before(), change.after());
        }
        log.info("重算组织编码: rootId={}, 变更={}, 冲突={}", plan.rootId(), changed.size(),
            plan.conflicts().size());

        String cascadeId = root.getOrgCode();
        runAfterCommit(() -> nodesByPlatform.forEach((platformId, nodes) -> eventPublisher
            .publishEvent(new LocalCascadeCatalogChangeEvent(platformId, "ORG", cascadeId, "DEL", nodes))));
    }

    /**
     * 平台内查重：按<b>生效码</b>跨对象类型比对，因为唯一键不含 {@code object_type}。
     *
     * <p><b>为什么按生效码而不是 {@code publish_code} 列</b>：没配覆盖的对象（列值为空）
     * 上报的其实是<b>本地码</b>。只查列会漏掉「设备/通道的本地码恰好等于组织的新编码」这种真撞车，
     * 计划算完、库也改到一半才被唯一键打回。
     *
     * <p><b>为什么先按平台聚合</b>：{@code uk_publish_code} 是 {@code (platform_id, publish_code)}，
     * 同一编码在平台 A 被占用不影响平台 B。而组织只会被改到「它自己有发布行的平台」上
     * （{@link CascadePublishManager#renameObjectKey} 也只动这些平台的行），
     * 逐条 {@code change} 扫全表既看错了范围，也会把别处的同号误报成冲突。
     *
     * <p><b>自身豁免按「整批被改走的旧码」判</b>：{@code A→B}、{@code B→A} 互换时，
     * B 组织正被一起改走，它的行不算占用；只拿单条 {@code change.before()} 比会漏掉同批的其它节点。
     */
    private List<String> publishConflicts(List<OrgCodeChange> effective) {
        List<String> conflicts = new ArrayList<>();
        if (effective.isEmpty()) {
            return conflicts;
        }
        Set<String> targets = new LinkedHashSet<>();
        Set<String> movingKeys = new HashSet<>();
        for (OrgCodeChange change : effective) {
            targets.add(change.after());
            movingKeys.add(change.before());
        }
        Set<String> platformIds = new LinkedHashSet<>();
        for (String before : movingKeys) {
            platformIds.addAll(cascadePublishManager.platformIdsOf(PublishObjectType.ORG.name(), before));
        }
        for (String platformId : platformIds) {
            for (CascadePublishDTO row : cascadePublishManager.listByPlatformAll(platformId)) {
                String code = CascadeCodeBuilder.effectiveCode(row.getPublishCode(),
                    CascadeCodeBuilder.localCodeOf(row.getObjectType(), row.getObjectKey()));
                if (!targets.contains(code)) {
                    continue;
                }
                boolean self = PublishObjectType.ORG.name().equals(row.getObjectType())
                    && movingKeys.contains(row.getObjectKey());
                if (!self) {
                    conflicts.add("编码【" + code + "】在平台 " + platformId + " 已被 "
                        + row.getObjectType() + " " + row.getObjectKey() + " 占用");
                }
            }
        }
        return conflicts;
    }

    /** 子树涉及的平台集合（按旧编码查，改库前调用） */
    private Set<String> platformIdsOf(List<CascadeOrgDTO> subtree) {
        Set<String> platformIds = new LinkedHashSet<>();
        for (CascadeOrgDTO node : subtree) {
            if (StringUtils.isNotBlank(node.getOrgCode())) {
                platformIds.addAll(cascadePublishManager.platformIdsOf(PublishObjectType.ORG.name(),
                    node.getOrgCode()));
            }
        }
        return platformIds;
    }

    /**
     * 重算后的物化路径：按邻接表自顶向下拼，编码一律取 {@code codeById}（新值）。
     *
     * <p>不能用「旧路径前缀替换」：旧编码长度都是 20，替换看似安全，但一旦某个祖先的
     * {@code org_path} 本身没回填，前缀就匹配不上，整棵子树的路径会原地不动。
     */
    private Map<Long, String> buildCodePaths(CascadeOrgDTO root, Map<Long, String> codeById,
        Map<Long, CascadeOrgDTO> byId) {
        Map<Long, List<CascadeOrgDTO>> childrenByParent = postOrderSubtree(root).stream()
            .filter(o -> o.getParentId() != null)
            .collect(java.util.stream.Collectors.groupingBy(CascadeOrgDTO::getParentId));
        CascadeOrgDTO parent = root.getParentId() == null ? null : byId.get(root.getParentId());
        String base = parent == null ? PATH_SEPARATOR
            : StringUtils.defaultIfBlank(parent.getOrgPath(), PATH_SEPARATOR);
        Map<Long, String> paths = new LinkedHashMap<>();
        appendCodePath(root, base + codeById.get(root.getId()) + PATH_SEPARATOR, codeById, childrenByParent,
            paths);
        return paths;
    }

    private void appendCodePath(CascadeOrgDTO node, String path, Map<Long, String> codeById,
        Map<Long, List<CascadeOrgDTO>> childrenByParent, Map<Long, String> paths) {
        if (node == null || node.getId() == null || paths.containsKey(node.getId())) {
            return;
        }
        Assert.isTrue(path.length() <= ORG_PATH_MAX_LENGTH,
            "重编码后层级过深，物化路径超出上限 " + ORG_PATH_MAX_LENGTH + ": " + path);
        paths.put(node.getId(), path);
        for (CascadeOrgDTO child : childrenByParent.getOrDefault(node.getId(), List.of())) {
            appendCodePath(child, path + StringUtils.defaultString(codeById.get(child.getId())) + PATH_SEPARATOR,
                codeById, childrenByParent, paths);
        }
    }

    /** 事务提交后再跑：异步监听者若在提交前读库，会读到改前的旧数据 */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    /**
     * 全局兜底段：{@code gateway.cascade.code-prefix} 是「中心编码(8) + 行业(2)」，
     * 拆成四段，类型取 {@code gateway.cascade.org-type}，网络位固定 0。
     *
     * <p>配置里写了非数字时按位硬切而不是换默认前缀：改造前就是这个行为。
     * 静默换前缀会让「配置明显写错」表现为编码突然从另一个省开始，比直接拼出非法串更难排查。
     *
     * <p><b>对外可见</b>：{@code CascadeCodeResolver} 走「平台 → 组织 → 全局」三级回落时，
     * 最后一级必须与本类同源。本类不能反过来依赖它（它依赖本类取组织规则，注入即成环），
     * 于是把这一段开放出去，而不是在解析器里再抄一份前缀拆位逻辑。
     */
    public Segments globalCodeSegments() {
        String prefix = resolvePrefix();
        Segments segments = CascadeCodeBuilder.fromPrefix(prefix, orgType);
        if (segments != null) {
            return segments;
        }
        log.warn("级联编码前缀含非数字，按位切分继续发号: codePrefix={}", prefix);
        return new Segments(prefix.substring(0, 2), prefix.substring(2, 4), prefix.substring(4, 8),
            prefix.substring(8, 10), StringUtils.defaultIfBlank(orgType, CascadeCodeBuilder.DEFAULT_ORG_TYPE),
            CascadeCodeBuilder.DEFAULT_NETWORK);
    }

    /** 组织自带的七段配置；未配置的段为 {@code null}，由回落链补齐 */
    private Segments segmentsOf(CascadeOrgDTO org) {
        return org == null ? null
            : new Segments(org.getCodeProvince(), org.getCodeCity(), org.getCodeCounty(),
                org.getCodeIndustry(), org.getCodeType(), org.getCodeNetwork());
    }

    /** 该组织是否启用了七段编码规则（{@code code_rule_enabled=1}） */
    private boolean ruleEnabled(CascadeOrgDTO org) {
        return org != null && org.getCodeRuleEnabled() != null && org.getCodeRuleEnabled() == 1;
    }

    /** 编码前缀：取配置的前 10 位；未配置或长度不足时回退默认值。 */
    private String resolvePrefix() {
        String configured = StringUtils.trimToNull(codePrefix);
        if (configured != null && configured.length() >= PREFIX_LENGTH) {
            return configured.substring(0, PREFIX_LENGTH);
        }
        return DEFAULT_PREFIX;
    }

    /**
     * 生成物化路径：{@code 祖先路径 + 自身编码 + '/'}。
     *
     * <p>向上找最近一个已回填 {@code orgPath} 的祖先作为基底，缺失的层级用
     * {@code parent_id} 链补齐——这样历史库即使没跑回填脚本，新节点写进去的路径也是对的。
     * 循环用 {@code guard} 兜底，人为改库造环时不会死循环。
     */
    private String buildOrgPath(Long parentId, String selfCode) {
        if (StringUtils.isBlank(selfCode)) {
            return null;
        }
        if (parentId == null) {
            return PATH_SEPARATOR + selfCode + PATH_SEPARATOR;
        }
        List<String> missing = new ArrayList<>();
        String base = null;
        Set<Long> guard = new HashSet<>();
        Long cursor = parentId;
        while (cursor != null && guard.add(cursor)) {
            CascadeOrgDTO cur = getById(cursor);
            if (cur == null) {
                break;
            }
            if (StringUtils.isNotBlank(cur.getOrgPath())) {
                base = cur.getOrgPath();
                break;
            }
            missing.add(0, cur.getOrgCode());
            cursor = cur.getParentId();
        }
        StringBuilder path = new StringBuilder(StringUtils.defaultIfBlank(base, PATH_SEPARATOR));
        for (String code : missing) {
            if (StringUtils.isNotBlank(code)) {
                path.append(code).append(PATH_SEPARATOR);
            }
        }
        return path.append(selfCode).append(PATH_SEPARATOR).toString();
    }

    /** 读路径时顺手补齐历史数据：只补一次，之后都走索引。 */
    private String ensureOrgPath(CascadeOrgDTO org) {
        if (org == null) {
            return null;
        }
        if (StringUtils.isNotBlank(org.getOrgPath())) {
            return org.getOrgPath();
        }
        String path = buildOrgPath(org.getParentId(), org.getOrgCode());
        if (StringUtils.isBlank(path) || org.getId() == null) {
            return path;
        }
        CascadeOrgDO patch = new CascadeOrgDO();
        patch.setId(org.getId());
        patch.setOrgPath(path);
        cascadeOrgService.updateById(patch);
        log.info("补齐组织物化路径: id={}, orgCode={}, orgPath={}", org.getId(), org.getOrgCode(), path);
        return path;
    }

    /**
     * 发布「目录结构变更」事件，由级联事件桥接重推整棵目录快照。
     *
     * <p><b>按已发布平台逐个分发</b>：组织本身不认识平台，「推给谁」只能从发布行读。
     * 同一个组织推给两个上级，就是两条目的地不同的事件，各自重推各自的快照。
     *
     * <p>停用发布行的平台不参与：它压根没配这个组织，发过去也只会触发一次空快照。
     */
    private void notifyCatalogChange(String orgCode, String event) {
        if (eventPublisher == null || StringUtils.isBlank(orgCode)) {
            return;
        }
        for (String platformId : cascadePublishManager.enabledPlatformIdsOf(PublishObjectType.ORG.name(),
            orgCode)) {
            eventPublisher.publishEvent(
                new LocalCascadeCatalogChangeEvent(platformId, "ORG", orgCode, event));
        }
    }

    /**
     * 发布「先删旧树、再重推快照」事件（组织移动专用）。
     *
     * <p>只在事务提交后发：{@code CascadeEventBridge} 是异步监听者，提交前发出去会读到移动前的旧数据，
     * 删完又把旧树推回上级，等于白做。
     *
     * <p><b>节点清单要逐平台构建</b>：各平台可能给同一个组织配了不同的 {@code publish_code}，
     * 上级目录里存的是那个码，DEL 必须按它发才能删中得到。
     */
    private void publishStructureRepush(String orgCode, List<CascadeOrgDTO> subtree,
        Map<Long, CascadeOrgDTO> byId) {
        if (eventPublisher == null || StringUtils.isBlank(orgCode)) {
            return;
        }
        List<String> platformIds = cascadePublishManager.platformIdsOf(PublishObjectType.ORG.name(), orgCode);
        if (platformIds.isEmpty()) {
            return;
        }
        Runnable publish = () -> {
            for (String platformId : platformIds) {
                eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(platformId, "ORG", orgCode, "DEL",
                    platformOrgNodes(platformId, subtree, byId)));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
            return;
        }
        publish.run();
    }

    /**
     * 发布「停用」事件：按各平台的通行处置策略，通道先删、组织按叶子到根删。
     *
     * <p>{@code REROUTE} 只删组织，另外发一次改挂事件把通道的父节点改成域节点；
     * 拆成两个事件而不是一个，是因为两者失败原因不同（删组织 vs 改父），日志里能分开定位。
     *
     * <p>策略从<b>发布行</b>读：同一组织推给两个上级，可以一边连带删通道、一边让通道上浮。
     */
    private void publishRevoke(CascadeOrgDTO org) {
        if (eventPublisher == null || org == null || org.getId() == null) {
            return;
        }
        List<String> platformIds = cascadePublishManager.platformIdsOf(PublishObjectType.ORG.name(),
            org.getOrgCode());
        if (platformIds.isEmpty()) {
            log.debug("组织未发布到任何平台，停用无需通知上级: id={}, orgCode={}", org.getId(), org.getOrgCode());
            return;
        }
        List<CascadeOrgDTO> subtree = postOrderSubtree(org);
        Map<Long, CascadeOrgDTO> byId = indexById(listAll());

        for (String platformId : platformIds) {
            /* 编码覆盖按平台解析：上级目录里存的是该平台的 publish_code，发本地码删不到 */
            List<LocalCascadeCatalogChangeEvent.Node> orgNodes = platformOrgNodes(platformId, subtree, byId);
            List<LocalCascadeCatalogChangeEvent.Node> channelNodes = listEffectiveChannels(platformId, subtree)
                .stream().map(this::channelNode).collect(java.util.stream.Collectors.toList());
            if (RevokePolicy.REROUTE.equals(resolveRevokePolicy(platformId, org.getOrgCode()))) {
                /* 组织先删（后序），通道随后改挂；通道不删，因此不进 DEL 清单 */
                eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(platformId, "ORG",
                    org.getOrgCode(), "DEL", orgNodes));
                if (!channelNodes.isEmpty()) {
                    eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(platformId, "CHANNEL",
                        org.getOrgCode(), "REPARENT", channelNodes));
                }
                continue;
            }
            /* CASCADE：通道必须排在组织之前，否则删父会让通道变成孤儿节点 */
            List<LocalCascadeCatalogChangeEvent.Node> all = new ArrayList<>(channelNodes);
            all.addAll(orgNodes);
            eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(platformId, "ORG",
                org.getOrgCode(), "DEL", all));
        }
    }

    /** 某平台给该组织配置的通道处置策略；没有发布行时按 CASCADE。 */
    private String resolveRevokePolicy(String platformId, String orgCode) {
        return policyOf(cascadePublishManager.get(platformId, PublishObjectType.ORG.name(), orgCode));
    }

    /** 发布行上的通道处置策略；行为空按 CASCADE。 */
    private String policyOf(CascadePublishDTO publish) {
        return RevokePolicy.normalize(publish == null ? null : publish.getRevokePolicy());
    }

    /** 组织 → 目录节点（本地视角：父编码由 {@code byId} 解析，根组织留空由发送方回落域编码） */
    private LocalCascadeCatalogChangeEvent.Node orgNode(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId) {
        return new LocalCascadeCatalogChangeEvent.Node(
            org.getOrgCode(), org.getOrgName(), resolveParentCode(org, byId, null));
    }

    /**
     * 一批组织 → <b>某平台视角</b>的目录节点（编码与父编码都按该平台的发布行覆盖解析）。
     *
     * <p><b>为什么不能用 {@link #orgNode}</b>：上级目录里的 {@code DeviceID} 是 {@code publish_code}，
     * 配过覆盖的平台收到本地码只会当陌生节点忽略——“组织停用了、上级那边却还在、点播还能拉到流”
     * 这种最难查的残留就是从这里开始的。
     *
     * <p>发布行取<b>含停用</b>的全集：待删子树里可能有该平台未启用的节点，但祖先一旦配过覆盖，
     * 子节点的 {@code ParentID} 也应该用覆盖值拼，否则删除请求定位不到那棵树。
     */
    private List<LocalCascadeCatalogChangeEvent.Node> platformOrgNodes(String platformId,
        List<CascadeOrgDTO> subtree, Map<Long, CascadeOrgDTO> byId) {
        Map<String, CascadePublishDTO> publishByKey = cascadePublishManager.indexByObjectKey(
            platformId, PublishObjectType.ORG.name(), false);
        List<LocalCascadeCatalogChangeEvent.Node> nodes = new ArrayList<>(subtree.size());
        for (CascadeOrgDTO org : subtree) {
            String selfCode = platformCodeOf(org, publishByKey);
            String parentCode = null;
            if (org.getParentId() != null) {
                CascadeOrgDTO parent = byId.get(org.getParentId());
                parentCode = parent == null ? null : platformCodeOf(parent, publishByKey);
            }
            nodes.add(new LocalCascadeCatalogChangeEvent.Node(selfCode, platformNameOf(org, publishByKey),
                parentCode));
        }
        return nodes;
    }

    /**
     * 上级看到的组织编码：发布行覆盖优先，无覆盖回落本地编码。
     *
     * <p>口径收在 {@link CascadeCodeBuilder#effectiveCode}：与本类的目录投影、{@code DEL} 报文、
     * 平台内查重必须是同一个答案，否则「页面上的码」与「报文里的码」会各算各的。
     */
    private String platformCodeOf(CascadeOrgDTO org, Map<String, CascadePublishDTO> publishByKey) {
        CascadePublishDTO publish = publishByKey.get(org.getOrgCode());
        return CascadeCodeBuilder.effectiveCode(publish == null ? null : publish.getPublishCode(),
            org.getOrgCode());
    }

    /** 上级看到的组织名称：发布行覆盖优先，无覆盖回落本地名称。 */
    private String platformNameOf(CascadeOrgDTO org, Map<String, CascadePublishDTO> publishByKey) {
        CascadePublishDTO publish = publishByKey.get(org.getOrgCode());
        return StringUtils.defaultIfBlank(publish == null ? null : publish.getPublishName(), org.getOrgName());
    }

    /** 通道 → 目录节点（parentCode 为当前所属组织，REROUTE 的改挂模式需要） */
    private LocalCascadeCatalogChangeEvent.Node channelNode(CascadeChannelDTO channel) {
        return new LocalCascadeCatalogChangeEvent.Node(
            channel.getCascadeChannelId(), channel.getCascadeName(), ownershipOf(channel));
    }

    /**
     * 子树内<b>上报中</b>的通道：已停报的通道上级那里早就没有，不必再发 DEL。
     *
     * <p><b>必须与目录组装同一口径</b>：通道从发布集取（{@link CascadePublishedAssetResolver#listChannels}），
     * 而不是查 {@code tb_cascade_channel}。两套判断会让「推上去的目录」与「删掉的目录」各自成立却对不上——
     * 组装器认为不存在的通道不会出现在上级目录里，这里却发 DEL；反过来漏发则留下孤儿节点。
     *
     * <p>归属取通道<b>自己</b>的组织，为空时回落设备归属组织：两者都不在子树内，说明该通道
     * 本就不受这个组织停用影响。
     */
    private List<CascadeChannelDTO> listEffectiveChannels(String platformId, List<CascadeOrgDTO> subtree) {
        Set<String> codes = subtree.stream()
            .map(CascadeOrgDTO::getOrgCode)
            .filter(StringUtils::isNotBlank)
            .collect(java.util.stream.Collectors.toSet());
        if (codes.isEmpty() || StringUtils.isBlank(platformId)) {
            return new ArrayList<>();
        }
        return cascadePublishedAssetResolver.listChannels(platformId).stream()
            .filter(c -> codes.contains(ownershipOf(c)))
            .collect(java.util.stream.Collectors.toList());
    }

    /** 通道的归属组织编码：通道自己的归属优先，为空时跟随设备（设备层 / 设备的组织）。 */
    private String ownershipOf(CascadeChannelDTO channel) {
        return StringUtils.defaultIfBlank(channel.getCascadeOrgId(), channel.getDeviceOrgCode());
    }

    /**
     * 子树<b>后序</b>排列：先子后父，保证删除时父节点在子节点之后消失。
     *
     * <p>用 {@code parentId} 邻接表做 DFS 而不靠 {@code orgPath}：历史库可能还没回填路径，
     * 依赖路径排序会在半张树上退化成平坦列表，顺序错乱。
     */
    private List<CascadeOrgDTO> postOrderSubtree(CascadeOrgDTO root) {
        List<CascadeOrgDTO> all = listAll();
        Map<Long, List<CascadeOrgDTO>> childrenByParent = all.stream()
            .filter(o -> o.getParentId() != null)
            .collect(java.util.stream.Collectors.groupingBy(CascadeOrgDTO::getParentId));
        List<CascadeOrgDTO> ordered = new ArrayList<>();
        collectPostOrder(root, childrenByParent, ordered, new HashSet<>());
        return ordered;
    }

    private void collectPostOrder(CascadeOrgDTO node, Map<Long, List<CascadeOrgDTO>> childrenByParent,
        List<CascadeOrgDTO> out, Set<Long> visited) {
        if (node == null || node.getOrgCode() == null) {
            return;
        }
        if (node.getId() != null && !visited.add(node.getId())) {
            return;
        }
        for (CascadeOrgDTO child : childrenByParent.getOrDefault(node.getId(), new ArrayList<>())) {
            collectPostOrder(child, childrenByParent, out, visited);
        }
        out.add(node);
    }

    /**
     * 子树每个节点的新物化路径：{@code 目标父路径 + 自身在树中的相对链}。
     *
     * <p>不走「旧路径前缀替换」而按邻接表重算，是因为历史库可能还存在 {@code org_path} 未回填的行，
     * 前缀替换会把它们的路径算成空串，整棵子树当场丢失。
     */
    private Map<Long, String> buildNewPaths(CascadeOrgDTO root, List<CascadeOrgDTO> subtree, String newBasePath) {
        Map<Long, List<CascadeOrgDTO>> childrenByParent = subtree.stream()
            .filter(o -> o.getParentId() != null)
            .collect(java.util.stream.Collectors.groupingBy(CascadeOrgDTO::getParentId));
        Map<Long, String> paths = new LinkedHashMap<>();
        appendNewPath(root, newBasePath + root.getOrgCode() + PATH_SEPARATOR, childrenByParent, paths);
        return paths;
    }

    private void appendNewPath(CascadeOrgDTO node, String path, Map<Long, List<CascadeOrgDTO>> childrenByParent,
        Map<Long, String> paths) {
        if (node == null || node.getId() == null || paths.containsKey(node.getId())) {
            return;
        }
        Assert.isTrue(path.length() <= ORG_PATH_MAX_LENGTH,
            "移动后层级过深，物化路径超出上限 " + ORG_PATH_MAX_LENGTH + ": " + path);
        paths.put(node.getId(), path);
        for (CascadeOrgDTO child : childrenByParent.getOrDefault(node.getId(), List.of())) {
            appendNewPath(child, path + child.getOrgCode() + PATH_SEPARATOR, childrenByParent, paths);
        }
    }

    private CascadeOrgDTO assertParentExists(Long parentId) {
        if (parentId == null) {
            return null;
        }
        CascadeOrgDTO parent = getById(parentId);
        Assert.notNull(parent, "父组织不存在: id=" + parentId);
        return parent;
    }

    /**
     * 校验组织能否重新启用：父及所有上级都必须启用。
     *
     * <p>只校验父链不校验自身——调用方就是那个「要打开」的节点。
     * 父记录缺失（历史脏数据）时放行并告警：上级会把它挂到域节点上，不会产生孤儿。
     */
    private void assertAncestorsEnabled(CascadeOrgDTO org) {
        if (org == null || org.getParentId() == null) {
            return;
        }
        Map<Long, CascadeOrgDTO> byId = indexById(listAll());
        CascadeOrgDTO parent = byId.get(org.getParentId());
        if (parent == null) {
            log.warn("组织父节点记录缺失，按挂根节点恢复: id={}, parentId={}",
                org.getId(), org.getParentId());
            return;
        }
        CascadeOrgDTO disabled = firstDisabled(parent, byId);
        Assert.isNull(disabled, () -> "上级组织【" + orgLabel(disabled) + "】处于停用状态，请先启用它");
    }

    /** 组织展示名：名称为空时回落编码，报错与日志都用它，避免出现「【null】」 */
    private String orgLabel(CascadeOrgDTO org) {
        return org == null ? "" : StringUtils.defaultIfBlank(org.getOrgName(), org.getOrgCode());
    }

    private boolean isEffectivelyEnabled(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId) {
        return firstDisabled(org, byId) == null;
    }

    /**
     * 自下向上找组织链上第一个停用的节点（含自身）。
     *
     * @param byId 组织索引（id → dto）
     * @return 停用的节点；整条链都启用返回 null
     */
    private CascadeOrgDTO firstDisabled(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId) {
        Set<Long> guard = new HashSet<>();
        CascadeOrgDTO cur = org;
        while (cur != null) {
            if (cur.getEnabled() == null || cur.getEnabled() != 1) {
                return cur;
            }
            Long parentId = cur.getParentId();
            if (parentId == null) {
                return null;
            }
            if (!guard.add(parentId)) {
                /* 环形数据（人为改库造成）不能死循环，按"不生效"处理 */
                log.warn("级联组织存在环形父子关系，按停用处理: orgId={}", org.getId());
                return cur;
            }
            cur = byId.get(parentId);
        }
        return null;
    }

    /**
     * 直属通道数（按本地资产层口径，key = 组织主键）。
     *
     * <p>「直属」包含两种情况：通道自己归属该组织（GB28181 业务分组直挂摄像机），
     * 以及通道未显式归属、跟随所属设备归属该组织。这正是上级目录里挂在组织节点下的通道。
     */
    private Map<Long, Long> countChannelsByOrgId() {
        Map<Long, Long> counts = new HashMap<>();
        Map<String, Long> orgIdByDeviceId = new HashMap<>();
        for (DeviceDO device : deviceManager.listOrgLinkProjection()) {
            if (device.getDeviceId() != null && device.getOrgId() != null) {
                orgIdByDeviceId.put(device.getDeviceId(), device.getOrgId());
            }
        }
        for (DeviceChannelDO channel : deviceChannelManager.listOrgLinkProjection()) {
            Long orgId = channel.getOrgId() != null ? channel.getOrgId() : orgIdByDeviceId.get(channel.getDeviceId());
            if (orgId != null) {
                counts.merge(orgId, 1L, Long::sum);
            }
        }
        return counts;
    }

    /**
     * 某组织下的通道占用数：显式归属该组织的通道 + 归属该组织的设备下未显式归属的通道。
     *
     * <p>删除组织前必须两边都数——只数显式归属会放过"设备挂在这、通道跟着设备"的绝大多数场景。
     */
    private long countOccupiedChannels(Long orgId) {
        long direct = deviceChannelManager.countByOrgId(orgId);
        long inherited = deviceChannelManager.countUnownedByDeviceIds(
            deviceManager.listDeviceIdsByOrgId(orgId));
        return direct + inherited;
    }

    /**
     * 编码段八列透传（六段 + 起始序号 + 规则开关）：null 表示不改（updateById 跳过 null），空串表示清空。
     *
     * <p><b>这里刻意不做归一</b>：{@code ""} 正是「清空这一段」的唯一表达，先 {@code trimToNull}
     * 会让 {@code updateById} 把它当成「没传」跳过——界面显示已清空、库里还是旧值。归一因此拆成两步：
     * 新增在 {@code toDO} 之后直接 {@code trimToNull}，编辑在 {@code updateById} 之后补一次显式 SET。
     */
    private void applyCodeRule(CascadeOrgDO update, CascadeOrgDTO dto) {
        update.setCodeProvince(dto.getCodeProvince());
        update.setCodeCity(dto.getCodeCity());
        update.setCodeCounty(dto.getCodeCounty());
        update.setCodeIndustry(dto.getCodeIndustry());
        update.setCodeType(dto.getCodeType());
        update.setCodeNetwork(dto.getCodeNetwork());
        update.setCodeStartSeq(dto.getCodeStartSeq());
        update.setCodeRuleEnabled(dto.getCodeRuleEnabled());
    }

    /**
     * 把「提交过但归一后与原值不同」的编码段显式写回，让库里「未配置」只剩 NULL 一种表示。
     *
     * <p><b>为什么不能只靠 {@code updateById}</b>：它的 null 是「不改」，表达不了「把这一段清空」。
     * 提交 {@code ""} 要的是写 NULL，若提前归一成 null 再交给 {@code updateById}，这个动作会静默
     * 失效——界面显示清空了，库里还是旧值。
     *
     * <p><b>为什么排在 {@code updateById} 之后</b>：先归一再交给 {@code updateById}，只提交段位的
     * patch 会变成空 SET 的非法 SQL。代价是提交了带空白的段时多发一条 UPDATE，换掉整类边界问题。
     *
     * <p><b>为什么是六段而不是五段</b>：组织侧的 {@code code_type} 与省市区一样是可编辑的列；
     * 平台侧的类型位由对象类型决定、不进入参，所以那边只归一五段。
     */
    private void normalizeSubmittedSegments(CascadeOrgDTO dto) {
        LambdaUpdateWrapper<CascadeOrgDO> wrapper = new LambdaUpdateWrapper<>();
        boolean dirty = setSegmentIfDirty(wrapper, CascadeOrgDO::getCodeProvince, dto.getCodeProvince());
        dirty |= setSegmentIfDirty(wrapper, CascadeOrgDO::getCodeCity, dto.getCodeCity());
        dirty |= setSegmentIfDirty(wrapper, CascadeOrgDO::getCodeCounty, dto.getCodeCounty());
        dirty |= setSegmentIfDirty(wrapper, CascadeOrgDO::getCodeIndustry, dto.getCodeIndustry());
        dirty |= setSegmentIfDirty(wrapper, CascadeOrgDO::getCodeType, dto.getCodeType());
        dirty |= setSegmentIfDirty(wrapper, CascadeOrgDO::getCodeNetwork, dto.getCodeNetwork());
        if (!dirty) {
            return;
        }
        wrapper.eq(CascadeOrgDO::getId, dto.getId());
        cascadeOrgService.update(wrapper);
    }

    /**
     * 提交值已经干净、或本段压根没提交时不动这一列，交给 {@code updateById} 处理。
     *
     * @return 是否需要显式 SET 一次
     */
    private static boolean setSegmentIfDirty(LambdaUpdateWrapper<CascadeOrgDO> wrapper,
        SFunction<CascadeOrgDO, String> column, String submitted) {
        if (submitted == null) {
            return false;
        }
        String normalized = StringUtils.trimToNull(submitted);
        if (Objects.equals(normalized, submitted)) {
            return false;
        }
        /* normalized 为 null 时生成的是 column = NULL，正是「清空这一段」 */
        wrapper.set(column, normalized);
        return true;
    }

    private List<String> codes(List<CascadeOrgDTO> orgs) {
        return orgs.stream()
            .map(CascadeOrgDTO::getOrgCode)
            .filter(StringUtils::isNotBlank)
            .collect(java.util.stream.Collectors.toList());
    }
}
