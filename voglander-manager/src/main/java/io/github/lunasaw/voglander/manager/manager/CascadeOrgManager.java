package io.github.lunasaw.voglander.manager.manager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
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
import org.springframework.util.Assert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.assembler.CascadeAssembler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
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
        assertParentExists(dto.getPlatformId(), dto.getParentOrgId());

        /* 编码一律由后端生成：手填容易出现重复、类型位错误或与平台前缀不一致，上级判为非法节点 */
        dto.setCascadeOrgId(nextOrgCode(dto.getPlatformId()));
        /* 路径与编码同时生成：两者都是下发后不可变的标识，不允许外部传入 */
        dto.setOrgPath(buildOrgPath(dto.getParentOrgId(), dto.getCascadeOrgId()));
        if (dto.getEnabled() == null) {
            dto.setEnabled(1);
        }
        if (dto.getSortOrder() == null) {
            dto.setSortOrder(0);
        }
        CascadeOrgDO doObj = CascadeAssembler.toDO(dto);
        cascadeOrgService.save(doObj);
        log.info("新增级联组织: platformId={}, orgName={}, cascadeOrgId={}, parentOrgId={}",
            dto.getPlatformId(), dto.getOrgName(), dto.getCascadeOrgId(), dto.getParentOrgId());
        publishCatalogChange(dto.getPlatformId(), dto.getCascadeOrgId(), "ADD");
        return doObj.getId();
    }

    /**
     * 更新组织。只允许改名称 / 排序 / 启停。
     *
     * <p><b>父节点与编码都不可变</b>：改父节点等于在上级侧把节点从一棵树挪到另一棵，
     * 上级没有"移动"语义，只会表现为旧节点消失、新节点出现，通道引用随之失效。
     * 真要调整层级，删除后重建并重新绑定通道。
     */
    public boolean update(CascadeOrgDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.notNull(dto.getId(), "id 不能为空");
        CascadeOrgDTO existed = getById(dto.getId());
        Assert.notNull(existed, "组织不存在: id=" + dto.getId());

        CascadeOrgDO update = new CascadeOrgDO();
        update.setId(dto.getId());
        update.setOrgName(StringUtils.defaultIfBlank(dto.getOrgName(), existed.getOrgName()));
        update.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : existed.getSortOrder());
        update.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : existed.getEnabled());
        boolean ok = cascadeOrgService.updateById(update);
        log.info("更新级联组织: id={}, cascadeOrgId={}, orgName={}, enabled={}",
            dto.getId(), existed.getCascadeOrgId(), update.getOrgName(), update.getEnabled());
        if (ok) {
            /* 改名只影响节点自身，但改启停会连带整棵子树进出目录，统一按结构变更处理 */
            publishCatalogChange(existed.getPlatformId(), existed.getCascadeOrgId(), "UPDATE");
        }
        return ok;
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

        List<CascadeOrgDTO> roots = new ArrayList<>();
        for (CascadeOrgDTO org : all) {
            org.setChildren(new ArrayList<>());
            org.setChannelCount(countByOrg.getOrDefault(org.getCascadeOrgId(), 0L).intValue());
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

    /** 组织编码是否存在且启用（通道绑定组织时的校验入口）。 */
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

    private void assertParentExists(String platformId, Long parentOrgId) {
        if (parentOrgId == null) {
            return;
        }
        CascadeOrgDTO parent = getById(parentOrgId);
        Assert.notNull(parent, "父组织不存在: id=" + parentOrgId);
        Assert.isTrue(Objects.equals(parent.getPlatformId(), platformId), "父组织不属于该上级平台");
    }

    private boolean isEffectivelyEnabled(CascadeOrgDTO org, Map<Long, CascadeOrgDTO> byId) {
        Set<Long> guard = new HashSet<>();
        CascadeOrgDTO cur = org;
        while (cur != null) {
            if (cur.getEnabled() == null || cur.getEnabled() != 1) {
                return false;
            }
            Long parentId = cur.getParentOrgId();
            if (parentId == null) {
                return true;
            }
            if (!guard.add(parentId)) {
                /* 环形数据（人为改库造成）不能死循环，按"不生效"处理 */
                log.warn("级联组织存在环形父子关系，按停用处理: orgId={}", org.getId());
                return false;
            }
            cur = byId.get(parentId);
        }
        return true;
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
