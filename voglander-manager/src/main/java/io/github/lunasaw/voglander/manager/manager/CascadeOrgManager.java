package io.github.lunasaw.voglander.manager.manager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.RevokePolicy;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.assembler.CascadeAssembler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRevokePreviewDTO;
import io.github.lunasaw.voglander.manager.service.CascadeChannelService;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.repository.entity.CascadeChannelDO;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联组织树管理器（每平台一套，与本地部门表 {@code tb_dept} 解耦）。
 *
 * <p><b>为什么需要组织层</b>：GB28181 的 Catalog 是树，上级按 {@code Item/ParentID} 建节点。
 * 只下发通道时，通道的父节点直接指向平台编码，上级能收到 200 OK 但不会把通道挂到任何组织下，
 * 表现为「应答成功、界面仍然空」。组织节点补上之后层级才完整：域 → 组织 → 通道。
 *
 * <p><b>编码规则</b>：{@code 中心编码(8) + 行业编码(2) + 类型编码(3) + 网络标识(1) + 序号(6)}，
 * 类型编码默认 215（业务分组），可由 {@code gateway.cascade.org-type} 调整为 216（虚拟组织）。
 * 编码一经下发不可变更——上级按它建树，改编码等价于删旧建新。
 */
@Slf4j
@Component
public class CascadeOrgManager {

    /** 平台编码不可用时的兜底前缀（中心 8 位 + 行业 2 位） */
    private static final String DEFAULT_PREFIX = "3402000000";
    /** 国标编码中平台编码参与"前缀"的长度：中心 8 位 + 行业 2 位 */
    private static final int    PREFIX_LENGTH  = 10;
    /** 网络标识位，固定 0 */
    private static final String NET_ID         = "0";
    /** 物化路径分隔符：org_path 形如 {@code /A/B/}，前后都带分隔符，前缀匹配才能恰好覆盖整层 */
    private static final String PATH_SEPARATOR = "/";
    /** {@code org_path} 列宽上限（VARCHAR(512)，约 24 层国标编码），移动后不得越界 */
    private static final int    ORG_PATH_MAX_LENGTH = 512;

    @Autowired
    private CascadeOrgService     cascadeOrgService;

    @Autowired
    private CascadeChannelService cascadeChannelService;

    @Autowired
    private CascadePlatformManager cascadePlatformManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 组织节点的类型编码：215 业务分组（默认）/ 216 虚拟组织 */
    @Value("${gateway.cascade.org-type:215}")
    private String orgType = "215";

    /**
     * 新增组织，编码由本方法生成。
     *
     * @param dto platformId、orgName 必填；parentOrgId 为空表示根组织
     * @return 新增记录 id
     */
    public Long add(CascadeOrgDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.hasText(dto.getPlatformId(), "platformId 不能为空");
        Assert.hasText(dto.getOrgName(), "orgName 不能为空");
        CascadeOrgDTO parent = assertParentExists(dto.getPlatformId(), dto.getParentOrgId());

        /* 编码一律由后端生成：手填容易出现重复、类型位错误或与平台前缀不一致，上级判为非法节点 */
        dto.setCascadeOrgId(nextOrgCode(dto.getPlatformId()));
        /* 路径与编码同时生成：两者都是下发后不可变的标识，不允许外部传入 */
        dto.setOrgPath(buildOrgPath(dto.getParentOrgId(), dto.getCascadeOrgId()));
        if (dto.getEnabled() == null) {
            dto.setEnabled(1);
        }
        /* 父链停报时新组织跟着落成停报：否则页面显示「上报中」，上级目录里却因祖先被剪掉而看不到它 */
        if (dto.getEnabled() != 0 && parent != null
            && !isEffectivelyEnabled(dto.getPlatformId(), parent.getCascadeOrgId())) {
            dto.setEnabled(0);
            log.info("父组织链路处于停报状态，新增组织按停报落库: platformId={}, parentOrgId={}, parentCode={}",
                dto.getPlatformId(), dto.getParentOrgId(), parent.getCascadeOrgId());
        }
        if (dto.getSortOrder() == null) {
            dto.setSortOrder(0);
        }
        dto.setRevokePolicy(RevokePolicy.normalize(dto.getRevokePolicy()));
        CascadeOrgDO doObj = CascadeAssembler.toDO(dto);
        cascadeOrgService.save(doObj);
        log.info("新增级联组织: platformId={}, orgName={}, cascadeOrgId={}, parentOrgId={}, enabled={}",
            dto.getPlatformId(), dto.getOrgName(), dto.getCascadeOrgId(), dto.getParentOrgId(), dto.getEnabled());
        /* 停报的组织上级本来就看不到，推 ADD 只会让事件桥白跑一次快照 */
        if (dto.getEnabled() != null && dto.getEnabled() == 1) {
            publishCatalogChange(dto.getPlatformId(), dto.getCascadeOrgId(), "ADD");
        }
        return doObj.getId();
    }

    /**
     * 更新组织。只允许改名称 / 排序 / 启停。
     *
     * <p><b>父节点与编码都不可变</b>：改父节点等于在上级侧把节点从一棵树挪到另一棵，
     * 上级没有"移动"语义，只会表现为旧节点消失、新节点出现，通道引用随之失效。
     * 真要调整层级，删除后重建并重新绑定通道。
     *
     * <p><b>启停不只是改字段</b>：{@code 1 -> 0} 会把整棵子树从上级目录里移除，必须显式推
     * {@code Event=DEL}（快照是增量 upsert 语义，不会因为节点「这次没出现」就删掉它），
     * 因此这里直接转到 {@link #revoke} 的处理链路；{@code 0 -> 1} 则重推快照让节点回来。
     */
    public boolean update(CascadeOrgDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.notNull(dto.getId(), "id 不能为空");
        CascadeOrgDTO existed = getById(dto.getId());
        Assert.notNull(existed, "组织不存在: id=" + dto.getId());
        /* 父节点变更走 move()：静默忽略会让调用方以为改成功了 */
        Assert.isTrue(dto.getParentOrgId() == null, "编辑接口不支持修改父组织，请使用移动接口");

        CascadeOrgDO update = new CascadeOrgDO();
        update.setId(dto.getId());
        update.setOrgName(StringUtils.defaultIfBlank(dto.getOrgName(), existed.getOrgName()));
        update.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : existed.getSortOrder());
        update.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : existed.getEnabled());
        if (StringUtils.isNotBlank(dto.getRevokePolicy())) {
            update.setRevokePolicy(RevokePolicy.normalize(dto.getRevokePolicy()));
        }
        boolean wasEnabled = existed.getEnabled() == null || existed.getEnabled() == 1;
        boolean nowEnabled = update.getEnabled() == null || update.getEnabled() == 1;
        /* 0 -> 1 是「重新上报」：父及所有上级必须都在报，否则写进去只是一棵挂不上去的子树 */
        if (!wasEnabled && nowEnabled) {
            assertAncestorsEnabled(existed);
        }
        boolean ok = cascadeOrgService.updateById(update);
        log.info("更新级联组织: id={}, cascadeOrgId={}, orgName={}, enabled={}",
            dto.getId(), existed.getCascadeOrgId(), update.getOrgName(), update.getEnabled());

        /* 改名只影响节点自身，但改启停会连带整棵子树进出目录，两条链路完全不同 */
        if (ok) {
            if (wasEnabled && !nowEnabled) {
                publishRevoke(existed, update.getRevokePolicy());
            } else {
                /* 重推快照即可：{@code 0 -> 1} 靠 upsert 把节点加回来，改名同理 */
                publishCatalogChange(existed.getPlatformId(), existed.getCascadeOrgId(), "UPDATE");
            }
        }
        return ok;
    }

    /**
     * 移动组织：把整棵子树改挂到另一个父节点下（{@code targetParentId} 为空表示回到平台根节点）。
     *
     * <p><b>为什么要单独一个方法</b>：上级目录没有“移动节点”语义，快照对已存在的节点只做 upsert、
     * 不迁移父组织（海康实测），因此在上级侧只能「先按旧父删干净、再按新父重建」。
     * 这条链路的时序必须由一处控制：删除清单用<b>移动前</b>的旧父编码，重命名/排序类更新绝不能走这里。
     *
     * <p><b>编码不变</b>：{@code cascadeOrgId} 保持不变，通道映射与上级对通道的引用都不受影响；
     * 变的只有 {@code parent_org_id} 与整棵子树的 {@code org_path}（物化路径）。
     *
     * <p><b>通道不随删</b>：通道的所属组织编码没变，重推快照后会自动回到原组织下；
     * 若上级在删组织时连带删了监控点，随后的快照也会把它补回来。
     *
     * @param id             被移动的组织主键
     * @param targetParentId 目标父组织主键；{@code null} 表示移到平台根节点
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean move(Long id, Long targetParentId) {
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);
        Assert.isTrue(!Objects.equals(org.getParentOrgId(), targetParentId),
            "目标父组织与当前父组织相同，无需移动");

        String platformId = org.getPlatformId();
        String selfCode   = org.getCascadeOrgId();
        String oldPath    = ensureOrgPath(org);
        Assert.hasText(oldPath, "组织物化路径缺失，无法安全移动: id=" + id);

        String newBasePath = PATH_SEPARATOR;
        if (targetParentId != null) {
            CascadeOrgDTO target = getById(targetParentId);
            Assert.notNull(target, "目标父组织不存在: id=" + targetParentId);
            Assert.isTrue(Objects.equals(target.getPlatformId(), platformId), "目标父组织不属于该上级平台");
            String targetPath = ensureOrgPath(target);
            Assert.hasText(targetPath, "目标父组织物化路径缺失: id=" + targetParentId);
            /* 挂到自己或自己的子孙下会让整棵子树从树上脱落：子孙路径必然是自身路径的前缀延伸 */
            Assert.isTrue(!targetPath.startsWith(oldPath), "不能把组织移到它自己或它的子组织下");
            newBasePath = targetPath;
        }

        /* 子树与「旧父」节点清单必须在写库前算好：写完之后 parentCode 已经是新父，DEL 会删错树 */
        List<CascadeOrgDTO> subtree = postOrderSubtree(org);
        Map<Long, CascadeOrgDTO> oldIndex = indexById(listByPlatformId(platformId));
        Map<Long, String> newPathById = buildNewPaths(org, subtree, newBasePath);

        LambdaUpdateWrapper<CascadeOrgDO> uw = new LambdaUpdateWrapper<>();
        uw.eq(CascadeOrgDO::getId, id)
            .set(CascadeOrgDO::getParentOrgId, targetParentId)
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
        log.info("移动级联组织: id={}, cascadeOrgId={}, 旧父={}, 新父={}, 子树节点数={}",
            id, selfCode, org.getParentOrgId(), targetParentId, subtree.size());

        List<LocalCascadeCatalogChangeEvent.Node> oldNodes = subtree.stream()
            .map(node -> orgNode(node, oldIndex))
            .collect(Collectors.toList());
        publishStructureRepush(platformId, selfCode, oldNodes);
        return true;
    }

    /**
     * 子树每个节点的新物化路径：{@code 目标父路径 + 自身在树中的相对链}。
     *
     * <p>不走「旧路径前缀替换」而按邻接表重算，是因为历史库可能还存在 {@code org_path} 未回填的行，
     * 前缀替换会把它们的路径算成空串，整棵子树当场丢失。
     */
    private Map<Long, String> buildNewPaths(CascadeOrgDTO root, List<CascadeOrgDTO> subtree, String newBasePath) {
        Map<Long, List<CascadeOrgDTO>> childrenByParent = subtree.stream()
            .filter(o -> o.getParentOrgId() != null)
            .collect(Collectors.groupingBy(CascadeOrgDTO::getParentOrgId));
        Map<Long, String> paths = new LinkedHashMap<>();
        appendNewPath(root, newBasePath + root.getCascadeOrgId() + PATH_SEPARATOR, childrenByParent, paths);
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
            appendNewPath(child, path + child.getCascadeOrgId() + PATH_SEPARATOR, childrenByParent, paths);
        }
    }

    /**
     * 取消同步的影响面预览：算出这次会把哪些节点从上级目录里摘掉。
     *
     * <p>只做计算，不落库不发报文——用户在确认框里看到数量才决定要不要执行。
     *
     * @param id     组织主键
     * @param policy 通道处置策略，空则用组织上已保存的值
     * @return 影响面；组织不存在时抛 {@link IllegalArgumentException}
     */
    public CascadeRevokePreviewDTO previewRevoke(Long id, String policy) {
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);

        CascadeRevokePreviewDTO preview = new CascadeRevokePreviewDTO();
        preview.setPlatformId(org.getPlatformId());
        preview.setCascadeOrgId(org.getCascadeOrgId());
        preview.setOrgName(org.getOrgName());
        String effective = RevokePolicy.normalize(
            StringUtils.isNotBlank(policy) ? policy : org.getRevokePolicy());
        preview.setRevokePolicy(effective);

        List<CascadeOrgDTO> subtree = postOrderSubtree(org);
        List<CascadeChannelDTO> channels = listEffectiveChannels(org.getPlatformId(), subtree);
        Map<Long, CascadeOrgDTO> byId = indexById(subtree);
        for (CascadeOrgDTO node : subtree) {
            preview.getOrgs().add(orgNode(node, byId));
        }
        for (CascadeChannelDTO channel : channels) {
            preview.getChannels().add(channelNode(channel));
        }
        return preview;
    }

    /**
     * 取消同步：把整棵子树从上级目录里移除（本地数据一条不删）。
     *
     * <p>与 {@link #delete} 的区别：删除是物理删除且不可恢复，还要先满足"无子组织、无绑定通道"；
     * 取消同步只是不再上报，组织树与通道映射全部保留，随时可以恢复。
     *
     * @param id     组织主键
     * @param policy 通道处置策略：{@code CASCADE} 通道一起移除 / {@code REROUTE} 通道改挂域节点
     * @return 是否变更（已处于停报状态时幂等返回 true）
     */
    public boolean revoke(Long id, String policy) {
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);
        String effective = RevokePolicy.normalize(
            StringUtils.isNotBlank(policy) ? policy : org.getRevokePolicy());

        CascadeOrgDO patch = new CascadeOrgDO();
        patch.setId(id);
        patch.setEnabled(0);
        patch.setRevokePolicy(effective);
        boolean ok = cascadeOrgService.updateById(patch);
        if (!ok) {
            return false;
        }
        log.info("取消级联组织同步: id={}, cascadeOrgId={}, orgName={}, policy={}",
            id, org.getCascadeOrgId(), org.getOrgName(), effective);
        publishRevoke(org, effective);
        return true;
    }

    /**
     * 恢复同步：重新上报，上级目录靠快照 upsert 把节点加回来。
     *
     * <p>不需要反向发 {@code ADD}：快照对上级就是新增/更新，重推一次整棵树最稳。
     *
     * @param id 组织主键
     * @return 是否变更（已处于上报状态时幂等返回 true）
     */
    public boolean publish(Long id) {
        CascadeOrgDTO org = getById(id);
        Assert.notNull(org, "组织不存在: id=" + id);
        /* 祖先是停报状态时坚持恢复只会得到一棵挂不上去的子树，必须在入口拦下 */
        assertAncestorsEnabled(org);

        CascadeOrgDO patch = new CascadeOrgDO();
        patch.setId(id);
        patch.setEnabled(1);
        boolean ok = cascadeOrgService.updateById(patch);
        if (ok) {
            log.info("恢复级联组织同步: id={}, cascadeOrgId={}, orgName={}",
                id, org.getCascadeOrgId(), org.getOrgName());
            publishCatalogChange(org.getPlatformId(), org.getCascadeOrgId(), "UPDATE");
        }
        return ok;
    }

    /**
     * 发布「取消同步」事件：通道先删、组织按叶子到根删，最后重推一次已裁剪的快照。
     *
     * <p>{@code REROUTE} 只删组织，另外发一次改挂事件把通道的父节点改成域节点：
     * 拆成两个事件而不是一个，是因为两者失败原因不同（删组织 vs 改父），
     * 日志里能分开定位是哪个环节出的问题。
     */
    private void publishRevoke(CascadeOrgDTO org, String policy) {
        if (eventPublisher == null || org == null || StringUtils.isBlank(org.getPlatformId())) {
            return;
        }
        String effective = RevokePolicy.normalize(policy);
        List<CascadeOrgDTO> subtree = postOrderSubtree(org);
        List<CascadeChannelDTO> channels = listEffectiveChannels(org.getPlatformId(), subtree);
        Map<Long, CascadeOrgDTO> byId = indexById(subtree);

        List<LocalCascadeCatalogChangeEvent.Node> orgNodes = subtree.stream()
            .map(node -> orgNode(node, byId))
            .collect(Collectors.toList());
        List<LocalCascadeCatalogChangeEvent.Node> channelNodes = channels.stream()
            .map(this::channelNode)
            .collect(Collectors.toList());

        if (RevokePolicy.REROUTE.equals(effective)) {
            /* 组织先删（后序），通道随后改挂；通道不删，因此不进 DEL 清单 */
            eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(org.getPlatformId(), "ORG",
                org.getCascadeOrgId(), "DEL", orgNodes));
            if (!channelNodes.isEmpty()) {
                eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(org.getPlatformId(), "CHANNEL",
                    org.getCascadeOrgId(), "REPARENT", channelNodes));
            }
            return;
        }

        /* CASCADE：通道必须排在组织之前，否则删父会让通道变成孤儿节点 */
        List<LocalCascadeCatalogChangeEvent.Node> all = new ArrayList<>(channelNodes);
        all.addAll(orgNodes);
        eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(org.getPlatformId(), "ORG",
            org.getCascadeOrgId(), "DEL", all));
    }

    /** 组织 → 目录节点。父编码由 {@code byId} 解析；根组织留空，由发送方回落域编码 */
    private LocalCascadeCatalogChangeEvent.Node orgNode(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId) {
        return new LocalCascadeCatalogChangeEvent.Node(
            org.getCascadeOrgId(), org.getOrgName(), resolveParentCode(org, byId, null));
    }

    /** 通道 → 目录节点（parentCode 为当前所属组织，REROUTE 的 DEL_ADD 模式需要） */
    private LocalCascadeCatalogChangeEvent.Node channelNode(CascadeChannelDTO channel) {
        return new LocalCascadeCatalogChangeEvent.Node(
            channel.getCascadeChannelId(), channel.getCascadeName(), channel.getCascadeOrgId());
    }

    /**
     * 子树内<b>上报中</b>的通道：已停报的通道上级那里早就没有，不必再发 DEL。
     */
    private List<CascadeChannelDTO> listEffectiveChannels(String platformId, List<CascadeOrgDTO> subtree) {
        Set<String> codes = subtree.stream()
            .map(CascadeOrgDTO::getCascadeOrgId)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return new ArrayList<>();
        }
        List<CascadeChannelDTO> channels = cascadeChannelService.list(
            new LambdaQueryWrapper<CascadeChannelDO>()
                .eq(CascadeChannelDO::getPlatformId, platformId)
                .eq(CascadeChannelDO::getEnabled, 1))
            .stream().map(CascadeAssembler::toDTO).collect(Collectors.toList());
        return channels.stream()
            .filter(c -> codes.contains(c.getCascadeOrgId()))
            .sorted(Comparator.comparing((CascadeChannelDTO c) -> StringUtils.defaultString(c.getCascadeOrgId()))
                .thenComparing(c -> StringUtils.defaultString(c.getCascadeChannelId())))
            .collect(Collectors.toList());
    }

    /**
     * 子树<b>后序</b>排列：先子后父，保证删除时父节点在子节点之后消失。
     *
     * <p>用 {@code parentOrgId} 邻接表做 DFS 而不靠 {@code orgPath}：历史库可能还没回填路径，
     * 依赖路径排序会在半张树上退化成平坦列表，顺序错乱。
     */
    private List<CascadeOrgDTO> postOrderSubtree(CascadeOrgDTO root) {
        List<CascadeOrgDTO> all = listByPlatformId(root.getPlatformId());
        Map<Long, List<CascadeOrgDTO>> childrenByParent = all.stream()
            .filter(o -> o.getParentOrgId() != null)
            .collect(Collectors.groupingBy(CascadeOrgDTO::getParentOrgId));
        List<CascadeOrgDTO> ordered = new ArrayList<>();
        collectPostOrder(root, childrenByParent, ordered, new HashSet<>());
        return ordered;
    }

    private void collectPostOrder(CascadeOrgDTO node, Map<Long, List<CascadeOrgDTO>> childrenByParent,
        List<CascadeOrgDTO> out, Set<Long> visited) {
        if (node == null || node.getCascadeOrgId() == null) {
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
     * 删除组织。仍有子组织或已绑定通道时拒绝——否则上级侧会留下空壳节点，
     * 而本地又查不到对应记录，后续无法再推 DEL 通知清理。
     */
    public boolean delete(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeOrgDTO existed = getById(id);
        if (existed == null) {
            return false;
        }
        List<CascadeOrgDTO> all = listByPlatformId(existed.getPlatformId());
        long children = all.stream()
            .filter(o -> id.equals(o.getParentOrgId()))
            .count();
        Assert.isTrue(children == 0, "该组织下还有 " + children + " 个子组织，请先删除子组织");

        long channels = countChannelsByOrg(existed.getPlatformId(), existed.getCascadeOrgId());
        Assert.isTrue(channels == 0, "该组织下还有 " + channels + " 个通道，请先把通道迁到其他组织或留空");
        boolean ok = cascadeOrgService.removeById(id);
        if (ok) {
            publishCatalogChange(existed.getPlatformId(), existed.getCascadeOrgId(), "DEL");
        }
        return ok;
    }

    public CascadeOrgDTO getById(Long id) {
        if (id == null) {
            return null;
        }
        return CascadeAssembler.toDTO(cascadeOrgService.getById(id));
    }

    /** 按上级平台列出全部组织（平铺，按 sortOrder 升序）。 */
    public List<CascadeOrgDTO> listByPlatformId(String platformId) {
        if (StringUtils.isBlank(platformId)) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeOrgDO::getPlatformId, platformId)
            .orderByAsc(CascadeOrgDO::getSortOrder)
            .orderByAsc(CascadeOrgDO::getId);
        return cascadeOrgService.list(qw).stream().map(CascadeAssembler::toDTO).collect(Collectors.toList());
    }

    /** 按上报编码取组织（编码在平台内唯一）。 */
    public CascadeOrgDTO getByPlatformAndCode(String platformId, String cascadeOrgId) {
        if (StringUtils.isBlank(platformId) || StringUtils.isBlank(cascadeOrgId)) {
            return null;
        }
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeOrgDO::getPlatformId, platformId)
            .eq(CascadeOrgDO::getCascadeOrgId, cascadeOrgId);
        return CascadeAssembler.toDTO(cascadeOrgService.getOne(qw));
    }

    /**
     * 取某组织下的整棵子树（按 {@code orgPath} 前缀匹配，一条 SQL 覆盖任意层级）。
     *
     * <p>这正是引入物化路径的目的：邻接表要靠递归 CTE 或应用层循环才能拿到十几层以下的节点，
     * 而 {@code orgPath LIKE '/A/B/%'} 一次就能取全，且能走 {@code (platform_id, org_path)} 索引。
     *
     * @param rootOrgId 子树根的组织编码；不存在或未回填路径时返回空列表
     * @param includeSelf 是否包含子树根自身
     */
    public List<CascadeOrgDTO> listSubtree(String platformId, String rootOrgId, boolean includeSelf) {
        if (StringUtils.isBlank(platformId) || StringUtils.isBlank(rootOrgId)) {
            return new ArrayList<>();
        }
        String rootPath = ensureOrgPath(getByPlatformAndCode(platformId, rootOrgId));
        if (StringUtils.isBlank(rootPath)) {
            log.warn("组织不存在或路径未回填，无法取子树: platformId={}, cascadeOrgId={}", platformId, rootOrgId);
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeOrgDO::getPlatformId, platformId)
            .likeRight(CascadeOrgDO::getOrgPath, rootPath)
            .orderByAsc(CascadeOrgDO::getSortOrder)
            .orderByAsc(CascadeOrgDO::getId);
        List<CascadeOrgDTO> subtree = cascadeOrgService.list(qw).stream()
            .map(CascadeAssembler::toDTO)
            .collect(Collectors.toList());
        if (includeSelf) {
            return subtree;
        }
        return subtree.stream()
            .filter(o -> !rootOrgId.equals(o.getCascadeOrgId()))
            .collect(Collectors.toList());
    }

    /** 子树内全部组织编码，供通道按编码批量过滤（{@code IN (...)}）。 */
    public List<String> listSubtreeCodes(String platformId, String rootOrgId, boolean includeSelf) {
        return listSubtree(platformId, rootOrgId, includeSelf).stream()
            .map(CascadeOrgDTO::getCascadeOrgId)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toList());
    }

    /**
     * 树形出参：children 挂在父节点上，并带直属通道数。
     */
    public List<CascadeOrgDTO> tree(String platformId) {
        return tree(platformId, null);
    }

    /**
     * 以某组织为根的子树（{@code rootOrgId} 为空时等价于整平台）。
     *
     * <p>懒加载树靠这个重载：前端点开一个节点只取它下面那一支，不必把整平台的
     * 组织与通道一起拉下来。子树根自身的 {@code parentOrgId} 指向集合外的节点，
     * 因此会作为本层的根返回，前端据此挂到已展开的父节点下即可。
     *
     * @param rootOrgId 子树根的组织编码（cascadeOrgId）
     */
    public List<CascadeOrgDTO> tree(String platformId, String rootOrgId) {
        List<CascadeOrgDTO> all = StringUtils.isBlank(rootOrgId)
            ? listByPlatformId(platformId)
            : listSubtree(platformId, rootOrgId, true);
        if (all.isEmpty()) {
            return all;
        }
        Map<String, Long> countByOrg = countChannelsByOrg(platformId);
        Map<Long, CascadeOrgDTO> byId = all.stream()
            .filter(o -> o.getId() != null)
            .collect(Collectors.toMap(CascadeOrgDTO::getId, o -> o, (a, b) -> a));

        /* 先把所有节点的 children 初始化好再挂父子：列表按 sortOrder/id 排序，子组织完全可能
           排在父组织之前（排序值更小、或移动后 id 比新父更早），边遍历边挂会拿到还没初始化的 null */
        for (CascadeOrgDTO org : all) {
            org.setChildren(new ArrayList<>());
            org.setChannelCount(countByOrg.getOrDefault(org.getCascadeOrgId(), 0L).intValue());
        }
        List<CascadeOrgDTO> roots = new ArrayList<>();
        for (CascadeOrgDTO org : all) {
            /* 父节点不在本次集合里（子树根，或父节点已停用/删除）时，它就是这一层的根 */
            CascadeOrgDTO parent = org.getParentOrgId() == null ? null : byId.get(org.getParentOrgId());
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
            .collect(Collectors.toList());
        if (unreachable.isEmpty()) {
            return roots;
        }
        log.warn("级联组织存在环形或断裂的父子关系，已提升为根节点避免整段消失: orgIds={}",
            unreachable.stream().map(CascadeOrgDTO::getId).collect(Collectors.toList()));
        roots.addAll(unreachable);
        return roots;
    }

    /**
     * 取<b>生效</b>的组织：自身启用，且祖先链上全部启用。
     *
     * <p>上级目录是整棵树：把一个组织停用，它的子孙就失去了父节点，继续下发只会产生孤儿条目，
     * 因此停用必须连带整棵子树一起剪掉。
     */
    public List<CascadeOrgDTO> listEffective(String platformId) {
        List<CascadeOrgDTO> all = listByPlatformId(platformId);
        if (all.isEmpty()) {
            return all;
        }
        Map<Long, CascadeOrgDTO> byId = all.stream()
            .filter(o -> o.getId() != null)
            .collect(Collectors.toMap(CascadeOrgDTO::getId, o -> o, (a, b) -> a));
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
     * 解析某组织上报给上级的 {@code ParentID}：根组织返回平台编码，子组织返回父级组织编码。
     *
     * @param byId     同平台组织索引（id → dto）
     * @param domainId 域节点编码（本端平台编码）
     */
    public String resolveParentCode(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId, String domainId) {
        if (org == null || org.getParentOrgId() == null) {
            return domainId;
        }
        CascadeOrgDTO parent = byId.get(org.getParentOrgId());
        return parent != null ? parent.getCascadeOrgId() : domainId;
    }

    /** 按 id 建索引，供调用方解析父节点编码时复用，避免逐条回表。 */
    public Map<Long, CascadeOrgDTO> indexById(List<CascadeOrgDTO> orgs) {
        return orgs.stream()
            .filter(o -> o.getId() != null)
            .collect(Collectors.toMap(CascadeOrgDTO::getId, o -> o, (a, b) -> a));
    }

    /**
     * 组织在上级目录里是否<b>真的可见</b>：自身与整条祖先链都是上报状态。
     *
     * <p>{@link #isEnabledOrg} 只看自身开关，回答的是「这个组织自己配了没配」；
     * 凡是会真的往上级发报文的地方（通道上报、组织恢复）都必须用本方法：
     * 祖先停报后它的子孙早已被 DEL 删掉，此时再发 ADD 只会得到挂不上去的孤儿节点。
     *
     * @return 编码为空、组织不存在或链上有停报节点时返回 false
     */
    public boolean isEffectivelyEnabled(String platformId, String cascadeOrgId) {
        if (StringUtils.isBlank(platformId) || StringUtils.isBlank(cascadeOrgId)) {
            return false;
        }
        List<CascadeOrgDTO> all = listByPlatformId(platformId);
        CascadeOrgDTO org = findByCode(all, cascadeOrgId);
        return org != null && firstDisabled(org, indexById(all)) == null;
    }

    /**
     * 组织链上第一个停报的节点（含自身），用于把「为什么上级看不到」说清楚。
     *
     * @return 停报节点；组织不存在或整条链都在报返回 null
     */
    public CascadeOrgDTO findDisabledInChain(String platformId, String cascadeOrgId) {
        if (StringUtils.isBlank(platformId) || StringUtils.isBlank(cascadeOrgId)) {
            return null;
        }
        List<CascadeOrgDTO> all = listByPlatformId(platformId);
        CascadeOrgDTO org = findByCode(all, cascadeOrgId);
        return org == null ? null : firstDisabled(org, indexById(all));
    }

    /**
     * 平台内的目录可见性快照：组织是否生效、被哪一级停报连带、通道是否仍会出现在上级目录。
     *
     * <p>整平台只查一次库，供列表出参填充，避免逐行回表；判定口径必须与
     * {@code CascadeCatalogAssembler} 组装快照时一致，否则「页面显示的」和
     * 「上级收到的」会长期漂移。
     */
    public CatalogVisibility catalogVisibility(String platformId) {
        List<CascadeOrgDTO> all = listByPlatformId(platformId);
        Map<Long, CascadeOrgDTO> byId = indexById(all);
        Map<String, CascadeOrgDTO> orgByCode = new LinkedHashMap<>();
        Map<String, String> blockedByOrgName = new LinkedHashMap<>();
        Set<String> effectiveCodes = new HashSet<>();
        for (CascadeOrgDTO org : all) {
            String code = org.getCascadeOrgId();
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

    /** 生效组织编码集合：自身与整条祖先链都在报，可用于按生效范围过滤其它数据。 */
    public Set<String> listEffectiveCodes(String platformId) {
        return catalogVisibility(platformId).effectiveCodes();
    }

    /** 从平铺列表里按编码取组织，避免逐个回表。 */
    private CascadeOrgDTO findByCode(List<CascadeOrgDTO> all, String cascadeOrgId) {
        for (CascadeOrgDTO org : all) {
            if (cascadeOrgId.equals(org.getCascadeOrgId())) {
                return org;
            }
        }
        return null;
    }

    /**
     * 组织编码是否存在且<b>自身</b>启用。
     *
     * <p>只回答「这个组织自己的开关开着没」，不看祖先链——要判断上级能不能看到，
     * 用 {@link #isEffectivelyEnabled}。
     */
    public boolean isEnabledOrg(String platformId, String cascadeOrgId) {
        if (StringUtils.isBlank(platformId) || StringUtils.isBlank(cascadeOrgId)) {
            return false;
        }
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeOrgDO::getPlatformId, platformId)
            .eq(CascadeOrgDO::getCascadeOrgId, cascadeOrgId)
            .eq(CascadeOrgDO::getEnabled, 1);
        return cascadeOrgService.count(qw) > 0;
    }

    /**
     * 生成下一个组织编码：{@code 平台前10位 + 类型(215) + 网络标识(0) + 6位序号}。
     *
     * <p>序号取同平台同类型编码的最大值 +1。并发新增可能撞唯一键，
     * 由 {@code uk_cascade_org_code} 兜底，调用方重试即可。
     */
    public String nextOrgCode(String platformId) {
        String prefix = resolvePrefix(platformId);
        String head = prefix + StringUtils.defaultIfBlank(orgType, "215");
        int max = 0;
        for (CascadeOrgDTO org : listByPlatformId(platformId)) {
            String code = org.getCascadeOrgId();
            if (code == null || code.length() != 20 || !code.startsWith(head)) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(code.substring(head.length() + NET_ID.length())));
            } catch (NumberFormatException ignored) {
                /* 历史脏数据（非数字序号）不参与计数，跳过 */
            }
        }
        return head + NET_ID + String.format("%06d", max + 1);
    }

    /** 平台编码前 10 位作为本级前缀；平台未配置或长度不足时回退默认值。 */
    private String resolvePrefix(String platformId) {
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        String localClientId = platform == null ? null : platform.getLocalClientId();
        if (localClientId != null && localClientId.length() >= PREFIX_LENGTH) {
            return localClientId.substring(0, PREFIX_LENGTH);
        }
        return DEFAULT_PREFIX;
    }

    /**
     * 生成物化路径：{@code 祖先路径 + 自身编码 + '/'}。
     *
     * <p>向上找最近一个已回填 {@code orgPath} 的祖先作为基底，缺失的层级用
     * {@code parent_org_id} 链补齐——这样历史库即使没跑回填脚本，新节点写进去的路径也是对的。
     * 循环用 {@code guard} 兜底，人为改库造环时不会死循环。
     */
    private String buildOrgPath(Long parentOrgId, String selfCode) {
        if (StringUtils.isBlank(selfCode)) {
            return null;
        }
        if (parentOrgId == null) {
            return PATH_SEPARATOR + selfCode + PATH_SEPARATOR;
        }
        List<String> missing = new ArrayList<>();
        String base = null;
        Set<Long> guard = new HashSet<>();
        Long cursor = parentOrgId;
        while (cursor != null && guard.add(cursor)) {
            CascadeOrgDTO cur = getById(cursor);
            if (cur == null) {
                break;
            }
            if (StringUtils.isNotBlank(cur.getOrgPath())) {
                base = cur.getOrgPath();
                break;
            }
            missing.add(0, cur.getCascadeOrgId());
            cursor = cur.getParentOrgId();
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
        String path = buildOrgPath(org.getParentOrgId(), org.getCascadeOrgId());
        if (StringUtils.isBlank(path) || org.getId() == null) {
            return path;
        }
        CascadeOrgDO patch = new CascadeOrgDO();
        patch.setId(org.getId());
        patch.setOrgPath(path);
        cascadeOrgService.updateById(patch);
        log.info("补齐组织物化路径: id={}, cascadeOrgId={}, orgPath={}", org.getId(), org.getCascadeOrgId(), path);
        return path;
    }

    /**
     * 发布"目录结构变更"事件，由级联事件桥接重推整棵目录快照。
     *
     * <p>组织变化改变的是父子关系，增量条目表达不了（上级没有移动节点语义），
     * 因此这里只发事件、不带条目，由 {@code CascadeEventBridge} 决定怎么推。
     */
    private void publishCatalogChange(String platformId, String cascadeOrgId, String event) {
        if (eventPublisher == null || StringUtils.isBlank(platformId)) {
            return;
        }
        eventPublisher.publishEvent(
            new LocalCascadeCatalogChangeEvent(platformId, "ORG", cascadeOrgId, event));
    }

    /**
     * 发布「先删旧树、再重推快照」事件（组织移动专用）。
     *
     * <p>只在事务提交后发：{@code CascadeEventBridge} 是异步监听者，提交前发出去会读到移动前的旧数据，
     * 删完又把旧树推回上级，等于白做。
     */
    private void publishStructureRepush(String platformId, String cascadeOrgId,
        List<LocalCascadeCatalogChangeEvent.Node> oldNodes) {
        if (eventPublisher == null || StringUtils.isBlank(platformId)) {
            return;
        }
        Runnable publish = () -> eventPublisher.publishEvent(
            new LocalCascadeCatalogChangeEvent(platformId, "ORG", cascadeOrgId, "DEL", oldNodes));
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

    private CascadeOrgDTO assertParentExists(String platformId, Long parentOrgId) {
        if (parentOrgId == null) {
            return null;
        }
        CascadeOrgDTO parent = getById(parentOrgId);
        Assert.notNull(parent, "父组织不存在: id=" + parentOrgId);
        Assert.isTrue(Objects.equals(parent.getPlatformId(), platformId), "父组织不属于该上级平台");
        return parent;
    }

    /**
     * 校验组织能否重新上报：父及所有上级都必须在上报状态。
     *
     * <p>只校验父链不校验自身——调用方就是那个「要打开」的节点。
     * 父记录缺失（历史脏数据）时放行并告警：上级会把它挂到域节点上，不会产生孤儿。
     */
    private void assertAncestorsEnabled(CascadeOrgDTO org) {
        if (org == null || org.getParentOrgId() == null) {
            return;
        }
        Map<Long, CascadeOrgDTO> byId = indexById(listByPlatformId(org.getPlatformId()));
        CascadeOrgDTO parent = byId.get(org.getParentOrgId());
        if (parent == null) {
            log.warn("组织父节点记录缺失，按挂平台根节点恢复: id={}, parentOrgId={}",
                org.getId(), org.getParentOrgId());
            return;
        }
        CascadeOrgDTO disabled = firstDisabled(parent, byId);
        Assert.isNull(disabled, () -> "上级组织【" + orgLabel(disabled) + "】处于停报状态，请先恢复它");
    }

    /** 组织展示名：名称为空时回落编码，报错与日志都用它，避免出现「【null】」 */
    private String orgLabel(CascadeOrgDTO org) {
        return org == null ? "" : StringUtils.defaultIfBlank(org.getOrgName(), org.getCascadeOrgId());
    }

    private boolean isEffectivelyEnabled(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId) {
        return firstDisabled(org, byId) == null;
    }

    /**
     * 自下向上找组织链上第一个停报的节点（含自身）。
     *
     * @param byId 同平台组织索引（id → dto）
     * @return 停报的节点；整条链都在报返回 null
     */
    private CascadeOrgDTO firstDisabled(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId) {
        Set<Long> guard = new HashSet<>();
        CascadeOrgDTO cur = org;
        while (cur != null) {
            if (cur.getEnabled() == null || cur.getEnabled() != 1) {
                return cur;
            }
            Long parentId = cur.getParentOrgId();
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

    private long countChannelsByOrg(String platformId, String cascadeOrgId) {
        if (StringUtils.isBlank(cascadeOrgId)) {
            return 0;
        }
        LambdaQueryWrapper<CascadeChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeChannelDO::getPlatformId, platformId)
            .eq(CascadeChannelDO::getCascadeOrgId, cascadeOrgId);
        return cascadeChannelService.count(qw);
    }

    private Map<String, Long> countChannelsByOrg(String platformId) {
        LambdaQueryWrapper<CascadeChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeChannelDO::getPlatformId, platformId)
            .isNotNull(CascadeChannelDO::getCascadeOrgId);
        return cascadeChannelService.list(qw).stream()
            .map(CascadeChannelDO::getCascadeOrgId)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.groupingBy(o -> o, Collectors.counting()));
    }
}
